//! the answers of the window's commands for the test of the window of sakbackup in a browser
//! (`sak-e2e`, `SakbackupWindowTest`): the checks at the start with restic found or missing, then the
//! sign-in, built from the real types and texts so the window shows nothing rust would not send.
//! `mise run backup:test:window` runs it (`cargo test window_fixtures -- --ignored`); it writes
//! `<SAK_WINDOW_FIXTURES or build/ui-fixtures>/sakbackup-<case>.json`

use crate::core::checks::ResticCheck;
use rust_i18n::t;
use sak_desktop_shared::checks::{Check, CheckEvent, CheckItem};
use serde::Serialize;
use serde_json::{Value, json};

/// an event of a channel after `delay_ms`
fn after(delay_ms: u64, event: impl Serialize) -> Value {
    json!({ "delayMs": delay_ms, "message": event })
}

/// the events of the checks: restic listed and started, then what `ended` says
fn checks(ended: CheckEvent<()>) -> Vec<Value> {
    let restic = ResticCheck;
    vec![
        after(
            100,
            CheckEvent::<()>::Listed {
                checks: vec![CheckItem {
                    id: restic.id(),
                    name: restic.name(),
                }],
            },
        ),
        after(
            100,
            CheckEvent::<()>::Started {
                id: restic.id(),
                name: restic.name(),
            },
        ),
        after(300, ended),
    ]
}

/// the answers of a start of the window with `checks` as the events of the checks; `passed`
/// tells whether the checks end, so the sign-in follows. After a failure `run_checks` has no
/// answer: the page gets an error from it, as from rust
fn fixtures(checks: Vec<Value>, passed: bool) -> Value {
    let mut channel = checks;
    let mut answers = json!({
        "locale": ["en"],
        "ui_texts": [super::ui_texts()],
        "version": [sak_desktop_shared::update::version_of(&crate::core::APP)],
        "quit": [null],
        "restore_session": [null],
        "known_instances": [[]],
    });
    if passed {
        channel.push(after(0, CheckEvent::Finished { next: () }));
        answers["run_checks"] = json!([null]);
    }
    json!({
        "answers": answers,
        "channels": { "run_checks": channel },
    })
}

#[test]
#[ignore = "writes the fixtures of the window test of sakbackup, run by mise run backup:test:window"]
fn window_fixtures() {
    let restic = ResticCheck;
    let found = fixtures(
        checks(CheckEvent::Passed {
            id: restic.id(),
            name: restic.name(),
            text: t!("common.checks.restic.passed", version = "0.16.4").to_string(),
        }),
        true,
    );
    let missing = fixtures(
        checks(CheckEvent::Failed {
            id: restic.id(),
            name: restic.name(),
            text: t!("common.checks.restic.missing").to_string(),
        }),
        false,
    );
    let dir = std::env::var("SAK_WINDOW_FIXTURES")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../build/ui-fixtures")
        });
    std::fs::create_dir_all(&dir).unwrap();
    for (case, fixtures) in [("restic", found), ("no-restic", missing)] {
        let path = dir.join(format!("sakbackup-{case}.json"));
        std::fs::write(&path, serde_json::to_string_pretty(&fixtures).unwrap()).unwrap();
        println!("wrote {}", path.display());
    }
}
