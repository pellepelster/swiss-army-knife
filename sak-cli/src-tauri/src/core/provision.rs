//! the provisioning of the server of a deployment: ansible in the image of the tools, run
//! by the container runtime, against the server with the roles of the pelle_io.sak
//! collection the image holds. The inventory, the ssh configuration, the client key and
//! the known host key are written to a directory of their own for the run and mounted into
//! the container read-only; every line ansible prints goes to the caller

use crate::core::container::{self, ContainerRuntime};
use crate::core::keys::DerivedKey;
use rust_i18n::t;
use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::mpsc;

/// where the files of a run sit inside the container
const RUN_DIR: &str = "/run/sak";

/// the playbook of the collection a server is provisioned with
pub const PLAYBOOK: &str = "pelle_io.sak.server";

/// a server to provision and what ansible needs to reach and set it up
pub struct ProvisionRun {
    /// the sak name, the host in the inventory and `sak_name` of the controller configuration
    pub name: String,
    /// `sak_dns_mode` of the controller configuration
    pub dns_mode: sak_cloud::hetzner::deployment::DnsMode,
    /// `sak_root_domain` of the controller configuration
    pub root_domain: String,
    /// `sak_api_token_sha256` of the controller configuration: the sha-256 of the admin api
    /// token, never the token
    pub api_token_sha256: String,
    /// the public ipv4 of the server
    pub host: String,
    /// the public ipv6 of the server, when it has one
    pub host_ipv6: Option<String>,
    /// the key the server was created with, root logs in with it
    pub client_key: DerivedKey,
    /// the host key cloud-init installed, the connection is verified against it
    pub host_key: DerivedKey,
    /// the block device of the data volume, `/dev/disk/by-id/scsi-0HC_Volume_<id>`
    pub storage_device: String,
}

impl ProvisionRun {
    fn inventory(&self) -> String {
        format!(
            "all:\n  hosts:\n    {name}:\n      ansible_host: {host}\n      ansible_user: root\n      \
             ansible_ssh_private_key_file: {dir}/id_ed25519\n      ansible_ssh_common_args: -F {dir}/ssh_config\n      \
             storage_device: {device}\n      sak_name: {name}\n      sak_dns_mode: {dns_mode}\n      sak_root_domain: {root_domain}\n      sak_api_token_sha256: {api_token_sha256}\n",
            name = self.name,
            host = self.host,
            dir = RUN_DIR,
            device = self.storage_device,
            dns_mode = self.dns_mode,
            root_domain = self.root_domain,
            api_token_sha256 = self.api_token_sha256
        )
    }

    fn ssh_config(&self) -> String {
        format!(
            "Host *\n  IdentitiesOnly yes\n  IdentityFile {dir}/id_ed25519\n  UserKnownHostsFile {dir}/known_hosts\n  \
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

/// manages the provisioning of servers: one run of the playbook per call, in the image
/// of the tools
pub struct ProvisionManager;

impl ProvisionManager {
    /// runs the playbook against the server, every line of ansible's output to `on_line`;
    /// an error when ansible failed or the container could not run
    pub fn provision(
        &self,
        runtime: &ContainerRuntime,
        run: &ProvisionRun,
        on_line: &mut dyn FnMut(String),
    ) -> Result<(), String> {
        let runtime = runtime
            .get()
            .ok_or_else(|| t!("provision.no_runtime").to_string())?;
        let dir = RunDir::create(&run.name)?;
        dir.write("inventory.yml", &run.inventory(), false)?;
        dir.write("ssh_config", &run.ssh_config(), false)?;
        dir.write("known_hosts", &run.known_hosts(), false)?;
        dir.write("id_ed25519", &run.client_key.private_key(), true)?;

        let mut command = container::command(runtime);
        command.args(["run", "--rm"]);
        // the files are the user's, the container reads them as that user
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
            .args([
                "-e",
                "HOME=/tmp",
                "-e",
                "ANSIBLE_NOCOLOR=1",
                "-e",
                "PYTHONUNBUFFERED=1",
            ])
            .args(["-v", &format!("{}:{RUN_DIR}:ro", dir.path.display())])
            .arg(container::IMAGE)
            .args([
                "ansible-playbook",
                "-i",
                &format!("{RUN_DIR}/inventory.yml"),
                PLAYBOOK,
            ])
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let mut child = command
            .spawn()
            .map_err(|e| t!("provision.not_started", runtime = runtime, error = e).to_string())?;
        // both streams line by line into one channel, in the order they arrive
        let (sender, receiver) = mpsc::channel();
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
                    if sender.send(line).is_err() {
                        break;
                    }
                }
            }));
        }
        drop(sender);
        for line in receiver {
            on_line(line);
        }
        for reader in readers {
            let _ = reader.join();
        }
        let status = child.wait().map_err(|e| e.to_string())?;
        drop(dir);
        if status.success() {
            Ok(())
        } else {
            Err(t!("provision.failed", status = status.code().unwrap_or(-1)).to_string())
        }
    }
}

/// the directory of a run, the user's alone, gone with the run
struct RunDir {
    path: PathBuf,
}

impl RunDir {
    fn create(name: &str) -> Result<Self, String> {
        let path = std::env::temp_dir().join(format!("sakctl-{name}-{}", std::process::id()));
        if path.exists() {
            std::fs::remove_dir_all(&path).map_err(|e| e.to_string())?;
        }
        std::fs::create_dir_all(&path).map_err(|e| e.to_string())?;
        set_mode(&path, 0o700)?;
        Ok(RunDir { path })
    }

    fn write(&self, name: &str, content: &str, secret: bool) -> Result<(), String> {
        let path = self.path.join(name);
        std::fs::write(&path, content).map_err(|e| e.to_string())?;
        set_mode(&path, if secret { 0o600 } else { 0o644 })
    }
}

impl Drop for RunDir {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.path);
    }
}

#[cfg(unix)]
fn set_mode(path: &Path, mode: u32) -> Result<(), String> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode)).map_err(|e| e.to_string())
}

#[cfg(not(unix))]
fn set_mode(_path: &Path, _mode: u32) -> Result<(), String> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::keys::{derive_host_key, derive_key};

    fn run() -> ProvisionRun {
        ProvisionRun {
            name: "alpha".to_string(),
            dns_mode: sak_cloud::hetzner::deployment::DnsMode::Domain,
            root_domain: "pelle.io".to_string(),
            api_token_sha256: "ab".repeat(32),
            host: "203.0.113.1".to_string(),
            host_ipv6: Some("2001:db8::1".to_string()),
            client_key: derive_key("alpha", "a password of sixteen").unwrap(),
            host_key: derive_host_key("alpha", "a password of sixteen").unwrap(),
            storage_device: "/dev/disk/by-id/scsi-0HC_Volume_1".to_string(),
        }
    }

    #[test]
    fn the_inventory_names_the_host_its_key_and_its_volume() {
        let inventory = run().inventory();
        assert!(inventory.contains("    alpha:\n      ansible_host: 203.0.113.1\n"));
        // what the controller role writes into the configuration of the backend
        assert!(inventory.contains(
            "      sak_name: alpha\n      sak_dns_mode: domain\n      sak_root_domain: pelle.io\n      sak_api_token_sha256: abab"
        ));
        assert!(inventory.contains("ansible_ssh_private_key_file: /run/sak/id_ed25519"));
        assert!(inventory.contains("storage_device: /dev/disk/by-id/scsi-0HC_Volume_1"));
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
    fn the_run_dir_is_private_and_gone_afterwards() {
        let path = {
            let dir = RunDir::create("test").unwrap();
            dir.write("id_ed25519", "secret", true).unwrap();
            #[cfg(unix)]
            {
                use std::os::unix::fs::PermissionsExt;
                let mode = std::fs::metadata(dir.path.join("id_ed25519"))
                    .unwrap()
                    .permissions()
                    .mode();
                assert_eq!(mode & 0o777, 0o600);
            }
            dir.path.clone()
        };
        assert!(!path.exists());
    }
}
