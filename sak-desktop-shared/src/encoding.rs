//! hex and random bytes, the one place the desktop apps make them, and text of a server made safe
//! to print

/// the bytes as lower case hex, two digits each
pub fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// `N` bytes of the random source of the system
pub fn random<const N: usize>() -> Result<[u8; N], String> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|error| error.to_string())?;
    Ok(bytes)
}

/// `N` random bytes as hex, `2 * N` digits
pub fn random_hex<const N: usize>() -> Result<String, String> {
    Ok(hex(&random::<N>()?))
}

/// a line of a server as a desktop app prints it to a terminal: every control character but a tab
/// becomes U+FFFD, so a server that is taken over can not send escape sequences to the terminal, e.g.
/// one that sets the clipboard or hides a line (SEC-48, SEC-54)
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

/// `printable` for a text of lines, e.g. the excludes of a backup plan: its line breaks stay
pub fn printable_lines(text: &str) -> String {
    text.split('\n')
        .map(printable)
        .collect::<Vec<_>>()
        .join("\n")
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
        assert_eq!(printable("a\rb\u{9b}c\nd"), "a\u{FFFD}b\u{FFFD}c\u{FFFD}d");
        assert_eq!(printable("Grüße"), "Grüße");
        assert_eq!(
            printable_lines("*.tmp\n\u{1b}]52;c;aGk=\u{7}\r\nnode_modules"),
            "*.tmp\n\u{FFFD}]52;c;aGk=\u{FFFD}\u{FFFD}\nnode_modules"
        );
    }

    #[test]
    fn hex_has_two_lower_case_digits_a_byte_and_random_bytes_differ() {
        assert_eq!(hex(&[0x00, 0x0f, 0xab, 0xff]), "000fabff");
        assert_eq!(hex(&[]), "");
        let random_hex = random_hex::<16>().unwrap();
        assert_eq!(random_hex.len(), 32);
        assert!(
            random_hex
                .chars()
                .all(|digit| digit.is_ascii_hexdigit() && !digit.is_ascii_uppercase())
        );
        assert_ne!(random::<16>().unwrap(), random::<16>().unwrap());
    }
}
