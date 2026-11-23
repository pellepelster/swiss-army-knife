//! the text of an error as the desktop apps show it

/// an error with all its causes, so a failed request tells why it failed (no name, no
/// connection, no certificate): `error sending request: … : invalid peer certificate`
pub fn with_causes(error: &dyn std::error::Error) -> String {
    let mut text = error.to_string();
    let mut source = error.source();
    while let Some(cause) = source {
        let cause_text = cause.to_string();
        if !text.contains(&cause_text) {
            text.push_str(": ");
            text.push_str(&cause_text);
        }
        source = cause.source();
    }
    text
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fmt;

    /// an error with a cause of its own, as a failed request has its io error
    #[derive(Debug)]
    struct Caused(&'static str, Option<Box<Caused>>);

    impl fmt::Display for Caused {
        fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
            f.write_str(self.0)
        }
    }

    impl std::error::Error for Caused {
        fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
            self.1.as_deref().map(|cause| cause as _)
        }
    }

    #[test]
    fn an_error_says_its_causes_each_once() {
        let error = Caused(
            "error sending request",
            Some(Box::new(Caused(
                "client error (Connect)",
                Some(Box::new(Caused("invalid peer certificate", None))),
            ))),
        );
        assert_eq!(
            with_causes(&error),
            "error sending request: client error (Connect): invalid peer certificate"
        );
        // a cause its error says already is not said twice
        let said = Caused(
            "refused: connection refused",
            Some(Box::new(Caused("connection refused", None))),
        );
        assert_eq!(with_causes(&said), "refused: connection refused");
        assert_eq!(with_causes(&Caused("alone", None)), "alone");
    }
}
