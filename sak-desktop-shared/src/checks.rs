//! the checks of what a desktop app needs on its machine, run in order before its window or a
//! command starts: the splash screen of the window lists them (ui/checks.js), a command line logs
//! them. A failing check ends both. An app has its own checks (`Check<F>`), what they find (`F`,
//! e.g. the container runtime of sakadmin, restic of sakbackup) and what follows them (`N`)

use rust_i18n::t;
use serde::Serialize;
use std::cell::RefCell;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// `Ok` with what was found, shown below the check's name, or `Err` with why it is not ok
pub type CheckResult = Result<String, String>;

/// a check of an app that keeps what it finds in `F`; `run` may block, it is called on a thread of
/// its own
pub trait Check<F>: Send + Sync {
    fn id(&self) -> &'static str;

    /// the name in the list, in the user's language
    fn name(&self) -> String;

    /// a check that does not apply is not listed, e.g. one for a variable that is not set
    fn applies(&self) -> bool {
        true
    }

    /// a check that never reports progress shows a bar that only moves
    fn run(&self, found: &mut F, progress: &Progress) -> CheckResult;
}

/// how far a check is, handed to its `run`: the fraction done and a text saying it, e.g. the
/// megabytes of an image pulled
pub struct Progress<'a> {
    report: &'a dyn Fn(f64, &str),
    last: Mutex<Option<(Instant, f64)>>,
}

impl<'a> Progress<'a> {
    // more often does not help the eye and fills the channel
    const INTERVAL: Duration = Duration::from_millis(100);

    pub fn new(report: &'a dyn Fn(f64, &str)) -> Self {
        Self {
            report,
            last: Mutex::new(None),
        }
    }

    /// the fraction from 0 to 1; a fraction smaller than the one before counts as the one before
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

/// the progress of the checks with their texts in the user's language: the list first, then for
/// each check started, its progress and passed or failed. The window ends with finished and what
/// follows the splash screen (`N`)
#[derive(Clone, Serialize)]
#[serde(tag = "event", rename_all = "camelCase")]
pub enum CheckEvent<N> {
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
        next: N,
    },
}

/// runs the checks that apply in order, each reported to `on_event`; blocks, so it belongs on
/// a thread of its own. `Err` with why the first failing check failed, or why an event could not
/// be reported
pub fn run<F: Default, N>(
    checks: Vec<Box<dyn Check<F>>>,
    on_event: &mut dyn FnMut(CheckEvent<N>) -> Result<(), String>,
) -> Result<F, String> {
    let checks: Vec<Box<dyn Check<F>>> =
        checks.into_iter().filter(|check| check.applies()).collect();
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
    let mut found = F::default();
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

/// the checks on a thread of their own, for the window and the command line alike
pub async fn run_all<F, N>(
    checks: Vec<Box<dyn Check<F>>>,
    mut on_event: impl FnMut(CheckEvent<N>) -> Result<(), String> + Send + 'static,
) -> Result<F, String>
where
    F: Default + Send + 'static,
    N: 'static,
{
    tokio::task::spawn_blocking(move || run(checks, &mut on_event))
        .await
        .map_err(not_finished)?
}

/// the text of a check that did not finish, e.g. its thread panicked
fn not_finished(error: impl std::fmt::Display) -> String {
    t!("common.checks.not_finished", error = error.to_string()).to_string()
}

/// an event of the checks to the splash screen of the window
pub fn send<N: Serialize + Clone>(
    on_event: &tauri::ipc::Channel<CheckEvent<N>>,
    event: CheckEvent<N>,
) -> Result<(), String> {
    on_event
        .send(event)
        .map_err(|e| t!("common.checks.splash_not_updated", error = e.to_string()).to_string())
}

/// the quit of the splash screen: exit code 1 after a check failed, else 0
#[tauri::command]
pub fn quit(app: tauri::AppHandle, failed: bool) {
    app.exit(if failed { 1 } else { 0 });
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

    /// what the checks of the tests find: the id of the last one that ran
    #[derive(Default)]
    struct Found(Option<&'static str>);

    struct Fixed(&'static str, CheckResult);

    impl Check<Found> for Fixed {
        fn id(&self) -> &'static str {
            self.0
        }
        fn name(&self) -> String {
            self.0.to_string()
        }
        fn run(&self, found: &mut Found, _: &Progress) -> CheckResult {
            found.0 = Some(self.0);
            self.1.clone()
        }
    }

    /// not listed and not run
    struct NotApplying;

    impl Check<Found> for NotApplying {
        fn id(&self) -> &'static str {
            "none"
        }
        fn name(&self) -> String {
            "none".to_string()
        }
        fn applies(&self) -> bool {
            false
        }
        fn run(&self, _: &mut Found, _: &Progress) -> CheckResult {
            panic!("a check that does not apply ran")
        }
    }

    fn events(checks: Vec<Box<dyn Check<Found>>>) -> (Result<Found, String>, Vec<String>) {
        let mut seen = Vec::new();
        let result = run(checks, &mut |event: CheckEvent<()>| {
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
            Box::new(NotApplying),
            Box::new(Fixed("b", Ok("".into()))),
        ]);
        assert_eq!(found.unwrap().0, Some("b"));
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

    #[test]
    fn an_event_names_itself_and_what_follows_the_checks() {
        let finished = serde_json::to_string(&CheckEvent::Finished { next: "ready" }).unwrap();
        assert_eq!(finished, r#"{"event":"finished","next":"ready"}"#);
        let passed: CheckEvent<()> = CheckEvent::Passed {
            id: "restic",
            name: "restic".into(),
            text: "restic 0.16.4".into(),
        };
        assert_eq!(
            serde_json::to_string(&passed).unwrap(),
            r#"{"event":"passed","id":"restic","name":"restic","text":"restic 0.16.4"}"#
        );
    }
}
