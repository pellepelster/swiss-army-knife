//! hex and random bytes, the one place sakctl makes them, and text of the server made safe to print

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

/// a line of the server or of ansible as sakctl prints it (SEC-25): every control character, an
/// escape sequence of a terminal among them, is replaced with U+FFFD, so a server that is taken over
/// can not move the cursor, rewrite what the terminal shows or change its title; a tab stays
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

    #[test]
    fn hex_has_two_lower_case_digits_a_byte_and_random_bytes_differ() {
        assert_eq!(hex(&[0x00, 0x0f, 0xab, 0xff]), "000fabff");
        assert_eq!(random_hex::<16>().unwrap().len(), 32);
        assert_ne!(random::<16>().unwrap(), random::<16>().unwrap());
    }
}
