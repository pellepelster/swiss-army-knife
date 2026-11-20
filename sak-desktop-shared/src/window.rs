//! the sign-in in the window of a desktop app: the tauri commands behind `ui/login.js`. The app
//! manages a `SessionManager` and lists the commands in its `generate_handler!`, e.g.
//! `sak_desktop_shared::window::login`

use crate::session::{Address, LoginError, Pages, SessionManager, User};
use rust_i18n::t;
use tauri::State;
use tauri_plugin_opener::OpenerExt;

/// who is signed in, none at the start and after the sign-out
#[tauri::command]
pub fn user(sessions: State<'_, SessionManager>) -> Option<User> {
    sessions.user()
}

/// signs in at the SAK address through the browser; the error is the text the page shows
#[tauri::command]
pub async fn login(
    app: tauri::AppHandle,
    sessions: State<'_, SessionManager>,
    address: String,
) -> Result<User, String> {
    let address = Address::parse(&address).map_err(|error| message(&error))?;
    let pages = pages(&app.package_info().name);
    sessions
        .login(address, pages, |url| {
            app.opener()
                .open_url(url, None::<&str>)
                .map_err(|error| error.to_string())
        })
        .await
        .map_err(|error| message(&error))
}

/// stops a sign-in that waits for the browser
#[tauri::command]
pub fn cancel_login(sessions: State<'_, SessionManager>) {
    sessions.cancel();
}

/// forgets the session
#[tauri::command]
pub fn logout(sessions: State<'_, SessionManager>) {
    sessions.logout();
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
    }
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
    fn the_browser_names_the_app() {
        assert!(pages("SAK Backup").signed_in.1.contains("SAK Backup"));
        assert!(pages("SAK Backup").cancelled.1.contains("SAK Backup"));
    }
}
