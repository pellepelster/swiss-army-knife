//! hex and random bytes (of sak-desktop-shared), and text of the server made safe to print

pub use sak_desktop_shared::encoding::{hex, random, random_hex};

/// a line of the server or of ansible as sakadmin prints it: every control character but a tab becomes
/// U+FFFD, so a server that is taken over can not send escape sequences to the terminal
pub fn printable(line: &str) -> String {
    line.chars()
        .map(|c| {
            if c.is_control() && c != '\t' {
                '\u{FFFD}'
            } else {
                c
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn printable_replaces_the_control_characters_of_a_line_but_tabs() {
        assert_eq!(printable("ok\tdone"), "ok\tdone");
        assert_eq!(
            printable("\u{1b}[2J\u{1b}]0;title\u{7}x"),
            "\u{FFFD}[2J\u{FFFD}]0;title\u{FFFD}x"
        );
        assert_eq!(printable("a\rb\u{9b}c"), "a\u{FFFD}b\u{FFFD}c");
        assert_eq!(printable("Grüße"), "Grüße");
    }
}
