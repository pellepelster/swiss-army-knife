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

    /// a resource of the declared name exists, but its identity labels say it belongs to
    /// someone else; it is left as it is
    #[error("{resource} exists and does not belong to this deployment: {reason}")]
    NotOwned { resource: String, reason: String },

    /// a resource would have to be destroyed and created again (`changes`), but it is not
    /// declared recreatable, e.g. because it holds data or an address; nothing was changed
    #[error(
        "{resource} would have to be created anew ({changes}), which would remove it with what it holds"
    )]
    RecreateRefused { resource: String, changes: String },

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
