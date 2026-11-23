//! the test that the page of an app and its window agree: every `invoke('name', {…})` of the
//! scripts of the page names a command of `tauri::generate_handler!`, with the arguments the
//! command takes. tauri finds a wrong name or argument only when the page calls it, this test when
//! it is written. An app calls `command_tests!` in its tests; the functions here read the sources
//! as text, so they need no tauri and are no test code of this crate only

use std::collections::{BTreeMap, BTreeSet};
use std::path::Path;

/// the test of the commands of the window of an app, in the test module it is called in:
/// - `handler`: the source of the file with `tauri::generate_handler!`
/// - `sources`: the source of the module of a command of the app, by the path of the module as
///   the handler has it (`commands` of `commands::restart`, empty for a command without a module);
///   the sources of the commands of this crate are its own (`shared_source`)
/// - `ui`: the directory of the page, with its scripts and the ones of `shared/`
/// - `unused`: the scripts of `shared/` the page does not load, their invokes are not checked
/// - `invokes`: how many invokes the page has at least, so a test that reads none fails
#[macro_export]
macro_rules! command_tests {
    (
        handler: $handler:expr,
        sources: $sources:expr,
        ui: $ui:expr,
        unused: $unused:expr,
        invokes: $invokes:expr $(,)?
    ) => {
        #[test]
        fn every_invoke_of_the_page_names_a_command_with_its_arguments() {
            let mismatches = $crate::command_tests::mismatches(
                $handler,
                $sources,
                std::path::Path::new($ui),
                &$unused,
                $invokes,
            );
            assert!(mismatches.is_empty(), "{}", mismatches.join("\n"));
        }
    };
}

/// the commands of a window as `tauri::generate_handler![…]` lists them in `handler`, each with
/// its path: `commands::restart`, `sak_desktop_shared::window::login`
pub fn registered(handler: &str) -> Vec<String> {
    handler
        .split("generate_handler![")
        .nth(1)
        .and_then(|list| list.split(']').next())
        .unwrap_or_default()
        .split(',')
        .map(|entry| entry.trim().to_string())
        .filter(|entry| !entry.is_empty())
        .collect()
}

/// the source of the commands of a module of this crate an app registers (`window`, `update`,
/// `checks`)
pub fn shared_source(module: &str) -> Option<&'static str> {
    match module {
        "checks" => Some(include_str!("checks.rs")),
        "window" => Some(include_str!("window.rs")),
        "update" => Some(include_str!("update.rs")),
        _ => None,
    }
}

/// the names of the arguments the page gives the command `name` of `source`: the parameters of
/// `fn name(…)` without the ones tauri fills (the state, the app, the window); none without the
/// function
pub fn arguments(source: &str, name: &str) -> Option<BTreeSet<String>> {
    let after = source.split(&format!("fn {name}(")).nth(1)?;
    // without the comments between the parameters, a comma or a bracket of a comment is none of
    // the signature
    let after: String = after
        .lines()
        .map(|line| line.split("//").next().unwrap_or_default())
        .collect::<Vec<_>>()
        .join("\n");
    Some(
        parts(&after, true)?
            .into_iter()
            .filter_map(|parameter| parameter.split_once(':'))
            .filter(|(_, kind)| {
                !["State<", "AppHandle", "Window"]
                    .iter()
                    .any(|filled| kind.contains(filled))
            })
            .map(|(argument, _)| argument.trim().trim_start_matches("mut ").to_string())
            .collect(),
    )
}

/// an `invoke` of a script of the page
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Invoke {
    /// the script, by its path below the directory of the page
    pub file: String,
    pub command: String,
    /// the names of the arguments in snake case, as the command has them; none when they are no
    /// object written at the call (a variable, a spread), which the test can not read
    pub arguments: Option<BTreeSet<String>>,
}

/// every `invoke('name', {…})` of the script `source`
pub fn invokes_of(file: &str, source: &str) -> Vec<Invoke> {
    source
        .split("invoke('")
        .skip(1)
        .filter_map(|call| call.split_once('\''))
        .map(|(command, rest)| {
            let rest = rest.trim_start();
            let arguments = match rest.strip_prefix(',').map(str::trim_start) {
                None => Some(BTreeSet::new()),
                Some(given) => given
                    .strip_prefix('{')
                    .and_then(|object| parts(object, false))
                    .and_then(|properties| {
                        properties
                            .into_iter()
                            .map(|property| property.split(':').next().unwrap_or_default().trim())
                            .filter(|key| !key.is_empty())
                            .map(|key| (!key.starts_with("...")).then(|| snake(key)))
                            .collect()
                    }),
            };
            Invoke {
                file: file.to_string(),
                command: command.to_string(),
                arguments,
            }
        })
        .collect()
}

/// the invokes of the scripts of the page in `ui` and in its `shared/`, without the scripts
/// `unused` of `shared/`. Panics when a directory or a script can not be read
pub fn invoked(ui: &Path, unused: &[&str]) -> Vec<Invoke> {
    scripts(ui)
        .into_iter()
        .filter(|(file, _)| {
            !unused
                .iter()
                .any(|unused| *file == format!("shared/{unused}"))
        })
        .flat_map(|(file, source)| invokes_of(&file, &source))
        .collect()
}

/// what the page and the window do not agree on, each as a sentence; empty when every invoke names
/// a registered command with its arguments. The arguments of the macro `command_tests!`
pub fn mismatches(
    handler: &str,
    source_of: impl Fn(&str) -> &'static str,
    ui: &Path,
    unused: &[&str],
    at_least: usize,
) -> Vec<String> {
    let mut mismatches = Vec::new();
    // the page calls a command by its name alone, the last part of its path
    let mut commands: BTreeMap<String, BTreeSet<String>> = BTreeMap::new();
    for path in registered(handler) {
        let (module, name) = path.rsplit_once("::").unwrap_or(("", path.as_str()));
        let source = match module.strip_prefix("sak_desktop_shared::") {
            Some(shared) => shared_source(shared),
            None => Some(source_of(module)),
        };
        let arguments = source.and_then(|source| {
            arguments(source, name).or_else(|| {
                // the commands `ui_text_commands!` writes are not in the source of their module
                source
                    .contains("ui_text_commands!(")
                    .then(|| arguments(include_str!("i18n.rs"), name))
                    .flatten()
            })
        });
        match arguments {
            None => mismatches.push(format!(
                "{path} is registered, the source of its module has no fn {name}"
            )),
            Some(arguments) => {
                if commands.insert(name.to_string(), arguments).is_some() {
                    mismatches.push(format!("two registered commands have the name {name}"));
                }
            }
        }
    }
    let all = scripts(ui);
    for script in unused {
        let file = format!("shared/{script}");
        if !all.iter().any(|(name, _)| *name == file) {
            mismatches.push(format!("{file} is no script of the page"));
        }
        // a script of the page that names it loads it
        for (name, source) in &all {
            if *name != file && source.contains(script) {
                mismatches.push(format!(
                    "{name} names {file}, which the test takes as unused"
                ));
            }
        }
    }
    let invokes = invoked(ui, unused);
    if invokes.len() < at_least {
        mismatches.push(format!(
            "the page has {} invokes, {at_least} at least are expected",
            invokes.len()
        ));
    }
    for invoke in invokes {
        let Invoke { file, command, .. } = &invoke;
        match (commands.get(command), &invoke.arguments) {
            (None, _) => {
                mismatches.push(format!(
                    "{file} invokes {command}, no command of the window"
                ));
            }
            (Some(_), None) => mismatches.push(format!(
                "{file} invokes {command} with arguments that are no object written at the call"
            )),
            (Some(expected), Some(given)) if expected != given => mismatches.push(format!(
                "{file} invokes {command} with {given:?}, the command takes {expected:?}"
            )),
            _ => {}
        }
    }
    mismatches
}

/// the scripts of the page: `<name>.js` of `ui` and `shared/<name>.js`, each with its source,
/// sorted
fn scripts(ui: &Path) -> Vec<(String, String)> {
    let mut scripts = Vec::new();
    for (directory, prefix) in [(ui.to_path_buf(), ""), (ui.join("shared"), "shared/")] {
        let entries = std::fs::read_dir(&directory)
            .unwrap_or_else(|error| panic!("{}: {error}", directory.display()));
        for entry in entries {
            let path = entry.expect("an entry of the directory of the page").path();
            if path.extension().is_some_and(|extension| extension == "js") {
                let name = path.file_name().unwrap_or_default().to_string_lossy();
                let source = std::fs::read_to_string(&path)
                    .unwrap_or_else(|error| panic!("{}: {error}", path.display()));
                scripts.push((format!("{prefix}{name}"), source));
            }
        }
    }
    scripts.sort();
    scripts
}

/// the parts of `text` between its commas, up to the bracket that closes what `text` starts in;
/// a comma inside brackets of its own is no end of a part. With `rust` the angle brackets of a
/// type count, else (javascript) a text in quotes is skipped. None when nothing closes `text`
fn parts(text: &str, rust: bool) -> Option<Vec<&str>> {
    let mut parts = Vec::new();
    let (mut depth, mut start) = (0, 0);
    let mut quote = None;
    let mut previous = ' ';
    for (at, c) in text.char_indices() {
        if let Some(open) = quote {
            if c == open && previous != '\\' {
                quote = None;
            }
        } else {
            match c {
                '\'' | '"' | '`' if !rust => quote = Some(c),
                '(' | '[' | '{' => depth += 1,
                '<' if rust => depth += 1,
                // the arrow of a return type closes no angle bracket
                '>' if rust && previous != '-' && depth > 0 => depth -= 1,
                ')' | ']' | '}' if depth > 0 => depth -= 1,
                ')' | ']' | '}' => {
                    parts.push(&text[start..at]);
                    return Some(parts);
                }
                ',' if depth == 0 => {
                    parts.push(&text[start..at]);
                    start = at + 1;
                }
                _ => {}
            }
        }
        previous = c;
    }
    None
}

/// `onEvent` of the page as the command has it, `on_event`
fn snake(name: &str) -> String {
    let mut snake = String::new();
    for c in name.chars() {
        if c.is_ascii_uppercase() {
            snake.push('_');
        }
        snake.push(c.to_ascii_lowercase());
    }
    snake
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(names: &[&str]) -> BTreeSet<String> {
        names.iter().map(|name| name.to_string()).collect()
    }

    const HANDLER: &str = "
        .invoke_handler(tauri::generate_handler![
            locale,
            sak_desktop_shared::window::login,
            commands::run_backup,
            commands::restart
        ])";

    const COMMANDS: &str = "
        pub async fn run_backup(
            app: tauri::AppHandle,
            runs: State<'_, Runs>,
            id: u32,
            // the paths (of the plan, when none are given), each with its files
            mut paths: HashMap<String, Vec<String>>,
            on_event: Channel<RunEvent>,
        ) -> Result<Summary, RunError> {}
        pub fn restart(app: tauri::AppHandle, runs: State<'_, Runs>) -> Result<(), String> {}";

    #[test]
    fn the_commands_of_a_window_are_the_ones_of_its_handler() {
        assert_eq!(
            registered(HANDLER),
            [
                "locale",
                "sak_desktop_shared::window::login",
                "commands::run_backup",
                "commands::restart"
            ]
        );
        assert!(registered("no handler").is_empty());
    }

    #[test]
    fn the_arguments_of_a_command_are_its_parameters_without_the_ones_tauri_fills() {
        assert_eq!(
            arguments(COMMANDS, "run_backup"),
            Some(set(&["id", "paths", "on_event"]))
        );
        assert_eq!(arguments(COMMANDS, "restart"), Some(set(&[])));
        assert_eq!(arguments(COMMANDS, "start"), None);
    }

    #[test]
    fn the_commands_of_this_crate_have_their_arguments() {
        let window = shared_source("window").unwrap();
        assert_eq!(
            arguments(window, "login"),
            Some(set(&["address", "on_code"]))
        );
        assert_eq!(arguments(window, "logout"), Some(set(&[])));
        let update = shared_source("update").unwrap();
        for command in ["version", "check_update", "install_update"] {
            assert_eq!(arguments(update, command), Some(set(&[])), "{command}");
        }
        assert_eq!(shared_source("session"), None);
    }

    #[test]
    fn an_invoke_has_the_names_of_its_arguments_in_snake_case() {
        let script = "
            await Promise.all([invoke('plans'), invoke('host' )]);
            invoke('run_backup', { id: plan.id, paths: { a: [1, 2], b }, onEvent });
            invoke('send', {
              smtp: { host: text('a, b'), port: 1 },
              rootDomain,
            });
            invoke('restart', options);
            invoke('create', { ...plan, id });";
        let invokes: Vec<_> = invokes_of("app.js", script)
            .into_iter()
            .map(|invoke| (invoke.command, invoke.arguments))
            .collect();
        assert_eq!(
            invokes,
            [
                ("plans".to_string(), Some(set(&[]))),
                ("host".to_string(), Some(set(&[]))),
                (
                    "run_backup".to_string(),
                    Some(set(&["id", "paths", "on_event"]))
                ),
                ("send".to_string(), Some(set(&["smtp", "root_domain"]))),
                ("restart".to_string(), None),
                ("create".to_string(), None),
            ]
        );
    }

    /// a page with `script` and the footer of the update of this crate, in a directory of its own
    fn page(name: &str, script: &str) -> std::path::PathBuf {
        let ui = std::env::temp_dir().join(format!(
            "sak-command-tests-{name}-{}",
            crate::encoding::random_hex::<8>().unwrap()
        ));
        std::fs::create_dir_all(ui.join("shared")).unwrap();
        std::fs::write(ui.join("app.js"), script).unwrap();
        std::fs::write(ui.join("shared/login.js"), "invoke('logout');").unwrap();
        ui
    }

    fn found(script: &str, unused: &[&str], at_least: usize) -> Vec<String> {
        let ui = page("page", script);
        let sources = |module: &str| match module {
            "commands" => COMMANDS,
            _ => "sak_desktop_shared::ui_text_commands!(shared: true);",
        };
        let found = mismatches(HANDLER, sources, &ui, unused, at_least);
        std::fs::remove_dir_all(&ui).unwrap();
        found
    }

    #[test]
    fn a_page_that_invokes_its_commands_with_their_arguments_has_no_mismatch() {
        let script = "invoke('locale'); invoke('restart');
            invoke('login', { address, onCode });
            invoke('run_backup', { id, paths, onEvent: channel });";
        assert_eq!(found(script, &["login.js"], 4), Vec::<String>::new());
    }

    #[test]
    fn a_wrong_name_or_argument_of_an_invoke_is_a_mismatch() {
        let script = "invoke('locale', { language }); invoke('quit');
            invoke('run_backup', { id, onEvent }); invoke('restart', options);";
        assert_eq!(
            found(script, &[], 9),
            [
                "the page has 5 invokes, 9 at least are expected",
                "app.js invokes locale with {\"language\"}, the command takes {}",
                "app.js invokes quit, no command of the window",
                "app.js invokes run_backup with {\"id\", \"on_event\"}, the command takes {\"id\", \"on_event\", \"paths\"}",
                "app.js invokes restart with arguments that are no object written at the call",
                "shared/login.js invokes logout, no command of the window",
            ]
        );
    }

    #[test]
    fn an_unused_script_that_the_page_names_is_a_mismatch() {
        let script = "import { signIn } from './shared/login.js';";
        assert_eq!(
            found(script, &["login.js", "gone.js"], 0),
            [
                "app.js names shared/login.js, which the test takes as unused",
                "shared/gone.js is no script of the page",
            ]
        );
    }

    #[test]
    fn a_registered_command_without_its_function_is_a_mismatch() {
        let ui = page("handler", "");
        let handler = "generate_handler![commands::gone, sak_desktop_shared::session::x]";
        let found = mismatches(handler, |_| COMMANDS, &ui, &["login.js"], 0);
        std::fs::remove_dir_all(&ui).unwrap();
        assert_eq!(
            found,
            [
                "commands::gone is registered, the source of its module has no fn gone",
                "sak_desktop_shared::session::x is registered, the source of its module has no fn x",
            ]
        );
    }
}
