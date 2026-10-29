#!/bin/sh
# runs in the image of the tools (mise run cli:image:test): a playbook of one task with the
# callback pelle_io.sak.http_notify, posting to a stub that stands for the endpoint of sakctl; the
# event of the task has to arrive with the token of the run
set -e
printf token > /tmp/token
cat > /tmp/play.yml <<'PLAY'
- hosts: localhost
  gather_facts: false
  tasks:
    - name: Say hello
      ansible.builtin.command: echo hello
PLAY
python3 /test/stub.py > /tmp/posts &
sleep 1
ANSIBLE_CALLBACKS_ENABLED=pelle_io.sak.http_notify \
  SAK_EVENTS_URL=http://127.0.0.1:8765/events \
  SAK_EVENTS_TOKEN_FILE=/tmp/token \
  ansible-playbook /tmp/play.yml > /dev/null 2>&1
grep -q '^Bearer token {"event": "ok", "host": "localhost", "task": "Say hello", "changed": true}$' /tmp/posts
echo "the callback posts its events"
