//! the checks of core on the splash screen: each event sent to it, what they found put into the
//! state of the window; a failing one ends the app with a dialog

use crate::core::checks::{self, CheckEvent, Next, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::providers::ProviderTokens;
use rust_i18n::t;
use tauri::ipc::Channel;
use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

#[tauri::command]
pub async fn run_checks(app: AppHandle, on_event: Channel<CheckEvent>) -> Result<(), String> {
    let channel = on_event.clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        let checks = checks::checks(ProviderTokensCheck::from_environment());
        checks::run(checks, &mut |event| send(&channel, event))
    })
    .await
    .map_err(checks::not_finished)
    .and_then(|result| result);
    let found = match result {
        Ok(found) => found,
        Err(text) => {
            fail(app, &text);
            return Ok(());
        }
    };
    if let Some(runtime) = found.runtime {
        app.state::<ContainerRuntime>().set(runtime);
    }
    let tokens = app.state::<ProviderTokens>();
    for (provider, token) in &found.tokens {
        tokens.add(*provider, token)?;
    }
    let next = if tokens.is_empty() {
        Next::Providers
    } else {
        Next::Ready
    };
    send(&on_event, CheckEvent::Finished { next })
}

fn send(on_event: &Channel<CheckEvent>, event: CheckEvent) -> Result<(), String> {
    on_event
        .send(event)
        .map_err(|e| t!("checks.splash_not_updated", error = e).to_string())
}

fn fail(app: AppHandle, message: &str) {
    eprintln!("{message}");
    app.dialog()
        .message(message)
        .title(t!("checks.failed_title"))
        .kind(MessageDialogKind::Error)
        .buttons(MessageDialogButtons::OkCustom(
            t!("checks.quit").to_string(),
        ))
        .show(move |_| app.exit(1));
}
