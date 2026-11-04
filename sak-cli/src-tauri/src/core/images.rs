//! the images of SAK on ghcr.io and their cosign signatures: the tools and the canary sakctl runs,
//! the backend and the ingress the server runs. Each is pulled by the digest of its tag, after the
//! signature of that digest is verified: signed by the workflow of the repository at the tag of the
//! release (`<version>`, `<version>-rc`) or at main (`latest`), with a certificate of sigstore for
//! its id token (`mise run image:sign`). The signature is a bundle of sigstore next to the digest
//! in the registry (cosign 3: an index tagged `sha256-<hex>`, ghcr.io has no referrers api), checked
//! offline against the trusted root of sigstore built into sakctl, as the update (`update.rs`). A
//! tag no workflow pushes, set by hand with SAK_DOCKER_IMAGE_TAG (e.g. images a developer pushed),
//! has no signer: it is pulled by its tag, unchecked

use crate::core::config::with_causes;
use reqwest::Client;
use reqwest::redirect::{Attempt, Policy};
use rust_i18n::t;
use semver::Version;
use serde::Deserialize;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Duration;

/// the registry and the owner of the images
const REGISTRY: &str = "ghcr.io";
const OWNER: &str = "pellepelster";

/// who signs the images: the workflow of the repository at a ref, with a certificate of sigstore
const SIGNER_WORKFLOW: &str =
    "https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml";
const SIGNER_ISSUER: &str = "https://token.actions.githubusercontent.com";

/// the media type of a bundle of sigstore, the artifact type of the manifests of the signatures
const BUNDLE: &str = "application/vnd.dev.sigstore.bundle.v0.3+json";
const MANIFESTS: &str = "application/vnd.oci.image.index.v1+json, \
     application/vnd.docker.distribution.manifest.list.v2+json, \
     application/vnd.oci.image.manifest.v1+json, \
     application/vnd.docker.distribution.manifest.v2+json";

/// the tools of sakctl (sak-cli/Dockerfile)
pub const TOOLS: &str = "sak-cli-tools";
/// the small image that proves a container runtime (sak-cli/canary)
pub const CANARY: &str = "sak-cli-docker-canary";
pub const BACKEND: &str = "sak-backend";
pub const INGRESS: &str = "sak-ingress";

/// `ghcr.io/pellepelster/<name>:<tag>`, the image by its tag
pub fn tagged(name: &str, tag: &str) -> String {
    format!("{REGISTRY}/{OWNER}/{name}:{tag}")
}

/// the identity that signs the images of `tag`: the workflow at the tag of its version for
/// `<version>` and `<version>-rc`, at main for `latest`; none for a tag no workflow pushes
fn signer(tag: &str) -> Option<String> {
    if tag == "latest" {
        return Some(format!("{SIGNER_WORKFLOW}@refs/heads/main"));
    }
    let version = Version::parse(tag.strip_suffix("-rc").unwrap_or(tag)).ok()?;
    // a version of its own with -rc, e.g. 1.2.3-rc.1, is no tag of the workflow
    version
        .pre
        .is_empty()
        .then(|| format!("{SIGNER_WORKFLOW}@refs/tags/v{version}"))
}

/// the image `name` of the tag of sakctl (`core::image_tag`) as it is pulled and run: by the
/// digest of its tag after its signature is verified, `ghcr.io/pellepelster/<name>@sha256:…`; by
/// its tag for a tag without a signer. Once per image and run of sakctl
pub fn reference(name: &str) -> Result<String, String> {
    static VERIFIED: Mutex<Option<HashMap<String, String>>> = Mutex::new(None);
    let tag = crate::core::image_tag();
    let Some(signer) = signer(tag) else {
        return Ok(tagged(name, tag));
    };
    if let Some(reference) = VERIFIED
        .lock()
        .ok()
        .and_then(|verified| verified.as_ref()?.get(name).cloned())
    {
        return Ok(reference);
    }
    let registry = Registry::ghcr();
    let reference = crate::core::runtime::block_on(registry.verified(name, tag, &signer))?;
    if let Ok(mut verified) = VERIFIED.lock() {
        verified
            .get_or_insert_with(HashMap::new)
            .insert(name.to_string(), reference.clone());
    }
    Ok(reference)
}

/// the registry, for the tests a mock server over plain http
struct Registry {
    url: String,
    host: String,
    https_only: bool,
}

#[derive(Deserialize)]
struct Token {
    token: String,
}

#[derive(Deserialize)]
struct Index {
    #[serde(default)]
    manifests: Vec<Descriptor>,
}

#[derive(Deserialize)]
struct Manifest {
    #[serde(default)]
    layers: Vec<Descriptor>,
}

#[derive(Deserialize)]
struct Descriptor {
    #[serde(rename = "mediaType", default)]
    media_type: String,
    #[serde(rename = "artifactType", default)]
    artifact_type: String,
    digest: String,
}

impl Registry {
    fn ghcr() -> Self {
        Registry {
            url: format!("https://{REGISTRY}"),
            host: REGISTRY.to_string(),
            https_only: true,
        }
    }

    /// the digest of `<name>:<tag>` and a signature of it by `signer`, the image by its digest
    async fn verified(&self, name: &str, tag: &str, signer: &str) -> Result<String, String> {
        let image = tagged(name, tag);
        let repository = format!("{OWNER}/{name}");
        let client = client(self.https_only)?;
        let token = self.token(&client, &repository).await?;
        let digest = self.digest(&client, &token, &repository, tag).await?;
        let bundles = self.bundles(&client, &token, &repository, &digest).await?;
        if bundles.is_empty() {
            return Err(t!("common.images.not_signed", image = image).to_string());
        }
        // one signature by the signer is enough, others may be there
        let mut errors = Vec::new();
        for bundle in &bundles {
            match verify_signed_by(&digest, bundle, signer, SIGNER_ISSUER) {
                Ok(()) => return Ok(format!("{REGISTRY}/{repository}@{digest}")),
                Err(error) => errors.push(error),
            }
        }
        Err(t!(
            "common.images.signature_invalid",
            image = image,
            signer = signer,
            error = errors.join("; ")
        )
        .to_string())
    }

    /// an anonymous token to pull the repository
    async fn token(&self, client: &Client, repository: &str) -> Result<String, String> {
        let url = format!(
            "{}/token?scope=repository:{repository}:pull&service={}",
            self.url, self.host
        );
        let token: Token = read_json(get(client, &url, None, None).await?, &url).await?;
        Ok(token.token)
    }

    /// the digest of the manifest of the tag, the index of all its platforms
    async fn digest(
        &self,
        client: &Client,
        token: &str,
        repository: &str,
        tag: &str,
    ) -> Result<String, String> {
        let url = format!("{}/v2/{repository}/manifests/{tag}", self.url);
        let response = client
            .head(&url)
            .bearer_auth(token)
            .header(reqwest::header::ACCEPT, MANIFESTS)
            .send()
            .await
            .map_err(|error| unreachable(&url, &error))?;
        let response = answered(response, &url)?;
        let digest = response
            .headers()
            .get("docker-content-digest")
            .and_then(|digest| digest.to_str().ok())
            .unwrap_or_default()
            .to_string();
        if !is_sha256(&digest) {
            return Err(unreadable(&url, &format!("digest {digest:?}")));
        }
        Ok(digest)
    }

    /// the bundles of the signatures of `digest`: the manifests of the index `sha256-<hex>` with
    /// the artifact type of a bundle, each with the bundle as its layer; none without the index
    async fn bundles(
        &self,
        client: &Client,
        token: &str,
        repository: &str,
        digest: &str,
    ) -> Result<Vec<Vec<u8>>, String> {
        let manifests =
            |reference: &str| format!("{}/v2/{repository}/manifests/{reference}", self.url);
        let url = manifests(&digest.replace(':', "-"));
        let response = get(client, &url, Some(token), Some(MANIFESTS)).await;
        let index: Index = match response {
            Ok(response) => read_json(response, &url).await?,
            Err(Missing(_)) => return Ok(Vec::new()),
            Err(Failed(error)) => return Err(error),
        };
        let mut bundles = Vec::new();
        for signature in index
            .manifests
            .iter()
            .filter(|manifest| manifest.artifact_type == BUNDLE)
        {
            let url = manifests(&signature.digest);
            let manifest: Manifest =
                read_json(get(client, &url, Some(token), Some(MANIFESTS)).await?, &url).await?;
            for layer in manifest
                .layers
                .iter()
                .filter(|layer| layer.media_type == BUNDLE)
            {
                let url = format!("{}/v2/{repository}/blobs/{}", self.url, layer.digest);
                let bytes = get(client, &url, Some(token), None)
                    .await?
                    .bytes()
                    .await
                    .map_err(|error| unreachable(&url, &error))?;
                // the registry answers the blob of the digest asked for, or it is no bundle of it
                if format!(
                    "sha256:{}",
                    crate::core::encoding::hex(&Sha256::digest(&bytes))
                ) == layer.digest
                {
                    bundles.push(bytes.to_vec());
                }
            }
        }
        Ok(bundles)
    }
}

/// why a request answered nothing: the registry has nothing at the url, or it failed
enum Fetch {
    Missing(String),
    Failed(String),
}
use Fetch::{Failed, Missing};

impl From<Fetch> for String {
    fn from(fetch: Fetch) -> Self {
        match fetch {
            Missing(url) => t!(
                "common.images.failed",
                url = url,
                status = reqwest::StatusCode::NOT_FOUND.to_string()
            )
            .to_string(),
            Failed(error) => error,
        }
    }
}

async fn get(
    client: &Client,
    url: &str,
    token: Option<&str>,
    accept: Option<&str>,
) -> Result<reqwest::Response, Fetch> {
    let mut request = client.get(url);
    if let Some(token) = token {
        request = request.bearer_auth(token);
    }
    if let Some(accept) = accept {
        request = request.header(reqwest::header::ACCEPT, accept);
    }
    let response = request
        .send()
        .await
        .map_err(|error| Failed(unreachable(url, &error)))?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Err(Missing(url.to_string()));
    }
    answered(response, url).map_err(Failed)
}

fn answered(response: reqwest::Response, url: &str) -> Result<reqwest::Response, String> {
    if response.status().is_success() {
        Ok(response)
    } else {
        Err(t!(
            "common.images.failed",
            url = url,
            status = response.status().to_string()
        )
        .to_string())
    }
}

async fn read_json<T: for<'de> Deserialize<'de>>(
    response: reqwest::Response,
    url: &str,
) -> Result<T, String> {
    response
        .json()
        .await
        .map_err(|error| unreadable(url, &with_causes(&error)))
}

fn unreachable(url: &str, error: &reqwest::Error) -> String {
    t!(
        "common.images.unreachable",
        url = url,
        error = with_causes(error)
    )
    .to_string()
}

fn unreadable(url: &str, error: &str) -> String {
    t!("common.images.unreadable", url = url, error = error).to_string()
}

fn is_sha256(digest: &str) -> bool {
    digest
        .strip_prefix("sha256:")
        .is_some_and(|hex| hex.len() == 64 && hex.bytes().all(|byte| byte.is_ascii_hexdigit()))
}

/// https only (plain http for the tests), redirects only to ghcr.io and the storage of github,
/// where ghcr.io sends a blob
fn client(https_only: bool) -> Result<Client, String> {
    Client::builder()
        .user_agent(concat!("sakctl/", env!("SAK_VERSION")))
        .timeout(Duration::from_secs(60))
        .redirect(Policy::custom(follow_to_github))
        .https_only(https_only)
        .build()
        .map_err(|error| error.to_string())
}

fn follow_to_github(attempt: Attempt) -> reqwest::redirect::Action {
    let allowed = attempt.url().scheme() == "https"
        && attempt
            .url()
            .host_str()
            .is_some_and(|host| host == REGISTRY || host.ends_with(".githubusercontent.com"));
    if allowed && attempt.previous().len() < 5 {
        attempt.follow()
    } else {
        attempt.stop()
    }
}

/// the bundle signs the image `digest` (an in-toto statement whose subject is the digest) with a
/// certificate of sigstore for `identity` of `issuer`, logged in the transparency log; all against
/// the trusted root of sigstore built into sakctl
fn verify_signed_by(
    digest: &str,
    bundle: &[u8],
    identity: &str,
    issuer: &str,
) -> Result<(), String> {
    use sigstore_verify::trust_root::{SIGSTORE_PRODUCTION_TRUSTED_ROOT, TrustedRoot};
    use sigstore_verify::types::{Artifact, ArtifactDigest, Bundle, Sha256Hash};
    use sigstore_verify::{IdentityMatcher, VerificationPolicy};
    let hex = digest
        .strip_prefix("sha256:")
        .ok_or_else(|| format!("no sha-256: {digest}"))?;
    let hash = Sha256Hash::from_hex(hex).map_err(|error| error.to_string())?;
    let root = TrustedRoot::from_json(SIGSTORE_PRODUCTION_TRUSTED_ROOT)
        .map_err(|error| error.to_string())?;
    let bundle = std::str::from_utf8(bundle)
        .map_err(|error| error.to_string())
        .and_then(|json| Bundle::from_json(json).map_err(|error| error.to_string()))?;
    let policy = VerificationPolicy::new(IdentityMatcher::Uri(identity.to_string()), issuer);
    sigstore_verify::verify(
        Artifact::from_digest(ArtifactDigest::sha256(hash)),
        &bundle,
        &policy,
        &root,
    )
    .map(|_| ())
    .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    /// an attestation that a workflow on github signed keyless, of the sha-256 of a package (from
    /// the tests of sigstore-verify, Apache-2.0): signed as an image is, a statement of in-toto
    /// whose subject is the digest
    const BUNDLE_JSON: &[u8] = include_bytes!("testdata/conda-attestation.sigstore.json");
    const SIGNED: &str = "sha256:54303491a8418fbed24344b513546182c29b43bf282ceb433af65e2299f9271f";
    const SIGNER: &str = "https://github.com/prefix-dev/sigstore-example/.github/workflows/action.yaml@refs/heads/main";

    #[test]
    fn the_images_of_a_tag_are_signed_by_the_workflow_at_its_ref() {
        let workflow = "https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml";
        assert_eq!(
            signer("latest"),
            Some(format!("{workflow}@refs/heads/main"))
        );
        assert_eq!(
            signer("1.2.3"),
            Some(format!("{workflow}@refs/tags/v1.2.3"))
        );
        assert_eq!(
            signer("1.2.3-rc"),
            Some(format!("{workflow}@refs/tags/v1.2.3"))
        );
        // no workflow pushes these
        for tag in ["latest-rc", "1.2.3-rc.1", "0f3c9a1b2c3d", "v1.2.3", ""] {
            assert_eq!(signer(tag), None, "{tag}");
        }
        assert_eq!(
            tagged(TOOLS, "1.2.3"),
            "ghcr.io/pellepelster/sak-cli-tools:1.2.3"
        );
    }

    #[test]
    fn a_signature_is_verified_for_its_digest_and_its_signer() {
        assert_eq!(
            verify_signed_by(SIGNED, BUNDLE_JSON, SIGNER, SIGNER_ISSUER),
            Ok(())
        );
        // another digest, another signer, another issuer, no bundle
        let other = format!("sha256:{}", "0".repeat(64));
        assert!(verify_signed_by(&other, BUNDLE_JSON, SIGNER, SIGNER_ISSUER).is_err());
        let main = format!("{SIGNER_WORKFLOW}@refs/heads/main");
        assert!(verify_signed_by(SIGNED, BUNDLE_JSON, &main, SIGNER_ISSUER).is_err());
        assert!(
            verify_signed_by(SIGNED, BUNDLE_JSON, SIGNER, "https://accounts.google.com").is_err()
        );
        assert!(verify_signed_by(SIGNED, b"{}", SIGNER, SIGNER_ISSUER).is_err());
    }

    fn sha256(bytes: &[u8]) -> String {
        format!(
            "sha256:{}",
            crate::core::encoding::hex(&Sha256::digest(bytes))
        )
    }

    /// a registry with the image `<name>:<tag>` of the digest `SIGNED` and `bundle` as its
    /// signature, or no signature at all
    async fn registry(bundle: Option<&[u8]>) -> MockServer {
        let server = MockServer::start().await;
        let repository = "/v2/pellepelster/sak-backend";
        Mock::given(method("GET"))
            .and(path("/token"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(serde_json::json!({"token": "anonymous"})),
            )
            .mount(&server)
            .await;
        Mock::given(method("HEAD"))
            .and(path(format!("{repository}/manifests/1.2.3")))
            .respond_with(ResponseTemplate::new(200).insert_header("docker-content-digest", SIGNED))
            .mount(&server)
            .await;
        if let Some(bundle) = bundle {
            let blob = sha256(bundle);
            let manifest = serde_json::json!({
                "schemaVersion": 2,
                "mediaType": "application/vnd.oci.image.manifest.v1+json",
                "artifactType": BUNDLE,
                "layers": [{ "mediaType": BUNDLE, "digest": blob, "size": bundle.len() }],
            });
            let manifest_digest = sha256(manifest.to_string().as_bytes());
            Mock::given(method("GET"))
                .and(path(format!("{repository}/manifests/{}", SIGNED.replace(':', "-"))))
                .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                    "schemaVersion": 2,
                    "mediaType": "application/vnd.oci.image.index.v1+json",
                    "manifests": [
                        { "mediaType": "application/vnd.oci.image.manifest.v1+json", "artifactType": "application/vnd.example.other", "digest": format!("sha256:{}", "1".repeat(64)) },
                        { "mediaType": "application/vnd.oci.image.manifest.v1+json", "artifactType": BUNDLE, "digest": manifest_digest },
                    ],
                })))
                .mount(&server)
                .await;
            Mock::given(method("GET"))
                .and(path(format!("{repository}/manifests/{manifest_digest}")))
                .respond_with(ResponseTemplate::new(200).set_body_json(manifest))
                .mount(&server)
                .await;
            Mock::given(method("GET"))
                .and(path(format!("{repository}/blobs/{blob}")))
                .respond_with(ResponseTemplate::new(200).set_body_bytes(bundle.to_vec()))
                .mount(&server)
                .await;
        }
        server
    }

    fn mock(server: &MockServer) -> Registry {
        Registry {
            url: server.uri(),
            host: "ghcr.io".to_string(),
            https_only: false,
        }
    }

    #[tokio::test]
    async fn a_signed_image_is_pulled_by_its_digest() {
        let server = registry(Some(BUNDLE_JSON)).await;
        assert_eq!(
            mock(&server).verified(BACKEND, "1.2.3", SIGNER).await,
            Ok(format!("ghcr.io/pellepelster/sak-backend@{SIGNED}"))
        );
    }

    #[tokio::test]
    async fn an_image_without_the_signature_of_its_signer_is_refused() {
        let main = format!("{SIGNER_WORKFLOW}@refs/heads/main");
        let server = registry(Some(BUNDLE_JSON)).await;
        let error = mock(&server)
            .verified(BACKEND, "1.2.3", &main)
            .await
            .unwrap_err();
        assert!(
            error.starts_with("ghcr.io/pellepelster/sak-backend:1.2.3 is not signed by "),
            "{error}"
        );
        let unsigned = registry(None).await;
        let error = mock(&unsigned)
            .verified(BACKEND, "1.2.3", SIGNER)
            .await
            .unwrap_err();
        assert_eq!(
            error,
            "ghcr.io/pellepelster/sak-backend:1.2.3 has no signature, sakctl does not pull it."
        );
        let broken = registry(Some(b"not a bundle")).await;
        assert!(
            mock(&broken)
                .verified(BACKEND, "1.2.3", SIGNER)
                .await
                .is_err()
        );
    }

    #[tokio::test]
    async fn a_tag_the_registry_does_not_know_says_so() {
        let server = registry(None).await;
        let error = mock(&server)
            .verified(BACKEND, "9.9.9", SIGNER)
            .await
            .unwrap_err();
        assert!(error.contains("404"), "{error}");
    }

    #[test]
    fn a_digest_is_a_sha_256() {
        assert!(is_sha256(SIGNED));
        assert!(!is_sha256("sha256:abc"));
        assert!(!is_sha256(&format!("sha512:{}", "0".repeat(64))));
    }
}
