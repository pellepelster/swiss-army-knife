//! the checks of what sakbackup needs on its computer, run before its window starts
//! (sak_desktop_shared::checks, the splash screen lists them): restic, which makes every backup,
//! restore and list of snapshots. A failing check ends the start

use crate::core::restic;
use rust_i18n::t;
use sak_desktop_shared::checks::{Check, CheckResult, Progress};
use std::path::Path;
use std::process::{Command, Stdio};

/// what the checks found
#[derive(Default)]
pub struct Found {
    /// the version of restic of this computer, e.g. 0.16.4
    pub restic: Option<String>,
}

/// the checks in the order they run
pub fn checks() -> Vec<Box<dyn Check<Found>>> {
    vec![Box::new(ResticCheck)]
}

/// restic is on this computer and runs: `restic version` answers its version
pub struct ResticCheck;

impl Check<Found> for ResticCheck {
    fn id(&self) -> &'static str {
        "restic"
    }

    fn name(&self) -> String {
        t!("common.checks.restic.name").to_string()
    }

    fn run(&self, found: &mut Found, _: &Progress) -> CheckResult {
        let program =
            restic::program().ok_or_else(|| t!("common.checks.restic.missing").to_string())?;
        let version = version(&program)?;
        let passed = t!("common.checks.restic.passed", version = version).to_string();
        found.restic = Some(version);
        Ok(passed)
    }
}

/// the version `program version` answers; why not, when it does not run or answers something else
fn version(program: &Path) -> Result<String, String> {
    let failed = |error: String| {
        t!(
            "common.checks.restic.failed",
            program = program.display().to_string(),
            error = error
        )
        .to_string()
    };
    let output = crate::core::restic::without_session(Command::new(program))
        .arg("version")
        .stdin(Stdio::null())
        .output()
        .map_err(|error| failed(error.to_string()))?;
    let said = String::from_utf8_lossy(&output.stdout);
    if !output.status.success() {
        let error = String::from_utf8_lossy(&output.stderr);
        return Err(failed(
            error.lines().last().unwrap_or_default().trim().to_string(),
        ));
    }
    parse_version(&said).ok_or_else(|| failed(said.lines().next().unwrap_or_default().to_string()))
}

/// the version of `restic 0.16.4 compiled with go1.21.6 on linux/amd64`, the first line restic
/// prints; none for another answer
fn parse_version(said: &str) -> Option<String> {
    let mut words = said.lines().next()?.split_whitespace();
    if words.next()? != "restic" {
        return None;
    }
    let version = words.next()?;
    version
        .starts_with(|first: char| first.is_ascii_digit())
        .then(|| version.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_version_is_the_second_word_of_what_restic_prints() {
        assert_eq!(
            parse_version("restic 0.16.4 compiled with go1.21.6 on linux/amd64\n").as_deref(),
            Some("0.16.4")
        );
        assert_eq!(
            parse_version("restic 0.18.0-dev (compiled manually) compiled with go1.24\n")
                .as_deref(),
            Some("0.18.0-dev")
        );
        assert_eq!(parse_version("rustic 0.9.0"), None);
        assert_eq!(parse_version("restic"), None);
        assert_eq!(parse_version("restic unknown"), None);
        assert_eq!(parse_version(""), None);
    }

    #[test]
    fn a_program_that_does_not_run_says_why() {
        let missing = version(Path::new("/nowhere/restic")).unwrap_err();
        assert!(
            missing.starts_with("/nowhere/restic does not run: "),
            "{missing}"
        );
    }

    #[cfg(unix)]
    #[test]
    fn a_program_that_is_not_restic_says_what_it_answered() {
        // echo answers its argument: "version"
        let other = version(Path::new("/bin/echo")).unwrap_err();
        assert_eq!(other, "/bin/echo does not run: version");
    }

    #[test]
    fn the_check_is_named_and_listed() {
        let checks = checks();
        assert_eq!(checks.len(), 1);
        assert_eq!(checks[0].id(), "restic");
        assert_eq!(checks[0].name(), "Find restic");
    }
}

/// with restic of this computer
#[cfg(test)]
mod integration {
    use super::*;

    #[test]
    fn the_check_finds_restic_of_this_computer_and_its_version() {
        let mut found = Found::default();
        let passed = ResticCheck
            .run(&mut found, &Progress::new(&|_, _| {}))
            .unwrap();
        let version = found.restic.unwrap();
        assert!(
            version.starts_with(|first: char| first.is_ascii_digit()),
            "{version}"
        );
        assert_eq!(passed, format!("restic {version}"));
    }
}
