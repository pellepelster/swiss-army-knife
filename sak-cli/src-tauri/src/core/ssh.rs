//! an ssh session as root on the server of a deployment, run in the image of the tools like the
//! provisioning, in one container of its own. The private client key is never written and is in no
//! environment: the container runs an ssh-agent, and the key goes to it through the standard input
//! of `ssh-add` (`exec`). The session is a second `exec` with the terminal of sakctl, which a
//! standard input that carried the key could no longer be. The host key derived from the password
//! is the only known host, so the server is verified, not trusted on first use. The container goes
//! afterwards, also on a termination (`provision::stop_runs`). A command can also run with a
//! stream instead of the terminal (`run_streaming`), the way a backup leaves the server and a
//! restore reaches it

use crate::core::container;
use crate::core::encoding::random_hex;
use crate::core::keys::DerivedKey;
use crate::core::provision::{remove_container, track_container, untrack_container};
use rust_i18n::t;
use std::io::{BufRead, BufReader, Read, Write};
use std::process::{Command, Stdio};

/// where the agent listens and the known hosts are, inside the container
const AGENT_SOCKET: &str = "/tmp/sak-agent.sock";
const KNOWN_HOSTS: &str = "/tmp/sak-known-hosts";

/// ssh asks the server every 15 seconds and gives up after 4 asks without an answer
const ALIVE_INTERVAL_SECONDS: u32 = 15;
const ALIVE_COUNT: u32 = 4;

/// what ssh answers when it fails itself: the connection, the host key, the client key
pub const SSH_FAILED: i32 = 255;

/// the error of an ssh that failed itself, in its own words: the last line it wrote
pub fn failed(last_line: Option<&str>) -> String {
    t!("shared.ssh.failed", error = last_line.unwrap_or_default()).to_string()
}

/// the server of a deployment and the keys to reach it
pub struct SshTarget {
    /// the public ipv4 of the server, ssh connects to it
    pub host: String,
    /// the public ipv6 of the server, a known host too
    pub host_ipv6: Option<String>,
    pub client_key: DerivedKey,
    pub host_key: DerivedKey,
}

impl SshTarget {
    /// the host key for both addresses, as ssh reads a known hosts file
    fn known_hosts(&self) -> String {
        let key = self.host_key.public_key();
        std::iter::once(&self.host)
            .chain(self.host_ipv6.as_ref())
            .map(|address| format!("{address} {key}\n"))
            .collect()
    }
}

/// the arguments of `<runtime> exec` that write the known hosts (public, an argument) and run ssh
/// in the container: with a terminal for a session, `command` on the server when it is given
fn ssh_arguments(
    container: &str,
    host: &str,
    known_hosts: &str,
    terminal: bool,
    command: &[String],
) -> Vec<String> {
    let mut arguments = vec!["exec".to_string(), "-i".to_string()];
    if terminal {
        arguments.push("-t".to_string());
    }
    arguments.extend([
        "-e".to_string(),
        format!("SSH_AUTH_SOCK={AGENT_SOCKET}"),
        container.to_string(),
        "sh".to_string(),
        "-c".to_string(),
        format!("printf '%s' \"$0\" > {KNOWN_HOSTS} && exec \"$@\""),
        known_hosts.to_string(),
        "ssh".to_string(),
        "-o".to_string(),
        format!("UserKnownHostsFile={KNOWN_HOSTS}"),
        "-o".to_string(),
        "StrictHostKeyChecking=yes".to_string(),
        "-o".to_string(),
        "ConnectTimeout=30".to_string(),
        // a connection that drops (the network of the laptop, the server gone) ends ssh after
        // a minute without an answer, instead of the hours of the keepalive of tcp; a backup or
        // restore would wait that long otherwise
        "-o".to_string(),
        format!("ServerAliveInterval={ALIVE_INTERVAL_SECONDS}"),
        "-o".to_string(),
        format!("ServerAliveCountMax={ALIVE_COUNT}"),
    ]);
    if terminal {
        arguments.push("-t".to_string());
    }
    arguments.push(format!("root@{host}"));
    arguments.extend(command.iter().cloned());
    arguments
}

/// runs `<runtime> exec -i <container> <arguments>` with `input` on its standard input
fn exec_with_input(
    runtime: &str,
    container: &str,
    arguments: &[&str],
    input: &str,
) -> Result<(), String> {
    let mut child = container::command(runtime)
        .args([
            "exec",
            "-i",
            "-e",
            &format!("SSH_AUTH_SOCK={AGENT_SOCKET}"),
            container,
        ])
        .args(arguments)
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| error.to_string())?;
    if let Some(mut stdin) = child.stdin.take() {
        stdin
            .write_all(input.as_bytes())
            .map_err(|error| error.to_string())?;
    }
    let output = child
        .wait_with_output()
        .map_err(|error| error.to_string())?;
    if output.status.success() {
        Ok(())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

/// the container of a session: started with an agent, removed when it is dropped
struct SessionContainer {
    runtime: String,
    name: String,
}

impl SessionContainer {
    fn start(runtime: &str) -> Result<Self, String> {
        let name = format!("sakctl-ssh-{}-{}", std::process::id(), random_hex::<16>()?);
        track_container(runtime, &name);
        let container = SessionContainer {
            runtime: runtime.to_string(),
            name,
        };
        let status = container::command(runtime)
            .args([
                "run",
                "-d",
                "--rm",
                "--name",
                &container.name,
                container::image(),
            ])
            .args(["ssh-agent", "-D", "-a", AGENT_SOCKET])
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .status()
            .map_err(|error| {
                t!("cli.ssh_not_started", runtime = runtime, error = error).to_string()
            })?;
        if !status.success() {
            return Err(t!("cli.ssh_not_started", runtime = runtime, error = status).to_string());
        }
        // the agent listens a moment after the container started
        for _ in 0..50 {
            let ready = container::command(runtime)
                .args(["exec", &container.name, "test", "-S", AGENT_SOCKET])
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|status| status.success());
            if ready {
                return Ok(container);
            }
            std::thread::sleep(std::time::Duration::from_millis(100));
        }
        Err(t!(
            "cli.ssh_not_started",
            runtime = runtime,
            error = "ssh-agent"
        )
        .to_string())
    }
}

impl Drop for SessionContainer {
    fn drop(&mut self) {
        remove_container(&self.runtime, &self.name);
        untrack_container(&self.name);
    }
}

/// the terminal sakctl runs in, also when its own input and output are pipes (a mise task, the dev
/// runner of the window); none without one, e.g. in a script, and off unix, where the session takes the
/// input of sakctl
fn controlling_terminal() -> Option<std::fs::File> {
    if cfg!(unix) {
        std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/tty")
            .ok()
    } else {
        None
    }
}

/// a session as root on the server in the terminal sakctl runs in, or `command` run on the server
/// with the input and output of sakctl; the exit code of ssh
pub fn open_session(runtime: &str, target: &SshTarget, command: &[String]) -> Result<i32, String> {
    use std::io::IsTerminal;
    let container = SessionContainer::start(runtime)?;
    exec_with_input(
        runtime,
        &container.name,
        &["ssh-add", "-q", "-"],
        &target.client_key.private_key(),
    )?;
    let session = command.is_empty();
    let tty = session.then(controlling_terminal).flatten();
    let terminal = tty.is_some() || (session && std::io::stdin().is_terminal());
    let mut ssh = Command::new(runtime);
    ssh.args(ssh_arguments(
        &container.name,
        &target.host,
        &target.known_hosts(),
        terminal,
        command,
    ));
    // the session reads from and writes to the terminal itself, not to what sakctl was given
    if let Some(tty) = tty {
        let clone = |file: &std::fs::File| file.try_clone().map_err(|error| error.to_string());
        ssh.stdin(clone(&tty)?).stdout(clone(&tty)?).stderr(tty);
    }
    let status = ssh.status().map_err(|error| error.to_string())?;
    Ok(status.code().unwrap_or(1))
}

/// the data of a command run with `run_streaming`: its standard output written to a writer, or
/// its standard input read from a reader
pub enum Stream<'a> {
    Out(&'a mut (dyn Write + Send)),
    In(&'a mut (dyn Read + Send)),
}

/// `command` on the server, without a terminal, its data through `stream`; each line it writes to
/// its standard error goes to `on_line`, while it runs. ssh joins the words of `command` with
/// spaces into one line for the shell of the server, so a word is never quoted: each must be a
/// word the shell takes as it is (no space, no quote), or the whole command one line for the shell. The exit code of ssh. A stream that fails
/// on the side of sakctl (a full disk, a backup that does not decrypt) is the error, whatever the
/// command answered
pub fn run_streaming(
    runtime: &str,
    target: &SshTarget,
    command: &[String],
    stream: Stream<'_>,
    on_line: &mut (dyn FnMut(String) + Send),
) -> Result<i32, String> {
    let container = SessionContainer::start(runtime)?;
    exec_with_input(
        runtime,
        &container.name,
        &["ssh-add", "-q", "-"],
        &target.client_key.private_key(),
    )?;
    let mut ssh = Command::new(runtime);
    ssh.args(ssh_arguments(
        &container.name,
        &target.host,
        &target.known_hosts(),
        false,
        command,
    ))
    .stderr(Stdio::piped());
    match stream {
        Stream::Out(_) => ssh.stdin(Stdio::null()).stdout(Stdio::piped()),
        Stream::In(_) => ssh.stdin(Stdio::piped()).stdout(Stdio::null()),
    };
    let mut child = ssh.spawn().map_err(|error| error.to_string())?;
    let stderr = child.stderr.take();
    let copied = std::thread::scope(|scope| {
        if let Some(stderr) = stderr {
            scope.spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    on_line(line);
                }
            });
        }
        match stream {
            Stream::Out(writer) => match child.stdout.take() {
                Some(mut stdout) => std::io::copy(&mut stdout, writer).and_then(|_| writer.flush()),
                None => Ok(()),
            },
            Stream::In(reader) => match child.stdin.take() {
                // dropped at the end, the command reads the end of its input
                Some(mut stdin) => std::io::copy(reader, &mut stdin).map(|_| ()),
                None => Ok(()),
            },
        }
    });
    let status = child.wait().map_err(|error| error.to_string())?;
    let code = status.code().unwrap_or(1);
    match copied {
        // the command ended before it read everything, its exit code tells why
        Err(error) if error.kind() == std::io::ErrorKind::BrokenPipe && code != 0 => Ok(code),
        Err(error) => Err(error.to_string()),
        Ok(()) => Ok(code),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::keys::{derive_host_key, derive_key};

    fn target() -> SshTarget {
        SshTarget {
            host: "203.0.113.1".to_string(),
            host_ipv6: Some("2001:db8::1".to_string()),
            client_key: derive_key("alpha", "a password of sixteen").unwrap(),
            host_key: derive_host_key("alpha", "a password of sixteen").unwrap(),
        }
    }

    #[test]
    fn the_server_is_known_by_its_derived_host_key_on_both_addresses() {
        let target = target();
        let key = target.host_key.public_key();
        assert_eq!(
            target.known_hosts(),
            format!("203.0.113.1 {key}\n2001:db8::1 {key}\n")
        );
    }

    #[test]
    fn ssh_runs_as_root_with_the_agent_checks_the_host_key_and_notices_a_dropped_connection() {
        let session = ssh_arguments("c", "203.0.113.1", "KNOWN", true, &[]);
        assert_eq!(
            &session[..6],
            [
                "exec",
                "-i",
                "-t",
                "-e",
                &format!("SSH_AUTH_SOCK={AGENT_SOCKET}"),
                "c"
            ]
        );
        // the known hosts written first, then ssh in their place
        assert_eq!(
            session[8],
            format!("printf '%s' \"$0\" > {KNOWN_HOSTS} && exec \"$@\"")
        );
        assert_eq!(session[9], "KNOWN");
        assert_eq!(
            session[10..].join(" "),
            format!(
                "ssh -o UserKnownHostsFile={KNOWN_HOSTS} -o StrictHostKeyChecking=yes -o ConnectTimeout=30 \
                 -o ServerAliveInterval=15 -o ServerAliveCountMax=4 -t root@203.0.113.1"
            )
        );
        // a command runs without a terminal, its arguments as given
        let command = ssh_arguments(
            "c",
            "203.0.113.1",
            "KNOWN",
            false,
            &["uptime".into(), "-p".into()],
        );
        assert!(!command.contains(&"-t".to_string()));
        assert_eq!(
            &command[command.len() - 3..],
            ["root@203.0.113.1", "uptime", "-p"]
        );
    }

    /// the integration tests (`mise run cli:test:integration`), with docker and the image of the
    /// tools, skipped when the image is not there
    mod integration {
        use super::*;

        /// with docker and the image of the tools: the agent of the container holds the client key,
        /// no file has it, and the container is gone afterwards
        #[test]
        fn the_agent_of_the_session_holds_the_key_and_the_container_goes() {
            let runtime = "docker";
            let image_there = container::command(runtime)
                .args(["image", "inspect", container::image()])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|status| status.success());
            if !image_there {
                eprintln!("no docker with {}, skipped", container::image());
                return;
            }
            let target = target();
            let name = {
                let container = SessionContainer::start(runtime).unwrap();
                exec_with_input(
                    runtime,
                    &container.name,
                    &["ssh-add", "-q", "-"],
                    &target.client_key.private_key(),
                )
                .unwrap();
                let listed = container::command(runtime)
                    .args([
                        "exec",
                        "-e",
                        &format!("SSH_AUTH_SOCK={AGENT_SOCKET}"),
                        &container.name,
                        "ssh-add",
                        "-L",
                    ])
                    .output()
                    .unwrap();
                let blob = target
                    .client_key
                    .public_key()
                    .split(' ')
                    .nth(1)
                    .unwrap()
                    .to_string();
                assert!(String::from_utf8_lossy(&listed.stdout).contains(&blob));
                container.name.clone()
            };
            let gone = container::command(runtime)
                .args(["inspect", &name])
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .status()
                .is_ok_and(|status| !status.success());
            assert!(gone, "the container {name} is still there");
        }
    }
}
