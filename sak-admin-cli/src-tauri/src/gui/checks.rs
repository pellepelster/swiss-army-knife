//! the checks of core on the splash screen: each event goes to the splash screen, what the checks
//! find goes into the state of the window. A failure shows on the splash screen, in the row of its
//! check or, when no check failed, as the error of the command; then the user can only quit (the
//! command `quit` of sak_desktop_shared::checks)

use crate::core::checks::{self, CheckEvent, Next, ProviderTokensCheck};
use crate::core::container::ContainerRuntime;
use crate::core::providers::ProviderTokens;
use sak_desktop_shared::checks::send;
use tauri::ipc::Channel;
use tauri::{AppHandle, Manager};

#[tauri::command]
pub async fn run_checks(app: AppHandle, on_event: Channel<CheckEvent>) -> Result<(), String> {
    let channel = on_event.clone();
    let found = checks::run_all(ProviderTokensCheck::from_environment(), move |event| {
        send(&channel, event)
    })
    .await
    .inspect_err(|text| eprintln!("{text}"))?;
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
