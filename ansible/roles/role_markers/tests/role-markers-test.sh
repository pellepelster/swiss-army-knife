#!/bin/bash
# the tests of the role role_markers, in docker: a debian 13 like the servers, with the ansible of
# debian and the collection of this repository. Run by `mise run test:backend:integration`
set -euo pipefail

collection=$(cd "$(dirname "$0")/../../.." && pwd)
name=sak-role-markers-test-$$
cleanup() {
  docker rm -f "$name" >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker run -d --name "$name" -v "$collection:/collections/ansible_collections/pelle_io/sak:ro" \
  debian:trixie sleep infinity >/dev/null
on_server() { docker exec -i "$name" bash -euo pipefail -c "$1"; }
fail() {
  echo "FAILED: $*" >&2
  exit 1
}
on_server "export DEBIAN_FRONTEND=noninteractive && apt-get update -qq && apt-get install -qq -y ansible-core >/dev/null"
markers() {
  on_server "ANSIBLE_COLLECTIONS_PATH=/collections ansible localhost --connection local -m include_role \
    -a name=pelle_io.sak.role_markers -e '{\"sak_role_markers\": $1}' >/dev/null"
}

echo "a marker for each role, root only"
markers '{"common": "abc", "backend": "0123"}'
on_server "[ \"\$(cat /var/lib/sak/roles/common)\" = abc ] && [ \"\$(cat /var/lib/sak/roles/backend)\" = 0123 ]" \
  || fail "the markers"
on_server "[ \"\$(stat -c %a /var/lib/sak/roles)\" = 700 ] && [ \"\$(stat -c %a:%U /var/lib/sak/roles/common)\" = 600:root ]" \
  || fail "the markers are readable by others"

echo "the markers of the next run take their place"
markers '{"common": "def"}'
on_server "[ \"\$(cat /var/lib/sak/roles/common)\" = def ]" || fail "a marker was not written again"

echo "what sakctl reads"
read_command='for f in /var/lib/sak/roles/*; do [ -f "$f" ] && echo "${f##*/} $(cat "$f")"; done; exit 0'
[ "$(on_server "$read_command" | sort)" = "$(printf 'backend 0123\ncommon def')" ] || fail "the read of the markers"
on_server "rm -rf /var/lib/sak/roles"
[ -z "$(on_server "$read_command")" ] || fail "a server without markers answers something"

echo "all tests passed"
