//! what a run of a backup plan leaves out (the excludes of the plan, kept on the instance with it):
//! the patterns of restic, one a line as the user wrote them with comments, and whether the folders
//! marked as caches go too. A run hands them to restic (`--exclude-file`, `--iexclude-file` where the
//! file system ignores the case, `--exclude-caches`); the preview walks the paths of a plan with the
//! rules of restic and tells what a run would leave out, without restic and without reading a file

use serde::Serialize;
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

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

/// a pattern of restic, as its package filter reads it: the environment variables expanded, cleaned
/// as a path, split at its separators; a rooted one starts with "/"
#[derive(Clone, Debug)]
struct Pattern {
    /// the line as the user wrote it
    line: String,
    parts: Vec<String>,
    /// a pattern of `!`, which takes back what another left out
    negated: bool,
}

impl Pattern {
    fn parse(line: &str, ignore_case: bool, variable: &dyn Fn(&str) -> Option<String>) -> Pattern {
        let (negated, rest) = match line.strip_prefix('!') {
            Some(rest) => (true, rest),
            None => (false, line),
        };
        let mut expanded = expand(rest, variable);
        if ignore_case {
            expanded = expanded.to_lowercase();
        }
        Pattern {
            line: line.to_string(),
            parts: split(&clean(&expanded)),
            negated,
        }
    }

    /// whether the pattern matches the path, split as `split` does, as restic's `filter.Match` does
    fn matches(&self, path: &[String]) -> bool {
        matches(&self.parts, path)
    }
}

/// `$NAME` and `${NAME}` by the value of the variable, none for one that is not set, as `os.ExpandEnv`
/// of go does
fn expand(text: &str, variable: &dyn Fn(&str) -> Option<String>) -> String {
    let mut expanded = String::new();
    let mut rest = text;
    while let Some(at) = rest.find('$') {
        expanded.push_str(&rest[..at]);
        let after = &rest[at + 1..];
        let (name, used) = if let Some(braced) = after.strip_prefix('{') {
            match braced.find('}') {
                Some(end) => (&braced[..end], end + 2),
                // go gives up on a brace that is not closed
                None => ("", after.len()),
            }
        } else {
            let end = after
                .find(|c: char| !(c.is_ascii_alphanumeric() || c == '_'))
                .unwrap_or(after.len());
            (&after[..end], end)
        };
        if name.is_empty() && used == 0 {
            // a $ that names nothing stays
            expanded.push('$');
        } else {
            expanded.push_str(&variable(name).unwrap_or_default());
        }
        rest = &after[used..];
    }
    expanded.push_str(rest);
    expanded
}

/// the path as `filepath.Clean` of go makes it: no empty part, no ".", a ".." takes the part before
fn clean(path: &str) -> String {
    // only on windows is the backslash the separator, elsewhere it escapes a character of a pattern
    let path = if cfg!(windows) {
        path.replace('\\', "/")
    } else {
        path.to_string()
    };
    let rooted = path.starts_with('/');
    let mut parts: Vec<&str> = Vec::new();
    for part in path.split('/') {
        match part {
            "" | "." => {}
            ".." if parts.last().is_some_and(|last| *last != "..") => {
                parts.pop();
            }
            ".." if rooted => {}
            part => parts.push(part),
        }
    }
    let joined = parts.join("/");
    if rooted {
        format!("/{joined}")
    } else if joined.is_empty() {
        ".".to_string()
    } else {
        joined
    }
}

/// the parts of a path, a rooted one starting with "/", as restic's `splitPath` has them
fn split(path: &str) -> Vec<String> {
    let mut parts = Vec::new();
    if path.starts_with('/') {
        parts.push("/".to_string());
    }
    parts.extend(
        path.split('/')
            .filter(|part| !part.is_empty())
            .map(String::from),
    );
    parts
}

/// restic's `match`: `**` stands for any number of parts; then the parts of the pattern match a run
/// of parts of the path one by one, of its start only for a rooted pattern
fn matches(pattern: &[String], path: &[String]) -> bool {
    if let Some(at) = pattern.iter().position(|part| part == "**") {
        // the `**` as no part, then as one more `*` each time
        for stars in 0..=path.len().saturating_sub(pattern.len()) + 1 {
            let mut expanded: Vec<String> = pattern[..at].to_vec();
            expanded.extend(std::iter::repeat_n("*".to_string(), stars));
            expanded.extend(pattern[at + 1..].iter().cloned());
            if matches(&expanded, path) {
                return true;
            }
        }
        return false;
    }
    if pattern.is_empty() {
        return path.is_empty();
    }
    if pattern.len() > path.len() {
        return false;
    }
    let max_offset = path.len() - pattern.len();
    let (min_offset, max_offset) = if pattern[0] == "/" {
        (0, 0)
    } else if path[0] == "/" {
        (1, max_offset)
    } else {
        (0, max_offset)
    };
    (min_offset..=max_offset)
        .rev()
        .any(|offset| (0..pattern.len()).all(|i| glob(&pattern[i], &path[offset + i])))
}

/// `filepath.Match` of go for one part of a path: `*` any characters, `?` one, `[…]` one of a class
/// (`^` or `!` for none of it, `a-z` a range), `\` takes the next character as it is (but on windows,
/// where it is the separator)
fn glob(pattern: &str, name: &str) -> bool {
    fn class(pattern: &[char], c: char) -> Option<(bool, usize)> {
        // the class at the start of `pattern` (after its `[`): whether c is in it, and its length
        let mut i = 0;
        let negated = matches!(pattern.first(), Some('^' | '!'));
        if negated {
            i += 1;
        }
        let mut found = false;
        let mut first = true;
        while i < pattern.len() {
            if pattern[i] == ']' && !first {
                return Some((found != negated, i + 1));
            }
            first = false;
            let mut low = pattern[i];
            if low == '\\' && !cfg!(windows) {
                i += 1;
                low = *pattern.get(i)?;
            }
            let mut high = low;
            if pattern.get(i + 1) == Some(&'-') && pattern.get(i + 2).is_some_and(|c| *c != ']') {
                high = pattern[i + 2];
                if high == '\\' && !cfg!(windows) {
                    high = *pattern.get(i + 3)?;
                    i += 1;
                }
                i += 2;
            }
            if low <= c && c <= high {
                found = true;
            }
            i += 1;
        }
        // a class that is not closed is a broken pattern, which matches nothing
        None
    }
    fn at(pattern: &[char], name: &[char]) -> bool {
        match pattern.first() {
            None => name.is_empty(),
            Some('*') => (0..=name.len()).any(|skip| at(&pattern[1..], &name[skip..])),
            Some('?') => !name.is_empty() && at(&pattern[1..], &name[1..]),
            Some('[') => match (
                name.first(),
                class(&pattern[1..], *name.first().unwrap_or(&'\0')),
            ) {
                (Some(_), Some((true, length))) => at(&pattern[1 + length..], &name[1..]),
                _ => false,
            },
            Some('\\') if !cfg!(windows) && pattern.len() > 1 => {
                name.first() == Some(&pattern[1]) && at(&pattern[2..], &name[1..])
            }
            Some(c) => name.first() == Some(c) && at(&pattern[1..], &name[1..]),
        }
    }
    let pattern: Vec<char> = pattern.chars().collect();
    let name: Vec<char> = name.chars().collect();
    at(&pattern, &name)
}

/// the patterns of excludes, each with its line
struct Patterns(Vec<Pattern>);

impl Patterns {
    fn of(
        excludes: &Excludes,
        ignore_case: bool,
        variable: &dyn Fn(&str) -> Option<String>,
    ) -> Patterns {
        Patterns(
            excludes
                .lines()
                .into_iter()
                .map(|line| Pattern::parse(line, ignore_case, variable))
                .collect(),
        )
    }

    /// the pattern that leaves the path out: the first that matches, unless a pattern of `!` that
    /// matches comes after it, as restic decides by the last pattern that matches
    fn leaving_out(&self, path: &[String]) -> Option<&Pattern> {
        let last = self.0.iter().rev().find(|pattern| pattern.matches(path))?;
        if last.negated {
            return None;
        }
        self.0
            .iter()
            .find(|pattern| !pattern.negated && pattern.matches(path))
    }
}

/// the first bytes of a CACHEDIR.TAG that marks its folder as a cache
const CACHEDIR_SIGNATURE: &[u8] = b"Signature: 8a477f597d28d172789f06886806bc55";

/// what a run would leave out of what it backs up, as the preview tells it
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Preview {
    /// false when it stopped at its limit of time or of entries, its counts are then too low
    pub complete: bool,
    /// the files a run would back up, and their bytes
    pub included_files: u64,
    pub included_bytes: u64,
    /// what each pattern left out, in the order of the patterns; the cache folders as the line
    /// `CACHEDIR.TAG`
    pub skipped: Vec<Skipped>,
    /// the patterns that left out nothing, e.g. one with a typo
    pub unused: Vec<String>,
    /// the paths of the plan that are not on this computer
    pub missing: Vec<String>,
}

/// what one pattern left out: its files, their bytes, and the first places it left out
#[derive(Clone, Debug, Default, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Skipped {
    pub pattern: String,
    pub files: u64,
    pub bytes: u64,
    pub examples: Vec<String>,
}

/// how much a preview walks before it stops
#[derive(Clone, Copy, Debug)]
pub struct Limits {
    pub entries: u64,
    pub time: Duration,
}

impl Default for Limits {
    fn default() -> Self {
        Limits {
            entries: 2_000_000,
            time: Duration::from_secs(60),
        }
    }
}

/// how many places of a pattern the preview names
const EXAMPLES: usize = 5;

/// what a run of the paths would leave out with the excludes, on this computer
pub fn preview(paths: &[String], excludes: &Excludes, limits: Limits) -> Preview {
    preview_with(paths, excludes, limits, IGNORE_CASE, &|name| {
        std::env::var(name).ok()
    })
}

fn preview_with(
    paths: &[String],
    excludes: &Excludes,
    limits: Limits,
    ignore_case: bool,
    variable: &dyn Fn(&str) -> Option<String>,
) -> Preview {
    let patterns = Patterns::of(excludes, ignore_case, variable);
    let mut walk = Walk {
        patterns: &patterns,
        caches: excludes.caches,
        ignore_case,
        skipped: BTreeMap::new(),
        preview: Preview {
            complete: true,
            ..Preview::default()
        },
        entries: 0,
        limits,
        started: Instant::now(),
    };
    for path in paths {
        let path = PathBuf::from(path);
        match std::fs::symlink_metadata(&path) {
            Ok(metadata) => walk.entry(&path, &metadata),
            Err(_) => walk.preview.missing.push(path.display().to_string()),
        }
    }
    let mut preview = walk.preview;
    let mut skipped = walk.skipped;
    let lines: Vec<&str> = excludes.lines();
    for line in &lines {
        match skipped.remove(*line) {
            Some(found) => preview.skipped.push(found),
            None if !line.starts_with('!') => preview.unused.push(line.to_string()),
            None => {}
        }
    }
    if let Some(caches) = skipped.remove(CACHEDIR_TAG) {
        preview.skipped.push(caches);
    }
    preview
}

/// the name of the file that marks a folder as a cache, and the line of what it leaves out
const CACHEDIR_TAG: &str = "CACHEDIR.TAG";

struct Walk<'a> {
    patterns: &'a Patterns,
    caches: bool,
    ignore_case: bool,
    skipped: BTreeMap<String, Skipped>,
    preview: Preview,
    entries: u64,
    limits: Limits,
    started: Instant,
}

impl Walk<'_> {
    fn stop(&mut self) -> bool {
        self.entries += 1;
        if self.entries > self.limits.entries || self.started.elapsed() > self.limits.time {
            self.preview.complete = false;
        }
        !self.preview.complete
    }

    /// the path as the patterns see it
    fn parts(&self, path: &Path) -> Vec<String> {
        let text = clean(&path.to_string_lossy());
        split(&if self.ignore_case {
            text.to_lowercase()
        } else {
            text
        })
    }

    /// a file or a folder of the paths: left out by a pattern, or backed up, a folder with what is in it
    fn entry(&mut self, path: &Path, metadata: &std::fs::Metadata) {
        if self.stop() {
            return;
        }
        let parts = self.parts(path);
        if let Some(pattern) = self.patterns.leaving_out(&parts) {
            let line = pattern.line.clone();
            let (files, bytes) = self.count(path, metadata);
            self.skip(&line, path, files, bytes);
            return;
        }
        if !metadata.is_dir() {
            self.preview.included_files += 1;
            self.preview.included_bytes += metadata.len();
            return;
        }
        let Ok(children) = std::fs::read_dir(path) else {
            return;
        };
        let mut children: Vec<_> = children.filter_map(Result::ok).collect();
        children.sort_by_key(|child| child.file_name());
        // a folder marked as a cache keeps its CACHEDIR.TAG, the rest goes (restic --exclude-caches)
        if self.caches && is_cache(path) {
            for child in children {
                let Ok(child_metadata) = child.metadata() else {
                    continue;
                };
                if child.file_name() == CACHEDIR_TAG {
                    self.preview.included_files += 1;
                    self.preview.included_bytes += child_metadata.len();
                } else {
                    let (files, bytes) = self.count(&child.path(), &child_metadata);
                    self.skip(CACHEDIR_TAG, &child.path(), files, bytes);
                }
            }
            return;
        }
        for child in children {
            let Ok(child_metadata) = std::fs::symlink_metadata(child.path()) else {
                continue;
            };
            self.entry(&child.path(), &child_metadata);
            if !self.preview.complete {
                return;
            }
        }
    }

    /// the files and their bytes below a path that is left out
    fn count(&mut self, path: &Path, metadata: &std::fs::Metadata) -> (u64, u64) {
        if !metadata.is_dir() {
            return (1, metadata.len());
        }
        let (mut files, mut bytes) = (0, 0);
        let Ok(children) = std::fs::read_dir(path) else {
            return (0, 0);
        };
        for child in children.filter_map(Result::ok) {
            if self.stop() {
                break;
            }
            if let Ok(child_metadata) = std::fs::symlink_metadata(child.path()) {
                let (child_files, child_bytes) = self.count(&child.path(), &child_metadata);
                files += child_files;
                bytes += child_bytes;
            }
        }
        (files, bytes)
    }

    fn skip(&mut self, line: &str, path: &Path, files: u64, bytes: u64) {
        let skipped = self
            .skipped
            .entry(line.to_string())
            .or_insert_with(|| Skipped {
                pattern: line.to_string(),
                ..Skipped::default()
            });
        skipped.files += files;
        skipped.bytes += bytes;
        if skipped.examples.len() < EXAMPLES {
            skipped.examples.push(path.display().to_string());
        }
    }
}

/// a folder with a CACHEDIR.TAG that starts with its signature
fn is_cache(folder: &Path) -> bool {
    let Ok(mut file) = std::fs::File::open(folder.join(CACHEDIR_TAG)) else {
        return false;
    };
    let mut start = [0u8; CACHEDIR_SIGNATURE.len()];
    std::io::Read::read_exact(&mut file, &mut start).is_ok() && start == CACHEDIR_SIGNATURE
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pattern(line: &str) -> Pattern {
        Pattern::parse(line, false, &|name| {
            (name == "HOME").then(|| "/home/anna".to_string())
        })
    }

    fn excluded(line: &str, path: &str) -> bool {
        pattern(line).matches(&split(&clean(path)))
    }

    #[test]
    fn a_pattern_is_a_line_with_its_variables_expanded() {
        let excludes = Excludes {
            patterns: "# caches\n\n  $HOME/.cache  \n#node_modules\nnode_modules".to_string(),
            caches: false,
        };
        assert_eq!(excludes.lines(), ["$HOME/.cache", "node_modules"]);
        assert_eq!(
            pattern("$HOME/.cache").parts,
            ["/", "home", "anna", ".cache"]
        );
        assert_eq!(pattern("${HOME}/x").parts, ["/", "home", "anna", "x"]);
        // a variable that is not set is nothing, as in go
        assert_eq!(pattern("$NOPE/x").parts, ["/", "x"]);
        assert_eq!(pattern("a//b/./c/../d/").parts, ["a", "b", "d"]);
    }

    #[test]
    fn a_name_matches_at_any_depth_a_rooted_pattern_at_the_root_only() {
        assert!(excluded("node_modules", "/home/anna/git/p/node_modules"));
        assert!(excluded("node_modules", "/node_modules"));
        assert!(!excluded("node_modules", "/home/anna/node_modules_old"));
        assert!(excluded("$HOME/.cache", "/home/anna/.cache"));
        assert!(!excluded("$HOME/.cache", "/home/bert/.cache"));
        assert!(!excluded("/.cache", "/home/anna/.cache"));
        // the parts of a pattern match parts of the path in a row
        assert!(excluded("p/build", "/home/anna/git/p/build"));
        assert!(!excluded("p/build", "/home/anna/git/p/src/build"));
    }

    #[test]
    fn wildcards_match_within_a_part_two_stars_across_parts() {
        assert!(excluded("*.pyc", "/a/b/c.pyc"));
        assert!(!excluded("*.pyc", "/a/b/c.pyco"));
        assert!(excluded("*~", "/a/notes.txt~"));
        assert!(excluded(
            "$HOME/.zcompdump*",
            "/home/anna/.zcompdump-host-5.9"
        ));
        assert!(excluded(
            "$HOME/.var/app/*/cache",
            "/home/anna/.var/app/org.x/cache"
        ));
        assert!(!excluded(
            "$HOME/.var/app/*/cache",
            "/home/anna/.var/app/a/b/cache"
        ));
        assert!(excluded("$HOME/git/**/build", "/home/anna/git/build"));
        assert!(excluded("$HOME/git/**/build", "/home/anna/git/a/b/c/build"));
        assert!(!excluded(
            "$HOME/git/**/build",
            "/home/anna/projects/a/build"
        ));
        assert!(excluded("file?.txt", "/a/file1.txt"));
        assert!(excluded("[a-c]x", "/bx"));
        assert!(!excluded("[^a-c]x", "/bx"));
        assert!(excluded(r"\*star", "/*star"));
        assert!(!excluded(r"\*star", "/xstar"));
    }

    #[test]
    fn the_last_pattern_that_matches_decides() {
        let patterns = Patterns::of(
            &Excludes {
                patterns: "*.log\n!keep.log".to_string(),
                caches: false,
            },
            false,
            &|_| None,
        );
        assert!(patterns.leaving_out(&split("/a/x.log")).is_some());
        assert!(patterns.leaving_out(&split("/a/keep.log")).is_none());
        assert!(patterns.leaving_out(&split("/a/x.txt")).is_none());
    }

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

    /// a tree of files below a temporary folder
    fn tree(files: &[(&str, &[u8])]) -> tempfile_dir::Dir {
        let dir = tempfile_dir::Dir::new();
        for (path, content) in files {
            let file = dir.0.join(path);
            std::fs::create_dir_all(file.parent().unwrap()).unwrap();
            std::fs::write(file, content).unwrap();
        }
        dir
    }

    #[test]
    fn the_preview_tells_what_each_pattern_leaves_out_and_what_it_backs_up() {
        let dir = tree(&[
            ("home/docs/letter.txt", b"dear"),
            ("home/.cache/a/b.bin", b"0123456789"),
            ("home/git/p/node_modules/m/i.js", b"js"),
            ("home/git/p/node_modules/n/j.js", b"js"),
            ("home/git/p/main.rs", b"fn main"),
            (
                "home/git/p/target/CACHEDIR.TAG",
                b"Signature: 8a477f597d28d172789f06886806bc55\n",
            ),
            ("home/git/p/target/debug/x", b"12345"),
        ]);
        let home = dir.0.join("home");
        let excludes = Excludes {
            patterns: "### caches\n$HOME/.cache\nnode_modules\n$HOME/Downloads".to_string(),
            caches: true,
        };
        let home_text = home.display().to_string();
        let preview = preview_with(
            &[home_text.clone(), "/no/such/path".to_string()],
            &excludes,
            Limits::default(),
            false,
            &|name| (name == "HOME").then(|| home_text.clone()),
        );
        assert!(preview.complete);
        assert_eq!(
            preview.skipped,
            [
                Skipped {
                    pattern: "$HOME/.cache".into(),
                    files: 1,
                    bytes: 10,
                    examples: vec![home.join(".cache").display().to_string()]
                },
                Skipped {
                    pattern: "node_modules".into(),
                    files: 2,
                    bytes: 4,
                    examples: vec![home.join("git/p/node_modules").display().to_string()]
                },
                Skipped {
                    pattern: "CACHEDIR.TAG".into(),
                    files: 1,
                    bytes: 5,
                    examples: vec![home.join("git/p/target/debug").display().to_string()]
                },
            ]
        );
        // the letter, main.rs and the CACHEDIR.TAG, which restic keeps
        assert_eq!(preview.included_files, 3);
        assert_eq!(preview.unused, ["$HOME/Downloads"]);
        assert_eq!(preview.missing, ["/no/such/path"]);
    }

    #[test]
    fn the_preview_stops_at_its_limit() {
        let dir = tree(&[("a/1", b"1"), ("a/2", b"2"), ("a/3", b"3")]);
        let limits = Limits {
            entries: 2,
            time: Duration::from_secs(60),
        };
        let preview = preview_with(
            &[dir.0.display().to_string()],
            &NO_EXCLUDES,
            limits,
            false,
            &|_| None,
        );
        assert!(!preview.complete);
    }

    /// a temporary folder that goes when it is dropped
    mod tempfile_dir {
        pub struct Dir(pub std::path::PathBuf);

        impl Dir {
            pub fn new() -> Dir {
                let mut bytes = [0u8; 8];
                getrandom::fill(&mut bytes).unwrap();
                let dir = std::env::temp_dir()
                    .join(format!("sak-excludes-{}", u64::from_le_bytes(bytes)));
                std::fs::create_dir_all(&dir).unwrap();
                Dir(dir)
            }
        }

        impl Drop for Dir {
            fn drop(&mut self) {
                let _ = std::fs::remove_dir_all(&self.0);
            }
        }
    }
}

/// with restic of this computer: the preview leaves out what restic leaves out
#[cfg(test)]
mod integration {
    use super::*;
    use std::process::Command;

    /// the files restic would back up of `root` with the excludes, by a run that writes nothing
    /// (`--dry-run`), with their sizes summed
    fn restic_backs_up(
        work: &Path,
        root: &Path,
        excludes: &Excludes,
        ignore_case: bool,
        home: &str,
    ) -> (u64, u64) {
        let restic = crate::core::restic::program().expect("restic on this computer");
        let repository = work.join("repository");
        let run = |arguments: &[&std::ffi::OsStr]| {
            let output = Command::new(&restic)
                .args(arguments)
                .env("RESTIC_REPOSITORY", &repository)
                .env("RESTIC_PASSWORD", "a password")
                .env("HOME", home)
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            String::from_utf8_lossy(&output.stdout).to_string()
        };
        if !repository.exists() {
            run(&["init".as_ref()]);
        }
        let file = work.join(if ignore_case { "iexcludes" } else { "excludes" });
        std::fs::write(&file, &excludes.patterns).unwrap();
        let mut arguments: Vec<&std::ffi::OsStr> = vec![
            "backup".as_ref(),
            "--json".as_ref(),
            "--dry-run".as_ref(),
            "-vv".as_ref(),
        ];
        arguments.push(
            if ignore_case {
                "--iexclude-file"
            } else {
                "--exclude-file"
            }
            .as_ref(),
        );
        arguments.push(file.as_os_str());
        if excludes.caches {
            arguments.push("--exclude-caches".as_ref());
        }
        arguments.push(root.as_os_str());
        let (mut files, mut bytes) = (0, 0);
        for line in run(&arguments).lines() {
            let Ok(event) = serde_json::from_str::<Value>(line) else {
                continue;
            };
            let item = event["item"].as_str().unwrap_or_default();
            if event["message_type"] == "verbose_status"
                && event["action"] == "new"
                && !item.ends_with('/')
            {
                files += 1;
                bytes += std::fs::metadata(item).unwrap().len();
            }
        }
        (files, bytes)
    }

    #[test]
    fn the_preview_leaves_out_what_restic_leaves_out() {
        let work = std::env::temp_dir().join(format!("sak-excludes-restic-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&work);
        let home = work.join("home");
        // each file of another size, a power of two: equal sums are equal sets of files
        let files = [
            "docs/letter.txt",
            "docs/Notes.TXT",
            ".cache/a/b.bin",
            ".local/share/Trash/files/old.txt",
            ".var/app/org.x/cache/c",
            ".var/app/org.x/data/d",
            "git/p/node_modules/m/i.js",
            "git/p/main.rs",
            "git/p/build/o.class",
            "git/q/deep/er/build/x.o",
            "git/q/src/build.rs",
            "projects/r/build/y",
            "projects/r/target/CACHEDIR.TAG",
            "projects/r/target/debug/bin",
            "src/app.pyc",
            "src/app.py",
            "src/.DS_Store",
            "src/Thumbs.db",
            "src/notes.md~",
            "logs/a.log",
            "logs/keep.log",
            ".zcompdump-host-5.9",
            "[weird]/file",
        ];
        for (index, file) in files.iter().enumerate() {
            let path = home.join(file);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            if file.ends_with("CACHEDIR.TAG") {
                std::fs::write(&path, b"Signature: 8a477f597d28d172789f06886806bc55\n").unwrap();
            } else {
                std::fs::write(&path, vec![b'x'; 64 << index]).unwrap();
            }
        }
        let patterns = [
            "### caches and trash",
            "$HOME/.cache",
            "$HOME/.local/share/Trash",
            "${HOME}/.var/app/*/cache",
            "",
            "# build output",
            "node_modules",
            "$HOME/git/**/build",
            "*.pyc",
            "*~",
            ".DS_Store",
            "thumbs.db",
            "$HOME/.zcompdump*",
            "*.log",
            "!keep.log",
            "$HOME/\\[weird\\]",
            "$HOME/Downloads",
        ]
        .join("\n");
        let home_text = home.display().to_string();
        let mut seen = Vec::new();
        for ignore_case in [false, true] {
            for caches in [false, true] {
                let excludes = Excludes {
                    patterns: patterns.clone(),
                    caches,
                };
                let preview = preview_with(
                    std::slice::from_ref(&home_text),
                    &excludes,
                    Limits::default(),
                    ignore_case,
                    &|name| (name == "HOME").then(|| home_text.clone()),
                );
                assert!(preview.complete);
                assert_eq!(
                    (preview.included_files, preview.included_bytes),
                    restic_backs_up(&work, &home, &excludes, ignore_case, &home_text),
                    "ignore case {ignore_case}, caches {caches}: {preview:#?}"
                );
                seen.push(preview.included_files);
            }
        }
        // the cases differ, so the comparison means something: thumbs.db leaves out Thumbs.db only
        // when the case is ignored, the cache folder goes only with caches
        assert!(
            seen.iter().all(|included| *included < files.len() as u64),
            "{seen:?}"
        );
        assert_eq!(seen.len(), 4);
        assert!(
            seen[0] > seen[1] && seen[0] > seen[2] && seen[2] > seen[3],
            "{seen:?}"
        );
        let _ = std::fs::remove_dir_all(&work);
    }
}
