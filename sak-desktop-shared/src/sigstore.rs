//! what the release workflow of SAK signs with cosign, keyless, and how an app checks it: the
//! identity of the workflow at a git ref (`signer_at_tag`, `signer_at_branch`), the check of a
//! sigstore bundle offline against the trusted root of sigstore built into the app (`verify`), and
//! the http client that asks github and follows a redirect only to the hosts its caller allows
//! (`github_client`). The update of an app checks the zip of a release with it (update.rs),
//! sakadmin the images it pulls (core/images.rs of sak-admin-cli)

use reqwest::Client;
use reqwest::redirect::Policy;
use semver::Version;
use std::time::Duration;

/// how the certificate names who signed, as `verify` compares it
pub use sigstore_verify::IdentityMatcher as Identity;

/// who signs the zips of a release and the images (`mise run desktop:sign`, `image:sign`):
/// the workflow of the repository, with a certificate of sigstore for its id token
pub const SIGNER_WORKFLOW: &str =
    "https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml";
pub const SIGNER_ISSUER: &str = "https://token.actions.githubusercontent.com";

/// the workflow at the tag of the release `version`, so what another release or a branch signed
/// fails
pub fn signer_at_tag(version: &Version) -> String {
    format!("{SIGNER_WORKFLOW}@refs/tags/v{version}")
}

/// the workflow at the head of `branch`
pub fn signer_at_branch(branch: &str) -> String {
    format!("{SIGNER_WORKFLOW}@refs/heads/{branch}")
}

/// what a bundle signs: the content itself, or its digest as `sha256:<hex>` (the subject of an
/// in-toto statement, as an image is signed)
#[derive(Clone, Copy, Debug)]
pub enum Signed<'a> {
    Content(&'a [u8]),
    Sha256(&'a str),
}

/// the bundle signs `signed` with a certificate of sigstore for `identity` of `issuer`, logged in
/// the transparency log; all against the trusted root of sigstore built into the app, so no host
/// is asked. The error is the one of sigstore, in english; the caller says what was not signed
pub fn verify(
    signed: Signed,
    bundle: &[u8],
    identity: Identity,
    issuer: &str,
) -> Result<(), String> {
    use sigstore_verify::VerificationPolicy;
    use sigstore_verify::trust_root::{SIGSTORE_PRODUCTION_TRUSTED_ROOT, TrustedRoot};
    use sigstore_verify::types::{Artifact, ArtifactDigest, Bundle, Sha256Hash};
    let artifact = match signed {
        Signed::Content(content) => Artifact::from(content),
        Signed::Sha256(digest) => {
            let hex = digest
                .strip_prefix("sha256:")
                .ok_or_else(|| format!("no sha-256: {digest}"))?;
            let hash = Sha256Hash::from_hex(hex).map_err(|error| error.to_string())?;
            Artifact::from_digest(ArtifactDigest::sha256(hash))
        }
    };
    let root = TrustedRoot::from_json(SIGSTORE_PRODUCTION_TRUSTED_ROOT)
        .map_err(|error| error.to_string())?;
    let bundle = std::str::from_utf8(bundle)
        .map_err(|error| error.to_string())
        .and_then(|json| Bundle::from_json(json).map_err(|error| error.to_string()))?;
    let policy = VerificationPolicy::new(identity, issuer);
    sigstore_verify::verify(artifact, &bundle, &policy, &root)
        .map(|_| ())
        .map_err(|error| error.to_string())
}

/// the client that asks github or its registry: https only (plain http for the tests, with
/// `https_only` false), and a redirect is followed only over https to a host `allowed` takes, five
/// at most. A redirect not followed is the answer, with its status
pub fn github_client(
    user_agent: &str,
    timeout: Duration,
    https_only: bool,
    allowed: impl Fn(&str) -> bool + Send + Sync + 'static,
) -> Result<Client, String> {
    Client::builder()
        .user_agent(user_agent)
        .timeout(timeout)
        .redirect(Policy::custom(move |attempt| {
            let follow =
                attempt.url().scheme() == "https" && attempt.url().host_str().is_some_and(&allowed);
            if follow && attempt.previous().len() < 5 {
                attempt.follow()
            } else {
                attempt.stop()
            }
        }))
        .https_only(https_only)
        .build()
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    #[test]
    fn the_signer_is_the_workflow_at_a_ref() {
        assert_eq!(
            signer_at_tag(&Version::new(1, 2, 3)),
            format!("{SIGNER_WORKFLOW}@refs/tags/v1.2.3")
        );
        assert_eq!(
            signer_at_branch("main"),
            format!("{SIGNER_WORKFLOW}@refs/heads/main")
        );
    }

    #[test]
    fn a_digest_that_is_no_sha_256_is_refused_before_the_bundle_is_read() {
        let identity = || Identity::Uri(signer_at_branch("main"));
        assert_eq!(
            verify(Signed::Sha256("md5:00"), b"{}", identity(), SIGNER_ISSUER),
            Err("no sha-256: md5:00".to_string())
        );
        assert!(
            verify(
                Signed::Content(b"x"),
                b"not json",
                identity(),
                SIGNER_ISSUER
            )
            .is_err()
        );
    }

    #[tokio::test]
    async fn a_redirect_to_a_host_that_is_not_allowed_is_the_answer() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/"))
            .respond_with(
                ResponseTemplate::new(302).insert_header("location", "https://example.org/"),
            )
            .mount(&server)
            .await;
        let client = github_client("test/1", Duration::from_secs(10), false, |_| false).unwrap();
        let response = client.get(server.uri()).send().await.unwrap();
        assert_eq!(response.status(), reqwest::StatusCode::FOUND);
    }
}
