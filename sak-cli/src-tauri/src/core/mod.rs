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
        for (name, source) in [
            ("checks/mod.rs", include_str!("checks/mod.rs")),
            (
                "checks/container_runtime.rs",
                include_str!("checks/container_runtime.rs"),
            ),
            ("checks/image.rs", include_str!("checks/image.rs")),
            (
                "checks/provider_tokens.rs",
                include_str!("checks/provider_tokens.rs"),
            ),
            ("ansible_roles.rs", include_str!("ansible_roles.rs")),
            ("backup.rs", include_str!("backup.rs")),
            ("config.rs", include_str!("config.rs")),
            ("container.rs", include_str!("container.rs")),
            ("deployments.rs", include_str!("deployments.rs")),
            ("encoding.rs", include_str!("encoding.rs")),
            ("error.rs", include_str!("error.rs")),
            ("provision.rs", include_str!("provision.rs")),
            ("roles.rs", include_str!("roles.rs")),
            ("sak_backend.rs", include_str!("sak_backend.rs")),
            ("i18n.rs", include_str!("i18n.rs")),
            ("keys.rs", include_str!("keys.rs")),
            ("maintenance.rs", include_str!("maintenance.rs")),
            ("update.rs", include_str!("update.rs")),
            ("providers/mod.rs", include_str!("providers/mod.rs")),
            ("providers/hetzner.rs", include_str!("providers/hetzner.rs")),
        ] {
            for word in ["tauri", "clap"] {
                assert!(!source.contains(word), "core/{name} mentions {word}");
            }
        }
    }
}
