//! the output of the command line: each line `<date> <level> <message>`, the level in colour on a
//! terminal

use super::*;

/// a resource as the command line names it: its kind in the language of the command line and its
/// name, `ssh key 'sak-alpha'`, `SSH-Key 'sak-alpha'`
pub(super) fn title(kind: &str, name: &str) -> String {
    format!(
        "{} '{name}'",
        t!(format!("shared.deployments.resource.{kind}"))
    )
}

pub(super) fn print_json(value: &impl serde::Serialize) -> Result<(), SakctlError> {
    let text = serde_json::to_string_pretty(value).map_err(|error| error.to_string())?;
    anstream::println!("{text}");
    Ok(())
}

/// a size in bytes for people: `812 B`, `3.4 MiB`
pub(super) fn size(bytes: u64) -> String {
    const UNITS: [&str; 5] = ["B", "KiB", "MiB", "GiB", "TiB"];
    let mut value = bytes as f64;
    let mut unit = 0;
    while value >= 1024.0 && unit < UNITS.len() - 1 {
        value /= 1024.0;
        unit += 1;
    }
    if unit == 0 {
        format!("{bytes} B")
    } else {
        format!("{value:.1} {}", UNITS[unit])
    }
}

/// the level of a line of the log, with its colour on a terminal
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Level {
    Info,
    /// a change the plan found, a resource the apply removes, an ignored failure of a task
    Warn,
    /// the command failed
    Error,
}

impl Level {
    pub(super) fn name(self) -> &'static str {
        match self {
            Level::Info => "INFO",
            Level::Warn => "WARN",
            Level::Error => "ERROR",
        }
    }

    pub(super) fn style(self) -> Style {
        let color = match self {
            Level::Info => AnsiColor::Green,
            Level::Warn => AnsiColor::Yellow,
            Level::Error => AnsiColor::Red,
        };
        Style::new().fg_color(Some(color.into())).bold()
    }
}

/// `<date> <level> <message>`, one space between each
pub(super) fn log_line(date: &str, level: Level, message: impl Display) -> String {
    let style = level.style();
    format!("{date} {style}{}{style:#} {message}", level.name())
}

/// prints a line of the log, an error to stderr. anstream removes the colour when the output is
/// not a terminal or NO_COLOR is set
pub(super) fn log(level: Level, message: impl Display) {
    let date = chrono::Local::now().format("%Y-%m-%d %H:%M:%S").to_string();
    let line = log_line(&date, level, message);
    match level {
        Level::Error => anstream::eprintln!("{line}"),
        _ => anstream::println!("{line}"),
    }
}
