//! the provisioning of the server of a deployment: the container runtime runs ansible in the
//! image of the tools, with the roles of the pelle_io.sak collection in the image.
//!
//! sakctl never writes the private client key: it goes to an ssh-agent in the container through
//! the standard input. The other files of a run (inventory, ssh configuration, public client key,
//! known host key, environment of the backend, token of the events endpoint) go into a directory
//! of the run. The directory has a random name, only the user can read it, and the container
//! mounts it read-only. The directory goes when the run ends, is interrupted or the window closes;
//! the next run removes one that a killed sakctl left. Every line ansible prints and every event
//! its callback posts to the endpoint of the run (`core::events`) go to the caller

use crate::core::container::{self, ContainerRuntime};
use crate::core::events::{AnsibleEvent, EVENTS_PATH, EventServer, task_name};
use crate::core::keys::DerivedKey;
use rust_i18n::t;
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::{Mutex, mpsc};

/// where the files of a run sit inside the container
const RUN_DIR: &str = "/run/sak";

/// the playbook of the collection a server is provisioned with
pub const PLAYBOOK: &str = "pelle_io.sak.server";

/// the longest a run may take; a run takes some minutes, one that hangs (a server that does not
/// answer a task) is stopped then
const RUN_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(45 * 60);

/// a server to provision and what ansible needs to reach and set it up
#[derive(Clone)]
pub struct ProvisionRun {
    /// the name of the deployment: the host in the inventory and `sak_name`
    pub name: String,
    /// `sak_dns_mode` of the backend configuration
    pub dns_mode: sak_cloud::hetzner::deployment::DnsMode,
    /// `sak_root_domain` of the backend configuration
    pub root_domain: String,
    /// `sak_api_public_key` of the backend configuration: the public half of the api key the
    /// requests of sakctl are signed with, 32 bytes as base64
    pub api_public_key: String,
    /// the public ipv4 of the server
    pub host: String,
    /// the public ipv6 of the server, when it has one
    pub host_ipv6: Option<String>,
    /// the key the server was created with; root logs in with it
    pub client_key: DerivedKey,
    /// the host key cloud-init installed; ssh verifies the connection against it
    pub host_key: DerivedKey,
    /// the block device of the data volume, `/dev/disk/by-id/scsi-0HC_Volume_<id>`
    pub storage_device: String,
    /// `sak_admin_email`: the admin of a multi user deployment, empty for a single user one
    pub admin_email: String,
    /// the mail server of the e-mail login; none for the password login only
    pub smtp: Option<crate::core::deployments::Smtp>,
    /// `SAK_ADMIN_PASSWORD_HASH` of `backend.env`, the argon2id hash of the admin password
    pub admin_password_hash: String,
    /// `sak_acme_ca`: the acme directory caddy gets the certificates from, none for its default
    pub acme_ca: Option<String>,
}

impl ProvisionRun {
    /// the variables the roles get from the inventory, by name, each as a string. The roles take
    /// the flags with `| bool`. The fingerprints of the roles take them too (`core::roles`)
    pub(crate) fn variables(&self) -> Vec<(&'static str, String)> {
        vec![
            ("storage_device", self.storage_device.clone()),
            ("sak_name", self.name.clone()),
            ("sak_dns_mode", self.dns_mode.to_string()),
            ("sak_root_domain", self.root_domain.clone()),
            ("sak_acme_ca", self.acme_ca.clone().unwrap_or_default()),
            ("sak_api_public_key", self.api_public_key.clone()),
            ("sak_admin_email", self.admin_email.clone()),
            ("sak_smtp_enabled", self.smtp.is_some().to_string()),
            // without starttls the connection is implicit tls (smtps), never plain text
            (
                "sak_smtp_tls",
                self.smtp
                    .as_ref()
                    .is_some_and(|smtp| !smtp.starttls)
                    .to_string(),
            ),
            (
                "sak_smtp_starttls",
                self.smtp
                    .as_ref()
                    .is_some_and(|smtp| smtp.starttls)
                    .to_string(),
            ),
            ("backend_env_src", format!("{RUN_DIR}/backend.env")),
            // the server runs the images of the version of this sakctl, or of SAK_DOCKER_IMAGE_TAG
            ("sak_version", crate::core::VERSION.to_string()),
            ("sak_image_tag", crate::core::image_tag().to_string()),
        ]
    }

    /// the inventory of the run: the host, how ansible reaches it, the variables of the roles and
    /// the fingerprints the last role writes as their markers; every value quoted as json, which
    /// yaml reads as a string
    fn inventory(&self) -> String {
        let quoted = |value: &str| serde_json::to_string(value).unwrap_or_default();
        let mut text = format!(
            "all:\n  hosts:\n    {name}:\n      ansible_host: {host}\n      ansible_user: root\n      \
             ansible_ssh_common_args: -F {dir}/ssh_config\n",
            name = self.name,
            host = self.host,
            dir = RUN_DIR,
        );
        for (name, value) in self.variables() {
            text.push_str(&format!("      {name}: {}\n", quoted(&value)));
        }
        text.push_str("      sak_role_markers:\n");
        for (role, fingerprint) in crate::core::roles::fingerprints(self) {
            text.push_str(&format!("        {role}: {}\n", quoted(&fingerprint)));
        }
        text
    }

    /// the environment of the backend (`backend.env`), one `KEY=value` on each line as podman reads
    /// it: the hash of the admin password, the addresses of the server and the settings of the mail
    /// server. Secret, written for the run only
    pub(crate) fn backend_env(&self) -> String {
        let mut lines = vec![
            format!("SAK_ADMIN_PASSWORD_HASH={}", self.admin_password_hash),
            format!("SAK_IPV4={}", self.host),
            format!("SAK_IPV6={}", self.host_ipv6.clone().unwrap_or_default()),
        ];
        if let Some(smtp) = &self.smtp {
            lines.push(format!("SAK_SMTP_HOST={}", smtp.host.trim()));
            lines.push(format!("SAK_SMTP_PORT={}", smtp.port));
            lines.push(format!("SAK_SMTP_USERNAME={}", smtp.username));
            lines.push(format!("SAK_SMTP_PASSWORD={}", smtp.password));
        }
        lines.join("\n") + "\n"
    }

    /// ssh uses only the key of the agent that the public key file names
    fn ssh_config(&self) -> String {
        format!(
            "Host *\n  IdentitiesOnly yes\n  IdentityFile {dir}/id_ed25519.pub\n  UserKnownHostsFile {dir}/known_hosts\n  \
             StrictHostKeyChecking yes\n  ConnectTimeout 30\n",
            dir = RUN_DIR
        )
    }

    fn known_hosts(&self) -> String {
        let key = self.host_key.public_key();
        let mut lines = format!("{} {key}\n", self.host);
        if let Some(ipv6) = &self.host_ipv6 {
            lines.push_str(&format!("{ipv6} {key}\n"));
        }
        lines
    }
}

/// what a run hands its caller as it goes: a line ansible printed, or an event of its callback
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ProvisionOutput {
    Line(String),
    Event(AnsibleEvent),
}

/// why a run failed: the task that stopped it when its events tell it, and the message that says
/// what failed, with the task
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProvisionFailure {
    pub task: Option<String>,
    pub message: String,
}

/// a failure that no task tells, e.g. a container that did not start
impl From<String> for ProvisionFailure {
    fn from(message: String) -> Self {
        ProvisionFailure {
            task: None,
            message,
        }
    }
}

/// the arguments of `run` and the host of the url with which the container reaches the endpoint
/// of the run on the loopback of the machine. On linux the container shares the network of the
/// machine; docker desktop and a podman machine (macos, windows) give the machine a name
fn events_host(runtime: &str) -> (Vec<&'static str>, &'static str) {
    if cfg!(target_os = "linux") {
        (vec!["--network", "host"], "127.0.0.1")
    } else if runtime == "podman" {
        (Vec::new(), "host.containers.internal")
    } else {
        (Vec::new(), "host.docker.internal")
    }
}

/// manages the provisioning of servers: one run of the playbook for each call, in the image of
/// the tools
pub struct ProvisionManager;

impl ProvisionManager {
    /// runs the playbook against the server and gives every line of ansible and every event of
    /// its callback to `on_output`, in the order they come. An error when the container did not
    /// run or ansible failed; it names the failed task when the events tell it
    pub fn provision(
        &self,
        runtime: &ContainerRuntime,
        run: &ProvisionRun,
        on_output: &mut dyn FnMut(ProvisionOutput),
    ) -> Result<(), ProvisionFailure> {
        let runtime = runtime
            .get()
            .ok_or_else(|| t!("shared.provision.no_runtime").to_string())?;
        let dir = RunDir::create(runtime)?;
        dir.write_run(run)?;

        // the lines and the events go into one channel in the order they arrive; the endpoint
        // lives as long as the run
        let (sender, receiver) = mpsc::channel();
        let events = {
            let sender = sender.clone();
            EventServer::start(move |event| {
                let _ = sender.send(ProvisionOutput::Event(event));
            })?
        };
        dir.write("events_token", events.token())?;

        let mut child = container_command(runtime, &dir, events.port())?
            .spawn()
            .map_err(|e| {
                t!("shared.provision.not_started", runtime = runtime, error = e).to_string()
            })?;
        // the key, then the end of the input (the drop of `stdin`): ssh-add reads until there
        if let Some(mut stdin) = child.stdin.take() {
            let key = run.client_key.private_key();
            // the error is ignored: a container that stopped at once tells why in its output
            let _ = stdin.write_all(key.as_bytes());
        }
        let mut pumped = pump_output(
            &mut child,
            sender,
            &receiver,
            RUN_TIMEOUT,
            &mut || remove_container(runtime, &dir.container),
            on_output,
        );
        let status = child.wait().map_err(|e| e.to_string())?;
        // stop the endpoint, then pass on the events it got after the last line
        drop(events);
        for output in receiver.try_iter() {
            pumped.pass(output, on_output);
        }
        drop(dir);
        outcome(status, pumped)
    }
}

/// the command that runs the playbook in the image of the tools: a container of the name of the
/// run, as the user that owns its files, with the directory of the run mounted read-only and the
/// endpoint of the events at `events_port`. The private key comes through the standard input
fn container_command(
    runtime: &str,
    dir: &RunDir,
    events_port: u16,
) -> Result<std::process::Command, String> {
    let (network, host) = events_host(runtime);
    let mut command = container::command(runtime);
    // a name of its own, so that `stop_runs` can stop it
    command.args(["run", "--rm", "-i", "--name", &dir.container]);
    // the files belong to the user, the container reads them as that user
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        let meta = std::fs::metadata(&dir.path).map_err(|e| e.to_string())?;
        command.args(["--user", &format!("{}:{}", meta.uid(), meta.gid())]);
        if runtime == "podman" {
            command.args(["--userns=keep-id", "--security-opt", "label=disable"]);
        }
    }
    command
        .args(network)
        .args([
            "-e",
            "HOME=/tmp",
            "-e",
            "ANSIBLE_NOCOLOR=1",
            "-e",
            "PYTHONUNBUFFERED=1",
            "-e",
            "ANSIBLE_CALLBACKS_ENABLED=pelle_io.sak.http_notify",
            "-e",
            &format!("SAK_EVENTS_TOKEN_FILE={RUN_DIR}/events_token"),
            "-e",
            &format!("SAK_EVENTS_URL=http://{host}:{events_port}{EVENTS_PATH}"),
        ])
        .args(["-v", &format!("{}:{RUN_DIR}:ro", dir.path.display())])
        .arg(container::image())
        // the private key from the standard input into an agent, then ansible
        .args([
            "sh",
            "-c",
            "eval \"$(ssh-agent -s)\" >/dev/null && ssh-add -q - && exec ansible-playbook -i \"$0\" \"$1\"",
            &format!("{RUN_DIR}/inventory.yml"),
            PLAYBOOK,
        ])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    Ok(command)
}

/// what the output of a run told: the failure of a task that stopped it, and whether it took
/// longer than its timeout
#[derive(Default)]
struct Pumped {
    failed: Option<(String, Option<String>)>,
    timed_out: bool,
}

impl Pumped {
    /// passes `output` on, the failure of a task kept
    fn pass(&mut self, output: ProvisionOutput, on_output: &mut dyn FnMut(ProvisionOutput)) {
        if let ProvisionOutput::Event(event) = &output {
            self.failed = self.failed.take().or_else(|| failure_of(event));
        }
        on_output(output);
    }
}

/// both output streams of `child` line by line into the channel of `sender`, and everything of
/// `receiver` to `on_output`, until both streams are read to the end: the endpoint holds a
/// sender until it stops, so the channel does not close before. A run longer than `timeout` is
/// stopped with `stop` and a kill; its streams end once the container is gone
fn pump_output(
    child: &mut std::process::Child,
    sender: mpsc::Sender<ProvisionOutput>,
    receiver: &mpsc::Receiver<ProvisionOutput>,
    timeout: std::time::Duration,
    stop: &mut dyn FnMut(),
    on_output: &mut dyn FnMut(ProvisionOutput),
) -> Pumped {
    let mut readers = Vec::new();
    for stream in [
        child
            .stdout
            .take()
            .map(|out| Box::new(out) as Box<dyn std::io::Read + Send>),
        child
            .stderr
            .take()
            .map(|err| Box::new(err) as Box<dyn std::io::Read + Send>),
    ]
    .into_iter()
    .flatten()
    {
        let sender = sender.clone();
        readers.push(std::thread::spawn(move || {
            for line in BufReader::new(stream).lines().map_while(Result::ok) {
                if sender.send(ProvisionOutput::Line(line)).is_err() {
                    break;
                }
            }
        }));
    }
    drop(sender);
    let mut pumped = Pumped::default();
    let started = std::time::Instant::now();
    let readers_done = std::thread::spawn(move || {
        for reader in readers {
            let _ = reader.join();
        }
    });
    loop {
        match receiver.recv_timeout(std::time::Duration::from_millis(100)) {
            Ok(output) => pumped.pass(output, on_output),
            Err(mpsc::RecvTimeoutError::Timeout) if !readers_done.is_finished() => {
                if !pumped.timed_out && started.elapsed() > timeout {
                    pumped.timed_out = true;
                    stop();
                    let _ = child.kill();
                }
            }
            Err(_) => break,
        }
    }
    pumped
}

/// the result of a run from the status of its container and what its output told: a timeout,
/// else the failed task, else the exit status. A failure names the task that failed first
fn outcome(status: std::process::ExitStatus, pumped: Pumped) -> Result<(), ProvisionFailure> {
    let task = pumped.failed.as_ref().map(|(task, _)| task.clone());
    let message = if pumped.timed_out {
        t!(
            "shared.provision.timed_out",
            minutes = RUN_TIMEOUT.as_secs() / 60
        )
    } else {
        match (status.success(), pumped.failed) {
            (true, _) => return Ok(()),
            (false, Some((task, message))) => t!(
                "shared.provision.task_failed",
                task = task,
                message = message.unwrap_or_default()
            ),
            (false, None) => t!(
                "shared.provision.failed",
                status = status.code().unwrap_or(-1)
            ),
        }
    };
    Err(ProvisionFailure {
        task,
        message: message.to_string(),
    })
}

/// the task of a failure that stops the run and what ansible says about it; none for an ignored
/// failure
fn failure_of(event: &AnsibleEvent) -> Option<(String, Option<String>)> {
    match event {
        AnsibleEvent::Failed {
            task,
            msg,
            ignore_errors: false,
            ..
        }
        | AnsibleEvent::Unreachable { task, msg, .. } => Some((task_name(task), msg.clone())),
        _ => None,
    }
}

/// the runs going on, as `stop_runs` stops and removes them: the directory, the runtime and the
/// container
static RUNS: Mutex<Vec<(PathBuf, String, String)>> = Mutex::new(Vec::new());

/// the directory of a run and the name of its container. The directory has a random name below
/// the runtime directory of the user (a tmpfs only the user reads), else below the temporary
/// directory. Only the user can read it, and it goes with the run
struct RunDir {
    path: PathBuf,
    container: String,
}

impl RunDir {
    fn create(runtime: &str) -> Result<Self, String> {
        let base = std::env::var_os("XDG_RUNTIME_DIR")
            .map(PathBuf::from)
            .filter(|dir| dir.is_dir())
            .unwrap_or_else(std::env::temp_dir);
        remove_left_behind(&base);
        let name = format!(
            "{RUN_PREFIX}{}-{}",
            std::process::id(),
            crate::core::encoding::random_hex::<16>()?
        );
        Self::create_at(base.join(&name), runtime, name)
    }

    /// fails when anything exists at `path`, also a link of someone else; private from the start
    fn create_at(path: PathBuf, runtime: &str, name: String) -> Result<Self, String> {
        #[allow(unused_mut)]
        let mut builder = std::fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&path).map_err(|e| e.to_string())?;
        if let Ok(mut runs) = RUNS.lock() {
            runs.push((path.clone(), runtime.to_string(), name.clone()));
        }
        Ok(RunDir {
            path,
            container: name,
        })
    }

    /// the files of `run`: inventory, ssh configuration, known host key, public client key and
    /// environment of the backend. The token of the events endpoint follows once it runs
    fn write_run(&self, run: &ProvisionRun) -> Result<(), String> {
        self.write("inventory.yml", &run.inventory())?;
        self.write("ssh_config", &run.ssh_config())?;
        self.write("known_hosts", &run.known_hosts())?;
        self.write("id_ed25519.pub", &run.client_key.public_key())?;
        self.write("backend.env", &run.backend_env())
    }

    /// a new file only the user can read; the container reads it as the user
    fn write(&self, name: &str, content: &str) -> Result<(), String> {
        let mut options = std::fs::OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o400);
        }
        let mut file = options
            .open(self.path.join(name))
            .map_err(|e| e.to_string())?;
        file.write_all(content.as_bytes())
            .map_err(|e| e.to_string())
    }
}

impl Drop for RunDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
        if let Ok(mut runs) = RUNS.lock() {
            runs.retain(|(path, ..)| *path != self.path);
        }
    }
}

/// the start of the name of a run directory; the process id of its sakctl follows
const RUN_PREFIX: &str = "sakctl-run-";

/// stops the containers of the runs going on and removes their directories: on ctrl+c, a
/// termination, a hangup or when the window closes, when no `Drop` runs any more
pub fn stop_runs() {
    let runs = RUNS
        .lock()
        .map(|mut runs| std::mem::take(&mut *runs))
        .unwrap_or_default();
    for (path, runtime, container) in runs {
        if !path.as_os_str().is_empty() {
            let _ = std::fs::remove_dir_all(&path);
        }
        remove_container(&runtime, &container);
    }
}

/// a container without a directory of its own that `stop_runs` removes too, e.g. the one of an ssh
/// session; `untrack_container` when it is gone
pub(crate) fn track_container(runtime: &str, name: &str) {
    if let Ok(mut runs) = RUNS.lock() {
        runs.push((PathBuf::new(), runtime.to_string(), name.to_string()));
    }
}

pub(crate) fn untrack_container(name: &str) {
    if let Ok(mut runs) = RUNS.lock() {
        runs.retain(|(_, _, container)| container != name);
    }
}

/// stops and removes the container of a run, whatever state it is in
pub(crate) fn remove_container(runtime: &str, name: &str) {
    let _ = container::command(runtime)
        .args(["rm", "--force", name])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

/// stops the runs on ctrl+c, a termination or a hangup (unix), then ends the process with 130
pub fn stop_runs_on_signal() {
    // a runtime of its own: the listener lives as long as the process
    let Ok(listener) = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
    else {
        return;
    };
    std::thread::spawn(move || {
        listener.block_on(async {
            #[cfg(unix)]
            {
                use tokio::signal::unix::{SignalKind, signal};
                let (Ok(mut terminate), Ok(mut hangup)) = (
                    signal(SignalKind::terminate()),
                    signal(SignalKind::hangup()),
                ) else {
                    return;
                };
                tokio::select! {
                    _ = tokio::signal::ctrl_c() => {}
                    _ = terminate.recv() => {}
                    _ = hangup.recv() => {}
                }
            }
            #[cfg(not(unix))]
            if tokio::signal::ctrl_c().await.is_err() {
                return;
            }
            stop_runs();
            std::process::exit(130);
        })
    });
}

/// removes the run directories that a killed sakctl left: the process id in their name names no
/// live process. Only on unix; elsewhere every process counts as alive
fn remove_left_behind(base: &Path) {
    let Ok(entries) = std::fs::read_dir(base) else {
        return;
    };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(pid) = name
            .strip_prefix(RUN_PREFIX)
            .and_then(|rest| rest.split_once('-'))
            .and_then(|(pid, _)| pid.parse::<u32>().ok())
        else {
            continue;
        };
        if pid != std::process::id() && !alive(pid) {
            let _ = std::fs::remove_dir_all(entry.path());
        }
    }
}

/// whether the process `pid` lives: signal 0 checks without sending anything. A process of
/// another user answers EPERM, and lives too
#[cfg(unix)]
fn alive(pid: u32) -> bool {
    let Ok(pid) = libc::pid_t::try_from(pid) else {
        return false;
    };
    // SAFETY: kill with signal 0 sends no signal and touches no memory of this process
    let answer = unsafe { libc::kill(pid, 0) };
    answer == 0 || std::io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

#[cfg(not(unix))]
fn alive(_pid: u32) -> bool {
    true
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::core::keys::{derive_host_key, derive_key};

    #[cfg(unix)]
    #[test]
    fn a_process_lives_also_when_it_belongs_to_another_user() {
        assert!(alive(std::process::id()));
        // the first process belongs to root: signal 0 is refused with EPERM, it lives
        assert!(alive(1));
        // no process has the largest id a pid_t can hold
        assert!(!alive(libc::pid_t::MAX as u32));
    }

    pub(crate) fn run() -> ProvisionRun {
        ProvisionRun {
            name: "alpha".to_string(),
            dns_mode: sak_cloud::hetzner::deployment::DnsMode::Domain,
            root_domain: "pelle.io".to_string(),
            api_public_key: "PUBLIC+KEY/=".to_string(),
            host: "203.0.113.1".to_string(),
            host_ipv6: Some("2001:db8::1".to_string()),
            client_key: derive_key("alpha", "a password of sixteen").unwrap(),
            host_key: derive_host_key("alpha", "a password of sixteen").unwrap(),
            storage_device: "/dev/disk/by-id/scsi-0HC_Volume_1".to_string(),
            admin_email: "admin@example.org".to_string(),
            smtp: Some(crate::core::deployments::Smtp {
                host: "smtp.example.org".to_string(),
                port: 465,
                username: "user".to_string(),
                password: "p@$$ w0rd#!".to_string(),
                starttls: false,
            }),
            admin_password_hash: "$argon2id$v=19$m=19456,t=2,p=1$salt$hash".to_string(),
            acme_ca: Some("https://acme-staging-v02.api.letsencrypt.org/directory".to_string()),
        }
    }

    #[test]
    fn a_failure_that_stops_the_run_names_its_task() {
        let failed = |ignore_errors| AnsibleEvent::Failed {
            host: "alpha".into(),
            task: "Start the backend".into(),
            msg: Some("no".into()),
            ignore_errors,
        };
        assert_eq!(
            failure_of(&failed(false)),
            Some(("Start the backend".into(), Some("no".into())))
        );
        assert_eq!(failure_of(&failed(true)), None);
        assert!(
            failure_of(&AnsibleEvent::Unreachable {
                host: "alpha".into(),
                task: "Gathering Facts".into(),
                msg: None
            })
            .is_some()
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_failed_run_names_the_task_that_failed_first() {
        use std::os::unix::process::ExitStatusExt;
        let failed = std::process::ExitStatus::from_raw(2 << 8);
        let pumped = |timed_out| Pumped {
            failed: Some(("Start the backend".into(), Some("no".into()))),
            timed_out,
        };
        let failure = outcome(failed, pumped(false)).unwrap_err();
        assert_eq!(failure.task.as_deref(), Some("Start the backend"));
        assert!(failure.message.contains("Start the backend"), "{failure:?}");
        // a timeout says so, the task stays
        let failure = outcome(failed, pumped(true)).unwrap_err();
        assert_eq!(failure.task.as_deref(), Some("Start the backend"));
        // a run that ends well has no failure, whatever its events told
        let ok = std::process::ExitStatus::from_raw(0);
        assert_eq!(outcome(ok, pumped(false)), Ok(()));
        // without a task the status tells
        let failure = outcome(failed, Pumped::default()).unwrap_err();
        assert_eq!(failure.task, None);
    }

    #[test]
    fn the_container_reaches_the_events_endpoint_on_the_loopback() {
        let (args, host) = events_host("docker");
        if cfg!(target_os = "linux") {
            assert_eq!((args, host), (vec!["--network", "host"], "127.0.0.1"));
        }
    }

    #[test]
    fn the_backend_gets_the_admin_password_hash_the_addresses_and_the_mail_server() {
        let env = run().backend_env();
        assert_eq!(
            env,
            "SAK_ADMIN_PASSWORD_HASH=$argon2id$v=19$m=19456,t=2,p=1$salt$hash\n\
             SAK_IPV4=203.0.113.1\nSAK_IPV6=2001:db8::1\n\
             SAK_SMTP_HOST=smtp.example.org\nSAK_SMTP_PORT=465\nSAK_SMTP_USERNAME=user\n\
             SAK_SMTP_PASSWORD=p@$$ w0rd#!\n"
        );
        // no key of a secret: the database is plain until it is encrypted as a whole
        assert!(!env.contains("SECRET_KEY"));

        let without_smtp = ProvisionRun {
            smtp: None,
            ..run()
        };
        assert!(!without_smtp.backend_env().contains("SAK_SMTP"));
    }

    #[test]
    fn the_inventory_tells_the_backend_role_the_admin_and_the_mail_server() {
        let inventory = run().inventory();
        assert!(inventory.contains("      sak_admin_email: \"admin@example.org\"\n"));
        assert!(inventory.contains(
            "      sak_acme_ca: \"https://acme-staging-v02.api.letsencrypt.org/directory\"\n"
        ));
        assert!(inventory.contains("      sak_smtp_enabled: \"true\"\n"));
        // without starttls implicit tls, never plain text
        assert!(
            inventory
                .contains("      sak_smtp_tls: \"true\"\n      sak_smtp_starttls: \"false\"\n")
        );
        assert!(inventory.contains("      backend_env_src: \"/run/sak/backend.env\"\n"));
        assert!(inventory.contains(&format!(
            "      sak_version: \"{}\"\n",
            crate::core::VERSION
        )));

        let mut given = run();
        if let Some(smtp) = given.smtp.as_mut() {
            smtp.starttls = true;
        }
        let inventory = given.inventory();
        assert!(
            inventory
                .contains("      sak_smtp_tls: \"false\"\n      sak_smtp_starttls: \"true\"\n")
        );

        let without_smtp = ProvisionRun {
            smtp: None,
            ..run()
        }
        .inventory();
        assert!(without_smtp.contains("      sak_smtp_enabled: \"false\"\n"));
    }

    #[test]
    fn the_inventory_names_the_host_its_key_and_its_volume() {
        let inventory = run().inventory();
        assert!(inventory.contains("    alpha:\n      ansible_host: 203.0.113.1\n"));
        // what the backend role writes into the configuration of the backend
        assert!(inventory.contains(
            "      sak_name: \"alpha\"\n      sak_dns_mode: \"domain\"\n      sak_root_domain: \"pelle.io\"\n      sak_acme_ca: \"https://acme-staging-v02.api.letsencrypt.org/directory\"\n      sak_api_public_key: \"PUBLIC+KEY/=\"\n"
        ));
        // no private key file, the key is in the agent of the container
        assert!(!inventory.contains("private_key_file"));
        assert!(
            run()
                .ssh_config()
                .contains("IdentityFile /run/sak/id_ed25519.pub\n")
        );
        assert!(inventory.contains("storage_device: \"/dev/disk/by-id/scsi-0HC_Volume_1\""));
    }

    #[test]
    fn the_known_hosts_hold_the_host_key_for_both_addresses() {
        let run = run();
        let known = run.known_hosts();
        let key = run.host_key.public_key();
        assert_eq!(known, format!("203.0.113.1 {key}\n2001:db8::1 {key}\n"));
        assert!(run.ssh_config().contains("StrictHostKeyChecking yes"));
    }

    #[test]
    fn the_run_dir_is_private_has_a_random_name_and_is_gone_afterwards() {
        let (path, other) = {
            let dir = RunDir::create("true").unwrap();
            let other = RunDir::create("true").unwrap();
            assert_ne!(dir.path, other.path);
            assert_ne!(dir.container, other.container);
            dir.write("backend.env", "secret").unwrap();
            // a file is written once, never through what is there
            assert!(dir.write("backend.env", "other").is_err());
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let mode =
                    |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
                assert_eq!(mode(&dir.path), 0o700);
                assert_eq!(mode(&dir.path.join("backend.env")), 0o400);
            }
            (dir.path.clone(), other)
        };
        assert!(!path.exists());
        // what stop_runs removes when no drop runs any more
        assert!(other.path.exists());
        stop_runs();
        assert!(!other.path.exists());
    }

    #[test]
    fn a_run_dir_is_never_created_where_something_is() {
        let base = std::env::temp_dir().join(format!(
            "sakctl-test-{}",
            crate::core::encoding::random_hex::<16>().unwrap()
        ));
        std::fs::create_dir(&base).unwrap();
        let taken = base.join("taken");
        std::fs::create_dir(&taken).unwrap();
        assert!(RunDir::create_at(taken.clone(), "true", "taken".into()).is_err());
        // a link another user put there, to a directory of the victim
        #[cfg(unix)]
        {
            let link = base.join("link");
            std::os::unix::fs::symlink(&taken, &link).unwrap();
            assert!(RunDir::create_at(link, "true", "link".into()).is_err());
            assert_eq!(std::fs::read_dir(&taken).unwrap().count(), 0);
        }
        std::fs::remove_dir_all(&base).unwrap();
    }

    #[test]
    fn a_run_dir_a_killed_sakctl_left_behind_goes_with_the_next_run() {
        let base = std::env::temp_dir().join(format!(
            "sakctl-test-{}",
            crate::core::encoding::random_hex::<16>().unwrap()
        ));
        std::fs::create_dir(&base).unwrap();
        // no process has this id, pid_max is much lower
        let gone = base.join(format!("{RUN_PREFIX}4294967294-ab"));
        let running = base.join(format!("{RUN_PREFIX}{}-ab", std::process::id()));
        let foreign = base.join("other-1-ab");
        for dir in [&gone, &running, &foreign] {
            std::fs::create_dir(dir).unwrap();
        }
        remove_left_behind(&base);
        if cfg!(unix) {
            assert!(!gone.exists());
        }
        assert!(running.exists());
        assert!(foreign.exists());
        std::fs::remove_dir_all(&base).unwrap();
    }
}
