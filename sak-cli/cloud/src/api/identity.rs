//! the identity of a resource: its name and the identity labels of its lookup. A resource
//! with the name but other identity labels belongs to someone else, every lookup refuses it
//! (`Error::NotOwned`) instead of treating it as the declared one, so no diff, apply or
//! destroy ever touches it

use crate::api::error::{Error, Result};
use crate::api::resource::{Labeled, Labels};

/// `found` when it carries every label of `identity` with the same value; `None` when nothing
/// has the name; `NotOwned` for a resource of the name that misses one or has another value.
/// An empty identity takes whatever has the name
pub fn check<R: Labeled>(found: Option<R>, identity: &Labels, log_text: &str) -> Result<Option<R>> {
    let Some(found) = found else {
        return Ok(None);
    };
    for (key, value) in identity {
        match found.labels().get(key) {
            Some(actual) if actual == value => {}
            Some(_) => {
                return Err(not_owned(
                    log_text,
                    format!("its label '{key}' has another value"),
                ));
            }
            None => return Err(not_owned(log_text, format!("it has no label '{key}'"))),
        }
    }
    Ok(Some(found))
}

fn not_owned(log_text: &str, reason: String) -> Error {
    Error::NotOwned {
        resource: log_text.to_string(),
        reason,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Found(Labels);

    impl Labeled for Found {
        fn labels(&self) -> &Labels {
            &self.0
        }
    }

    fn labels(pairs: &[(&str, &str)]) -> Labels {
        pairs
            .iter()
            .map(|(key, value)| (key.to_string(), value.to_string()))
            .collect()
    }

    #[test]
    fn a_resource_with_every_identity_label_is_the_declared_one() {
        let identity = labels(&[("name", "alpha"), ("hash", "abc")]);
        let found = Found(labels(&[
            ("name", "alpha"),
            ("hash", "abc"),
            ("env", "test"),
        ]));
        assert!(
            check(Some(found), &identity, "server 'alpha'")
                .unwrap()
                .is_some()
        );
        // nothing of the name: nothing to refuse
        assert!(
            check::<Found>(None, &identity, "server 'alpha'")
                .unwrap()
                .is_none()
        );
        // an empty identity takes whatever has the name
        assert!(
            check(
                Some(Found(Labels::new())),
                &Labels::new(),
                "zone 'pelle.io'"
            )
            .unwrap()
            .is_some()
        );
    }

    #[test]
    fn a_resource_with_a_missing_or_another_identity_label_is_not_owned() {
        let identity = labels(&[("name", "alpha"), ("hash", "abc")]);
        let missing = check(
            Some(Found(labels(&[("name", "alpha")]))),
            &identity,
            "server 'alpha'",
        );
        let Err(Error::NotOwned { resource, reason }) = missing else {
            panic!("not owned");
        };
        assert_eq!(resource, "server 'alpha'");
        assert_eq!(reason, "it has no label 'hash'");

        let other = check(
            Some(Found(labels(&[("name", "alpha"), ("hash", "xyz")]))),
            &identity,
            "server 'alpha'",
        );
        assert!(
            matches!(other, Err(Error::NotOwned { reason, .. }) if reason == "its label 'hash' has another value")
        );
        assert!(check(Some(Found(Labels::new())), &identity, "server 'alpha'").is_err());
    }
}
