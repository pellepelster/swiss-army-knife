//! the checks of what sak-wizard needs on the machine it runs on, all run in order on startup and
//! shown as a list on the splash screen; a failing one ends the app

mod container_runtime;
mod image;
mod provider_tokens;

use crate::core::providers::ProviderTokens;
use rust_i18n::t;
use serde::Serialize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tauri::ipc::Channel;
use tauri::{AppHandle, Manager};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

/// `Ok` with what was found, shown below the check's name, or `Err` with why it is not ok
pub type CheckResult = Result<String, String>;

/// a check; `run` is called on a thread of its own and may block, an async check waits for its
/// future with `tauri::async_runtime::block_on`
pub trait Check: Send + Sync {
    fn id(&self) -> &'static str;

    /// the name in the list, in the user's language
    fn name(&self) -> String;

    /// a check not applying is not listed, e.g. one for a variable not set
    fn applies(&self) -> bool {
        true
    }

    /// a check that never reports progress is shown with a bar that only moves
    fn run(&self, app: &AppHandle, progress: &Progress) -> CheckResult;
}

fn checks() -> Vec<Arc<dyn Check>> {
    vec![
        Arc::new(container_runtime::ContainerRuntimeCheck),
        Arc::new(image::ImageCheck),
        Arc::new(provider_tokens::ProviderTokensCheck),
    ]
}

type Report = Box<dyn Fn(f64, &str) + Send + Sync>;

/// how far a check is, handed to its `run`: the fraction done and a text saying it, e.g. the
/// megabytes of an image pulled
pub struct Progress {
    report: Report,
    last: Mutex<Option<(Instant, f64)>>,
}

impl Progress {
    // more often is lost on the eye and floods the channel
    const INTERVAL: Duration = Duration::from_millis(100);

    pub fn new(report: impl Fn(f64, &str) + Send + Sync + 'static) -> Self {
        Self {
            report: Box::new(report),
            last: Mutex::new(None),
        }
    }

    /// the fraction from 0 to 1, a smaller one than before is taken as the one before
    pub fn update(&self, fraction: f64, text: &str) {
        let Ok(mut last) = self.last.lock() else {
            return;
        };
        let mut fraction = fraction.clamp(0.0, 1.0);
        if let Some((at, before)) = *last {
            fraction = fraction.max(before);
            if fraction < 1.0 && at.elapsed() < Self::INTERVAL {
                return;
            }
        }
        *last = Some((Instant::now(), fraction));
        (self.report)(fraction, text);
    }
}

#[derive(Clone, Serialize)]
pub struct CheckItem {
    id: &'static str,
    name: String,
}

/// the progress of the checks, sent to the splash screen with its texts in the user's language:
/// the list first, then each check started, its progress and passed or failed
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CheckEvent {
    Listed {
        checks: Vec<CheckItem>,
    },
    Started {
        id: &'static str,
    },
    Progress {
        id: &'static str,
        fraction: f64,
        text: String,
    },
    Passed {
        id: &'static str,
        text: String,
    },
    Failed {
        id: &'static str,
        text: String,
    },
    Finished {
        next: Next,
    },
}

/// what follows the splash screen
#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Next {
    // no provider token yet, the user enters them
    Providers,
    // the tokens came from the environment
    Ready,
}

#[tauri::command]
pub async fn run_checks(app: AppHandle, on_event: Channel<CheckEvent>) -> Result<(), String> {
    let checks: Vec<Arc<dyn Check>> = checks()
        .into_iter()
        .filter(|check| check.applies())
        .collect();
    send(
        &on_event,
        CheckEvent::Listed {
            checks: checks
                .iter()
                .map(|check| CheckItem {
                    id: check.id(),
                    name: check.name(),
                })
                .collect(),
        },
    )?;
    for check in checks {
        let id = check.id();
        send(&on_event, CheckEvent::Started { id })?;
        let channel = on_event.clone();
        let progress = Progress::new(move |fraction, text| {
            // a progress lost is no reason to stop the check
            let _ = channel.send(CheckEvent::Progress {
                id,
                fraction,
                text: text.to_string(),
            });
        });
        let handle = app.clone();
        let result = tauri::async_runtime::spawn_blocking(move || check.run(&handle, &progress))
            .await
            .map_err(|e| t!("checks.not_finished", error = e).to_string())?;
        match result {
            Ok(text) => send(&on_event, CheckEvent::Passed { id, text })?,
            Err(text) => {
                send(
                    &on_event,
                    CheckEvent::Failed {
                        id,
                        text: text.clone(),
                    },
                )?;
                fail(app, &text);
                return Ok(());
            }
        }
    }
    let next = if app.state::<ProviderTokens>().is_empty() {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn recorded() -> (Progress, Arc<Mutex<Vec<f64>>>) {
        let updates = Arc::new(Mutex::new(Vec::new()));
        let recorder = updates.clone();
        let progress = Progress::new(move |fraction, _| recorder.lock().unwrap().push(fraction));
        (progress, updates)
    }

    #[test]
    fn the_progress_never_goes_back() {
        let (progress, updates) = recorded();
        progress.update(0.5, "");
        std::thread::sleep(Progress::INTERVAL);
        progress.update(0.25, "");
        assert_eq!(*updates.lock().unwrap(), vec![0.5, 0.5]);
    }

    #[test]
    fn the_progress_is_reported_10_times_a_second_at_most_but_done_always() {
        let (progress, updates) = recorded();
        progress.update(0.1, "");
        progress.update(0.2, "");
        progress.update(1.0, "");
        assert_eq!(*updates.lock().unwrap(), vec![0.1, 1.0]);
    }

    #[test]
    fn the_progress_stays_between_0_and_1() {
        let (progress, updates) = recorded();
        progress.update(2.0, "");
        assert_eq!(*updates.lock().unwrap(), vec![1.0]);
    }
}
