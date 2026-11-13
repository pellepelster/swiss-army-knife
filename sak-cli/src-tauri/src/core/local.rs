//! what sakctl keeps of a deployment on this machine, all below `~/.sak/<name>/`, only for the
//! user: the settings of its last create or deploy (`config.json`) and its backups (`backups/`).
//! The settings are encrypted with age to a key derived from the password of the deployment, so
//! only that password reads them, on whatever machine; they hold the password of the mail server

use crate::core::keys::BackupKey;
use rust_i18n::t;
use serde::Serialize;
use serde::de::DeserializeOwned;
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};

/// `~/.sak/<name>`, where sakctl keeps what it knows of the deployment
pub fn directory(name: &str) -> Result<PathBuf, String> {
    let home = std::env::home_dir().ok_or_else(|| t!("common.backup.no_home").to_string())?;
    Ok(home.join(".sak").join(name))
}

/// `~/.sak/<name>/config.json`, the settings of the last create or deploy
pub fn settings_path(name: &str) -> Result<PathBuf, String> {
    Ok(directory(name)?.join("config.json"))
}

/// creates the directory and those above it below the home of the user, each only for the user
pub(crate) fn create_private_dir(path: &Path) -> Result<(), String> {
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(path)
        .map_err(|error| not_written(path, error))
}

/// a new file only the user reads, never one that is there already
pub(crate) fn create_private_file(path: &Path) -> Result<File, String> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|error| not_written(path, error))
}

fn not_written(path: &Path, error: impl std::fmt::Display) -> String {
    t!(
        "common.backup.not_written",
        path = path.display(),
        error = error
    )
    .to_string()
}

/// writes the settings of the deployment, encrypted with `key` (`keys::local_settings_key`), and
/// answers the path; a file written before is replaced atomically, never left half written
pub fn write_settings<T: Serialize>(
    name: &str,
    key: &BackupKey,
    settings: &T,
) -> Result<PathBuf, String> {
    let path = settings_path(name)?;
    write_settings_to(&path, key, settings)?;
    Ok(path)
}

fn write_settings_to<T: Serialize>(
    path: &Path,
    key: &BackupKey,
    settings: &T,
) -> Result<(), String> {
    use std::str::FromStr;
    let directory = path.parent().unwrap_or(Path::new("."));
    create_private_dir(directory)?;
    let plain = serde_json::to_vec(settings).map_err(|error| not_written(path, error))?;
    let recipient = age::x25519::Recipient::from_str(&key.recipient())
        .map_err(|error| not_written(path, error))?;
    let encrypted = age::encrypt(&recipient, &plain).map_err(|error| not_written(path, error))?;
    let partial = path.with_extension("json.partial");
    let _ = std::fs::remove_file(&partial);
    let mut file = create_private_file(&partial)?;
    file.write_all(&encrypted)
        .and_then(|()| file.sync_all())
        .map_err(|error| not_written(&partial, error))?;
    std::fs::rename(&partial, path).map_err(|error| not_written(path, error))
}

/// the settings of the deployment as the last create or deploy on this machine wrote them, none
/// when there are none; settings that do not decrypt with `key` are an error
pub fn read_settings<T: DeserializeOwned>(
    name: &str,
    key: &BackupKey,
) -> Result<Option<T>, String> {
    read_settings_from(&settings_path(name)?, key)
}

fn read_settings_from<T: DeserializeOwned>(
    path: &Path,
    key: &BackupKey,
) -> Result<Option<T>, String> {
    let mut encrypted = Vec::new();
    match File::open(path) {
        Ok(mut file) => file.read_to_end(&mut encrypted).map(|_| ()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) => Err(error),
    }
    .map_err(|error| {
        t!(
            "common.backup.not_read",
            path = path.display(),
            error = error
        )
        .to_string()
    })?;
    let plain = age::decrypt(key.identity(), &encrypted).map_err(|error| {
        t!(
            "common.backup.not_decrypted",
            path = path.display(),
            error = error
        )
        .to_string()
    })?;
    serde_json::from_slice(&plain).map(Some).map_err(|error| {
        t!(
            "common.backup.not_read",
            path = path.display(),
            error = error
        )
        .to_string()
    })
}

/// deletes the settings of the deployment, e.g. with a destroy that deletes its data; none is fine
pub fn delete_settings(name: &str) -> Result<(), String> {
    let path = settings_path(name)?;
    match std::fs::remove_file(&path) {
        Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
            Err(not_written(&path, error))
        }
        _ => Ok(()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Debug, PartialEq, Serialize, Deserialize)]
    struct Settings {
        root_domain: String,
        smtp_password: String,
    }

    fn settings() -> Settings {
        Settings {
            root_domain: "example.org".to_string(),
            smtp_password: "secret".to_string(),
        }
    }

    #[test]
    fn the_settings_are_read_with_the_password_they_were_written_with_and_only_with_it() {
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let directory =
            std::env::temp_dir().join(format!("sak-local-{}-{nanos}", std::process::id()));
        let path = directory.join("alpha").join("config.json");
        let key = crate::core::keys::local_settings_key("alpha", "a password of sixteen").unwrap();
        let other =
            crate::core::keys::local_settings_key("alpha", "another password of sixteen").unwrap();
        assert_eq!(read_settings_from::<Settings>(&path, &key), Ok(None));

        write_settings_to(&path, &key, &settings()).unwrap();
        assert_eq!(
            read_settings_from::<Settings>(&path, &key),
            Ok(Some(settings()))
        );
        // the password of the mail server is not in plain text on the disk
        assert!(!String::from_utf8_lossy(&std::fs::read(&path).unwrap()).contains("secret"));
        assert!(read_settings_from::<Settings>(&path, &other).is_err());
        // written again, the file is replaced
        write_settings_to(&path, &key, &settings()).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert_eq!(
                std::fs::metadata(path.parent().unwrap())
                    .unwrap()
                    .permissions()
                    .mode()
                    & 0o777,
                0o700
            );
        }
        std::fs::remove_dir_all(&directory).unwrap();
    }
}
