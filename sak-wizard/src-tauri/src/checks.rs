use serde::Serialize;
use std::process::Command;
use tauri::ipc::Channel;
use tauri::AppHandle;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

// tried in this order, the first one running a container is used
const CONTAINER_RUNTIMES: &[&str] = &["docker", "podman"];
// fully qualified, podman refuses short names without a tty to pick the registry on,
// and pinned, the check does not depend on what `latest` means today
const CONTAINER_IMAGE: &str = "docker.io/library/alpine:3.24";
const CONTAINER_ANSWER: &str = "sak-wizard";

/// a check of what sak-wizard needs on the machine it runs on, all run in order on startup
struct Check {
    name: &'static str,
    run: fn() -> Result<String, String>,
}

const CHECKS: &[Check] = &[Check {
    name: "container runtime",
    run: container_runtime,
}];

/// the progress of the checks, sent to the splash screen
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CheckEvent {
    Started { name: &'static str },
    Passed { name: &'static str, message: String },
    Finished,
}

// a failing check shows why in a dialog and ends the app, nothing works without it
#[tauri::command]
pub async fn run_checks(app: AppHandle, on_event: Channel<CheckEvent>) -> Result<(), String> {
    for check in CHECKS {
        send(&on_event, CheckEvent::Started { name: check.name })?;
        // the checks run processes and block, off the async runtime's workers
        let result = tauri::async_runtime::spawn_blocking(check.run)
            .await
            .map_err(|e| format!("the check of the {} did not finish: {e}", check.name))?;
        match result {
            Ok(message) => send(&on_event, CheckEvent::Passed { name: check.name, message })?,
            Err(message) => {
                fail(app, &message);
                return Ok(());
            }
        }
    }
    send(&on_event, CheckEvent::Finished)
}

fn send(on_event: &Channel<CheckEvent>, event: CheckEvent) -> Result<(), String> {
    on_event
        .send(event)
        .map_err(|e| format!("the splash screen could not be updated: {e}"))
}

fn fail(app: AppHandle, message: &str) {
    eprintln!("{message}");
    app.dialog()
        .message(message)
        .title("SwissArmyKnife can not start")
        .kind(MessageDialogKind::Error)
        .buttons(MessageDialogButtons::Ok)
        .show(move |_| app.exit(1));
}

fn container_runtime() -> Result<String, String> {
    container_runtime_of(CONTAINER_RUNTIMES)
}

fn container_runtime_of(runtimes: &[&str]) -> Result<String, String> {
    let mut errors = Vec::new();
    for runtime in runtimes {
        match run_container(runtime) {
            Ok(()) => return Ok(format!("{runtime} runs containers")),
            Err(error) => errors.push(error),
        }
    }
    Err(format!(
        "SwissArmyKnife needs docker or podman to run containers. Install one of them, \
         make sure it can run containers without sudo and start SwissArmyKnife again.\n\n{}",
        errors.join("\n")
    ))
}

// a runtime is only proven to work by a container that runs and answers
fn run_container(runtime: &str) -> Result<(), String> {
    let output = command(runtime)
        .args(["run", "--rm", CONTAINER_IMAGE, "echo", CONTAINER_ANSWER])
        .output()
        .map_err(|e| format!("{runtime} could not be started: {e}"))?;
    if !output.status.success() {
        return Err(format!(
            "{runtime} could not run a container: {}",
            String::from_utf8_lossy(&output.stderr).trim()
        ));
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    let answer = stdout.trim();
    if answer != CONTAINER_ANSWER {
        return Err(format!(
            "the container of {runtime} answered '{answer}' instead of '{CONTAINER_ANSWER}'"
        ));
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
        let error = container_runtime_of(&["sak-wizard-no-such-docker", "sak-wizard-no-such-podman"])
            .unwrap_err();
        assert!(error.starts_with("SwissArmyKnife needs docker or podman"), "{error}");
        assert!(error.contains("sak-wizard-no-such-docker could not be started"), "{error}");
        assert!(error.contains("sak-wizard-no-such-podman could not be started"), "{error}");
    }
}
