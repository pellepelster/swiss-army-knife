//! the update of a desktop app (sakadmin, sakbackup: its `App`) to the newest release on github.
//! Only on request: github sees the address of the machine (architecture/SYSTEM.md, "Privacy"), so
//! the ui names it (`RECEIVER`) before it asks. The zip is checked against the sha-256 of the release
//! and its cosign signature by the release workflow at the tag (`sigstore::signer_at_tag`), offline
//! against the trusted root of sigstore built into the app, so no host but github sees the update. A build that
//! is no release (`latest`) asks nothing. The command line of an app runs `run`, its window the
//! commands `version`, `check_update` and `install_update` (ui/update.js)

use crate::encoding::{hex, random_hex};
use crate::errors::with_causes;
use crate::sigstore::{self, Identity, SIGNER_ISSUER, Signed};
use reqwest::Client;
use rust_i18n::t;
use semver::Version;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::sync::OnceLock;
use std::time::Duration;

pub const RECEIVER: &str = "github.com";

/// how the app was installed, which decides what its update replaces: the binary of the zip, an
/// AppImage of linux (the file `$APPIMAGE`, the binary runs from its read-only mount), or a deb or
/// an rpm (the binary in `/usr/bin`, the package manager's: the update only says that there is one)
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Install {
    Binary,
    AppImage(PathBuf),
    Package,
}

impl Install {
    /// the install of the running app
    pub fn this() -> Install {
        Install::of(
            std::env::var_os("APPIMAGE").map(PathBuf::from),
            std::env::current_exe().ok(),
        )
    }

    /// by `$APPIMAGE`, which the runtime of an AppImage sets, and by where the binary is; linux only
    fn of(appimage: Option<PathBuf>, exe: Option<PathBuf>) -> Install {
        if !cfg!(target_os = "linux") {
            return Install::Binary;
        }
        if let Some(path) = appimage.filter(|path| path.is_absolute()) {
            return Install::AppImage(path);
        }
        if exe.is_some_and(|exe| exe.starts_with("/usr/bin")) {
            return Install::Package;
        }
        Install::Binary
    }
}

/// a desktop app as its release has it; an app keeps one and hands it to its window (`manage`).
/// Its version is the one of the build (`crate::VERSION`)
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct App {
    /// the name of the program, as the texts say it: sakadmin
    pub name: &'static str,
    /// how its zips start: `<zip>-<system>-<architecture>-<version>.zip`, e.g. sak-admin-cli
    pub zip: &'static str,
}

impl App {
    pub const fn new(name: &'static str, zip: &'static str) -> Self {
        App { name, zip }
    }

    /// the binaries in the zip, the update replaces all: on windows the window (`<name>.exe`,
    /// without a console) and the command line (`<name>-cli.exe`, the feature `console`,
    /// desktop:build)
    pub fn binaries(&self) -> Vec<String> {
        if cfg!(windows) {
            vec![
                format!("{}.exe", self.name),
                format!("{}-cli.exe", self.name),
            ]
        } else {
            vec![self.name.to_string()]
        }
    }
}

/// the releases of the repository; `releases/latest` is the newest one that is neither a draft nor
/// a pre-release
const API: &str = "https://api.github.com/repos/pellepelster/swiss-army-knife";

/// `<system>-<architecture>` of `<zip>-<system>-<architecture>-<version>.zip`; `macos-universal`
/// for both macOS architectures. An architecture the release does not build finds no zip
fn platform() -> String {
    match std::env::consts::OS {
        "macos" => "macos-universal".to_string(),
        system => format!("{system}-{}", std::env::consts::ARCH),
    }
}

/// the binary of the zip that runs: by the name of this program, the first when it was renamed
fn running(binaries: &[String]) -> String {
    let name = std::env::current_exe().ok().and_then(|path| {
        path.file_name()
            .map(|name| name.to_string_lossy().to_string())
    });
    binaries
        .iter()
        .find(|binary| name.as_deref() == Some(binary.as_str()))
        .unwrap_or(&binaries[0])
        .clone()
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum Status {
    /// nothing was asked: `current` is the version of a build that is no release, e.g. latest
    NoRelease {
        current: String,
    },
    /// this version is the newest release, or newer
    UpToDate {
        current: String,
    },
    Newer {
        current: String,
        newest: String,
    },
    /// a newer release of an app installed with a package, which the package manager installs
    NewerPackage {
        current: String,
        newest: String,
    },
}

/// whether this build is a release, which an update compares with the newest one
pub fn is_release() -> bool {
    Version::parse(crate::VERSION).is_ok()
}

impl Status {
    pub fn message(&self, app: &App) -> String {
        match self {
            Status::NoRelease { current } => t!(
                "common.update.no_release",
                app = app.name,
                version = current
            )
            .to_string(),
            Status::UpToDate { current } => t!(
                "common.update.up_to_date",
                app = app.name,
                version = current
            )
            .to_string(),
            Status::Newer { current, newest } => t!(
                "common.update.newer",
                app = app.name,
                current = current,
                newest = newest
            )
            .to_string(),
            Status::NewerPackage { current, newest } => t!(
                "common.update.newer_package",
                app = app.name,
                current = current,
                newest = newest
            )
            .to_string(),
        }
    }

    pub fn installed_message(&self, app: &App) -> String {
        match self {
            Status::Newer { current, newest } => t!(
                "common.update.installed",
                app = app.name,
                current = current,
                newest = newest
            )
            .to_string(),
            status => status.message(app),
        }
    }
}

/// the newest release with its file for this platform and install (the zip, or the AppImage), its
/// sha-256 and its cosign signature (`<file>.sigstore.json`)
#[derive(Debug, PartialEq, Eq)]
struct Release {
    version: Version,
    file: Asset,
    checksum: Asset,
    bundle: Asset,
}

#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
struct Asset {
    name: String,
    browser_download_url: String,
}

#[derive(Deserialize)]
struct LatestRelease {
    tag_name: String,
    assets: Vec<Asset>,
}

pub async fn check(app: &App) -> Result<Status, String> {
    Updater::github(*app).check().await
}

/// replaces this app with the newest release when it is newer; it runs from the next start
pub async fn install(app: &App) -> Result<Status, String> {
    Updater::github(*app)
        .install(&|binary| self_replace::self_replace(binary))
        .await
}

/// what the command `update` of an app does: compares the app with the newest release on github.com
/// and, unless `check_only`, installs a newer one; each step is a line for `log`. True when a newer
/// release is there and was not installed (`check_only`)
pub async fn run(app: &App, check_only: bool, log: &dyn Fn(String)) -> Result<bool, String> {
    Updater::github(*app).run(check_only, log).await
}

/// the version in the footer of the window, the name of the app and who a check asks
#[derive(Serialize)]
pub struct VersionResponse {
    pub app: &'static str,
    pub version: &'static str,
    pub receiver: &'static str,
}

/// what a check or an install found: the message to show, the newer version when there is one,
/// and whether an install replaced the app, so a restart starts the new version
#[derive(Serialize)]
pub struct UpdateResponse {
    message: String,
    newest: Option<String>,
    installed: bool,
}

/// the commands of the footer of the window (ui/update.js), for the `App` the window manages; the
/// footer asks github only when the user clicks, its button names github first. The restart after
/// an install is the app's own command `restart`: it knows what must not be stopped
#[tauri::command]
pub fn version(app: tauri::State<'_, App>) -> VersionResponse {
    version_of(&app)
}

pub fn version_of(app: &App) -> VersionResponse {
    VersionResponse {
        app: app.name,
        version: crate::VERSION,
        receiver: RECEIVER,
    }
}

#[tauri::command]
pub async fn check_update(app: tauri::State<'_, App>) -> Result<UpdateResponse, String> {
    let status = check(&app).await?;
    Ok(UpdateResponse {
        message: status.message(&app),
        newest: newer(&status),
        installed: false,
    })
}

/// the new version runs from the next start of the app
#[tauri::command]
pub async fn install_update(app: tauri::State<'_, App>) -> Result<UpdateResponse, String> {
    let status = install(&app).await?;
    let newest = newer(&status);
    Ok(UpdateResponse {
        message: status.installed_message(&app),
        installed: newest.is_some(),
        newest,
    })
}

/// the version the window offers to install: none for a package, the button stays a check
fn newer(status: &Status) -> Option<String> {
    match status {
        Status::Newer { newest, .. } => Some(newest.clone()),
        _ => None,
    }
}

/// checks the zip `name` of the release `version` against its cosign signature
type SignatureCheck =
    fn(zip: &[u8], bundle: &[u8], name: &str, version: &Version) -> Result<(), String>;

/// the tests use a mock server over plain http and a signature check that needs no zip signed by
/// the release workflow
struct Updater {
    app: App,
    /// the version of the app, `crate::VERSION` but in the tests
    current: &'static str,
    api: String,
    https_only: bool,
    signature: SignatureCheck,
    /// how the app was installed, `Install::this()` but in the tests
    install: Install,
    /// the client of all requests of this update, built with the first one (`client`)
    client: OnceLock<Client>,
}

impl Updater {
    fn github(app: App) -> Self {
        Updater::new(app, crate::VERSION, API.to_string(), true, verify_signature)
            .installed(Install::this())
    }

    fn new(
        app: App,
        current: &'static str,
        api: String,
        https_only: bool,
        signature: SignatureCheck,
    ) -> Self {
        Updater {
            app,
            current,
            api,
            https_only,
            signature,
            install: Install::Binary,
            client: OnceLock::new(),
        }
    }

    fn installed(self, install: Install) -> Self {
        Updater { install, ..self }
    }

    /// a newer release of a package is the package manager's
    fn status(&self, current: &Version, newest: &Version) -> Status {
        match (status(current, newest), &self.install) {
            (Status::Newer { current, newest }, Install::Package) => {
                Status::NewerPackage { current, newest }
            }
            (status, _) => status,
        }
    }

    /// https only (plain http for the tests), redirects only to github and its storage, where
    /// github sends the download of an asset
    fn client(&self) -> Result<&Client, String> {
        if let Some(client) = self.client.get() {
            return Ok(client);
        }
        let client = sigstore::github_client(
            &format!("{}/{}", self.app.name, self.current),
            Duration::from_secs(300),
            self.https_only,
            is_github,
        )?;
        Ok(self.client.get_or_init(|| client))
    }

    /// none for a build that is no release
    fn current(&self) -> Option<Version> {
        Version::parse(self.current).ok()
    }

    fn no_release(&self) -> Status {
        Status::NoRelease {
            current: self.current.to_string(),
        }
    }

    /// what the command `update` of the app does (`run`)
    async fn run(&self, check_only: bool, log: &dyn Fn(String)) -> Result<bool, String> {
        let app = &self.app;
        if self.current().is_none() {
            log(self.no_release().message(app));
            return Ok(false);
        }
        log(t!(
            "common.update.checking",
            app = app.name,
            receiver = RECEIVER
        )
        .to_string());
        let status = self.check().await?;
        log(status.message(app));
        let newest = match status {
            Status::Newer { newest, .. } => newest,
            // the package manager installs it: available, also without `check_only`
            Status::NewerPackage { .. } => return Ok(true),
            _ => return Ok(false),
        };
        if check_only {
            log(t!("cli.update.hint", app = app.name).to_string());
            return Ok(true);
        }
        log(t!(
            "common.update.installing",
            app = app.name,
            version = newest,
            receiver = RECEIVER
        )
        .to_string());
        log(self
            .install(&|binary| self_replace::self_replace(binary))
            .await?
            .installed_message(app));
        Ok(false)
    }

    async fn check(&self) -> Result<Status, String> {
        let Some(current) = self.current() else {
            return Ok(self.no_release());
        };
        Ok(self.status(&current, &self.latest().await?.version))
    }

    /// checks the file of a newer release (the zip, or the AppImage) against its sha-256 and its
    /// signature before `replace` gets its binary, or before it is written over the AppImage; a
    /// package downloads nothing
    async fn install(
        &self,
        replace: &(dyn Fn(&Path) -> std::io::Result<()> + Sync),
    ) -> Result<Status, String> {
        let Some(current) = self.current() else {
            return Ok(self.no_release());
        };
        let release = self.latest().await?;
        let status = self.status(&current, &release.version);
        if !matches!(status, Status::Newer { .. }) {
            return Ok(status);
        }
        let file = self.download(&release.file.browser_download_url).await?;
        let checksum = self
            .download(&release.checksum.browser_download_url)
            .await?;
        verify(&file, &checksum, &release.file.name)?;
        let bundle = self.download(&release.bundle.browser_download_url).await?;
        (self.signature)(&file, &bundle, &release.file.name, &release.version)?;
        if let Install::AppImage(path) = &self.install {
            write_beside(&self.app, path, &file)?;
            return Ok(status);
        }
        let zip = file;
        let names = self.app.binaries();
        let binaries = names
            .iter()
            .map(|binary| Ok((binary, unzip(&zip, &release.file.name, binary)?)))
            .collect::<Result<Vec<_>, String>>()?;
        // the others next to this one first, the running one last
        let running = running(&names);
        for (name, binary) in binaries.iter().filter(|(name, _)| **name != running) {
            let target = std::env::current_exe()
                .map(|path| path.with_file_name(name))
                .unwrap_or_else(|_| PathBuf::from(name));
            write_beside(&self.app, &target, binary)?;
        }
        if let Some((_, binary)) = binaries.iter().find(|(name, _)| **name == running) {
            write_and_replace(&self.app, binary, replace)?;
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
        let extension = match self.install {
            Install::AppImage(_) => "AppImage",
            _ => "zip",
        };
        let file_name = format!("{}-{}-{version}.{extension}", self.app.zip, platform());
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
            file: asset(&file_name)?,
            checksum: asset(&format!("{file_name}.sha256"))?,
            bundle: asset(&format!("{file_name}.sigstore.json"))?,
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
            to_github(url, &self.app)?;
        }
        let response = self
            .client()?
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
            // also a redirect not followed, to a host that is not github's
            status => Err(t!(
                "common.update.failed",
                url = url,
                status = status.to_string()
            )
            .to_string()),
        }
    }
}

/// an older release is no update
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

/// the first url as the client checks the redirects (`sigstore::github_client`)
fn to_github(url: &str, app: &App) -> Result<(), String> {
    if !url.starts_with("https://") {
        return Err(t!("common.update.not_https", app = app.name, url = url).to_string());
    }
    let host = reqwest::Url::parse(url)
        .ok()
        .and_then(|url| url.host_str().map(str::to_string));
    if !host.as_deref().is_some_and(is_github) {
        return Err(t!("common.update.not_github", app = app.name, url = url).to_string());
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
    let actual = hex(&Sha256::digest(zip));
    if expected == actual {
        Ok(())
    } else {
        Err(t!("common.update.checksum_mismatch", name = name).to_string())
    }
}

/// signed by the release workflow at the tag of `version` (`sigstore::signer_at_tag`)
fn verify_signature(
    zip: &[u8],
    bundle: &[u8],
    name: &str,
    version: &Version,
) -> Result<(), String> {
    let identity = Identity::Uri(sigstore::signer_at_tag(version));
    verify_signed_by(zip, bundle, name, version, identity, SIGNER_ISSUER)
}

/// the zip `name` of the release `version` is signed by `identity` of `issuer` (`sigstore::verify`),
/// or the text that says it is not
fn verify_signed_by(
    zip: &[u8],
    bundle: &[u8],
    name: &str,
    version: &Version,
    identity: Identity,
    issuer: &str,
) -> Result<(), String> {
    sigstore::verify(Signed::Content(zip), bundle, identity, issuer).map_err(|error| {
        t!(
            "common.update.signature_invalid",
            name = name,
            version = version.to_string(),
            error = error
        )
        .to_string()
    })
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

/// writes the binary to a temporary executable file that `replace` puts in place of the running one
fn write_and_replace(
    app: &App,
    binary: &[u8],
    replace: &(dyn Fn(&Path) -> std::io::Result<()> + Sync),
) -> Result<(), String> {
    let path = std::env::temp_dir().join(format!("{}-update-{}", app.name, random_hex::<16>()?));
    let result = (|| {
        std::fs::write(&path, binary)?;
        executable(&path)?;
        replace(&path)
    })();
    let _ = std::fs::remove_file(&path);
    result.map_err(|error| {
        let target = std::env::current_exe().unwrap_or_default();
        t!(
            "common.update.not_replaced",
            app = app.name,
            path = target.display().to_string(),
            error = error.to_string()
        )
        .to_string()
    })
}

/// a file that does not run as this process (another binary of the zip, the AppImage): written
/// beside `target`, executable, and renamed over it, so a failed write leaves the old one
fn write_beside(app: &App, target: &Path, binary: &[u8]) -> Result<(), String> {
    let staged = target.with_extension("update");
    let result = std::fs::write(&staged, binary)
        .and_then(|()| executable(&staged))
        .and_then(|()| std::fs::rename(&staged, target));
    if result.is_err() {
        let _ = std::fs::remove_file(&staged);
    }
    result.map_err(|error| {
        t!(
            "common.update.not_replaced",
            app = app.name,
            path = target.display().to_string(),
            error = error.to_string()
        )
        .to_string()
    })
}

/// rwxr-xr-x on unix; windows knows a program by its name
fn executable(path: &Path) -> std::io::Result<()> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o755))?;
    }
    #[cfg(not(unix))]
    let _ = path;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// sakadmin as a release of the tests has it
    const APP: App = App::new("sakadmin", "sak-admin-cli");

    /// sakbackup, an app with zips and binaries of its own
    const BACKUP: App = App::new("sakbackup", "sak-backup");

    /// the binary of the zip of `APP` on this platform
    fn binary() -> String {
        APP.binaries()[0].clone()
    }

    /// the name of the zip of `app` of the release `version` on this platform
    fn zip_name(app: &App, version: &str) -> String {
        format!("{}-{}-{version}.zip", app.zip, platform())
    }

    #[test]
    fn the_zip_of_a_release_names_the_system_and_the_architecture() {
        // the platforms desktop:build makes zips of
        let expected = if cfg!(target_os = "macos") {
            Some("macos-universal")
        } else if cfg!(all(target_os = "linux", target_arch = "x86_64")) {
            Some("linux-x86_64")
        } else if cfg!(all(target_os = "windows", target_arch = "x86_64")) {
            Some("windows-x86_64")
        } else {
            None
        };
        if let Some(expected) = expected {
            assert_eq!(platform(), expected);
            assert_eq!(
                zip_name(&BACKUP, "0.2.0"),
                format!("sak-backup-{expected}-0.2.0.zip")
            );
        }
    }

    #[test]
    fn the_zip_of_an_app_has_its_binaries() {
        if cfg!(windows) {
            assert_eq!(BACKUP.binaries(), ["sakbackup.exe", "sakbackup-cli.exe"]);
        } else {
            assert_eq!(BACKUP.binaries(), ["sakbackup"]);
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
        hex(&Sha256::digest(bytes))
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
        let name = zip_name(&APP, tag.trim_start_matches('v'));
        github_file(tag, &name, zip, checksum, bundle).await
    }

    /// a github with the release `tag` and its file `name` (a zip, an AppImage)
    async fn github_file(
        tag: &str,
        name: &str,
        zip: Vec<u8>,
        checksum: &str,
        bundle: &[u8],
    ) -> MockServer {
        let server = MockServer::start().await;
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
        Updater::new(APP, current, server.uri(), false, fake_signature)
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
            Ok(Status::NoRelease {
                current: "latest".into()
            })
        );
        assert_eq!(
            updater(&server, "latest").install(&|_| Ok(())).await,
            Ok(Status::NoRelease {
                current: "latest".into()
            })
        );
        assert!(server.received_requests().await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn the_command_of_a_build_that_is_no_release_says_so_and_asks_nothing() {
        let lines = Mutex::new(Vec::new());
        let available = updater(&MockServer::start().await, "latest")
            .run(true, &|line| lines.lock().unwrap().push(line))
            .await;
        assert_eq!(available, Ok(false));
        assert_eq!(
            *lines.lock().unwrap(),
            vec![
                "This sakadmin (latest) is not a release, so there is nothing to compare it with."
                    .to_string()
            ]
        );
    }

    #[test]
    fn the_texts_and_the_footer_name_the_app() {
        let newer = Status::Newer {
            current: "0.1.1".into(),
            newest: "0.2.0".into(),
        };
        assert_eq!(
            newer.message(&BACKUP),
            "sakbackup 0.2.0 is available (installed: 0.1.1)."
        );
        assert_eq!(
            newer.installed_message(&BACKUP),
            "sakbackup was updated from 0.1.1 to 0.2.0. Start sakbackup again to use the new version."
        );
        // the version of the build: latest, or the one of the tag when the release builds it
        // (VERSION of the workflow), which then is a release
        assert_eq!(is_release(), crate::VERSION != "latest");
        let version = version_of(&BACKUP);
        assert_eq!(
            (version.app, version.version, version.receiver),
            ("sakbackup", crate::VERSION, "github.com")
        );
    }

    #[tokio::test]
    async fn the_zip_of_a_release_is_the_one_of_the_app() {
        // a release that has the zip of sakadmin only
        let zip = zip_of(&binary(), b"the new sakadmin");
        let server = github("v0.2.0", zip.clone(), &sha256_of(&zip)).await;
        let backup = Updater::new(BACKUP, "0.1.1", server.uri(), false, fake_signature);
        let error = backup.check().await.unwrap_err();
        assert!(error.contains(&zip_name(&BACKUP, "0.2.0")), "{error}");
    }

    #[tokio::test]
    async fn the_update_replaces_the_binary_with_the_one_of_the_zip() {
        let zip = zip_of(&binary(), b"the new sakadmin");
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
        assert_eq!(
            *replaced.lock().unwrap(),
            vec![b"the new sakadmin".to_vec()]
        );
    }

    #[test]
    fn the_install_is_an_appimage_a_package_or_the_binary_of_the_zip() {
        let exe = |path: &str| Some(PathBuf::from(path));
        let of = Install::of;
        if cfg!(target_os = "linux") {
            assert_eq!(
                of(
                    Some("/home/a/sakbackup.AppImage".into()),
                    exe("/tmp/.mount_x/usr/bin/sakbackup")
                ),
                Install::AppImage("/home/a/sakbackup.AppImage".into())
            );
            assert_eq!(of(None, exe("/usr/bin/sakbackup")), Install::Package);
            // a relative $APPIMAGE is none the runtime sets
            assert_eq!(
                of(
                    Some("x.AppImage".into()),
                    exe("/home/a/.local/bin/sakbackup")
                ),
                Install::Binary
            );
            assert_eq!(of(None, exe("/usr/local/bin/sakbackup")), Install::Binary);
        } else {
            assert_eq!(of(None, exe("/usr/bin/sakbackup")), Install::Binary);
        }
    }

    #[tokio::test]
    async fn an_appimage_is_replaced_by_the_appimage_of_the_release() {
        let name = format!("{}-{}-0.2.0.AppImage", APP.zip, platform());
        let image = b"the new AppImage".to_vec();
        let server = github_file("v0.2.0", &name, image.clone(), &sha256_of(&image), SIGNED).await;
        let dir = std::env::temp_dir().join(format!("sak-update-{}", random_hex::<8>().unwrap()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("sakadmin.AppImage");
        std::fs::write(&path, b"the old AppImage").unwrap();
        let replaced = Mutex::new(Vec::new());
        let status = updater(&server, "0.1.1")
            .installed(Install::AppImage(path.clone()))
            .install(&recording(&replaced))
            .await;
        assert!(matches!(status, Ok(Status::Newer { .. })), "{status:?}");
        // the AppImage is written over, the running binary of its mount is not replaced
        assert_eq!(std::fs::read(&path).unwrap(), image);
        assert!(replaced.lock().unwrap().is_empty());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(&path).unwrap().permissions().mode() & 0o777,
                0o755
            );
        }
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[tokio::test]
    async fn a_package_is_told_to_update_by_its_package_manager_and_downloads_nothing() {
        let zip = zip_of(&binary(), b"the new sakadmin");
        let server = github("v0.2.0", zip.clone(), &sha256_of(&zip)).await;
        let package = || updater(&server, "0.1.1").installed(Install::Package);
        let status = package()
            .install(&|_| panic!("a package is never replaced"))
            .await;
        let expected = Status::NewerPackage {
            current: "0.1.1".into(),
            newest: "0.2.0".into(),
        };
        assert_eq!(status, Ok(expected.clone()));
        assert_eq!(newer(&expected), None);
        assert!(
            expected.message(&APP).contains("installed with a package"),
            "{}",
            expected.message(&APP)
        );
        // the command says so and ends as "update available", also without --check
        let lines = Mutex::new(Vec::new());
        assert_eq!(
            package()
                .run(false, &|line| lines.lock().unwrap().push(line))
                .await,
            Ok(true)
        );
        assert!(
            lines
                .lock()
                .unwrap()
                .last()
                .unwrap()
                .contains("download the new .deb or .rpm")
        );
        // only the release was asked, nothing downloaded
        let requests = server.received_requests().await.unwrap();
        assert!(
            requests
                .iter()
                .all(|request| request.url.path() == "/releases/latest")
        );
    }

    #[tokio::test]
    async fn an_up_to_date_sakadmin_downloads_nothing() {
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
        let wrong = zip_of(&binary(), b"the new sakadmin");
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
        let zip = zip_of(&binary(), b"the new sakadmin");
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
        let name = zip_name(&APP, "0.2.0");
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
        let version = Version::new(0, 2, 0);
        let check = |blob: &[u8], bundle: &[u8], signer: &str, issuer: &str| {
            verify_signed_by(
                blob,
                bundle,
                "blob",
                &version,
                Identity::Email(signer.to_string()),
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

    /// the release verifies what it signed with cosign against this file (`mise run admin-cli:release`,
    /// `image:sign`), so a root of sigstore that cosign knows and sakadmin does not fails there
    #[test]
    fn the_trusted_root_of_the_repository_is_the_one_sakadmin_embeds() {
        assert_eq!(
            include_str!("../../sak-admin-cli/src-tauri/trusted_root.json"),
            sigstore_verify::trust_root::SIGSTORE_PRODUCTION_TRUSTED_ROOT,
            "sak-admin-cli/src-tauri/trusted_root.json is not the trusted root of sigstore-verify, copy it from the crate"
        );
    }

    #[test]
    fn the_zips_of_a_release_are_signed_by_the_workflow_at_its_tag() {
        assert_eq!(
            sigstore::signer_at_tag(&Version::new(1, 2, 3)),
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
        assert!(error.contains(&zip_name(&APP, "0.2.0")), "{error}");
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
        let updater = Updater::new(
            APP,
            "0.1.1",
            "http://api.github.com/repos/x".into(),
            true,
            verify_signature,
        );
        assert_eq!(
            updater.check().await,
            Err(
                "http://api.github.com/repos/x/releases/latest is not an https address; sakadmin asks GitHub over https only."
                    .into()
            )
        );
    }

    #[tokio::test]
    async fn a_download_is_asked_of_github_only() {
        let updater = Updater::new(APP, "0.1.1", API.into(), true, verify_signature);
        let error = updater
            .download("https://example.org/sak-admin-cli.zip")
            .await
            .unwrap_err();
        assert_eq!(
            error,
            "https://example.org/sak-admin-cli.zip is not a GitHub address; sakadmin downloads from GitHub only."
        );
        assert_eq!(
            to_github("https://github.com.example.org/x", &APP),
            Err("https://github.com.example.org/x is not a GitHub address; sakadmin downloads from GitHub only.".into())
        );
        assert_eq!(
            to_github("https://objects.githubusercontent.com/x", &APP),
            Ok(())
        );
        assert_eq!(to_github(API, &APP), Ok(()));
    }
}
