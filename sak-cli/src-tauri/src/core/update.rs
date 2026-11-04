//! the update of sakctl to the newest release on github. Only on request, never on its own:
//! github sees the address of the machine (architecture/SYSTEM.md, "Privacy"), so the window and the
//! command line name it (`RECEIVER`) before they ask. The zip of the platform is checked against
//! the sha-256 of the same release and against its cosign signature, by the release workflow of
//! the tag of the release (`signer`), before its binary replaces the running one; the signature is
//! checked offline against the trusted root of sigstore built into sakctl, no host but github sees
//! the update. A build that is no release (`latest`) asks nothing, it has no version to compare with

use crate::core::VERSION;
use crate::core::config::with_causes;
use reqwest::Client;
use reqwest::redirect::{Attempt, Policy};
use rust_i18n::t;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::{Cursor, Read};
use std::path::Path;
use std::time::Duration;

/// who the check and the download of an update go to
pub const RECEIVER: &str = "github.com";

/// the releases of the repository; `releases/latest` is the newest one that is neither a draft nor
/// a pre-release
const API: &str = "https://api.github.com/repos/pellepelster/swiss-army-knife";

/// who signs the zips of a release (`mise run cli:release`): the release workflow of the
/// repository at the tag of the release, with a certificate of sigstore for its id token
const SIGNER_WORKFLOW: &str =
    "https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml";
const SIGNER_ISSUER: &str = "https://token.actions.githubusercontent.com";

/// the identity in the certificate of the signature of the zips of `version`: the workflow at its
/// tag, so a signed zip of another release or another branch is no zip of this one
fn signer(version: &Version) -> String {
    format!("{SIGNER_WORKFLOW}@refs/tags/v{version}")
}

/// the system and architecture in the names of the zips of a release,
/// `sak-cli-<system>-<architecture>-<version>.zip`: `linux-x86_64`, `windows-x86_64`, and
/// `macos-universal` for the one binary of both macOS architectures. A sakctl of an architecture
/// the release does not build finds no zip of its own and installs nothing
fn platform() -> String {
    match std::env::consts::OS {
        "macos" => "macos-universal".to_string(),
        system => format!("{system}-{}", std::env::consts::ARCH),
    }
}

/// the binaries in the zip: on windows the window (sakctl.exe, without a console) and the
/// console program for the command line (sakctl-cli.exe, the feature `console`); the update
/// replaces all of them
#[cfg(windows)]
const BINARIES: &[&str] = &["sakctl.exe", "sakctl-cli.exe"];
#[cfg(not(windows))]
const BINARIES: &[&str] = &["sakctl"];

/// the binary of the zip that runs: the one of the name of this program, the first one when it
/// was renamed
fn running() -> &'static str {
    let name = std::env::current_exe().ok().and_then(|path| {
        path.file_name()
            .map(|name| name.to_string_lossy().to_string())
    });
    BINARIES
        .iter()
        .find(|binary| name.as_deref() == Some(**binary))
        .unwrap_or(&BINARIES[0])
}

/// what a check found
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum Status {
    /// this build is no release, nothing was asked
    NoRelease,
    /// this version is the newest release, or newer
    UpToDate { current: String },
    /// a newer release exists
    Newer { current: String, newest: String },
}

/// whether this build is a release, which has a version to compare with
pub fn is_release() -> bool {
    Version::parse(VERSION).is_ok()
}

impl Status {
    /// the status in the language of the user, as the window and the command line show it
    pub fn message(&self) -> String {
        match self {
            Status::NoRelease => t!("common.update.no_release", version = VERSION).to_string(),
            Status::UpToDate { current } => {
                t!("common.update.up_to_date", version = current).to_string()
            }
            Status::Newer { current, newest } => {
                t!("common.update.newer", current = current, newest = newest).to_string()
            }
        }
    }

    /// the status after an install: the versions it updated from and to, else as `message`
    pub fn installed_message(&self) -> String {
        match self {
            Status::Newer { current, newest } => t!(
                "common.update.installed",
                current = current,
                newest = newest
            )
            .to_string(),
            status => status.message(),
        }
    }
}

/// the newest release and the addresses of its zip for this platform, of its sha-256 and of its
/// cosign signature (`<zip>.sigstore.json`)
#[derive(Debug, PartialEq, Eq)]
struct Release {
    version: Version,
    zip: Asset,
    checksum: Asset,
    bundle: Asset,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
struct Asset {
    name: String,
    browser_download_url: String,
}

/// what `GET /releases/latest` answers, the fields sakctl reads
#[derive(Deserialize)]
struct LatestRelease {
    tag_name: String,
    assets: Vec<Asset>,
}

/// compares this sakctl with the newest release on github
pub async fn check() -> Result<Status, String> {
    Updater::github().check().await
}

/// replaces this sakctl with the newest release on github when it is newer; the status tells
/// what was installed, the new version runs from the next start
pub async fn install() -> Result<Status, String> {
    Updater::github()
        .install(&|binary| self_replace::self_replace(binary))
        .await
}

/// checks the zip `name` of the release `version` against its cosign signature
type SignatureCheck =
    fn(zip: &[u8], bundle: &[u8], name: &str, version: &Version) -> Result<(), String>;

/// the update against an api, for the tests a mock server over plain http and a check of the
/// signature that needs no zip signed by the release workflow
struct Updater {
    api: String,
    current: &'static str,
    https_only: bool,
    signature: SignatureCheck,
}

impl Updater {
    fn github() -> Self {
        Updater {
            api: API.to_string(),
            current: VERSION,
            https_only: true,
            signature: verify_signature,
        }
    }

    /// the version of this build, none for one that is no release
    fn current(&self) -> Option<Version> {
        Version::parse(self.current).ok()
    }

    async fn check(&self) -> Result<Status, String> {
        let Some(current) = self.current() else {
            return Ok(Status::NoRelease);
        };
        Ok(status(&current, &self.latest().await?.version))
    }

    /// downloads the zip of the newest release when it is newer, checks it against its sha-256
    /// and its signature and hands its binary to `replace`. Nothing is replaced when anything
    /// fails before
    async fn install(
        &self,
        replace: &(dyn Fn(&Path) -> std::io::Result<()> + Sync),
    ) -> Result<Status, String> {
        let Some(current) = self.current() else {
            return Ok(Status::NoRelease);
        };
        let release = self.latest().await?;
        let status = status(&current, &release.version);
        if !matches!(status, Status::Newer { .. }) {
            return Ok(status);
        }
        let zip = self.download(&release.zip.browser_download_url).await?;
        let checksum = self
            .download(&release.checksum.browser_download_url)
            .await?;
        verify(&zip, &checksum, &release.zip.name)?;
        let bundle = self.download(&release.bundle.browser_download_url).await?;
        (self.signature)(&zip, &bundle, &release.zip.name, &release.version)?;
        let binaries = BINARIES
            .iter()
            .map(|binary| Ok((*binary, unzip(&zip, &release.zip.name, binary)?)))
            .collect::<Result<Vec<_>, String>>()?;
        // the others next to this one first, the running one last
        let running = running();
        for (name, binary) in binaries.iter().filter(|(name, _)| *name != running) {
            write_beside(name, binary)?;
        }
        if let Some((_, binary)) = binaries.iter().find(|(name, _)| *name == running) {
            write_and_replace(binary, replace)?;
        }
        Ok(status)
    }

    async fn latest(&self) -> Result<Release, String> {
        let url = format!("{}/releases/latest", self.api);
        let response = self.get(&url).await?;
        let latest: LatestRelease = response.json().await.map_err(|error| {
            t!(
                "common.update.unreadable",
                receiver = RECEIVER,
                error = with_causes(&error)
            )
            .to_string()
        })?;
        let version = Version::parse(latest.tag_name.trim_start_matches('v')).map_err(|error| {
            t!(
                "common.update.unreadable",
                receiver = RECEIVER,
                error = format!("{}: {error}", latest.tag_name)
            )
            .to_string()
        })?;
        let zip_name = format!("sak-cli-{}-{version}.zip", platform());
        let asset = |name: &str| {
            latest
                .assets
                .iter()
                .find(|asset| asset.name == name)
                .cloned()
                .ok_or_else(|| {
                    t!(
                        "common.update.no_asset",
                        version = version.to_string(),
                        name = name
                    )
                    .to_string()
                })
        };
        Ok(Release {
            zip: asset(&zip_name)?,
            checksum: asset(&format!("{zip_name}.sha256"))?,
            bundle: asset(&format!("{zip_name}.sigstore.json"))?,
            version,
        })
    }

    async fn download(&self, url: &str) -> Result<Vec<u8>, String> {
        let response = self.get(url).await?;
        let bytes = response.bytes().await.map_err(|error| {
            t!(
                "common.update.unreachable",
                url = url,
                error = with_causes(&error)
            )
            .to_string()
        })?;
        Ok(bytes.to_vec())
    }

    async fn get(&self, url: &str) -> Result<reqwest::Response, String> {
        if self.https_only {
            to_github(url)?;
        }
        let response = client(self.https_only)?
            .get(url)
            .header(reqwest::header::ACCEPT, "application/vnd.github+json")
            .send()
            .await
            .map_err(|error| {
                t!(
                    "common.update.unreachable",
                    url = url,
                    error = with_causes(&error)
                )
                .to_string()
            })?;
        match response.status() {
            status if status.is_success() => Ok(response),
            // also a redirect the client did not follow, to a host that is not github's
            status => Err(t!(
                "common.update.failed",
                url = url,
                status = status.to_string()
            )
            .to_string()),
        }
    }
}

/// newer only when the release is newer, an older one is no update
fn status(current: &Version, newest: &Version) -> Status {
    if newest > current {
        Status::Newer {
            current: current.to_string(),
            newest: newest.to_string(),
        }
    } else {
        Status::UpToDate {
            current: current.to_string(),
        }
    }
}

/// the client of github: https only (plain http for the tests), redirects only to github and its
/// storage, where github sends the download of an asset
fn client(https_only: bool) -> Result<Client, String> {
    Client::builder()
        .user_agent(concat!("sakctl/", env!("SAK_VERSION")))
        .timeout(Duration::from_secs(300))
        .redirect(Policy::custom(follow_to_github))
        .https_only(https_only)
        .build()
        .map_err(|error| error.to_string())
}

fn follow_to_github(attempt: Attempt) -> reqwest::redirect::Action {
    let allowed =
        attempt.url().scheme() == "https" && attempt.url().host_str().is_some_and(is_github);
    if allowed && attempt.previous().len() < 5 {
        attempt.follow()
    } else {
        attempt.stop()
    }
}

/// the first url as the redirects after it: https, to github or its storage
fn to_github(url: &str) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err(t!("common.update.not_https", url = url).to_string());
    }
    let host = reqwest::Url::parse(url)
        .ok()
        .and_then(|url| url.host_str().map(str::to_string));
    if !host.as_deref().is_some_and(is_github) {
        return Err(t!("common.update.not_github", url = url).to_string());
    }
    Ok(())
}

fn is_github(host: &str) -> bool {
    host == "github.com" || host == "api.github.com" || host.ends_with(".githubusercontent.com")
}

/// the zip against the first word of its `.sha256`, as `sha256sum` writes it
fn verify(zip: &[u8], checksum: &[u8], name: &str) -> Result<(), String> {
    let expected = String::from_utf8_lossy(checksum)
        .split_whitespace()
        .next()
        .unwrap_or_default()
        .to_ascii_lowercase();
    let actual = crate::core::encoding::hex(&Sha256::digest(zip));
    if expected == actual {
        Ok(())
    } else {
        Err(t!("common.update.checksum_mismatch", name = name).to_string())
    }
}

/// the zip `name` of the release `version` against its cosign signature: signed by the release
/// workflow at the tag of `version` (`signer`)
fn verify_signature(
    zip: &[u8],
    bundle: &[u8],
    name: &str,
    version: &Version,
) -> Result<(), String> {
    let identity = sigstore_verify::IdentityMatcher::Uri(signer(version));
    verify_signed_by(zip, bundle, name, version, identity, SIGNER_ISSUER)
}

/// the zip against the bundle of its signature: the certificate of sigstore for `identity` of
/// `issuer`, the signature of the zip with it and its entry in the transparency log, all against
/// the trusted root of sigstore built into sakctl
fn verify_signed_by(
    zip: &[u8],
    bundle: &[u8],
    name: &str,
    version: &Version,
    identity: sigstore_verify::IdentityMatcher,
    issuer: &str,
) -> Result<(), String> {
    use sigstore_verify::VerificationPolicy;
    use sigstore_verify::trust_root::{SIGSTORE_PRODUCTION_TRUSTED_ROOT, TrustedRoot};
    use sigstore_verify::types::Bundle;
    let invalid = |error: String| {
        t!(
            "common.update.signature_invalid",
            name = name,
            version = version.to_string(),
            error = error
        )
        .to_string()
    };
    let root = TrustedRoot::from_json(SIGSTORE_PRODUCTION_TRUSTED_ROOT)
        .map_err(|error| invalid(error.to_string()))?;
    let bundle = std::str::from_utf8(bundle)
        .map_err(|error| error.to_string())
        .and_then(|json| Bundle::from_json(json).map_err(|error| error.to_string()))
        .map_err(invalid)?;
    let policy = VerificationPolicy::new(identity, issuer);
    sigstore_verify::verify(zip, &bundle, &policy, &root)
        .map(|_| ())
        .map_err(|error| invalid(error.to_string()))
}

fn unzip(zip: &[u8], name: &str, binary: &str) -> Result<Vec<u8>, String> {
    let no_binary = |_| t!("common.update.no_binary", name = name, binary = binary).to_string();
    let mut archive = zip::ZipArchive::new(Cursor::new(zip)).map_err(no_binary)?;
    let mut file = archive.by_name(binary).map_err(no_binary)?;
    let mut binary = Vec::new();
    file.read_to_end(&mut binary)
        .map_err(|error| no_binary(zip::result::ZipError::Io(error)))?;
    Ok(binary)
}

/// the binary into a file of its own, executable, which `replace` puts in the place of the
/// running one; the file goes afterwards
fn write_and_replace(
    binary: &[u8],
    replace: &(dyn Fn(&Path) -> std::io::Result<()> + Sync),
) -> Result<(), String> {
    let path = std::env::temp_dir().join(format!(
        "sakctl-update-{}",
        crate::core::encoding::random_hex::<16>()?
    ));
    let result = (|| {
        std::fs::write(&path, binary)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755))?;
        }
        replace(&path)
    })();
    let _ = std::fs::remove_file(&path);
    result.map_err(|error| {
        let target = std::env::current_exe().unwrap_or_default();
        t!(
            "common.update.not_replaced",
            path = target.display().to_string(),
            error = error.to_string()
        )
        .to_string()
    })
}

/// a binary that does not run, next to the running one: written beside it and renamed over it,
/// so a failed write leaves the old one
fn write_beside(name: &str, binary: &[u8]) -> Result<(), String> {
    let target = std::env::current_exe()
        .map(|path| path.with_file_name(name))
        .unwrap_or_else(|_| std::path::PathBuf::from(name));
    let staged = target.with_extension("update");
    let result = std::fs::write(&staged, binary).and_then(|()| std::fs::rename(&staged, &target));
    if result.is_err() {
        let _ = std::fs::remove_file(&staged);
    }
    result.map_err(|error| {
        t!(
            "common.update.not_replaced",
            path = target.display().to_string(),
            error = error.to_string()
        )
        .to_string()
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_zip_of_a_release_names_the_system_and_the_architecture() {
        let platform = platform();
        if cfg!(target_os = "macos") {
            assert_eq!(platform, "macos-universal");
        } else {
            assert_eq!(
                platform,
                format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
            );
        }
        if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
            assert_eq!(platform, "linux-x86_64");
        }
    }
    use std::io::Write;
    use std::sync::Mutex;
    use wiremock::matchers::{method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn zip_of(name: &str, content: &[u8]) -> Vec<u8> {
        let mut zip = zip::ZipWriter::new(Cursor::new(Vec::new()));
        zip.start_file(name, zip::write::SimpleFileOptions::default())
            .unwrap();
        zip.write_all(content).unwrap();
        zip.finish().unwrap().into_inner()
    }

    fn sha256_of(bytes: &[u8]) -> String {
        crate::core::encoding::hex(&Sha256::digest(bytes))
    }

    /// a github with the release `tag`, its zip of this platform, the sha-256 `checksum` and a
    /// signature the check of the tests takes (`SIGNED`)
    async fn github(tag: &str, zip: Vec<u8>, checksum: &str) -> MockServer {
        github_signed(tag, zip, checksum, SIGNED).await
    }

    /// the bundle of a signature `fake_signature` takes
    const SIGNED: &[u8] = b"signed by the release workflow";

    fn fake_signature(_: &[u8], bundle: &[u8], name: &str, _: &Version) -> Result<(), String> {
        if bundle == SIGNED {
            Ok(())
        } else {
            Err(format!("{name} is not signed"))
        }
    }

    async fn github_signed(tag: &str, zip: Vec<u8>, checksum: &str, bundle: &[u8]) -> MockServer {
        let server = MockServer::start().await;
        let version = tag.trim_start_matches('v');
        let name = format!("sak-cli-{}-{version}.zip", platform());
        Mock::given(method("GET"))
            .and(path("/releases/latest"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "tag_name": tag,
                "assets": [
                    { "name": name, "browser_download_url": format!("{}/zip", server.uri()) },
                    { "name": format!("{name}.sha256"), "browser_download_url": format!("{}/sha256", server.uri()) },
                    { "name": format!("{name}.sigstore.json"), "browser_download_url": format!("{}/bundle", server.uri()) },
                ],
            })))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/zip"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(zip))
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/sha256"))
            .respond_with(
                ResponseTemplate::new(200).set_body_string(format!("{checksum}  {name}\n")),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/bundle"))
            .respond_with(ResponseTemplate::new(200).set_body_bytes(bundle.to_vec()))
            .mount(&server)
            .await;
        server
    }

    fn updater(server: &MockServer, current: &'static str) -> Updater {
        Updater {
            api: server.uri(),
            current,
            https_only: false,
            signature: fake_signature,
        }
    }

    /// the binaries handed to `replace`, never the binary of the test
    fn recording(replaced: &Mutex<Vec<Vec<u8>>>) -> impl Fn(&Path) -> std::io::Result<()> + '_ {
        move |binary| {
            replaced.lock().unwrap().push(std::fs::read(binary)?);
            Ok(())
        }
    }

    #[tokio::test]
    async fn a_newer_release_is_an_update_the_same_or_an_older_one_is_none() {
        let server = github("v0.2.0", Vec::new(), "").await;
        assert_eq!(
            updater(&server, "0.1.1").check().await,
            Ok(Status::Newer {
                current: "0.1.1".into(),
                newest: "0.2.0".into()
            })
        );
        for current in ["0.2.0", "0.10.0"] {
            assert_eq!(
                updater(&server, current).check().await,
                Ok(Status::UpToDate {
                    current: current.into()
                })
            );
        }
    }

    #[tokio::test]
    async fn a_build_that_is_no_release_asks_nothing() {
        let server = MockServer::start().await;
        assert_eq!(
            updater(&server, "latest").check().await,
            Ok(Status::NoRelease)
        );
        assert_eq!(
            updater(&server, "latest").install(&|_| Ok(())).await,
            Ok(Status::NoRelease)
        );
        assert!(server.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn the_update_replaces_the_binary_with_the_one_of_the_zip() {
        let zip = zip_of(BINARIES[0], b"the new sakctl");
        let checksum = sha256_of(&zip);
        let server = github("v0.2.0", zip, &checksum).await;
        let replaced = Mutex::new(Vec::new());
        let status = updater(&server, "0.1.1")
            .install(&recording(&replaced))
            .await;
        assert_eq!(
            status,
            Ok(Status::Newer {
                current: "0.1.1".into(),
                newest: "0.2.0".into()
            })
        );
        assert_eq!(*replaced.lock().unwrap(), vec![b"the new sakctl".to_vec()]);
    }

    #[tokio::test]
    async fn an_up_to_date_sakctl_downloads_nothing() {
        let server = github("v0.1.1", Vec::new(), "").await;
        let replaced = Mutex::new(Vec::new());
        let status = updater(&server, "0.1.1")
            .install(&recording(&replaced))
            .await;
        assert_eq!(
            status,
            Ok(Status::UpToDate {
                current: "0.1.1".into()
            })
        );
        assert!(replaced.lock().unwrap().is_empty());
        let requests = server.received_requests().await.unwrap();
        assert_eq!(requests.len(), 1);
    }

    #[tokio::test]
    async fn a_wrong_checksum_or_a_zip_without_the_binary_replaces_nothing() {
        let wrong = zip_of(BINARIES[0], b"the new sakctl");
        let without = zip_of("README", b"no binary");
        for (zip, checksum) in [
            (wrong.clone(), sha256_of(b"something else")),
            (without.clone(), sha256_of(&without)),
        ] {
            let server = github("v0.2.0", zip, &checksum).await;
            let replaced = Mutex::new(Vec::new());
            let result = updater(&server, "0.1.1")
                .install(&recording(&replaced))
                .await;
            assert!(result.is_err(), "{result:?}");
            assert!(replaced.lock().unwrap().is_empty());
        }
    }

    #[tokio::test]
    async fn a_zip_without_the_signature_of_the_release_workflow_replaces_nothing() {
        let zip = zip_of(BINARIES[0], b"the new sakctl");
        let checksum = sha256_of(&zip);
        let server = github_signed("v0.2.0", zip, &checksum, b"signed by someone else").await;
        let replaced = Mutex::new(Vec::new());
        let error = updater(&server, "0.1.1")
            .install(&recording(&replaced))
            .await
            .unwrap_err();
        assert!(error.contains("is not signed"), "{error}");
        assert!(replaced.lock().unwrap().is_empty());
    }

    #[tokio::test]
    async fn a_release_without_a_signature_downloads_nothing() {
        let server = MockServer::start().await;
        let name = format!("sak-cli-{}-0.2.0.zip", platform());
        Mock::given(method("GET"))
            .and(path("/releases/latest"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "tag_name": "v0.2.0",
                "assets": [
                    { "name": name, "browser_download_url": format!("{}/zip", server.uri()) },
                    { "name": format!("{name}.sha256"), "browser_download_url": format!("{}/sha256", server.uri()) },
                ],
            })))
            .mount(&server)
            .await;
        let error = updater(&server, "0.1.1")
            .install(&|_| Ok(()))
            .await
            .unwrap_err();
        assert!(error.contains(&format!("{name}.sigstore.json")), "{error}");
        assert_eq!(server.received_requests().await.unwrap().len(), 1);
    }

    /// a blob that cosign 3 signed, keyless (from the tests of sigstore-verify, Apache-2.0): its
    /// certificate is of an e-mail address that signed in with github
    const BLOB: &[u8] = include_bytes!("testdata/cosign-v3-blob.txt");
    const BLOB_BUNDLE: &[u8] = include_bytes!("testdata/cosign-v3-blob.sigstore.json");
    const BLOB_SIGNER: &str = "w.vollprecht@gmail.com";
    const BLOB_ISSUER: &str = "https://github.com/login/oauth";

    #[test]
    fn a_signature_is_verified_for_its_signer_against_the_trusted_root_of_sigstore() {
        use sigstore_verify::IdentityMatcher;
        let version = Version::new(0, 2, 0);
        let check = |blob: &[u8], bundle: &[u8], signer: &str, issuer: &str| {
            verify_signed_by(
                blob,
                bundle,
                "blob",
                &version,
                IdentityMatcher::Email(signer.to_string()),
                issuer,
            )
        };
        assert_eq!(check(BLOB, BLOB_BUNDLE, BLOB_SIGNER, BLOB_ISSUER), Ok(()));
        // another signer, another issuer, another content, no bundle
        assert!(check(BLOB, BLOB_BUNDLE, "someone@example.org", BLOB_ISSUER).is_err());
        assert!(check(BLOB, BLOB_BUNDLE, BLOB_SIGNER, SIGNER_ISSUER).is_err());
        assert!(check(b"another blob", BLOB_BUNDLE, BLOB_SIGNER, BLOB_ISSUER).is_err());
        let error = check(BLOB, b"not json", BLOB_SIGNER, BLOB_ISSUER).unwrap_err();
        assert!(
            error.starts_with("blob is not signed by the release workflow of 0.2.0"),
            "{error}"
        );
        // valid, but not by the release workflow
        assert!(verify_signature(BLOB, BLOB_BUNDLE, "blob", &version).is_err());
    }

    #[test]
    fn the_zips_of_a_release_are_signed_by_the_workflow_at_its_tag() {
        assert_eq!(
            signer(&Version::new(1, 2, 3)),
            "https://github.com/pellepelster/swiss-army-knife/.github/workflows/swiss-army-knife.yml@refs/tags/v1.2.3"
        );
    }

    #[tokio::test]
    async fn a_release_without_the_zip_of_the_platform_says_so() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/releases/latest"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "tag_name": "v0.2.0",
                "assets": [],
            })))
            .mount(&server)
            .await;
        let error = updater(&server, "0.1.1").check().await.unwrap_err();
        assert!(
            error.contains(&format!("sak-cli-{}-0.2.0.zip", platform())),
            "{error}"
        );
    }

    #[tokio::test]
    async fn a_redirect_away_from_github_is_not_followed() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/releases/latest"))
            .respond_with(
                ResponseTemplate::new(302).insert_header("location", "https://example.org/"),
            )
            .mount(&server)
            .await;
        let error = updater(&server, "0.1.1").check().await.unwrap_err();
        assert!(error.contains("302"), "{error}");
        assert!(is_github("release-assets.githubusercontent.com"));
        assert!(!is_github("githubusercontent.com.example.org"));
    }

    #[tokio::test]
    async fn github_is_asked_over_https_only() {
        let updater = Updater {
            api: "http://api.github.com/repos/x".into(),
            current: "0.1.1",
            https_only: true,
            signature: verify_signature,
        };
        assert!(updater.check().await.is_err());
    }

    #[tokio::test]
    async fn a_download_is_asked_of_github_only() {
        let updater = Updater {
            api: API.into(),
            current: "0.1.1",
            https_only: true,
            signature: verify_signature,
        };
        let error = updater
            .download("https://example.org/sak-cli.zip")
            .await
            .unwrap_err();
        assert_eq!(
            error,
            "https://example.org/sak-cli.zip is no address of github, sakctl downloads from github only."
        );
        assert_eq!(
            to_github("https://github.com.example.org/x"),
            Err("https://github.com.example.org/x is no address of github, sakctl downloads from github only.".into())
        );
        assert_eq!(to_github("https://objects.githubusercontent.com/x"), Ok(()));
        assert_eq!(to_github(API), Ok(()));
    }
}
