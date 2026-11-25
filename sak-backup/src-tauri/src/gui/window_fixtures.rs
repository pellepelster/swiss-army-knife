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

/// a signed in user with one backup plan of this computer, `home`, and what the dialog of its
/// excludes asks: the presets of linux
fn excludes() -> Value {
    let mut answers = fixtures(
        checks(CheckEvent::Passed {
            id: ResticCheck.id(),
            name: ResticCheck.name(),
            text: t!("common.checks.restic.passed", version = "0.16.4").to_string(),
        }),
        true,
    );
    let plan = json!({
        "id": 3,
        "name": "home",
        "repositoryId": 1,
        "host": "annas-laptop",
        "paths": ["/home/anna"],
        "createdAt": "2026-11-23T08:00:00Z",
        "keepDaily": 7,
        "excludes": "node_modules",
        "excludeCaches": false,
    });
    let answers_of = answers["answers"].as_object_mut().unwrap();
    for (command, answer) in [
        (
            "restore_session",
            json!([{ "username": "anna@example.org", "name": "Anna", "expires_at": null, "roles": ["user"] }]),
        ),
        ("host", json!(["annas-laptop"])),
        (
            "repositories",
            json!([[
                { "id": 1, "name": "my-backups", "description": "My Backups", "url": "rest:https://api.example.org/restic/1/", "size": 1_288_490_189u64, "createdAt": "2026-11-23T08:00:00Z" },
                { "id": 2, "name": "photos", "description": "Photos", "url": "rest:https://api.example.org/restic/2/", "size": 536_870_912u64, "createdAt": "2026-11-24T08:00:00Z" }
            ]]),
        ),
        ("plans", json!([[plan.clone()]])),
        ("password_kept", json!([false])),
        ("env_password_set", json!([false])),
        (
            "exclude_presets",
            json!([crate::core::presets::presets(
                crate::core::presets::System::Linux
            )]),
        ),
        ("update_plan", json!([plan])),
        (
            "run_backup",
            json!([crate::core::run::RunSummary {
                files_new: 2,
                snapshot_id: "f7df".repeat(16),
                ..Default::default()
            }]),
        ),
    ] {
        answers_of.insert(command.to_string(), answer);
    }
    // a backup that reads a file with a path far wider than the window, then more of it; the test
    // holds it after the first event, so the page shows it running
    let deep = format!(
        "/home/anna/.config/mozilla/firefox/xxr3ailo.default-release/storage/default/{}/idb/a-file-with-a-long-name.sqlite",
        "https+++www.example.org+a+very+long+origin".repeat(4)
    );
    let progress = |percent: f64| crate::core::run::RunEvent::Progress {
        percent,
        files_done: 40387,
        total_files: 490015,
        bytes_done: 16 * 1024 * 1024 * 1024,
        total_bytes: 186 * 1024 * 1024 * 1024,
        bytes_per_second: Some(30 * 1024 * 1024),
        seconds_remaining: Some(5400),
        current_file: Some(deep.clone()),
    };
    answers["channels"]["run_backup"] =
        json!([after(50, progress(0.09)), after(50, progress(0.5))]);
    answers
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
    for (case, fixtures) in [
        ("restic", found),
        ("no-restic", missing),
        ("excludes", excludes()),
    ] {
        let path = dir.join(format!("sakbackup-{case}.json"));
        std::fs::write(&path, serde_json::to_string_pretty(&fixtures).unwrap()).unwrap();
        println!("wrote {}", path.display());
    }
}
