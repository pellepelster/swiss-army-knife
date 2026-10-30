#!/bin/bash
# the tests of sak-backend (templates/sak-backend.j2), in docker: a debian 13 like the servers
# next to a real victorialogs of the image the role pins, sharing its network; systemctl,
# systemd-run, needrestart and podman are stubs, apt is real, the backend is a static answer of /api/v1/info. Run by `mise run
# backend:test:integration`
set -euo pipefail

role=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
# the user sak of the container reads below it
chmod 0755 "$work"
name=sak-backend-test-$$
victorialogs_image=$(sed -n 's/^backend_victorialogs_image: //p' "$role/defaults/main.yml")
cleanup() {
  docker rm -f "$name-logs" "$name" >/dev/null 2>&1 || true
  docker run --rm -v "$work:/work" debian:trixie sh -c 'rm -rf /work/* /work/.[!.]*' >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# the script as the role renders it, for the deployment alpha on a server of version 0.2.0
ansible localhost --connection local -m ansible.builtin.template \
  -a "src=$role/templates/sak-backend.j2 dest=$work/sak-backend mode=0755" \
  -e "@$role/defaults/main.yml" -e sak_name=alpha -e sak_mount_dir=/work/storage/data \
  -e sak_version=0.2.0 -e backend_restore_wait=30 -e backend_apt_wait=10 >/dev/null

data=/work/storage/data/alpha
docker run -d --name "$name" -v "$work:/work" debian:trixie sleep infinity >/dev/null
docker exec "$name" bash -euc "mkdir -p $data/victorialogs $data/backend
head -c 32 /dev/urandom | base64 > $data/backend/victorialogs-partition.key
chown -R 10000:10000 $data/victorialogs $data/backend/victorialogs-partition.key"
docker run -d --name "$name-logs" --network "container:$name" --user 10000:10000 \
  -v "$work/storage/data/alpha/victorialogs:/victoria-logs-data" \
  -v "$work/storage/data/alpha/backend/victorialogs-partition.key:/etc/victoria-logs/partition.key:ro" \
  "$victorialogs_image" -storageDataPath=/victoria-logs-data -httpListenAddr=127.0.0.1:9428 \
  -partitionManageAuthKey=file:///etc/victoria-logs/partition.key -snapshotsMaxAge=1d >/dev/null

on_server() { docker exec -i "$name" bash -euo pipefail -c "$1"; }
fail() {
  echo "FAILED: $*" >&2
  exit 1
}

on_server "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && apt-get install -qq -y sqlite3 zstd age curl python3 procps >/dev/null"
on_server "groupadd -g 10000 sak && useradd -u 10000 -g sak -M -s /usr/sbin/nologin sak
cp /work/sak-backend /usr/local/bin/
printf '#!/bin/sh\necho \"\$@\" >> /work/systemctl.log\n' > /usr/local/bin/systemctl
printf '#!/bin/sh\necho \"\$@\" >> /work/systemd-run.log\n' > /usr/local/bin/systemd-run
printf '#!/bin/sh\necho NEEDRESTART-VER: 3.8\necho NEEDRESTART-SVC: dbus.service\n' > /usr/local/bin/needrestart
printf '#!/bin/sh\necho sha256:test\n' > /usr/local/bin/podman
chmod +x /usr/local/bin/systemctl /usr/local/bin/systemd-run /usr/local/bin/needrestart /usr/local/bin/podman
mkdir -p /work/answer/api/v1 && echo '{}' > /work/answer/api/v1/info
(cd /work/answer && nohup python3 -m http.server --bind 127.0.0.1 8080 >/dev/null 2>&1 &)
age-keygen -o /work/backup.key 2>/dev/null"

echo "the data of alpha: a database in wal mode, files, the ingress, vector and two lines of logs"
on_server "mkdir -p $data/backend/data/files $data/ingress/log $data/vector
echo provisioned > $data/backend/config.yaml
echo SAK_SECRET=1 > $data/backend/backend.env
echo photo-one > $data/backend/data/files/1.jpg
echo certificate > $data/ingress/cert.pem
echo line > $data/ingress/log/access.log
echo checkpoint > $data/vector/checkpoints.json
chown -R sak:sak $data/backend/data $data/ingress $data/vector
runuser -u sak -- sqlite3 $data/backend/data/sak.db \
  'PRAGMA journal_mode=WAL; CREATE TABLE notes (text TEXT); INSERT INTO notes VALUES (\"first\");' >/dev/null"
for i in 1 2; do
  on_server "printf '{\"_msg\":\"line $i\",\"_time\":\"%s\"}\n' \"\$(date -u +%FT%TZ)\" \
    | curl -fsS -X POST -H 'Content-Type: application/stream+json' --data-binary @- \
      'http://127.0.0.1:9428/insert/jsonline?_stream_fields=app'"
done

echo "a backup while the database has a writer that keeps it open"
on_server "runuser -u sak -- sqlite3 $data/backend/data/sak.db 'INSERT INTO notes VALUES (\"second\"); SELECT 1; ' >/dev/null
(echo 'BEGIN; INSERT INTO notes VALUES (\"uncommitted\");'; sleep 5) | runuser -u sak -- sqlite3 $data/backend/data/sak.db >/dev/null &
sleep 1
sak-backend backup \$(age-keygen -y /work/backup.key) > /work/backup.age 2> /work/backup.log"
on_server "! sak-backend backup not-a-recipient 2>/dev/null" || fail "a wrong recipient is refused"

echo "the backup is encrypted and has what it should"
on_server "! grep -q photo-one /work/backup.age && ! grep -q SQLite /work/backup.age" || fail "plain data in the backup"
listing=$(on_server "age -d -i /work/backup.key /work/backup.age | zstd -dc | tar -t")
[ "$(head -1 <<<"$listing")" = "backup.json" ] || fail "backup.json is not the first entry"
for wanted in backend/data/sak.db backend/data/files/1.jpg ingress/cert.pem ingress/log/access.log \
  vector/checkpoints.json victorialogs/partitions/; do
  grep -qx "$wanted" <<<"$listing" || fail "$wanted is not in the backup"
done
grep -q '^victorialogs/partitions/[0-9]\{8\}/datadb/' <<<"$listing" || fail "no day of victorialogs in the backup"
for unwanted in backend/config.yaml backend/backend.env backend/victorialogs-partition.key backend/data/sak.db-wal; do
  ! grep -qx "$unwanted" <<<"$listing" || fail "$unwanted is in the backup"
done
on_server "age -d -i /work/backup.key /work/backup.age | zstd -dc | tar -xO backup.json" \
  | python3 -c 'import json, sys; m = json.load(sys.stdin); assert (m["format"], m["version"], m["name"], m["backend_image"]) == (1, "0.2.0", "alpha", "sha256:test"), m'
[ "$(on_server "curl -fsS -X POST --data-urlencode authKey=\$(cat $data/backend/victorialogs-partition.key) http://127.0.0.1:9428/internal/partition/snapshot/list")" = "[]" ] \
  || {
    on_server "cat /work/backup.log"
    fail "a snapshot of victorialogs is left"
  }
on_server "[ ! -e /work/storage/data/.backup-alpha ]" || fail "the staging directory of the backup is left"

echo "a restore of a backup of another deployment changes nothing"
on_server "mkdir -p /work/other && cd /work/other && echo '{\"format\": 1, \"name\": \"beta\"}' > backup.json \
  && tar -c backup.json | zstd -q > /work/other.tar.zst"
on_server "! sak-backend restore < /work/other.tar.zst 2>/dev/null" || fail "a backup of another deployment is restored"
on_server "[ ! -e /work/systemctl.log ]" || fail "the deployment was stopped for a wrong backup"

echo "a backup of a newer version changes nothing, an older one or one of no known version is checked further"
# a backup of only its manifest: one that passes the check of its version fails at its database
backup_of() {
  on_server "rm -rf /work/version && mkdir -p /work/version && cd /work/version \
    && echo '{\"format\": 1, \"name\": \"alpha\", \"version\": \"$1\"}' > backup.json \
    && tar -c backup.json | zstd -q > /work/version.tar.zst"
}
backup_of 0.3.0
on_server "! sak-backend restore < /work/version.tar.zst 2> /work/version.log && grep -q 'deploy 0.3.0 or newer first' /work/version.log" \
  || { on_server "cat /work/version.log" >&2; fail "a backup of a newer version is restored"; }
for version in 0.1.9 0.2.0 latest; do
  backup_of "$version"
  on_server "! sak-backend restore < /work/version.tar.zst 2> /work/version.log && grep -q 'the backup has no database' /work/version.log" \
    || { on_server "cat /work/version.log" >&2; fail "a backup of version $version is refused for its version"; }
done
on_server "grep -q 'the version of the backup (latest) or of the server (0.2.0) is not known' /work/version.log" \
  || fail "no warning for a backup of no known version"
on_server "[ ! -e /work/systemctl.log ]" || fail "the deployment was stopped for a backup that was refused"

echo "changes after the backup, then the restore"
on_server "runuser -u sak -- sqlite3 -cmd '.timeout 10000' $data/backend/data/sak.db 'INSERT INTO notes VALUES (\"after\")'
rm $data/backend/data/files/1.jpg && echo photo-two > $data/backend/data/files/2.jpg
echo provisioned-anew > $data/backend/config.yaml"
docker stop "$name-logs" >/dev/null
on_server "age -d -i /work/backup.key /work/backup.age | sak-backend restore 2> /work/restore.log" || {
  on_server "cat /work/restore.log" >&2
  fail "the restore"
}
docker start "$name-logs" >/dev/null

echo "the data is that of the backup, the files of the provisioning are those of now"
[ "$(on_server "sqlite3 $data/backend/data/sak.db 'SELECT text FROM notes ORDER BY rowid' | paste -sd,")" = "first,second" ] \
  || fail "the rows of the database"
on_server "[ \"\$(cat $data/backend/data/files/1.jpg)\" = photo-one ] && [ ! -e $data/backend/data/files/2.jpg ]" || fail "the files"
on_server "[ \"\$(cat $data/backend/config.yaml)\" = provisioned-anew ]" || fail "the configuration of the provisioning"
on_server "[ \"\$(stat -c %u:%g $data/backend/data/sak.db $data/ingress/cert.pem | sort -u)\" = 10000:10000 ]" || fail "the owner"
on_server "[ ! -e $data/backend/data/sak.db-shm ] && [ ! -e /work/storage/data/.replaced-alpha ] && [ ! -e /work/storage/data/.restore-alpha ]" \
  || fail "what the restore left"
on_server "grep -qx 'stop sak-ingress sak-backend sak-vector sak-victorialogs' /work/systemctl.log && grep -qx 'start sak-ingress sak-backend sak-vector sak-victorialogs' /work/systemctl.log" \
  || fail "the units were not stopped and started"
for _ in $(seq 30); do
  lines=$(on_server "curl -fsS http://127.0.0.1:9428/select/logsql/query -d 'query=*' | wc -l" 2>/dev/null || echo 0)
  [ "$lines" = 2 ] && break
  sleep 1
done
[ "$lines" = 2 ] || fail "victorialogs answers $lines lines after the restore, not 2"

echo "a damaged backup changes nothing"
on_server "age -d -i /work/backup.key /work/backup.age > /work/full.tar.zst
head -c \$((\$(stat -c %s /work/full.tar.zst) / 2)) /work/full.tar.zst > /work/truncated.tar.zst"
on_server "! sak-backend restore < /work/truncated.tar.zst 2> /work/truncated.log" || {
  on_server "cat /work/truncated.log"
  fail "a truncated backup is restored"
}
on_server "[ \"\$(cat $data/backend/data/files/1.jpg)\" = photo-one ] && [ ! -e /work/storage/data/.restore-alpha ]" || fail "the truncated backup changed the data"

echo "a reboot is pending for /run/reboot-required and for a newer kernel; the upgrades since the boot"
# the server runs the kernel of the host; nothing in /boot yet, so no newer one
pending() { on_server "sak-backend reboot-pending" | python3 -c "import json, sys; m = json.load(sys.stdin); $1"; }
pending 'assert (m["reboot_pending"], m["reasons"], m["services"]) == (False, [], ["dbus.service"]), m' \
  || fail "a reboot is pending on a server that needs none"
on_server "touch /run/reboot-required"
pending 'assert (m["reboot_pending"], m["reasons"]) == (True, ["reboot-required"]), m' || fail "/run/reboot-required is no reason"
on_server "rm /run/reboot-required && touch /boot/vmlinuz-99.0.0-amd64"
pending 'assert m["reasons"] == ["kernel"] and m["kernel"]["installed"] == "99.0.0-amd64", m' || fail "a newer kernel is no reason"
on_server "echo '2000-01-01 00:00:00 upgrade old:amd64 1 2' >> /var/log/dpkg.log
echo \"\$(date '+%Y-%m-%d %H:%M:%S') upgrade openssl:amd64 3.5.1-1 3.5.4-1\" >> /var/log/dpkg.log"
pending 'u = m["updates_since_boot"]; assert {"package": "openssl", "from": "3.5.1-1", "to": "3.5.4-1"}.items() <= u[-1].items() and not any(x["package"] == "old" for x in u), u' \
  || fail "the upgrades since the boot"

echo "an update upgrades the packages and tells what changed"
on_server "sak-backend update > /work/update.json 2> /work/update.log" || {
  on_server "cat /work/update.log" >&2
  fail "the update"
}
on_server "python3 - <<'EOF'
import json, subprocess
m = json.load(open('/work/update.json'))
assert set(m) == {'upgraded', 'installed', 'reboot_pending', 'reasons'}, m
assert m['reasons'] == ['kernel'], m
for change in m['upgraded'] + m['installed']:
    installed = subprocess.run(['dpkg-query', '-W', '-f=\${Version}', change['package']], capture_output=True, text=True).stdout
    assert installed == change['to'], (change, installed)
EOF" || fail "the answer of the update"

echo "an update or a reboot waits for no backup: it stops while one holds the lock"
on_server "rm -f /work/systemctl.log"
docker exec -d "$name" flock /run/sak-backend.lock sleep 20
sleep 1
for command in update reboot; do
  on_server "set +e; sak-backend $command >/dev/null 2>&1; [ \$? = 2 ]" || fail "$command does not stop while the lock is held"
done
on_server "[ ! -e /work/systemctl.log ]" || fail "the deployment was stopped while the lock was held"
on_server "pkill -f 'sleep 20'" || true

echo "a reboot with --if-pending and none pending changes nothing, a reboot stops the deployment in order"
on_server "rm -f /work/systemctl.log /boot/vmlinuz-99.0.0-amd64"
on_server "sak-backend reboot --if-pending 2>/dev/null" | python3 -c 'import json, sys; assert json.load(sys.stdin)["rebooting"] is False' \
  || fail "a reboot without one pending"
on_server "[ ! -e /work/systemctl.log ] && [ ! -e /work/systemd-run.log ]" || fail "the reboot without one pending changed something"
on_server "sak-backend reboot 2>/dev/null" | python3 -c 'import json, sys; m = json.load(sys.stdin); assert m["rebooting"] is True and m["at"], m' \
  || fail "the answer of the reboot"
on_server "grep -qx 'stop sak-ingress sak-backend sak-vector sak-victorialogs' /work/systemctl.log" || fail "the units were not stopped in order"
on_server "grep -qx -- '--quiet --on-active=5s --unit=sak-backend-reboot systemctl reboot' /work/systemd-run.log" || fail "the reboot was not scheduled"
# the lock stays with the server until it goes down
on_server "set +e; sak-backend backup \$(age-keygen -y /work/backup.key) >/dev/null 2>&1; [ \$? = 2 ]" || fail "a backup starts after the reboot"
on_server "pkill -f 'sleep 600'" || true

echo "an unknown command says how to use it"
on_server "set +e; usage=\$(sak-backend nothing 2>&1 >/dev/null); [ \$? = 1 ] && grep -q 'usage: sak-backend backup' <<<\"\$usage\"" || fail "the usage"

echo "all tests passed"
