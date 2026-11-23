//! the passwords of the repositories (BACKUP-3): typed, from SAK_BACKUP_REPO_PASSWORD, or kept in
//! the keychain of the system when the user asked for it, one entry per repository, named by the
//! address of its REST backend; never in a file. The window and the command line take the variable
//! or a kept password before they ask. A kept one restic refuses is said and stays: it may be the
//! only copy, only the user forgets it

use crate::core::run::{RunError, failed};
use sak_desktop_shared::secrets::Keychain;
use sak_desktop_shared::session::SessionManager;
#[cfg(test)]
use std::collections::HashMap;
use std::sync::Arc;
#[cfg(test)]
use std::sync::Mutex;

/// the variable of the password of the repositories, for the command line and the window
pub const PASSWORD_VARIABLE: &str = "SAK_BACKUP_REPO_PASSWORD";

/// the keychain entries of the passwords, apart from the one of the session (sak-desktop-shared)
const SERVICE: &str = "io.pelle.sak.backup.repositories";

/// the passwords by the address of the REST backend of their repository
pub trait Passwords: Send + Sync {
    fn get(&self, repository: &str) -> Result<Option<String>, String>;
    fn set(&self, repository: &str, password: &str) -> Result<(), String>;
    /// also when there is none
    fn delete(&self, repository: &str) -> Result<(), String>;
}

/// the keychain of the system (sak-desktop-shared), the entries of `SERVICE`
impl Passwords for Keychain {
    fn get(&self, repository: &str) -> Result<Option<String>, String> {
        self.read(repository)
    }

    fn set(&self, repository: &str, password: &str) -> Result<(), String> {
        self.write(repository, password)
    }

    fn delete(&self, repository: &str) -> Result<(), String> {
        self.remove(repository)
    }
}

/// the passwords in memory, for the tests
#[cfg(test)]
#[derive(Default)]
pub struct MemoryPasswords(pub Mutex<HashMap<String, String>>);

#[cfg(test)]
impl Passwords for MemoryPasswords {
    fn get(&self, repository: &str) -> Result<Option<String>, String> {
        Ok(self.0.lock().unwrap().get(repository).cloned())
    }

    fn set(&self, repository: &str, password: &str) -> Result<(), String> {
        self.0
            .lock()
            .unwrap()
            .insert(repository.to_string(), password.to_string());
        Ok(())
    }

    fn delete(&self, repository: &str) -> Result<(), String> {
        self.0.lock().unwrap().remove(repository);
        Ok(())
    }
}

/// the store of the window, away from the async threads as the Secret Service may wait for d-bus;
/// a failure of the keychain is logged and is no password, the window then asks
#[derive(Clone)]
pub struct PasswordStore(pub Arc<dyn Passwords>);

impl Default for PasswordStore {
    fn default() -> Self {
        PasswordStore(Arc::new(Keychain::new(SERVICE)))
    }
}

impl PasswordStore {
    pub async fn get(&self, repository: &str) -> Option<String> {
        self.blocking(repository, |passwords, repository| {
            passwords.get(repository)
        })
        .await
        .flatten()
    }

    pub async fn set(&self, repository: &str, password: String) {
        self.blocking(repository, move |passwords, repository| {
            passwords.set(repository, &password)
        })
        .await;
    }

    pub async fn delete(&self, repository: &str) {
        self.blocking(repository, |passwords, repository| {
            passwords.delete(repository)
        })
        .await;
    }

    async fn blocking<T: Send + 'static>(
        &self,
        repository: &str,
        operation: impl FnOnce(&dyn Passwords, &str) -> Result<T, String> + Send + 'static,
    ) -> Option<T> {
        let (passwords, repository) = (self.0.clone(), repository.to_string());
        tauri::async_runtime::spawn_blocking(move || operation(passwords.as_ref(), &repository))
            .await
            .map_err(|error| error.to_string())
            .and_then(|result| result)
            .inspect_err(|error| {
                eprintln!("the keychain failed, the password is asked for: {error}")
            })
            .ok()
    }
}

/// the password of SAK_BACKUP_REPO_PASSWORD; none when it is not set or empty
pub(crate) fn env_password() -> Option<String> {
    std::env::var(PASSWORD_VARIABLE)
        .ok()
        .filter(|password| !password.is_empty())
}

/// whether a password of the repository is kept in the keychain
pub async fn kept(
    sessions: &SessionManager,
    passwords: &PasswordStore,
    repository_id: u64,
) -> Result<bool, RunError> {
    let account = crate::core::restic::base(sessions, repository_id)?.to_string();
    Ok(passwords.get(&account).await.is_some())
}

/// deletes the kept password of the repository
pub async fn forget(
    sessions: &SessionManager,
    passwords: &PasswordStore,
    repository_id: u64,
) -> Result<(), RunError> {
    let account = crate::core::restic::base(sessions, repository_id)?.to_string();
    passwords.delete(&account).await;
    Ok(())
}

/// the password a call gives: typed, or none for the one of SAK_BACKUP_REPO_PASSWORD or the kept
/// one; and whether a typed one is kept when restic takes it. No Debug, it holds the password
#[derive(Clone, Default)]
pub struct Given {
    pub typed: Option<String>,
    pub remember: bool,
}

/// where a password came from
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Source {
    Typed,
    Environment,
    Kept,
}

/// the password of a repository: the one typed, else the one of SAK_BACKUP_REPO_PASSWORD, else the
/// one kept in the keychain for it
pub(crate) async fn password_of(
    passwords: &PasswordStore,
    account: &str,
    typed: Option<String>,
) -> Option<(String, Source)> {
    if let Some(typed) = typed.filter(|password| !password.is_empty()) {
        return Some((typed, Source::Typed));
    }
    if let Some(password) = env_password() {
        return Some((password, Source::Environment));
    }
    passwords
        .get(account)
        .await
        .map(|password| (password, Source::Kept))
}

/// runs restic with the password away from the async threads, then settles the password (`settle`)
pub(crate) async fn run_with<T: Send + 'static>(
    passwords: &PasswordStore,
    account: &str,
    (password, source): (String, Source),
    remember: bool,
    run: impl FnOnce(&str) -> Result<T, RunError> + Send + 'static,
) -> Result<T, RunError> {
    let (result, password) = tauri::async_runtime::spawn_blocking(move || {
        let result = run(&password);
        (result, password)
    })
    .await
    .map_err(failed)?;
    settle(passwords, account, password, source, remember, result).await
}

/// after restic: a typed password kept when the user asked for it and restic took it; the error of
/// a refused password by where it came from. A kept one restic refused stays kept
async fn settle<T>(
    passwords: &PasswordStore,
    account: &str,
    password: String,
    source: Source,
    remember: bool,
    result: Result<T, RunError>,
) -> Result<T, RunError> {
    match (result, source) {
        (Ok(value), Source::Typed) => {
            if remember {
                passwords.set(account, password).await;
            }
            Ok(value)
        }
        (Err(RunError::WrongPassword), Source::Environment) => {
            Err(RunError::WrongEnvironmentPassword)
        }
        (Err(RunError::WrongPassword), Source::Kept) => Err(RunError::WrongKeptPassword),
        (result, _) => result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "multi_thread")]
    async fn a_password_is_the_typed_one_else_the_kept_one_and_kept_only_when_asked() {
        // the variable would win over a kept one; the tests run without it
        assert!(env_password().is_none());
        let memory = Arc::new(MemoryPasswords::default());
        let passwords = PasswordStore(memory.clone());
        let account = "https://api.example.org/restic/7/";
        let typed = || ("typed".to_string(), Source::Typed);
        assert_eq!(password_of(&passwords, account, None).await, None);
        assert_eq!(
            password_of(&passwords, account, Some("typed".to_string())).await,
            Some(typed())
        );

        // restic took it, but the user did not ask to keep it
        run_with(&passwords, account, typed(), false, |_| Ok(()))
            .await
            .unwrap();
        assert_eq!(password_of(&passwords, account, None).await, None);
        // kept when asked, used without typing then; restic got the password
        let used = run_with(&passwords, account, typed(), true, |password| {
            Ok(password.to_string())
        })
        .await;
        assert_eq!(used, Ok("typed".to_string()));
        assert_eq!(
            password_of(&passwords, account, None).await,
            Some(("typed".to_string(), Source::Kept))
        );
        // a refused one is never kept
        let refused = run_with(
            &passwords,
            "https://other/",
            ("x".to_string(), Source::Typed),
            true,
            |_| Err::<(), _>(RunError::WrongPassword),
        )
        .await;
        assert_eq!(refused, Err(RunError::WrongPassword));
        assert_eq!(memory.0.lock().unwrap().get("https://other/"), None);
        // a kept one restic refuses is told apart and stays: it may be the only copy
        let refused = run_with(
            &passwords,
            account,
            ("typed".to_string(), Source::Kept),
            false,
            |_| Err::<(), _>(RunError::WrongPassword),
        )
        .await;
        assert_eq!(refused, Err(RunError::WrongKeptPassword));
        assert_eq!(
            password_of(&passwords, account, None).await,
            Some(("typed".to_string(), Source::Kept))
        );
        // one typed and kept replaces it, only the user forgets it
        run_with(
            &passwords,
            account,
            ("another".to_string(), Source::Typed),
            true,
            |_| Ok(()),
        )
        .await
        .unwrap();
        assert_eq!(
            password_of(&passwords, account, None).await,
            Some(("another".to_string(), Source::Kept))
        );
        passwords.delete(account).await;
        assert_eq!(password_of(&passwords, account, None).await, None);
        // one of the variable is told apart
        let refused = run_with(
            &passwords,
            account,
            ("env".to_string(), Source::Environment),
            true,
            |_| Err::<(), _>(RunError::WrongPassword),
        )
        .await;
        assert_eq!(refused, Err(RunError::WrongEnvironmentPassword));
    }
}
