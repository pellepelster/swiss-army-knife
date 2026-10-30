use crate::api::error::{Error, Result};
use crate::api::resource::Labels;
use sha2::{Digest, Sha256};

/// separates a key from the index of a chunk: `key_0`, `key_1`
const DELIMITER: char = '_';

/// a hetzner label value has at most 63 characters; a longer value is chunked
const MAX_VALUE_LENGTH: usize = 63;

/// the length of a chunk, as the labels chunked so far have it
const CHUNK_LENGTH: usize = 62;

const MAX_TOTAL_VALUE_LENGTH: usize = CHUNK_LENGTH * 2;

pub fn sha256_hex(value: &str) -> String {
    hex::encode(Sha256::digest(value.as_bytes()))
}

/// how many hex characters of a sha256 a hash label keeps: 240 of its 256 bits, so the hash fits
/// in one label value
pub const LABEL_HASH_LEN: usize = 60;

// a hash label is never chunked
const _: () = assert!(LABEL_HASH_LEN < MAX_VALUE_LENGTH);

/// the sha256 of `value` cut to LABEL_HASH_LEN: the value of a hash label (user data, ssh key
/// fingerprints)
pub fn label_hash(value: &str) -> String {
    let mut hash = sha256_hex(value);
    hash.truncate(LABEL_HASH_LEN);
    hash
}

/// whether a hashed label still matches the value it was made from, with both hashes for
/// the diff
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HashComparison {
    pub matches: bool,
    pub expected: String,
    pub actual: Option<String>,
}

/// the labels of a hetzner resource. A value longer than a label allows is chunked across
/// indexed keys when stored and joined again when read. A hash label fits one value
/// (`label_hash`); a server labeled before the hash was cut has all 64 characters in two
/// chunks
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

    /// the labels with the chunks joined. Only a sequence `key_0` … `key_n` as `add` writes it
    /// is joined: at least two chunks, from 0 on without a gap, every one but the last of the full
    /// chunk length. Any other
    /// key with a numeric suffix (a label of the user, `backup_1`) stays as it is
    pub fn labels(&self) -> Labels {
        let mut chunked: std::collections::BTreeMap<&str, Vec<(usize, &str)>> = Default::default();
        for (key, value) in &self.raw {
            if let Some((base, index)) = chunk_of(key) {
                chunked.entry(base).or_default().push((index, value));
            }
        }
        let joined: std::collections::BTreeMap<&str, String> = chunked
            .into_iter()
            .filter_map(|(base, mut chunks)| {
                chunks.sort_by_key(|(index, _)| *index);
                let consecutive = chunks
                    .iter()
                    .enumerate()
                    .all(|(at, (index, _))| at == *index);
                let full = chunks[..chunks.len() - 1]
                    .iter()
                    .all(|(_, value)| value.len() == CHUNK_LENGTH);
                (chunks.len() >= 2 && consecutive && full && !self.raw.contains_key(base))
                    .then(|| (base, chunks.iter().map(|(_, value)| *value).collect()))
            })
            .collect();
        let mut labels = Labels::new();
        for (key, value) in &self.raw {
            match chunk_of(key) {
                Some((base, _)) if joined.contains_key(base) => {}
                _ => {
                    labels.insert(key.clone(), value.clone());
                }
            }
        }
        for (base, value) in joined {
            labels.insert(base.to_string(), value);
        }
        labels
    }

    /// adds `labels`, chunked when too long
    pub fn add_all(&mut self, labels: &Labels) -> Result<()> {
        labels
            .iter()
            .try_for_each(|(key, value)| self.add(key, value))
    }

    pub fn add(&mut self, key: &str, value: &str) -> Result<()> {
        // `key_1` would be read as the second chunk of `key`
        if chunk_of(key).is_some() {
            return Err(Error::InvalidLabel(format!(
                "key '{key}' ends with '{DELIMITER}' and a number, as the chunks of a long value do"
            )));
        }

        if value.len() > MAX_TOTAL_VALUE_LENGTH {
            return Err(Error::InvalidLabel(format!(
                "the value of '{key}' is longer than {MAX_TOTAL_VALUE_LENGTH} characters"
            )));
        }

        if value.len() > MAX_VALUE_LENGTH {
            let chunks = value.as_bytes().chunks(CHUNK_LENGTH).enumerate();
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

    /// stores the label hash of `value` under `key`; `hash_matches` compares it later
    pub fn add_hashed(&mut self, key: &str, value: &str) -> Result<()> {
        self.add(key, &label_hash(value))
    }

    pub fn hash_matches(&self, key: &str, value: &str) -> HashComparison {
        self.matches_hash(key, &label_hash(value))
    }

    /// `hash_matches` with the hash already made. A full sha256 from before the hash was cut
    /// also matches when it starts with the cut hash
    pub fn matches_hash(&self, key: &str, expected: &str) -> HashComparison {
        let actual = self.labels().get(key).cloned();
        let expected = expected.to_string();
        HashComparison {
            matches: actual.as_deref().is_some_and(|actual| {
                actual == expected || (actual.len() == 64 && actual.starts_with(&expected))
            }),
            expected,
            actual,
        }
    }
}

// `key_1` may be the second chunk of `key`: the key and the index; none for a key whose suffix is
// not a number
fn chunk_of(raw_key: &str) -> Option<(&str, usize)> {
    let (key, index) = raw_key.rsplit_once(DELIMITER)?;
    let index = index.parse::<usize>().ok()?;
    (!key.is_empty()).then_some((key, index))
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
        // one label, not chunked
        assert_eq!(
            labels.raw()["hash-test"],
            &HALLO_WELT_HASH[..LABEL_HASH_LEN]
        );

        assert!(labels.hash_matches("hash-test", "hallo welt").matches);
        let mismatch = labels.hash_matches("hash-test", "hallo welt1");
        assert!(!mismatch.matches);
        assert_eq!(
            mismatch.actual.as_deref(),
            Some(&HALLO_WELT_HASH[..LABEL_HASH_LEN])
        );
        assert_eq!(mismatch.expected, label_hash("hallo welt1"));
    }

    #[test]
    fn a_label_hash_fits_one_label_value() {
        assert_eq!(label_hash("hallo welt").len(), LABEL_HASH_LEN);
    }

    #[test]
    fn the_full_hash_of_a_label_from_before_still_matches() {
        let mut labels = HetznerLabels::new();
        labels.add("hash-test", HALLO_WELT_HASH).unwrap();

        assert!(labels.hash_matches("hash-test", "hallo welt").matches);
        assert!(!labels.hash_matches("hash-test", "hallo welt1").matches);
    }

    #[test]
    fn raw_labels_round_trip_through_a_new_instance() {
        let mut labels = HetznerLabels::new();
        labels.add_hashed("hash-test", "hallo welt").unwrap();
        labels.add_hashed("long-test", &"A".repeat(124)).unwrap();

        let imported = HetznerLabels::from_raw(labels.raw().clone());

        assert_eq!(
            imported.labels()["hash-test"],
            &HALLO_WELT_HASH[..LABEL_HASH_LEN]
        );
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
    fn a_value_of_63_characters_is_one_label_and_a_longer_one_is_chunked_and_read_back() {
        let mut labels = HetznerLabels::new();
        labels.add("label1", &"A".repeat(63)).unwrap();
        labels.add("label2", &"A".repeat(64)).unwrap();

        assert_eq!(labels.raw()["label1"], "A".repeat(63));
        assert_eq!(labels.raw()["label2_0"], "A".repeat(62));
        assert_eq!(labels.raw()["label2_1"], "AA");
        assert_eq!(labels.labels()["label2"], "A".repeat(64));
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

    #[test]
    fn a_key_with_a_numeric_suffix_is_refused_other_underscores_are_not() {
        let mut labels = HetznerLabels::new();
        assert!(matches!(
            labels.add("backup_1", "a"),
            Err(Error::InvalidLabel(_))
        ));
        assert!(matches!(
            labels.add("x_0", "a"),
            Err(Error::InvalidLabel(_))
        ));
        labels.add("my_label", "a").unwrap();
        labels.add("snake_case_key", "b").unwrap();
        assert_eq!(labels.labels()["my_label"], "a");
    }

    #[test]
    fn labels_of_the_user_with_a_numeric_suffix_are_not_folded() {
        let raw = |pairs: &[(&str, &str)]| {
            HetznerLabels::from_raw(
                pairs
                    .iter()
                    .map(|(key, value)| (key.to_string(), value.to_string()))
                    .collect(),
            )
        };
        // a lone one, and short ones that are no chunks
        let user = raw(&[("backup_1", "a"), ("snap_0", "x"), ("snap_1", "y")]);
        let labels = user.labels();
        assert_eq!(labels.get("backup_1").map(String::as_str), Some("a"));
        assert_eq!(labels.get("snap_0").map(String::as_str), Some("x"));
        assert!(!labels.contains_key("snap"));
        // a chunk sequence as add writes it is joined
        let long = "x".repeat(100);
        let mut written = HetznerLabels::new();
        written.add("sak.pelle.io/long", &long).unwrap();
        assert_eq!(written.labels()["sak.pelle.io/long"], long);
    }
}
