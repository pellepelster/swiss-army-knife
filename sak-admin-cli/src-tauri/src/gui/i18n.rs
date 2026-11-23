//! the commands of the pages about the texts: the language of the page and its texts below `ui.`
//! (the window alone) and `common.` (the window and the command line), with the ones of
//! sak-desktop-shared (the footer of the update)

sak_desktop_shared::ui_text_commands!(shared: true);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_window_has_its_texts_and_the_ones_of_the_update_footer() {
        let texts = ui_texts();
        // every text shared/update.js shows
        for key in [
            "ui.update.version",
            "ui.update.check",
            "ui.update.idle",
            "ui.update.install",
            "ui.update.restart",
            "ui.update.restart_hint",
            "common.update.checking",
            "common.update.installing",
        ] {
            assert!(texts.contains_key(key), "{key}");
        }
        // the keys of the app and the shared ones do not overlap
        let shared = sak_desktop_shared::i18n::shared_texts();
        let own = sak_desktop_shared::i18n::keys(crate::_rust_i18n_backend(), "en");
        assert!(own.iter().all(|key| !shared.contains_key(key)));
    }
}
