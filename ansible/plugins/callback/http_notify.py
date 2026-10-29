# Posts one JSON event for every play, task start and task result to the endpoint sakctl serves
# for the run, so sakctl can follow the provisioning task by task (docs/fdr/FDR-006). Enabled with
# ANSIBLE_CALLBACKS_ENABLED=pelle_io.sak.http_notify; the endpoint is SAK_EVENTS_URL, the token of
# the run is read from the file SAK_EVENTS_TOKEN_FILE, so it is in no environment. A post that fails
# is a warning, never a failure of the run.
from __future__ import annotations

import json
import os
import urllib.request

from ansible.plugins.callback import CallbackBase

DOCUMENTATION = """
    name: http_notify
    type: notification
    short_description: POST each play, task and result to the endpoint of sakctl
    description:
        - Sends a JSON event for every play start, task start and task result, and the counts at the end.
    options: {}
"""

# a post is given up after this many seconds, the run goes on
TIMEOUT = 2


class CallbackModule(CallbackBase):
    CALLBACK_VERSION = 2.0
    CALLBACK_TYPE = "notification"
    CALLBACK_NAME = "pelle_io.sak.http_notify"
    CALLBACK_NEEDS_ENABLED = True

    def __init__(self):
        super().__init__()
        self.url = os.environ.get("SAK_EVENTS_URL")
        self.token = None
        token_file = os.environ.get("SAK_EVENTS_TOKEN_FILE")
        if token_file:
            try:
                with open(token_file, encoding="utf-8") as file:
                    self.token = file.read().strip()
            except OSError as error:
                self._display.warning("http_notify: the token could not be read: %s" % error)

    def _post(self, event, **data):
        if not self.url or not self.token:
            return
        payload = json.dumps({"event": event, **data}, default=str).encode("utf-8")
        request = urllib.request.Request(
            self.url,
            data=payload,
            headers={"Content-Type": "application/json", "Authorization": "Bearer %s" % self.token},
        )
        try:
            urllib.request.urlopen(request, timeout=TIMEOUT).close()
        except Exception as error:  # the events never stop a run
            self._display.warning("http_notify: the event could not be posted: %s" % error)

    def v2_playbook_on_play_start(self, play):
        self._post("play_start", play=play.get_name())

    def v2_playbook_on_task_start(self, task, is_conditional):
        self._post("task_start", task=task.get_name())

    def v2_runner_on_ok(self, result):
        self._post(
            "ok",
            host=result._host.get_name(),
            task=result._task.get_name(),
            changed=bool(result._result.get("changed", False)),
        )

    def v2_runner_on_failed(self, result, ignore_errors=False):
        self._post(
            "failed",
            host=result._host.get_name(),
            task=result._task.get_name(),
            ignore_errors=bool(ignore_errors),
            msg=result._result.get("msg"),
        )

    def v2_runner_on_skipped(self, result):
        self._post("skipped", host=result._host.get_name(), task=result._task.get_name())

    def v2_runner_on_unreachable(self, result):
        self._post(
            "unreachable",
            host=result._host.get_name(),
            task=result._task.get_name(),
            msg=result._result.get("msg"),
        )

    def v2_playbook_on_stats(self, stats):
        self._post("stats", hosts={host: stats.summarize(host) for host in stats.processed.keys()})
