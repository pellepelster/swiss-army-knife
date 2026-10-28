//! what the window and the command line share: the deployments and their manager, the keys
//! derived from a password, the providers and their tokens, the texts. Nothing in here knows
//! tauri or clap

pub mod container;
pub mod deployments;
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
            ("container.rs", include_str!("container.rs")),
            ("deployments.rs", include_str!("deployments.rs")),
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
