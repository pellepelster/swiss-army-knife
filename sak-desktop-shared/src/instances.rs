//! the SAK instances a desktop app knows, in `instances.json` of its config directory: the
//! addresses it signed in at, the one used last first. Only addresses, never a session; plain, only
//! for the user (ACCEPTED-RISKS.md). A file that does not read is no instance, the next sign-in
//! writes it anew

use crate::session::Address;
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// the most instances the file keeps
pub const MAX_INSTANCES: usize = 20;

/// the name of the file in the config directory of the app
pub const FILE_NAME: &str = "instances.json";

#[derive(Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
struct Instances {
    instances: Vec<Instance>,
}

/// one instance; a place for its name or its last use later
#[derive(Debug, PartialEq, Eq, Serialize, Deserialize)]
struct Instance {
    address: String,
    /// the api the address named at the sign-in: a kept session goes to this one only
    #[serde(default)]
    api: Option<String>,
}

/// the file of the known instances; one writer at a time
pub struct InstanceStore {
    path: PathBuf,
    lock: Mutex<()>,
}

impl InstanceStore {
    pub fn new(path: PathBuf) -> Self {
        InstanceStore {
            path,
            lock: Mutex::new(()),
        }
    }

    /// the addresses, the one used last first; those that are no address any more are left out
    pub fn addresses(&self) -> Vec<String> {
        let _guard = self.lock.lock().unwrap();
        self.load()
            .instances
            .into_iter()
            .filter_map(|instance| Address::parse(&instance.address).ok())
            .map(|address| address.as_str().to_string())
            .collect()
    }

    /// the address used last, none before the first sign-in
    pub fn last(&self) -> Option<Address> {
        self.addresses()
            .first()
            .and_then(|address| Address::parse(address).ok())
    }

    /// the api of the sign-in at the address, none for an address that is not known
    pub fn api(&self, address: &Address) -> Option<String> {
        let _guard = self.lock.lock().unwrap();
        self.load()
            .instances
            .into_iter()
            .find(|instance| instance.address == address.as_str())
            .and_then(|instance| instance.api)
    }

    /// puts the address first, once, with the api of its sign-in, and keeps at most
    /// MAX_INSTANCES; answers the ones it dropped
    pub fn remember(&self, address: &Address, api: &str) -> Result<Vec<Address>, String> {
        let _guard = self.lock.lock().unwrap();
        let mut instances = self.load();
        instances
            .instances
            .retain(|instance| instance.address != address.as_str());
        instances.instances.insert(
            0,
            Instance {
                address: address.as_str().to_string(),
                api: Some(api.to_string()),
            },
        );
        let dropped = instances
            .instances
            .split_off(instances.instances.len().min(MAX_INSTANCES))
            .into_iter()
            .filter_map(|instance| Address::parse(&instance.address).ok())
            .collect();
        self.write(&instances)?;
        Ok(dropped)
    }

    fn load(&self) -> Instances {
        std::fs::read(&self.path)
            .ok()
            .and_then(|bytes| serde_json::from_slice(&bytes).ok())
            .unwrap_or_default()
    }

    /// a new file next to it, renamed over it: a crash leaves the old file or the new one
    fn write(&self, instances: &Instances) -> Result<(), String> {
        let directory = self
            .path
            .parent()
            .ok_or_else(|| format!("{} has no directory", self.path.display()))?;
        create_private_dir(directory)?;
        let temporary = self.path.with_extension("json.tmp");
        let _ = std::fs::remove_file(&temporary);
        let json = serde_json::to_vec_pretty(instances).map_err(|error| error.to_string())?;
        let mut file = create_private_file(&temporary)?;
        file.write_all(&json)
            .and_then(|_| file.sync_all())
            .map_err(|error| not_written(&temporary, error))?;
        std::fs::rename(&temporary, &self.path).map_err(|error| not_written(&self.path, error))
    }
}

fn not_written(path: &Path, error: std::io::Error) -> String {
    format!("{} could not be written: {error}", path.display())
}

/// the directory and those above it, each only for the user
fn create_private_dir(path: &Path) -> Result<(), String> {
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

/// a new file only the user reads
fn create_private_file(path: &Path) -> Result<std::fs::File, String> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|error| not_written(path, error))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn store(test: &str) -> (InstanceStore, PathBuf) {
        let directory =
            std::env::temp_dir().join(format!("sak-instances-{test}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        (
            InstanceStore::new(directory.join("app").join(FILE_NAME)),
            directory,
        )
    }

    const API: &str = "https://api.example.org/v1/";

    fn address(text: &str) -> Address {
        Address::parse(text).unwrap()
    }

    #[test]
    fn no_file_is_no_instance() {
        let (store, _) = store("none");
        assert!(store.addresses().is_empty());
        assert_eq!(store.last(), None);
    }

    #[test]
    fn the_address_used_last_comes_first_and_once() {
        let (store, directory) = store("order");
        store.remember(&address("alpha.example.org"), API).unwrap();
        store.remember(&address("beta.example.org"), API).unwrap();
        store
            .remember(&address("https://alpha.example.org/notes"), API)
            .unwrap();
        assert_eq!(
            store.addresses(),
            ["https://alpha.example.org/", "https://beta.example.org/"]
        );
        assert_eq!(store.last(), Some(address("alpha.example.org")));
        assert_eq!(
            store.api(&address("alpha.example.org")).as_deref(),
            Some(API)
        );
        assert_eq!(store.api(&address("gamma.example.org")), None);
        let json = std::fs::read_to_string(store.path.clone()).unwrap();
        assert!(json.contains("\"instances\""), "{json}");
        assert!(!directory.join("app").join("instances.json.tmp").exists());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn at_most_max_instances_are_kept() {
        let (store, directory) = store("max");
        for at in 0..MAX_INSTANCES {
            assert!(
                store
                    .remember(&address(&format!("i{at}.example.org")), API)
                    .unwrap()
                    .is_empty()
            );
        }
        for at in MAX_INSTANCES..MAX_INSTANCES + 5 {
            // the one used longest ago goes
            let dropped = store
                .remember(&address(&format!("i{at}.example.org")), API)
                .unwrap();
            assert_eq!(
                dropped,
                [address(&format!("i{}.example.org", at - MAX_INSTANCES))]
            );
        }
        let addresses = store.addresses();
        assert_eq!(addresses.len(), MAX_INSTANCES);
        assert_eq!(
            addresses[0],
            format!("https://i{}.example.org/", MAX_INSTANCES + 4)
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn a_file_that_does_not_read_is_no_instance_and_is_written_anew() {
        let (store, directory) = store("corrupt");
        std::fs::create_dir_all(store.path.parent().unwrap()).unwrap();
        std::fs::write(&store.path, "not json").unwrap();
        assert!(store.addresses().is_empty());
        store.remember(&address("alpha.example.org"), API).unwrap();
        assert_eq!(store.addresses(), ["https://alpha.example.org/"]);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn an_entry_that_is_no_address_is_left_out() {
        let (store, directory) = store("invalid");
        std::fs::create_dir_all(store.path.parent().unwrap()).unwrap();
        std::fs::write(
            &store.path,
            r#"{"instances":[{"address":"http://evil.example.org"},{"address":"https://sak.example.org/"}]}"#,
        )
        .unwrap();
        assert_eq!(store.addresses(), ["https://sak.example.org/"]);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[cfg(unix)]
    #[test]
    fn only_the_user_reads_the_file() {
        use std::os::unix::fs::PermissionsExt;
        let (store, directory) = store("private");
        store.remember(&address("alpha.example.org"), API).unwrap();
        let mode = |path: &Path| std::fs::metadata(path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode(&store.path), 0o600);
        assert_eq!(mode(store.path.parent().unwrap()), 0o700);
        std::fs::remove_dir_all(directory).unwrap();
    }
}
