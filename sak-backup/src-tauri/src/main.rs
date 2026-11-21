// no console window next to the app in windows release builds
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! sakbackup: the window that manages the backups of a SAK instance, signed in through the
//! browser (sak-desktop-shared), or, with a command as the first argument, the command line (cli)

// the texts of locales/, generated from `sak_backup` of i18n/texts.yml; english where a language has none
rust_i18n::i18n!("locales", fallback = "en");

mod cli;
mod core;
mod gui;

fn main() {
    sak_desktop_shared::i18n::init_locale(&rust_i18n::available_locales!());
    let args: Vec<String> = std::env::args().skip(1).collect();
    if sak_desktop_shared::cli::is_command_line(&args) {
        use clap::Parser;
        std::process::exit(cli::run(cli::Cli::parse()));
    }
    gui::run();
}
