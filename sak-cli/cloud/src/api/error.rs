use thiserror::Error as ThisError;

/// every error a lookup, diff, apply or destroy can end with; the message says what and
/// which resource, so it can be shown as is
#[derive(Debug, ThisError)]
pub enum Error {
    #[error("{0}")]
    Message(String),

    /// a lookup the resource depends on found nothing
    #[error("{0} not found")]
    NotFound(String),

    /// a lookup of a type no provider was registered for
    #[error("no lookup provider for {0}")]
    NoLookupProvider(String),

    #[error("invalid label: {0}")]
    InvalidLabel(String),

    /// the api of a provider failed, the error is the provider's own
    #[error("{0}")]
    Provider(#[source] Box<dyn std::error::Error + Send + Sync>),
}

impl Error {
    pub fn message(message: impl Into<String>) -> Self {
        Error::Message(message.into())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
