//! the checks of what sakctl needs on the machine it runs on, all run in order before the
//! window or a command starts: the window lists them on its splash screen, the command line logs
//! them; a failing one ends either. What they find, the container runtime and the valid provider
//! tokens, is handed back to be used

mod container_runtime;
mod image;
mod provider_tokens;

pub use provider_tokens::ProviderTokensCheck;

use crate::core::providers::ProviderType;
use rust_i18n::t;
use serde::Serialize;
use std::cell::RefCell;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// `Ok` with what was found, shown below the check's name, or `Err` with why it is not ok
pub type CheckResult = Result<String, String>;

/// what the checks found, for the window or the command after them
#[derive(Default)]
pub struct Found {
    /// docker or podman, the one proven working
    pub runtime: Option<&'static str>,
    /// the tokens validated with their provider
    pub tokens: Vec<(ProviderType, String)>,
}

/// a check; `run` may block, it is called on a thread of its own
pub trait Check: Send + Sync {
    fn id(&self) -> &'static str;

    /// the name in the list, in the user's language
    fn name(&self) -> String;

    /// a check not applying is not listed, e.g. one for a variable not set
    fn applies(&self) -> bool {
        true
    }

    /// a check that never reports progress is shown with a bar that only moves
    fn run(&self, found: &mut Found, progress: &Progress) -> CheckResult;
}

/// the checks in the order they run, the tokens checked those of `tokens`
pub fn checks(tokens: ProviderTokensCheck) -> Vec<Box<dyn Check>> {
    vec![
        Box::new(container_runtime::ContainerRuntimeCheck),
        Box::new(image::ImageCheck),
        Box::new(tokens),
    ]
}

/// how far a check is, handed to its `run`: the fraction done and a text saying it, e.g. the
/// megabytes of an image pulled
pub struct Progress<'a> {
    report: &'a dyn Fn(f64, &str),
    last: Mutex<Option<(Instant, f64)>>,
}

impl<'a> Progress<'a> {
    // more often is lost on the eye and floods the channel
    const INTERVAL: Duration = Duration::from_millis(100);

    pub fn new(report: &'a dyn Fn(f64, &str)) -> Self {
        Self {
            report,
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
    pub id: &'static str,
    pub name: String,
}

/// the progress of the checks with their texts in the user's language: the list first, then
/// each check started, its progress and passed or failed; the window ends with finished
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CheckEvent {
    Listed {
        checks: Vec<CheckItem>,
    },
    Started {
        id: &'static str,
        name: String,
    },
    Progress {
        id: &'static str,
        fraction: f64,
        text: String,
    },
    Passed {
        id: &'static str,
        name: String,
        text: String,
    },
    Failed {
        id: &'static str,
        name: String,
        text: String,
    },
    Finished {
        next: Next,
    },
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

/// runs the checks that apply in order, each reported to `on_event`; blocks, so it belongs on
/// a thread of its own. `Err` with why the first failing check failed, or why an event could not
/// be reported
pub fn run(
    checks: Vec<Box<dyn Check>>,
    on_event: &mut dyn FnMut(CheckEvent) -> Result<(), String>,
) -> Result<Found, String> {
    let checks: Vec<Box<dyn Check>> = checks.into_iter().filter(|check| check.applies()).collect();
    on_event(CheckEvent::Listed {
        checks: checks
            .iter()
            .map(|check| CheckItem {
                id: check.id(),
                name: check.name(),
            })
            .collect(),
    })?;
    let on_event = RefCell::new(on_event);
    let mut found = Found::default();
    for check in checks {
        let (id, name) = (check.id(), check.name());
        (on_event.borrow_mut())(CheckEvent::Started {
            id,
            name: name.clone(),
        })?;
        let report = |fraction: f64, text: &str| {
            // a progress lost is no reason to stop the check
            let _ = (on_event.borrow_mut())(CheckEvent::Progress {
                id,
                fraction,
                text: text.to_string(),
            });
        };
        let progress = Progress::new(&report);
        match check.run(&mut found, &progress) {
            Ok(text) => (on_event.borrow_mut())(CheckEvent::Passed { id, name, text })?,
            Err(text) => {
                (on_event.borrow_mut())(CheckEvent::Failed {
                    id,
                    name,
                    text: text.clone(),
                })?;
                return Err(text);
            }
        }
    }
    Ok(found)
}

/// the text of a check that did not finish, e.g. its thread panicked
pub fn not_finished(error: impl std::fmt::Display) -> String {
    t!("checks.not_finished", error = error).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn recorded(updates: &Arc<Mutex<Vec<f64>>>) -> impl Fn(f64, &str) + use<> {
        let recorder = updates.clone();
        move |fraction, _| recorder.lock().unwrap().push(fraction)
    }

    #[test]
    fn the_progress_never_goes_back() {
        let updates = Arc::new(Mutex::new(Vec::new()));
        let report = recorded(&updates);
        let progress = Progress::new(&report);
        progress.update(0.5, "");
        std::thread::sleep(Progress::INTERVAL);
        progress.update(0.25, "");
        assert_eq!(*updates.lock().unwrap(), vec![0.5, 0.5]);
    }

    #[test]
    fn the_progress_is_reported_10_times_a_second_at_most_but_done_always() {
        let updates = Arc::new(Mutex::new(Vec::new()));
        let report = recorded(&updates);
        let progress = Progress::new(&report);
        progress.update(0.1, "");
        progress.update(0.2, "");
        progress.update(1.0, "");
        assert_eq!(*updates.lock().unwrap(), vec![0.1, 1.0]);
    }

    #[test]
    fn the_progress_stays_between_0_and_1() {
        let updates = Arc::new(Mutex::new(Vec::new()));
        let report = recorded(&updates);
        let progress = Progress::new(&report);
        progress.update(2.0, "");
        assert_eq!(*updates.lock().unwrap(), vec![1.0]);
    }

    struct Fixed(&'static str, CheckResult);

    impl Check for Fixed {
        fn id(&self) -> &'static str {
            self.0
        }
        fn name(&self) -> String {
            self.0.to_string()
        }
        fn run(&self, found: &mut Found, _: &Progress) -> CheckResult {
            found.runtime = Some(self.0);
            self.1.clone()
        }
    }

    fn events(checks: Vec<Box<dyn Check>>) -> (Result<Found, String>, Vec<String>) {
        let mut seen = Vec::new();
        let result = run(checks, &mut |event| {
            seen.push(match event {
                CheckEvent::Listed { checks } => format!("listed {}", checks.len()),
                CheckEvent::Started { id, .. } => format!("started {id}"),
                CheckEvent::Passed { id, .. } => format!("passed {id}"),
                CheckEvent::Failed { id, text, .. } => format!("failed {id} {text}"),
                _ => "other".to_string(),
            });
            Ok(())
        });
        (result, seen)
    }

    #[test]
    fn the_checks_run_in_order_and_hand_back_what_they_found() {
        let (found, seen) = events(vec![
            Box::new(Fixed("a", Ok("".into()))),
            Box::new(Fixed("b", Ok("".into()))),
        ]);
        assert_eq!(found.unwrap().runtime, Some("b"));
        assert_eq!(
            seen,
            ["listed 2", "started a", "passed a", "started b", "passed b"]
        );
    }

    #[test]
    fn a_failing_check_ends_the_checks() {
        let (found, seen) = events(vec![
            Box::new(Fixed("a", Err("broken".into()))),
            Box::new(Fixed("b", Ok("".into()))),
        ]);
        assert_eq!(found.err().as_deref(), Some("broken"));
        assert_eq!(seen, ["listed 2", "started a", "failed a broken"]);
    }
}
