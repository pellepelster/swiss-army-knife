//! `sak-backend` on the server of a deployment, the script of the role backend: its commands and
//! what its exit codes mean, in one place for the backup, the restore and the maintenance. sakctl
//! calls it through ssh (`ssh::run_streaming`); each line it writes to its standard error goes to
//! the caller while it runs, its standard output carries the data of a backup or a json answer

use crate::core::ssh::{SSH_FAILED, SshTarget, Stream, run_streaming};
use rust_i18n::t;
use serde::Deserialize;

pub const SCRIPT: &str = "sak-backend";

/// another backup, restore, update or reboot holds the lock of the script
const BUSY: i32 = 2;
/// what the shell answers for a command that is not there: a server provisioned before the script
const NOT_FOUND: i32 = 127;

/// a command of the script
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Command<'a> {
    /// writes all data of the deployment, encrypted to `recipient`, to its standard output
    Backup { recipient: &'a str },
    /// replaces the data of the deployment with the backup on its standard input
    Restore,
    /// whether a reboot is pending, as json
    RebootPending,
    /// upgrades all packages, the result as json
    Update,
    /// stops the deployment and reboots the server; with `if_pending` only when a reboot is
    /// pending. The result as json
    Reboot { if_pending: bool },
}

impl Command<'_> {
    fn arguments(&self) -> Vec<String> {
        let mut arguments = vec![SCRIPT.to_string()];
        match self {
            Command::Backup { recipient } => {
                arguments.extend(["backup".to_string(), recipient.to_string()])
            }
            Command::Restore => arguments.push("restore".to_string()),
            Command::RebootPending => arguments.push("reboot-pending".to_string()),
            Command::Update => arguments.push("update".to_string()),
            Command::Reboot { if_pending } => {
                arguments.push("reboot".to_string());
                if *if_pending {
                    arguments.push("--if-pending".to_string());
                }
            }
        }
        arguments
    }
}

/// runs `command` with its data through `stream`
pub fn run(
    runtime: &str,
    target: &SshTarget,
    command: Command,
    stream: Stream<'_>,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<(), String> {
    let (code, last_line) = run_remembering(runtime, target, command, stream, on_line)?;
    ended(code, None, last_line.as_deref())
}

/// runs `command` and reads its json answer; a command that fails answers `{"error": …}`
pub fn run_json<T: for<'de> Deserialize<'de>>(
    runtime: &str,
    target: &SshTarget,
    command: Command,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<T, String> {
    let mut out = Vec::new();
    let (code, last_line) =
        run_remembering(runtime, target, command, Stream::Out(&mut out), on_line)?;
    answer(code, &out, last_line.as_deref())
}

/// runs `command`, each line to `on_line`; its exit code and the last line, which says why ssh
/// failed when it did
fn run_remembering(
    runtime: &str,
    target: &SshTarget,
    command: Command,
    stream: Stream<'_>,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<(i32, Option<String>), String> {
    let mut last_line = None;
    let code = run_streaming(
        runtime,
        target,
        &command.arguments(),
        stream,
        &mut |line: String| {
            last_line = Some(line.clone());
            on_line(line);
        },
    )?;
    Ok((code, last_line))
}

/// the json answer of the script, or what failed
fn answer<T: for<'de> Deserialize<'de>>(
    code: i32,
    out: &[u8],
    last_line: Option<&str>,
) -> Result<T, String> {
    ended(code, Some(out), last_line)?;
    serde_json::from_slice(out)
        .map_err(|error| t!("shared.sak_backend.unreadable", error = error.to_string()).to_string())
}

/// the exit code of the script as the result: the lock held, the script missing, ssh failed, the
/// error the script answered on `out`, or its exit code
fn ended(code: i32, out: Option<&[u8]>, last_line: Option<&str>) -> Result<(), String> {
    #[derive(Deserialize)]
    struct Failure {
        error: String,
    }
    match code {
        0 => Ok(()),
        BUSY => Err(t!("shared.sak_backend.busy").to_string()),
        NOT_FOUND => Err(t!("shared.sak_backend.no_script", script = SCRIPT).to_string()),
        SSH_FAILED => Err(crate::core::ssh::failed(last_line)),
        code => match out.and_then(|out| serde_json::from_slice::<Failure>(out).ok()) {
            Some(failure) => {
                Err(t!("shared.sak_backend.failed", error = failure.error).to_string())
            }
            None => Err(t!("shared.sak_backend.ended", script = SCRIPT, code = code).to_string()),
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_command_is_the_script_and_its_arguments() {
        let words = |command: Command| command.arguments().join(" ");
        assert_eq!(
            words(Command::Backup { recipient: "age1x" }),
            "sak-backend backup age1x"
        );
        assert_eq!(words(Command::Restore), "sak-backend restore");
        assert_eq!(words(Command::RebootPending), "sak-backend reboot-pending");
        assert_eq!(words(Command::Update), "sak-backend update");
        assert_eq!(
            words(Command::Reboot { if_pending: true }),
            "sak-backend reboot --if-pending"
        );
        assert_eq!(
            words(Command::Reboot { if_pending: false }),
            "sak-backend reboot"
        );
    }

    #[test]
    fn every_exit_code_says_what_failed() {
        assert!(ended(0, None, None).is_ok());
        // the lock, for a backup as for an update
        assert_eq!(
            ended(BUSY, None, None).unwrap_err(),
            t!("shared.sak_backend.busy").to_string()
        );
        assert_eq!(
            ended(NOT_FOUND, None, None).unwrap_err(),
            t!("shared.sak_backend.no_script", script = SCRIPT).to_string()
        );
        // ssh failed itself: its own words, not an exit code of the script
        let ssh = ended(SSH_FAILED, None, Some("Host key verification failed.")).unwrap_err();
        assert!(ssh.contains("Host key verification failed."), "{ssh}");
        assert!(!ssh.contains("255"), "{ssh}");
        // the error the script answered, else its exit code
        let failed = ended(1, Some(br#"{"error": "apt-get update failed"}"#), None).unwrap_err();
        assert!(failed.contains("apt-get update failed"), "{failed}");
        let ended_with = ended(1, Some(b""), None).unwrap_err();
        assert!(
            ended_with.contains(SCRIPT) && ended_with.contains('1'),
            "{ended_with}"
        );
    }

    #[test]
    fn a_json_answer_is_read_or_unreadable() {
        #[derive(Deserialize, Debug, PartialEq)]
        struct Answer {
            rebooting: bool,
        }
        assert_eq!(
            answer::<Answer>(0, br#"{"rebooting": false}"#, None).unwrap(),
            Answer { rebooting: false }
        );
        assert!(answer::<Answer>(0, b"not json", None).is_err());
        assert!(answer::<Answer>(BUSY, b"", None).is_err());
    }
}
