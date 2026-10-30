//! the backups of a deployment (docs/development/fdr/FDR-007-backup.md). `sak-backend backup` on the server writes all data
//! of the deployment as one tar stream, compressed with zstd and encrypted with age to the
//! recipient of the backup key; sakctl stores the bytes as they come, in
//! `~/.sak/backups/<name>/<name>-<UTC time>.age.sak`, and decrypts them once to check them. A
//! restore decrypts a backup and streams it, still compressed, into `sak-backend restore`, which checks it
//! before it replaces the data. The plain data of a backup is never on the disk of the user, and
//! the secret half of the key never on the server

use crate::core::keys::BackupKey;
use crate::core::sak_backend::{self, Command};
use crate::core::ssh::{SshTarget, Stream};
use rust_i18n::t;
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};

/// what a backup ends with: its file and its size in bytes
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Backup {
    pub path: PathBuf,
    pub size: u64,
}

/// the one place the backups of the deployments are made and restored
pub struct BackupManager;

/// `~/.sak/backups/<name>`, where the backups of a deployment are
pub fn directory(name: &str) -> Result<PathBuf, String> {
    let home = std::env::home_dir().ok_or_else(|| t!("shared.backup.no_home").to_string())?;
    Ok(home.join(".sak").join("backups").join(name))
}

/// `<name>-<UTC time>.age.sak`, e.g. `alpha-20261029T080000Z.age.sak`
pub fn file_name(name: &str, time: chrono::DateTime<chrono::Utc>) -> String {
    format!("{name}-{}.age.sak", time.format("%Y%m%dT%H%M%SZ"))
}

/// creates the directory and those above it below the home of the user, each only for the user
fn create_private_dir(path: &Path) -> Result<(), String> {
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder.create(path).map_err(|error| {
        t!(
            "shared.backup.not_written",
            path = path.display(),
            error = error
        )
        .to_string()
    })
}

/// a new file only the user reads, never one that is there already
fn create_private_file(path: &Path) -> Result<File, String> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|error| {
        t!(
            "shared.backup.not_written",
            path = path.display(),
            error = error
        )
        .to_string()
    })
}

/// the plain stream of a backup, as `sak-backend backup` wrote it before age: still compressed
fn decrypted(path: &Path, key: &BackupKey) -> Result<impl Read + Send + use<>, String> {
    let file = File::open(path).map_err(|error| {
        t!(
            "shared.backup.not_read",
            path = path.display(),
            error = error
        )
        .to_string()
    })?;
    let decryptor = age::Decryptor::new_buffered(BufReader::new(file)).map_err(|error| {
        t!(
            "shared.backup.not_decrypted",
            path = path.display(),
            error = error
        )
        .to_string()
    })?;
    decryptor
        .decrypt(std::iter::once(key.identity() as &dyn age::Identity))
        .map_err(|error| {
            t!(
                "shared.backup.not_decrypted",
                path = path.display(),
                error = error
            )
            .to_string()
        })
}

/// decrypts the whole backup and throws the plain bytes away: age authenticates each chunk and
/// marks the last one, so a changed, a truncated or another key's backup fails here. The size
/// of the plain stream
pub fn check(path: &Path, key: &BackupKey) -> Result<u64, String> {
    let mut plain = decrypted(path, key)?;
    std::io::copy(&mut plain, &mut std::io::sink()).map_err(|error| {
        t!(
            "shared.backup.not_decrypted",
            path = path.display(),
            error = error
        )
        .to_string()
    })
}

/// `<file>.partial`, the file while it is written
fn partial(path: &Path) -> PathBuf {
    let mut name = path.as_os_str().to_owned();
    name.push(".partial");
    PathBuf::from(name)
}

impl BackupManager {
    /// a backup of the deployment of `target` to `path` (`directory` and `file_name`), its
    /// directories made when they are not there; each message of the server goes to `on_line`. A
    /// backup that fails leaves no file
    pub fn backup(
        &self,
        runtime: &str,
        target: &SshTarget,
        key: &BackupKey,
        path: &Path,
        on_line: &mut (dyn FnMut(String) + Send),
    ) -> Result<Backup, String> {
        if let Some(directory) = path.parent() {
            create_private_dir(directory)?;
        }
        let path = path.to_path_buf();
        let partial = partial(&path);
        let result = (|| {
            let mut file = std::io::BufWriter::new(create_private_file(&partial)?);
            let recipient = key.recipient();
            sak_backend::run(
                runtime,
                target,
                Command::Backup {
                    recipient: &recipient,
                },
                Stream::Out(&mut file),
                on_line,
            )?;
            file.into_inner()
                .map_err(|error| error.to_string())?
                .sync_all()
                .map_err(|error| error.to_string())?;
            check(&partial, key)?;
            std::fs::rename(&partial, &path).map_err(|error| {
                t!(
                    "shared.backup.not_written",
                    path = path.display(),
                    error = error
                )
                .to_string()
            })?;
            let size = std::fs::metadata(&path)
                .map_err(|error| error.to_string())?
                .len();
            Ok(Backup {
                path: path.clone(),
                size,
            })
        })();
        if result.is_err() {
            let _ = std::fs::remove_file(&partial);
        }
        result
    }

    /// replaces the data of the deployment with the backup at `path`, checked first in full, so
    /// a wrong password or a damaged file fails before the server changes. `sak-backend restore` checks
    /// the name of the deployment and the database before it replaces anything
    pub fn restore(
        &self,
        runtime: &str,
        target: &SshTarget,
        key: &BackupKey,
        path: &Path,
        on_line: &mut (dyn FnMut(String) + Send),
    ) -> Result<(), String> {
        check(path, key)?;
        let mut plain = decrypted(path, key)?;
        sak_backend::run(
            runtime,
            target,
            Command::Restore,
            Stream::In(&mut plain),
            on_line,
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::core::keys::backup_key;
    use chrono::TimeZone;
    use std::io::Write;

    fn key() -> BackupKey {
        backup_key("alpha", "a password of sixteen").unwrap()
    }

    /// what `age -r <recipient>` on the server writes, with the crate
    fn encrypted(key: &BackupKey, plain: &[u8]) -> Vec<u8> {
        use std::str::FromStr;
        let recipient = age::x25519::Recipient::from_str(&key.recipient()).unwrap();
        let encryptor =
            age::Encryptor::with_recipients(std::iter::once(&recipient as &dyn age::Recipient))
                .unwrap();
        let mut out = Vec::new();
        let mut writer = encryptor.wrap_output(&mut out).unwrap();
        writer.write_all(plain).unwrap();
        writer.finish().unwrap();
        out
    }

    fn temp_dir(test: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("sakctl-backup-{test}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_backup_is_named_after_the_deployment_and_the_time_in_utc() {
        let time = chrono::Utc.with_ymd_and_hms(2026, 10, 29, 8, 0, 0).unwrap();
        assert_eq!(file_name("alpha", time), "alpha-20261029T080000Z.age.sak");
        assert!(
            directory("alpha")
                .unwrap()
                .ends_with(Path::new(".sak/backups/alpha"))
        );
    }

    #[test]
    fn a_backup_decrypts_only_complete_unchanged_and_with_its_own_key() {
        let dir = temp_dir("check");
        // more than one chunk of 64 KiB
        let plain: Vec<u8> = (0..200_000u32).map(|i| (i % 251) as u8).collect();
        let bytes = encrypted(&key(), &plain);
        let path = dir.join("alpha.age.sak");
        std::fs::write(&path, &bytes).unwrap();
        assert_eq!(check(&path, &key()).unwrap(), plain.len() as u64);
        let mut read = Vec::new();
        decrypted(&path, &key())
            .unwrap()
            .read_to_end(&mut read)
            .unwrap();
        assert_eq!(read, plain);

        // truncated at a chunk boundary and in the middle of one
        for length in [bytes.len() - 17, bytes.len() / 2] {
            std::fs::write(&path, &bytes[..length]).unwrap();
            assert!(check(&path, &key()).is_err(), "truncated to {length}");
        }
        let mut changed = bytes.clone();
        let middle = changed.len() / 2;
        changed[middle] ^= 1;
        std::fs::write(&path, &changed).unwrap();
        assert!(check(&path, &key()).is_err());

        std::fs::write(&path, &bytes).unwrap();
        let other = backup_key("alpha", "another password here").unwrap();
        assert!(check(&path, &other).is_err());
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn a_partial_file_is_only_for_the_user_and_never_replaces_one() {
        let dir = temp_dir("partial");
        let nested = dir.join("backups").join("alpha");
        create_private_dir(&nested).unwrap();
        let path = partial(&nested.join("alpha-20261029T080000Z.age.sak"));
        assert!(path.ends_with("alpha-20261029T080000Z.age.sak.partial"));
        create_private_file(&path).unwrap();
        assert!(create_private_file(&path).is_err());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode(&nested), 0o700);
            assert_eq!(mode(&path), 0o600);
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }
}
