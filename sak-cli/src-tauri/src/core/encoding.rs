//! hex and random bytes, the one place sakctl makes them

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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hex_has_two_lower_case_digits_a_byte_and_random_bytes_differ() {
        assert_eq!(hex(&[0x00, 0x0f, 0xab, 0xff]), "000fabff");
        assert_eq!(random_hex::<16>().unwrap().len(), 32);
        assert_ne!(random::<16>().unwrap(), random::<16>().unwrap());
    }
}
