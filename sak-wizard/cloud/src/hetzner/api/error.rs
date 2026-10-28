use serde::Deserialize;
use thiserror::Error;

/// the error codes of the hetzner cloud api, `Unknown` for one this crate has not seen
#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum ErrorCode {
    Forbidden,
    Unauthorized,
    InvalidInput,
    MethodNotAllowed,
    JsonError,
    Locked,
    NotFound,
    RateLimitExceeded,
    TargetAlreadyDefined,
    ResourceInUse,
    ServerNotStopped,
    ResourceLimitExceeded,
    ResourceUnavailable,
    ServerError,
    ServiceError,
    UniquenessError,
    Protected,
    Maintenance,
    Conflict,
    UnsupportedError,
    TokenReadonly,
    Unavailable,
    ServerAlreadyAttached,
    FirewallAlreadyApplied,
    IpNotAvailable,
    #[serde(other)]
    Unknown,
}

/// the body of an error response: `{ "error": { "code", "message" } }`
#[derive(Debug, Deserialize)]
pub(crate) struct ErrorResponse {
    pub error: ErrorBody,
}

#[derive(Debug, Deserialize)]
pub(crate) struct ErrorBody {
    pub code: ErrorCode,
    pub message: String,
}

#[derive(Debug, Error)]
pub enum ApiError {
    /// the request never got an answer: no connection, timeout, a body that is no json
    #[error("request {path} failed: {source}")]
    Request {
        path: String,
        #[source]
        source: reqwest::Error,
    },

    /// the api refused the request with one of its error codes
    #[error("{path}: {message} ({code:?})")]
    Api {
        path: String,
        code: ErrorCode,
        message: String,
    },

    /// a response that is neither a success nor an api error
    #[error("unexpected HTTP {status} from {path}: {body}")]
    Unexpected {
        path: String,
        status: u16,
        body: String,
    },

    /// an action the api ran for a request ended in an error
    #[error("action '{command}' ({id}) failed: {message}")]
    ActionFailed {
        id: u64,
        command: String,
        message: String,
    },

    #[error("action '{command}' ({id}) did not finish in time")]
    ActionTimeout { id: u64, command: String },
}

impl ApiError {
    /// the code of an api error, to react to one like [ErrorCode::UniquenessError]
    pub fn code(&self) -> Option<ErrorCode> {
        match self {
            ApiError::Api { code, .. } => Some(*code),
            _ => None,
        }
    }
}
