//! what the window and the command line share: the checks on startup, the deployments and their manager, the keys
//! derived from a password, the providers and their tokens, the texts. Nothing in here knows
//! tauri or clap

pub mod checks;
pub mod config;
pub mod container;
pub mod deployments;
pub mod error;
pub mod i18n;
pub mod keys;
pub mod providers;
pub mod provision;

#[cfg(test)]
mod tests {
    /// the boundary: what the window and the command line share names neither of them
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
            ("config.rs", include_str!("config.rs")),
            ("container.rs", include_str!("container.rs")),
            ("deployments.rs", include_str!("deployments.rs")),
            ("error.rs", include_str!("error.rs")),
            ("provision.rs", include_str!("provision.rs")),
            ("i18n.rs", include_str!("i18n.rs")),
            ("keys.rs", include_str!("keys.rs")),
            ("providers/mod.rs", include_str!("providers/mod.rs")),
            ("providers/hetzner.rs", include_str!("providers/hetzner.rs")),
        ] {
            for word in ["tauri", "clap"] {
                assert!(!source.contains(word), "core/{name} mentions {word}");
            }
        }
    }
}
