// no console window next to the app in windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! sak-backup: the window that manages the backups of a SAK instance, signed in through the
//! browser (sak-desktop-shared). The backups themselves are a proposal (README.md)

// the texts of locales/, generated from `sak_backup` of i18n/texts.yml; english where a language has none
rust_i18n::i18n!("locales", fallback = "en");

mod gui;

fn main() {
    rust_i18n::set_locale(&sak_desktop_shared::i18n::select_locale(
        sys_locale::get_locale(),
        &rust_i18n::available_locales!(),
    ));
    gui::run();
}
