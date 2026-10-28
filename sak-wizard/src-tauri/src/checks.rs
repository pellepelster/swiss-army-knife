use rust_i18n::t;
use serde::Serialize;
use std::process::Command;
use tauri::AppHandle;
use tauri::ipc::Channel;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

// tried in this order, the first one running a container is used
const CONTAINER_RUNTIMES: &[&str] = &["docker", "podman"];
// fully qualified, podman refuses short names without a tty to pick the registry on,
// and pinned, the check does not depend on what `latest` means today
const CONTAINER_IMAGE: &str = "docker.io/library/alpine:3.24";
const CONTAINER_ANSWER: &str = "sak-wizard";

/// a check of what sak-wizard needs on the machine it runs on, all run in order on startup
struct Check {
    // the key of the text shown while it runs
    checking: &'static str,
    run: fn() -> Result<String, String>,
}

const CHECKS: &[Check] = &[Check {
    checking: "checks.container_runtime.checking",
    run: container_runtime,
}];

/// the progress of the checks, sent to the splash screen with its text in the user's language
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CheckEvent {
    Started { text: String },
    Passed { text: String },
    Finished { text: String },
}

// a failing check shows why in a dialog and ends the app, nothing works without it
#[tauri::command]
pub async fn run_checks(app: AppHandle, on_event: Channel<CheckEvent>) -> Result<(), String> {
    for check in CHECKS {
        send(
            &on_event,
            CheckEvent::Started {
                text: t!(check.checking).to_string(),
            },
        )?;
        // the checks run processes and block, off the async runtime's workers
        let result = tauri::async_runtime::spawn_blocking(check.run)
            .await
            .map_err(|e| t!("checks.not_finished", error = e).to_string())?;
        match result {
            Ok(text) => send(&on_event, CheckEvent::Passed { text })?,
            Err(message) => {
                fail(app, &message);
                return Ok(());
            }
        }
    }
    send(
        &on_event,
        CheckEvent::Finished {
            text: t!("checks.finished").to_string(),
        },
    )
}

fn send(on_event: &Channel<CheckEvent>, event: CheckEvent) -> Result<(), String> {
    on_event
        .send(event)
        .map_err(|e| t!("checks.splash_not_updated", error = e).to_string())
}

fn fail(app: AppHandle, message: &str) {
    eprintln!("{message}");
    app.dialog()
        .message(message)
        .title(t!("checks.failed_title"))
        .kind(MessageDialogKind::Error)
        .buttons(MessageDialogButtons::OkCustom(
            t!("checks.quit").to_string(),
        ))
        .show(move |_| app.exit(1));
}

fn container_runtime() -> Result<String, String> {
    container_runtime_of(CONTAINER_RUNTIMES)
}

fn container_runtime_of(runtimes: &[&str]) -> Result<String, String> {
    let mut errors = Vec::new();
    for runtime in runtimes {
        match run_container(runtime) {
            Ok(()) => {
                return Ok(t!("checks.container_runtime.passed", runtime = runtime).to_string());
            }
            Err(error) => errors.push(error),
        }
    }
    Err(format!(
        "{}\n\n{}",
        t!("checks.container_runtime.needed"),
        errors.join("\n")
    ))
}

// a runtime is only proven to work by a container that runs and answers
fn run_container(runtime: &str) -> Result<(), String> {
    let output = command(runtime)
        .args(["run", "--rm", CONTAINER_IMAGE, "echo", CONTAINER_ANSWER])
        .output()
        .map_err(|e| {
            t!(
                "checks.container_runtime.not_started",
                runtime = runtime,
                error = e
            )
            .to_string()
        })?;
    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr);
        return Err(t!(
            "checks.container_runtime.no_container",
            runtime = runtime,
            error = error.trim()
        )
        .to_string());
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let answer = stdout.trim();
    if answer != CONTAINER_ANSWER {
        return Err(t!(
            "checks.container_runtime.wrong_answer",
            runtime = runtime,
            answer = answer,
            expected = CONTAINER_ANSWER
        )
        .to_string());
    }
    Ok(())
}

fn command(program: &str) -> Command {
    #[allow(unused_mut)]
    let mut command = Command::new(program);
    // no console window flashing up for every process the windows release build starts
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

#[cfg(test)]
mod tests {
    use super::*;

    fn installed(runtime: &str) -> bool {
        Command::new(runtime).arg("--version").output().is_ok()
    }

    // each runs a real container, skipped when the runtime is not installed
    #[test]
    fn docker_runs_a_container() {
        if !installed("docker") {
            eprintln!("docker is not installed, skipped");
            return;
        }
        assert_eq!(run_container("docker"), Ok(()));
    }

    #[test]
    fn podman_runs_a_container() {
        if !installed("podman") {
            eprintln!("podman is not installed, skipped");
            return;
        }
        assert_eq!(run_container("podman"), Ok(()));
    }

    #[test]
    fn the_next_runtime_is_tried_when_one_is_missing() {
        if !installed("docker") {
            eprintln!("docker is not installed, skipped");
            return;
        }
        assert_eq!(
            container_runtime_of(&["sak-wizard-no-such-runtime", "docker"]),
            Ok("docker runs containers".to_string())
        );
    }

    #[test]
    fn without_a_runtime_the_check_explains_what_is_needed() {
        let error =
            container_runtime_of(&["sak-wizard-no-such-docker", "sak-wizard-no-such-podman"])
                .unwrap_err();
        assert!(
            error.starts_with("SwissArmyKnife needs docker or podman"),
            "{error}"
        );
        assert!(
            error.contains("sak-wizard-no-such-docker could not be started"),
            "{error}"
        );
        assert!(
            error.contains("sak-wizard-no-such-podman could not be started"),
            "{error}"
        );
    }
}
