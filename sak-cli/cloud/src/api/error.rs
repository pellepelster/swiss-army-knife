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

    /// a registry without owner labels destroys nothing (`Registry::owned_by`)
    #[error("nothing is destroyed without the owner labels of the resources (owned_by)")]
    NoOwner,

    /// a resource of this reference is in the registry already
    #[error("{0} is declared twice")]
    DeclaredTwice(String),

    /// no provisioner is registered for the type of the resource
    #[error("no provisioner registered for {0}")]
    NoProvisioner(String),

    /// the dependencies of the resource lead back to it, directly or through others
    #[error("{0} is in a cycle of dependencies")]
    Cycle(String),

    /// the resource does not exist under its name, but one with its content does under another
    #[error("{resource}: {message}")]
    Duplicate { resource: String, message: String },

    /// what a provisioner runs on a resource failed, e.g. a playbook on a server: at `task` when
    /// it tells it; the message says what failed in its own words
    #[error("{message}")]
    Provisioning {
        task: Option<String>,
        message: String,
    },

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
