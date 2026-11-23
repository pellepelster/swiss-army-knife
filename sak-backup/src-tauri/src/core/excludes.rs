//! what a run of a backup plan leaves out (the excludes of the plan, kept on the instance with it):
//! the patterns of restic, one a line as the user wrote them with comments, and whether the folders
//! marked as caches go too. A run hands them to restic (`--exclude-file`, `--iexclude-file` where the
//! file system ignores the case, `--exclude-caches`)

use serde_json::Value;
use std::path::Path;

/// the excludes of a backup plan
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Excludes {
    /// the patterns as the user wrote them, with comments and blank lines
    pub patterns: String,
    /// the folders marked as caches by a CACHEDIR.TAG are left out
    pub caches: bool,
}

impl Excludes {
    /// the excludes of a backup plan of the api
    pub fn of(plan: &Value) -> Excludes {
        Excludes {
            patterns: plan["excludes"].as_str().unwrap_or_default().to_string(),
            caches: plan["excludeCaches"].as_bool().unwrap_or_default(),
        }
    }

    /// the patterns restic reads: a line without its spaces, none for a blank line or a comment
    pub fn lines(&self) -> Vec<&str> {
        self.patterns
            .lines()
            .map(str::trim)
            .filter(|line| !line.is_empty() && !line.starts_with('#'))
            .collect()
    }

    /// the arguments of restic for them, with the file of the patterns (`patterns_file`)
    pub fn arguments(&self, patterns_file: Option<&Path>) -> Vec<std::ffi::OsString> {
        let mut arguments = Vec::new();
        if let Some(file) = patterns_file {
            arguments.push(
                if IGNORE_CASE {
                    "--iexclude-file"
                } else {
                    "--exclude-file"
                }
                .into(),
            );
            arguments.push(file.as_os_str().to_owned());
        }
        if self.caches {
            arguments.push("--exclude-caches".into());
        }
        arguments
    }
}

/// no excludes, for the plans of the tests
#[cfg(test)]
pub static NO_EXCLUDES: Excludes = Excludes {
    patterns: String::new(),
    caches: false,
};

/// the file systems of macOS and windows ignore the case of a name, so do the excludes there
pub const IGNORE_CASE: bool = cfg!(any(target_os = "macos", windows));

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_arguments_are_the_file_of_the_patterns_and_the_caches() {
        let excludes = Excludes {
            patterns: "node_modules".to_string(),
            caches: true,
        };
        let arguments = excludes.arguments(Some(Path::new("/tmp/x")));
        let option = if IGNORE_CASE {
            "--iexclude-file"
        } else {
            "--exclude-file"
        };
        assert_eq!(
            arguments,
            [option, "/tmp/x", "--exclude-caches"].map(std::ffi::OsString::from)
        );
        assert!(NO_EXCLUDES.arguments(None).is_empty());
    }
}
