//! what the window and the command line share: the checks on startup, the deployments and their
//! manager, the provisioning, the container runtime, the keys derived from a password, the signed
//! requests to the api, the backups, the providers and their tokens, the texts. Nothing in here knows tauri or
//! clap

pub mod ansible_roles;
pub mod backup;
pub mod checks;
pub mod config;
pub mod container;
pub mod deployments;
pub mod encoding;
pub mod error;
pub mod events;
pub mod i18n;
pub mod keys;
pub mod maintenance;
pub mod providers;
pub mod provision;
pub mod roles;
pub mod runtime;
pub mod sak_backend;
pub mod ssh;
pub mod update;

/// the version of SAK this sakctl is (`VERSION` of the build, `build.rs`), the tag of the images
/// it pulls and deploys; `latest` for a build that is no release
pub const VERSION: &str = env!("SAK_VERSION");

/// the variable that sets another tag of the images than the version, e.g. `<version>-rc` to test
/// the images of a release before it is released
pub const IMAGE_TAG_VARIABLE: &str = "SAK_DOCKER_IMAGE_TAG";

/// the tag of the images sakctl runs and the server pulls: `SAK_DOCKER_IMAGE_TAG` when it is set,
/// else the version
pub fn image_tag() -> &'static str {
    static TAG: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
        std::env::var(IMAGE_TAG_VARIABLE)
            .ok()
            .map(|tag| tag.trim().to_string())
            .filter(|tag| !tag.is_empty())
            .unwrap_or_else(|| VERSION.to_string())
    });
    &TAG
}

#[cfg(test)]
mod tests {
    /// the boundary: the shared code names neither the window nor the command line
    #[test]
    fn core_knows_neither_tauri_nor_clap() {
        // every file below src/core, so a new one is checked too
        fn sources(dir: &std::path::Path, found: &mut Vec<std::path::PathBuf>) {
            for entry in std::fs::read_dir(dir).unwrap() {
                let path = entry.unwrap().path();
                if path.is_dir() {
                    sources(&path, found);
                } else if path.extension().is_some_and(|extension| extension == "rs") {
                    found.push(path);
                }
            }
        }
        let core = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src/core");
        let mut found = Vec::new();
        sources(&core, &mut found);
        assert!(found.len() > 20, "{found:?}");
        // this file names the words, in its doc and here
        for path in found
            .into_iter()
            .filter(|path| *path != core.join("mod.rs"))
        {
            let source = std::fs::read_to_string(&path).unwrap();
            for word in ["tauri", "clap"] {
                assert!(
                    !source.contains(word),
                    "{} mentions {word}",
                    path.strip_prefix(&core).unwrap().display()
                );
            }
        }
    }
}
