//! the sign-in in the window of a desktop app: the tauri commands behind `ui/login.js`. The app
//! manages a `SessionManager` (`session_manager`) and an `InstanceStore` (`instance_store`) and
//! lists the commands in its `generate_handler!`, e.g. `sak_desktop_shared::window::login`

use crate::instances::{FILE_NAME, InstanceStore};
use crate::secrets::Keychain;
use crate::session::{
    Address, LOGIN_TIMEOUT, LoginError, Pages, RestoreError, SessionManager, User,
};
use rust_i18n::t;
use std::sync::Arc;
use tauri::ipc::Channel;
use tauri::{Manager, State};
use tauri_plugin_opener::OpenerExt;

/// the instances of `instances.json` in the config directory of the app (its identifier)
pub fn instance_store<R: tauri::Runtime>(app: &tauri::App<R>) -> tauri::Result<InstanceStore> {
    Ok(InstanceStore::new(
        app.path().app_config_dir()?.join(FILE_NAME),
    ))
}

/// the sessions of the app, kept in the keychain of the system under its identifier
pub fn session_manager<R: tauri::Runtime>(app: &tauri::App<R>) -> SessionManager {
    SessionManager::new(
        Arc::new(Keychain::new(&app.config().identifier)),
        LOGIN_TIMEOUT,
    )
}

/// takes up the session of the instance used last, kept from the start before; none when there is
/// none, the error is the text the sign-in shows
#[tauri::command]
pub async fn restore_session(
    sessions: State<'_, SessionManager>,
    instances: State<'_, InstanceStore>,
) -> Result<Option<User>, String> {
    if let Some(user) = sessions.user() {
        return Ok(Some(user));
    }
    let Some(address) = instances.last() else {
        return Ok(None);
    };
    sessions
        .restore(address)
        .await
        .map_err(|error| restore_message(&error))
}

/// the addresses the app signed in at, the one used last first
#[tauri::command]
pub fn known_instances(instances: State<'_, InstanceStore>) -> Vec<String> {
    instances.addresses()
}

/// signs in at the SAK address through the browser; the error is the text the page shows. The
/// address is remembered once the sign-in succeeded
#[tauri::command]
pub async fn login(
    app: tauri::AppHandle,
    sessions: State<'_, SessionManager>,
    instances: State<'_, InstanceStore>,
    address: String,
    // gets the code the window shows while the browser asks, the page shows the same (SEC-31)
    on_code: Channel<String>,
) -> Result<User, String> {
    let address = Address::parse(&address).map_err(|error| message(&error))?;
    let pages = pages(&app.package_info().name);
    let user = sessions
        .login(address.clone(), pages, |url, code| {
            on_code
                .send(code.to_string())
                .map_err(|error| error.to_string())?;
            app.opener()
                .open_url(url, None::<&str>)
                .map_err(|error| error.to_string())
        })
        .await
        .map_err(|error| message(&error))?;
    // the window shows one instance: the tokens the keychain still has of the others go, the
    // user could not sign out of them (SEC-35)
    for other in instances.addresses() {
        if other != address.as_str() {
            if let Ok(other) = Address::parse(&other) {
                sessions.forget(&other).await;
            }
        }
    }
    // signed in all the same, the address is typed again next time
    match instances.remember(&address) {
        Ok(dropped) => {
            for address in dropped {
                sessions.forget(&address).await;
            }
        }
        Err(error) => eprintln!("the SAK address was not remembered: {error}"),
    }
    Ok(user)
}

/// stops a sign-in that waits for the browser
#[tauri::command]
pub fn cancel_login(sessions: State<'_, SessionManager>) {
    sessions.cancel();
}

/// forgets the session, in memory and in the keychain
#[tauri::command]
pub async fn logout(sessions: State<'_, SessionManager>) -> Result<(), String> {
    sessions.logout().await;
    Ok(())
}

/// what the browser shows when it comes back to the app named `app`
pub fn pages(app: &str) -> Pages {
    Pages {
        signed_in: (
            t!("ui.login.browser.signed_in_title").to_string(),
            t!("ui.login.browser.signed_in", app = app).to_string(),
        ),
        cancelled: (
            t!("ui.login.browser.cancelled_title").to_string(),
            t!("ui.login.browser.cancelled", app = app).to_string(),
        ),
        interfered: (
            t!("ui.login.browser.interfered_title").to_string(),
            t!("ui.login.browser.interfered", app = app).to_string(),
        ),
    }
}

/// the text of a session that was not taken up again
pub fn restore_message(error: &RestoreError) -> String {
    t!(format!("ui.login.restore.{}", error.kind())).to_string()
}

/// the text of a sign-in that failed
pub fn message(error: &LoginError) -> String {
    let key = format!("ui.login.error.{}", error.kind());
    t!(&key, error = error.detail().unwrap_or_default()).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_error_of_a_sign_in_has_a_text() {
        let texts = crate::i18n::shared_texts();
        for error in [
            LoginError::InvalidAddress,
            LoginError::NoInstance,
            LoginError::Listener("busy".to_string()),
            LoginError::Browser("none".to_string()),
            LoginError::Cancelled,
            LoginError::TimedOut,
            LoginError::Refused,
            LoginError::Interfered,
            LoginError::Failed("500".to_string()),
        ] {
            let key = format!("ui.login.error.{}", error.kind());
            assert!(texts.contains_key(&key), "{key}");
            if let Some(detail) = error.detail() {
                assert!(message(&error).contains(detail), "{key}");
            }
        }
    }

    #[test]
    fn every_error_of_a_restore_has_a_text() {
        let texts = crate::i18n::shared_texts();
        for error in [RestoreError::Expired, RestoreError::Unreachable] {
            let key = format!("ui.login.restore.{}", error.kind());
            assert!(texts.contains_key(&key), "{key}");
        }
    }

    #[test]
    fn the_browser_names_the_app() {
        assert!(pages("SAK Backup").signed_in.1.contains("SAK Backup"));
        assert!(pages("SAK Backup").cancelled.1.contains("SAK Backup"));
    }
}
