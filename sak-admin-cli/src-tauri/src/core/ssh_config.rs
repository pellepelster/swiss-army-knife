//! the files of `sakadmin ssh-config`: an ssh configuration, the client key and the known hosts of a
//! deployment in a directory the user names, so the ssh of the user reaches the server without
//! sakadmin. The one place the private client key is written to disk (`SECURITY-2`); sakadmin
//! itself never reads it back. No existing file is replaced, so a directory that has files of
//! these names (e.g. `~/.ssh`) keeps them

use crate::core::ssh::SshTarget;
use rust_i18n::t;
use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};

/// the names of the files in the directory
pub const CONFIG: &str = "config";
pub const PRIVATE_KEY: &str = "id_ed25519";
pub const PUBLIC_KEY: &str = "id_ed25519.pub";
pub const KNOWN_HOSTS: &str = "known_hosts";

/// the configuration: `ssh -F <directory>/config <name>` is a session as root on the server,
/// with the key of the directory only, the server checked against its derived host key. The
/// paths are absolute, so it works from every directory
fn config(name: &str, host: &str, directory: &Path) -> String {
    let config = directory.join(CONFIG);
    let key = directory.join(PRIVATE_KEY);
    let known_hosts = directory.join(KNOWN_HOSTS);
    format!(
        "# sakadmin ssh-config {name}: ssh -F \"{}\" {name}\n\
         Host {name}\n\
         \x20   HostName {host}\n\
         \x20   User root\n\
         \x20   IdentityFile \"{}\"\n\
         \x20   IdentitiesOnly yes\n\
         \x20   UserKnownHostsFile \"{}\"\n\
         \x20   StrictHostKeyChecking yes\n",
        config.display(),
        key.display(),
        known_hosts.display(),
    )
}

/// writes a new file that only the user reads and writes; an existing one is the error
fn write_new(path: &Path, content: &str) -> Result<(), String> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let failed = |error: std::io::Error| {
        t!(
            "cli.ssh_config_not_written",
            path = path.display(),
            error = error
        )
        .to_string()
    };
    let mut file = options.open(path).map_err(failed)?;
    file.write_all(content.as_bytes()).map_err(failed)
}

/// writes the configuration, the client key and the known hosts of the deployment `name` to
/// `directory` and answers the absolute path of the configuration. The directory is created when
/// it is not there, for the user only. Nothing is written when one of the files exists
pub fn write(name: &str, target: &SshTarget, directory: &Path) -> Result<PathBuf, String> {
    let directory = std::path::absolute(directory).map_err(|error| {
        t!(
            "cli.ssh_config_not_written",
            path = directory.display(),
            error = error
        )
        .to_string()
    })?;
    // ssh reads a path in quotes to the next quote, and a line break would start a new option
    if directory.to_string_lossy().contains(['"', '\n', '\r']) {
        return Err(t!("cli.ssh_config_bad_directory", path = directory.display()).to_string());
    }
    let files = [
        (PRIVATE_KEY, target.client_key.private_key()),
        (
            PUBLIC_KEY,
            format!("{}\n", target.client_key.public_key()).into(),
        ),
        (KNOWN_HOSTS, target.known_hosts().into()),
        (CONFIG, config(name, &target.host, &directory).into()),
    ];
    if let Some((existing, _)) = files.iter().find(|(file, _)| directory.join(file).exists()) {
        return Err(t!(
            "cli.ssh_config_exists",
            path = directory.join(existing).display()
        )
        .to_string());
    }
    if !directory.exists() {
        let mut builder = std::fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder.create(&directory).map_err(|error| {
            t!(
                "cli.ssh_config_not_written",
                path = directory.display(),
                error = error
            )
            .to_string()
        })?;
    }
    for (file, content) in &files {
        write_new(&directory.join(file), content)?;
    }
    Ok(directory.join(CONFIG))
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

    /// a directory of the test that is not there yet, below one that goes with `Removed`
    fn directory(test: &str) -> (Removed, PathBuf) {
        let base =
            std::env::temp_dir().join(format!("sakadmin-ssh-config-{test}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&base);
        (Removed(base.clone()), base.join("a directory").join("ssh"))
    }

    struct Removed(PathBuf);

    impl Drop for Removed {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn read(directory: &Path, file: &str) -> String {
        std::fs::read_to_string(directory.join(file)).unwrap()
    }

    #[test]
    fn the_directory_gets_the_configuration_the_key_and_the_known_hosts() {
        let (_removed, directory) = directory("written");
        let target = target();

        let written = write("alpha", &target, &directory).unwrap();

        assert_eq!(written, directory.join(CONFIG));
        assert_eq!(
            read(&directory, PRIVATE_KEY),
            *target.client_key.private_key()
        );
        assert_eq!(
            read(&directory, PUBLIC_KEY),
            format!("{}\n", target.client_key.public_key())
        );
        // the server is known by its derived host key, on both addresses
        let host_key = target.host_key.public_key();
        assert_eq!(
            read(&directory, KNOWN_HOSTS),
            format!("203.0.113.1 {host_key}\n2001:db8::1 {host_key}\n")
        );
        let path = |file: &str| directory.join(file).display().to_string();
        assert_eq!(
            read(&directory, CONFIG),
            format!(
                "# sakadmin ssh-config alpha: ssh -F \"{}\" alpha\n\
                 Host alpha\n\
                 \x20   HostName 203.0.113.1\n\
                 \x20   User root\n\
                 \x20   IdentityFile \"{}\"\n\
                 \x20   IdentitiesOnly yes\n\
                 \x20   UserKnownHostsFile \"{}\"\n\
                 \x20   StrictHostKeyChecking yes\n",
                path(CONFIG),
                path(PRIVATE_KEY),
                path(KNOWN_HOSTS)
            )
        );
    }

    #[cfg(unix)]
    #[test]
    fn only_the_user_reads_the_directory_and_its_files() {
        use std::os::unix::fs::PermissionsExt;
        let (_removed, directory) = directory("modes");

        write("alpha", &target(), &directory).unwrap();

        let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&directory), 0o700);
        for file in [PRIVATE_KEY, PUBLIC_KEY, KNOWN_HOSTS, CONFIG] {
            assert_eq!(mode(&directory.join(file)), 0o600, "{file}");
        }
    }

    #[test]
    fn no_existing_file_is_replaced_and_nothing_is_written_next_to_it() {
        for existing in [CONFIG, PRIVATE_KEY, PUBLIC_KEY, KNOWN_HOSTS] {
            let (_removed, directory) = directory(&format!("exists-{existing}"));
            std::fs::create_dir_all(&directory).unwrap();
            std::fs::write(directory.join(existing), "of the user").unwrap();

            let error = write("alpha", &target(), &directory).unwrap_err();

            assert!(
                error.contains(&directory.join(existing).display().to_string()),
                "{error}"
            );
            assert_eq!(read(&directory, existing), "of the user");
            assert_eq!(std::fs::read_dir(&directory).unwrap().count(), 1);
        }
    }

    #[test]
    fn a_directory_that_would_break_the_configuration_is_refused() {
        let (_removed, directory) = directory("quote");
        let directory = directory.join("a \" quote");

        assert!(write("alpha", &target(), &directory).is_err());
        assert!(!directory.exists());
    }

    /// the integration tests (`mise run admin-cli:test:integration`), they need ssh
    mod integration {
        use super::*;
        use std::process::Command;

        /// ssh reads the configuration as written: the host, the user, the key and the known
        /// hosts of the directory, also with a space in its path
        #[test]
        fn ssh_reads_the_configuration() {
            let (_removed, directory) = directory("ssh");
            let config = write("alpha", &target(), &directory).unwrap();

            let output = Command::new("ssh")
                .arg("-F")
                .arg(&config)
                .args(["-G", "alpha"])
                .output()
                .unwrap();

            assert!(output.status.success(), "{output:?}");
            let options = String::from_utf8_lossy(&output.stdout).to_lowercase();
            let path = |file: &str| directory.join(file).display().to_string().to_lowercase();
            for line in [
                "hostname 203.0.113.1".to_string(),
                "user root".to_string(),
                "identitiesonly yes".to_string(),
                // as `ssh -G` prints a yes of this option
                "stricthostkeychecking true".to_string(),
                format!("identityfile {}", path(PRIVATE_KEY)),
                format!("userknownhostsfile {}", path(KNOWN_HOSTS)),
            ] {
                assert!(
                    options.lines().any(|option| option == line),
                    "{line}\n{options}"
                );
            }
        }
    }
}
