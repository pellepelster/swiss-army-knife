#!/bin/bash
# the tests of sak-backup and sak-restore (templates/), in docker: a debian 13 like the servers
# next to a real victorialogs of the image the role pins, sharing its network; systemctl and
# podman are stubs, the backend is a static answer of /api/v1/info. Run by `mise run
# ansible:backup:test`
set -euo pipefail

role=$(cd "$(dirname "$0")/.." && pwd)
work=$(mktemp -d)
# the user sak of the container reads below it
chmod 0755 "$work"
name=sak-backup-test-$$
victorialogs_image=$(sed -n 's/^backend_victorialogs_image: //p' "$role/defaults/main.yml")
cleanup() {
  docker rm -f "$name-logs" "$name" >/dev/null 2>&1 || true
  docker run --rm -v "$work:/work" debian:trixie sh -c 'rm -rf /work/* /work/.[!.]*' >/dev/null 2>&1 || true
  rm -rf "$work"
}
trap cleanup EXIT

# the scripts as the role renders them, for the deployment alpha on a server of version 0.2.0
for script in sak-backup sak-restore; do
  ansible localhost --connection local -m ansible.builtin.template \
    -a "src=$role/templates/$script.j2 dest=$work/$script mode=0755" \
    -e "@$role/defaults/main.yml" -e sak_name=alpha -e sak_mount_dir=/work/storage/data \
    -e sak_version=0.2.0 -e backend_restore_wait=30 >/dev/null
done

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

on_server "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && apt-get install -qq -y sqlite3 zstd age curl python3 >/dev/null"
on_server "groupadd -g 10000 sak && useradd -u 10000 -g sak -M -s /usr/sbin/nologin sak
cp /work/sak-backup /work/sak-restore /usr/local/bin/
printf '#!/bin/sh\necho \"\$@\" >> /work/systemctl.log\n' > /usr/local/bin/systemctl
printf '#!/bin/sh\necho sha256:test\n' > /usr/local/bin/podman
chmod +x /usr/local/bin/systemctl /usr/local/bin/podman
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
sak-backup \$(age-keygen -y /work/backup.key) > /work/backup.age 2> /work/backup.log"
on_server "! sak-backup not-a-recipient 2>/dev/null" || fail "a wrong recipient is refused"

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
on_server "! sak-restore < /work/other.tar.zst 2>/dev/null" || fail "a backup of another deployment is restored"
on_server "[ ! -e /work/systemctl.log ]" || fail "the deployment was stopped for a wrong backup"

echo "a backup of a newer version changes nothing, an older one or one of no known version is checked further"
# a backup of only its manifest: one that passes the check of its version fails at its database
backup_of() {
  on_server "rm -rf /work/version && mkdir -p /work/version && cd /work/version \
    && echo '{\"format\": 1, \"name\": \"alpha\", \"version\": \"$1\"}' > backup.json \
    && tar -c backup.json | zstd -q > /work/version.tar.zst"
}
backup_of 0.3.0
on_server "! sak-restore < /work/version.tar.zst 2> /work/version.log && grep -q 'deploy 0.3.0 or newer first' /work/version.log" \
  || { on_server "cat /work/version.log" >&2; fail "a backup of a newer version is restored"; }
for version in 0.1.9 0.2.0 latest; do
  backup_of "$version"
  on_server "! sak-restore < /work/version.tar.zst 2> /work/version.log && grep -q 'the backup has no database' /work/version.log" \
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
on_server "age -d -i /work/backup.key /work/backup.age | sak-restore 2> /work/restore.log" || {
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
on_server "grep -qx 'stop sak-backend sak-ingress sak-vector sak-victorialogs' /work/systemctl.log && grep -qx 'start sak-backend sak-ingress sak-vector sak-victorialogs' /work/systemctl.log" \
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
on_server "! sak-restore < /work/truncated.tar.zst 2> /work/truncated.log" || {
  on_server "cat /work/truncated.log"
  fail "a truncated backup is restored"
}
on_server "[ \"\$(cat $data/backend/data/files/1.jpg)\" = photo-one ] && [ ! -e /work/storage/data/.restore-alpha ]" || fail "the truncated backup changed the data"

echo "all tests passed"
