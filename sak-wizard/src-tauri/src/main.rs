// no console window next to the app in windows release builds; the commands of cli.rs print
// nothing there then
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! sak-wizard: the window (gui) or, with a command as the first argument, the command line
//! (cli), both on the same core

// the texts of locales/, english for the ones missing in a language
rust_i18n::i18n!("locales", fallback = "en");

mod cli;
mod core;
mod gui;

fn main() {
    core::i18n::init();
    // list, create or deploy as the first argument: the command line, no window
    let args: Vec<String> = std::env::args().skip(1).collect();
    if cli::is_command_line(&args) {
        use clap::Parser;
        let code = match cli::Cli::parse().command {
            Some(command) => cli::run(command),
            None => 0,
        };
        std::process::exit(code);
    }
    gui::run();
}
