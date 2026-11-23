//! the presets of excludes (presets/ next to Cargo.toml): for each system the lines a backup plan can
//! copy into its excludes, which it then keeps as its own. The app offers those of the system it runs
//! on, the one of the backup plans it changes (BACKUP-5)

use rust_i18n::t;
use serde::Serialize;

/// the presets, in the order they are offered
pub const IDS: [&str; 5] = ["caches", "developer", "build", "scratch", "downloads"];

/// the systems that have presets
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum System {
    Linux,
    Macos,
    Windows,
}

impl System {
    /// the system this app runs on
    pub const fn this() -> System {
        if cfg!(target_os = "macos") {
            System::Macos
        } else if cfg!(windows) {
            System::Windows
        } else {
            System::Linux
        }
    }

    #[cfg(test)]
    pub const ALL: [System; 3] = [System::Linux, System::Macos, System::Windows];
}

/// a preset with its name in the language of the user and its patterns
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Preset {
    pub id: &'static str,
    pub name: String,
    pub patterns: Vec<&'static str>,
}

/// the presets of the system
pub fn presets(system: System) -> Vec<Preset> {
    IDS.iter()
        .map(|id| Preset {
            id,
            name: t!(format!("common.excludes.presets.{id}")).to_string(),
            patterns: lines(file(system, id)),
        })
        .collect()
}

/// the preset of the id for the system; none for an id that is no preset
pub fn preset(system: System, id: &str) -> Option<Preset> {
    presets(system).into_iter().find(|preset| preset.id == id)
}

/// the preset as lines of excludes: its name as a comment, then its patterns
pub fn as_excludes(preset: &Preset) -> String {
    std::iter::once(format!("### {}", preset.name))
        .chain(preset.patterns.iter().map(|pattern| pattern.to_string()))
        .collect::<Vec<_>>()
        .join("\n")
}

fn lines(text: &'static str) -> Vec<&'static str> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .collect()
}

fn file(system: System, id: &str) -> &'static str {
    macro_rules! of {
        ($system:literal) => {
            match id {
                "caches" => include_str!(concat!("../../presets/", $system, "/caches.txt")),
                "developer" => include_str!(concat!("../../presets/", $system, "/developer.txt")),
                "build" => include_str!(concat!("../../presets/", $system, "/build.txt")),
                "scratch" => include_str!(concat!("../../presets/", $system, "/scratch.txt")),
                "downloads" => include_str!(concat!("../../presets/", $system, "/downloads.txt")),
                _ => "",
            }
        };
    }
    match system {
        System::Linux => of!("linux"),
        System::Macos => of!("macos"),
        System::Windows => of!("windows"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    #[test]
    fn every_system_has_every_preset_with_patterns_each_once() {
        for system in System::ALL {
            for preset in presets(system) {
                assert!(!preset.patterns.is_empty(), "{system:?} {}", preset.id);
                let unique: BTreeSet<_> = preset.patterns.iter().collect();
                assert_eq!(
                    unique.len(),
                    preset.patterns.len(),
                    "{system:?} {}",
                    preset.id
                );
                // windows has no HOME, the others no USERPROFILE: a variable that is not set is
                // nothing, the pattern then means another place
                for pattern in &preset.patterns {
                    let wrong = if system == System::Windows {
                        "$HOME"
                    } else {
                        "$USERPROFILE"
                    };
                    assert!(
                        !pattern.contains(wrong),
                        "{system:?} {}: {pattern}",
                        preset.id
                    );
                    assert!(!pattern.chars().any(char::is_control), "{pattern}");
                }
            }
        }
    }

    #[test]
    fn a_preset_is_copied_with_its_name_as_a_comment() {
        let caches = preset(System::Linux, "caches").unwrap();
        assert_eq!(caches.name, "Caches and trash");
        let excludes = as_excludes(&caches);
        assert!(
            excludes.starts_with("### Caches and trash\n$HOME/.cache\n"),
            "{excludes}"
        );
        assert_eq!(preset(System::Linux, "nothing"), None);
    }
}
