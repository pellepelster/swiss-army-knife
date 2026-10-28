use crate::api::error::{Error, Result};
use crate::api::resource::Labels;
use sha2::{Digest, Sha256};

/// a hetzner label value holds at most 63 characters, longer values are chunked across
/// indexed keys: `key_0`, `key_1`
const DELIMITER: char = '_';

const MAX_VALUE_LENGTH: usize = 62;

const MAX_TOTAL_VALUE_LENGTH: usize = MAX_VALUE_LENGTH * 2;

pub fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

/// whether a hashed label still matches the value it was made from, with both hashes for
/// the diff
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HashComparison {
    pub matches: bool,
    pub expected: String,
    pub actual: Option<String>,
}

/// the labels of a hetzner resource with values longer than a label allows: those are
/// chunked across indexed keys when stored and reassembled when read. A sha256 hash of
/// a value (user data, ssh key fingerprints) is one such value, it is 64 characters
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HetznerLabels {
    raw: Labels,
}

impl HetznerLabels {
    pub fn new() -> Self {
        Self::default()
    }

    /// from the labels as the api reports them, chunks included
    pub fn from_raw(raw: Labels) -> Self {
        HetznerLabels { raw }
    }

    /// the labels as stored at hetzner, chunks included: what goes into a request
    pub fn raw(&self) -> &Labels {
        &self.raw
    }

    pub fn into_raw(self) -> Labels {
        self.raw
    }

    /// the labels with the chunks reassembled
    pub fn labels(&self) -> Labels {
        let mut labels = Labels::new();
        for (key, value) in &self.raw {
            labels
                .entry(key_of(key).to_string())
                .or_default()
                .push_str(value);
        }
        labels
    }

    /// `labels` added as they are, chunked when too long
    pub fn add_all(&mut self, labels: &Labels) -> Result<()> {
        labels
            .iter()
            .try_for_each(|(key, value)| self.add(key, value))
    }

    pub fn add(&mut self, key: &str, value: &str) -> Result<()> {
        if key.contains(DELIMITER) {
            return Err(Error::InvalidLabel(format!(
                "key '{key}' contains '{DELIMITER}'"
            )));
        }

        if value.len() > MAX_TOTAL_VALUE_LENGTH {
            return Err(Error::InvalidLabel(format!(
                "the value of '{key}' is longer than {MAX_TOTAL_VALUE_LENGTH} characters"
            )));
        }

        if value.len() >= MAX_VALUE_LENGTH {
            let chunks = value.as_bytes().chunks(MAX_VALUE_LENGTH).enumerate();
            for (index, chunk) in chunks {
                let chunk = std::str::from_utf8(chunk).map_err(|_| {
                    Error::InvalidLabel(format!("the value of '{key}' is not ascii"))
                })?;
                self.raw
                    .insert(format!("{key}{DELIMITER}{index}"), chunk.to_string());
            }
        } else {
            self.raw.insert(key.to_string(), value.to_string());
        }
        Ok(())
    }

    /// stores the sha256 of `value` under `key`, `hash_matches` compares it later
    pub fn add_hashed(&mut self, key: &str, value: &str) -> Result<()> {
        self.add(key, &sha256_hex(value))
    }

    pub fn hash_matches(&self, key: &str, value: &str) -> HashComparison {
        let actual = self.labels().get(key).cloned();
        let expected = sha256_hex(value);
        HashComparison {
            matches: actual.as_deref() == Some(expected.as_str()),
            expected,
            actual,
        }
    }
}

// `key_1` is the second chunk of `key`, a key whose suffix is no number is a key of its own
fn key_of(raw_key: &str) -> &str {
    match raw_key.rsplit_once(DELIMITER) {
        Some((key, index)) if index.parse::<usize>().is_ok() => key,
        _ => raw_key,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const HALLO_WELT_HASH: &str =
        "028fb9cd289c106642177d7bd4b6c5e107265b90f17f6b52a1cb0d7584264455";

    #[test]
    fn add_hashed_stores_the_hash_of_the_value_and_matches_it_back() {
        let mut labels = HetznerLabels::new();

        labels.add_hashed("hash-test", "hallo welt").unwrap();
        assert_eq!(labels.labels()["hash-test"], HALLO_WELT_HASH);

        assert!(labels.hash_matches("hash-test", "hallo welt").matches);
        let mismatch = labels.hash_matches("hash-test", "hallo welt1");
        assert!(!mismatch.matches);
        assert_eq!(mismatch.actual.as_deref(), Some(HALLO_WELT_HASH));
        assert_eq!(mismatch.expected, sha256_hex("hallo welt1"));
    }

    #[test]
    fn raw_labels_round_trip_through_a_new_instance() {
        let mut labels = HetznerLabels::new();
        labels.add_hashed("hash-test", "hallo welt").unwrap();
        labels.add_hashed("long-test", &"A".repeat(124)).unwrap();

        let imported = HetznerLabels::from_raw(labels.raw().clone());

        assert_eq!(imported.labels()["hash-test"], HALLO_WELT_HASH);
        assert!(imported.hash_matches("long-test", &"A".repeat(124)).matches);
    }

    #[test]
    fn an_overlong_value_is_chunked_across_indexed_keys_and_reassembled_on_read() {
        let mut labels = HetznerLabels::new();
        labels.add("label1", &"A".repeat(124)).unwrap();

        assert_eq!(labels.labels().len(), 1);
        assert_eq!(labels.labels()["label1"], "A".repeat(124));
        assert_eq!(labels.raw()["label1_0"], "A".repeat(62));
        assert_eq!(labels.raw()["label1_1"], "A".repeat(62));
    }

    #[test]
    fn a_value_beyond_twice_the_chunk_size_is_rejected() {
        let mut labels = HetznerLabels::new();

        assert!(labels.add("label1", &"A".repeat(125)).is_err());
    }

    #[test]
    fn a_key_containing_the_chunk_delimiter_is_rejected() {
        let mut labels = HetznerLabels::new();

        assert!(labels.add("label_1", "value1").is_err());
    }

    #[test]
    fn a_hash_of_a_missing_label_does_not_match() {
        let labels = HetznerLabels::new();

        let comparison = labels.hash_matches("none", "value");
        assert!(!comparison.matches);
        assert_eq!(comparison.actual, None);
    }

    #[test]
    fn a_key_with_a_non_numeric_suffix_is_a_key_of_its_own() {
        let labels = HetznerLabels::from_raw(Labels::from([(
            "kubernetes_io".to_string(),
            "yes".to_string(),
        )]));

        assert_eq!(labels.labels()["kubernetes_io"], "yes");
    }
}
