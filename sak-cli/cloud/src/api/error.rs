use thiserror::Error as ThisError;

/// an error of a lookup, diff, apply or destroy. The message names the resource and the cause,
/// so it can be shown as it is
#[derive(Debug, ThisError)]
pub enum Error {
    #[error("{0}")]
    Message(String),

    /// a lookup that the resource depends on found nothing
    #[error("{0} not found")]
    NotFound(String),

    /// no lookup provider is registered for the type of the lookup
    #[error("no lookup provider for {0}")]
    NoLookupProvider(String),

    #[error("invalid label: {0}")]
    InvalidLabel(String),

    /// a resource of the declared name exists, but its identity labels show that it belongs
    /// to someone else; it stays as it is
    #[error("{resource} exists and does not belong to this deployment: {reason}")]
    NotOwned { resource: String, reason: String },

    /// the `changes` need a destroy and a new create of the resource, but it is not
    /// recreatable, e.g. because it holds data or an address; nothing was changed
    #[error(
        "{resource} would have to be created anew ({changes}), which would remove it with what it holds"
    )]
    RecreateRefused { resource: String, changes: String },

    /// the api of a provider failed; the source is the error of the provider
    #[error("{0}")]
    Provider(#[source] Box<dyn std::error::Error + Send + Sync>),
}

impl Error {
    pub fn message(message: impl Into<String>) -> Self {
        Error::Message(message.into())
    }
}

pub type Result<T> = std::result::Result<T, Error>;
