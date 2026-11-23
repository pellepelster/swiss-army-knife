//! the checks of what sakadmin needs on its machine, run in order before the window or a command
//! starts (sak_desktop_shared::checks: the splash screen lists them, the command line logs them).
//! A failing check ends both. They give back what they find: the container runtime and the valid
//! provider tokens

mod container_runtime;
mod image;
mod provider_tokens;

pub use provider_tokens::ProviderTokensCheck;
pub use sak_desktop_shared::checks::{CheckItem, CheckResult, Progress};

use crate::core::providers::ProviderType;
use serde::Serialize;

/// a check of sakadmin
pub type Check = dyn sak_desktop_shared::checks::Check<Found>;

/// the events of the checks of sakadmin, which end with what follows the splash screen
pub type CheckEvent = sak_desktop_shared::checks::CheckEvent<Next>;

/// what the checks found, for the window or the command after them
#[derive(Default)]
pub struct Found {
    /// docker or podman, the one proven working
    pub runtime: Option<&'static str>,
    /// the tokens validated with their provider
    pub tokens: Vec<(ProviderType, String)>,
}

/// the checks in the order they run; the token check checks `tokens`
pub fn checks(tokens: ProviderTokensCheck) -> Vec<Box<Check>> {
    vec![
        Box::new(container_runtime::ContainerRuntimeCheck),
        Box::new(image::ImageCheck),
        Box::new(tokens),
    ]
}

/// what follows the splash screen of the window
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Next {
    // no provider token yet, the user enters them
    Providers,
    // the tokens came from the environment
    Ready,
}

/// the checks with `tokens` on a thread of their own, for the window and the command line alike
pub async fn run_all(
    tokens: ProviderTokensCheck,
    on_event: impl FnMut(CheckEvent) -> Result<(), String> + Send + 'static,
) -> Result<Found, String> {
    sak_desktop_shared::checks::run_all(checks(tokens), on_event).await
}
