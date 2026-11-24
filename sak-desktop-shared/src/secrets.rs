//! where a desktop app keeps the session token of each SAK instance (SECURITY-4): the keychain of
//! the system, one entry per address, never a file. Without a keychain the operations fail and
//! the session stays in memory only

use crate::session::Address;
use std::collections::HashMap;
use std::sync::Mutex;

/// the session tokens by the address of their instance
pub trait SecretStore: Send + Sync {
    /// the token of the address, none when there is none
    fn get(&self, address: &Address) -> Result<Option<String>, String>;
    fn set(&self, address: &Address, token: &str) -> Result<(), String>;
    /// removes the token, also when there is none
    fn delete(&self, address: &Address) -> Result<(), String>;
}

/// the keychain of the system: the Secret Service on linux, the Keychain on macOS, the
/// Credential Manager on windows. An entry is the service (e.g. the identifier of the app) and an
/// account: the address of an instance for a session token, or what else an app keeps (sakbackup:
/// the passwords of its repositories)
pub struct Keychain {
    service: String,
}

impl Keychain {
    pub fn new(service: &str) -> Self {
        Keychain {
            service: service.to_string(),
        }
    }

    fn entry(&self, account: &str) -> Result<keyring::Entry, String> {
        keyring::Entry::new(&self.service, account).map_err(|error| error.to_string())
    }

    /// the secret of the account, none when there is none
    pub fn read(&self, account: &str) -> Result<Option<String>, String> {
        match self.entry(account)?.get_password() {
            Ok(secret) => Ok(Some(secret)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(error) => Err(error.to_string()),
        }
    }

    pub fn write(&self, account: &str, secret: &str) -> Result<(), String> {
        self.entry(account)?
            .set_password(secret)
            .map_err(|error| error.to_string())
    }

    /// removes the secret, also when there is none
    pub fn remove(&self, account: &str) -> Result<(), String> {
        match self.entry(account)?.delete_credential() {
            Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
            Err(error) => Err(error.to_string()),
        }
    }
}

impl SecretStore for Keychain {
    fn get(&self, address: &Address) -> Result<Option<String>, String> {
        self.read(address.as_str())
    }

    fn set(&self, address: &Address, token: &str) -> Result<(), String> {
        self.write(address.as_str(), token)
    }

    fn delete(&self, address: &Address) -> Result<(), String> {
        self.remove(address.as_str())
    }
}

/// no keychain: nothing is kept, a session lasts as long as the app (the tests use `Memory`)
pub struct NoSecrets;

impl SecretStore for NoSecrets {
    fn get(&self, _: &Address) -> Result<Option<String>, String> {
        Ok(None)
    }

    fn set(&self, _: &Address, _: &str) -> Result<(), String> {
        Ok(())
    }

    fn delete(&self, _: &Address) -> Result<(), String> {
        Ok(())
    }
}

/// the tokens in memory, for the tests; `failing` makes every operation fail like a missing
/// keychain
#[derive(Default)]
pub struct Memory {
    pub tokens: Mutex<HashMap<String, String>>,
    pub failing: bool,
}

impl SecretStore for Memory {
    fn get(&self, address: &Address) -> Result<Option<String>, String> {
        self.fails()?;
        Ok(self.tokens.lock().unwrap().get(address.as_str()).cloned())
    }

    fn set(&self, address: &Address, token: &str) -> Result<(), String> {
        self.fails()?;
        self.tokens
            .lock()
            .unwrap()
            .insert(address.as_str().to_string(), token.to_string());
        Ok(())
    }

    fn delete(&self, address: &Address) -> Result<(), String> {
        self.fails()?;
        self.tokens.lock().unwrap().remove(address.as_str());
        Ok(())
    }
}

impl Memory {
    fn fails(&self) -> Result<(), String> {
        if self.failing {
            Err("no keychain".to_string())
        } else {
            Ok(())
        }
    }
}
