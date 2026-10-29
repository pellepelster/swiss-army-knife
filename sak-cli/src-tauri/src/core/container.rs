//! the container runtime the tools run with, docker or podman, and the image of the tools

use bollard::models::CreateImageInfo;
use bollard::query_parameters::CreateImageOptionsBuilder;
use bollard::{API_DEFAULT_VERSION, Docker};
use futures_util::StreamExt;
use rust_i18n::t;
use std::collections::HashMap;
use std::process::Command;
use std::sync::Mutex;

// tried in this order; sakctl uses the first one that works
pub const RUNTIMES: &[&str] = &["docker", "podman"];
// a small image that shows that a runtime pulls and runs images (canary/Dockerfile). The name
// is fully qualified, because without a tty podman refuses a short name
const CANARY: &str = "ghcr.io/pellepelster/sak-cli-docker-canary:latest";
const CANARY_ANSWER: &str = "sak-cli-docker-canary";
// the image of the tools (Dockerfile), pulled on every start so the latest one is used
pub const IMAGE: &str = "ghcr.io/pellepelster/sak-cli-tools:latest";
// the user of the image; only this image answers `id -un` with it
const IMAGE_USER: &str = "sak";
// the timeout of each request to the docker api; the pull of the image is one request
const API_TIMEOUT_SECONDS: u64 = 3600;

/// the runtime found working on startup
#[derive(Debug, Default)]
pub struct ContainerRuntime(Mutex<Option<&'static str>>);

impl Clone for ContainerRuntime {
    fn clone(&self) -> Self {
        let runtime = ContainerRuntime::default();
        if let Some(found) = self.get() {
            runtime.set(found);
        }
        runtime
    }
}

impl ContainerRuntime {
    pub fn get(&self) -> Option<&'static str> {
        self.0.lock().ok().and_then(|runtime| *runtime)
    }

    pub fn set(&self, runtime: &'static str) {
        if let Ok(mut current) = self.0.lock() {
            *current = Some(runtime);
        }
    }
}

/// the first of `runtimes` that runs the canary, with its version
pub fn find(runtimes: &[&'static str]) -> Result<(&'static str, String), String> {
    let mut errors = Vec::new();
    for runtime in runtimes {
        match canary(runtime) {
            Ok(version) => return Ok((runtime, version)),
            Err(error) => errors.push(error),
        }
    }
    Err(format!(
        "{}\n\n{}",
        t!("shared.checks.container_runtime.needed"),
        errors.join("\n")
    ))
}

// docker answers its version only when its daemon answers, podman has no daemon. The canary is
// pulled every time, so a runtime that cannot reach ghcr.io fails here and not at the image of the
// tools
fn canary(runtime: &str) -> Result<String, String> {
    let not_working = |error: String| {
        t!(
            "shared.checks.container_runtime.not_working",
            runtime = runtime,
            error = error
        )
        .to_string()
    };
    let version =
        output(runtime, &["version", "--format", "{{.Client.Version}}"]).map_err(not_working)?;
    let answer = output(runtime, &["run", "--rm", "--pull=always", CANARY]).map_err(not_working)?;
    if answer != CANARY_ANSWER {
        return Err(not_working(
            t!(
                "shared.checks.container_runtime.wrong_answer",
                image = CANARY,
                answer = answer,
                expected = CANARY_ANSWER
            )
            .to_string(),
        ));
    }
    Ok(version)
}

// runs the future to its end: on the runtime of this thread (a blocking task of the window or of
// the command line), else on a runtime of its own (the tests)
pub(crate) fn block_on<F: std::future::Future>(future: F) -> F::Output {
    match tokio::runtime::Handle::try_current() {
        Ok(handle) => handle.block_on(future),
        Err(_) => tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("a runtime of its own")
            .block_on(future),
    }
}

/// pulls the image again and runs it to prove it. The api of docker reports the bytes pulled, the
/// command line of docker and podman reports nothing
pub fn pull(runtime: &str, on_progress: &dyn Fn(f64, &str)) -> Result<(), String> {
    let pulled = runtime == "docker" && block_on(pull_with_api(on_progress)).is_ok();
    if !pulled {
        output(runtime, &["pull", IMAGE]).map_err(|error| {
            t!(
                "shared.checks.image.not_pulled",
                image = IMAGE,
                runtime = runtime,
                error = error
            )
            .to_string()
        })?;
    }
    let answer = output(runtime, &["run", "--rm", IMAGE, "id", "-un"]).map_err(|error| {
        t!(
            "shared.checks.image.not_run",
            image = IMAGE,
            runtime = runtime,
            error = error
        )
        .to_string()
    })?;
    if answer != IMAGE_USER {
        return Err(t!(
            "shared.checks.image.wrong_answer",
            image = IMAGE,
            answer = answer,
            expected = IMAGE_USER
        )
        .to_string());
    }
    Ok(())
}

// on an error `pull` uses the command line, and shows its error
async fn pull_with_api(on_progress: &dyn Fn(f64, &str)) -> Result<(), String> {
    let docker = docker_api().ok_or("no docker api")?;
    let (image, tag) = IMAGE.rsplit_once(':').ok_or("no tag")?;
    let options = CreateImageOptionsBuilder::new()
        .from_image(image)
        .tag(tag)
        .build();
    let mut stream = docker.create_image(Some(options), None, None);
    let mut progress = PullProgress::default();
    while let Some(info) = stream.next().await {
        let info = info.map_err(|e| e.to_string())?;
        if let Some(message) = info.error_detail.as_ref().and_then(|e| e.message.clone()) {
            return Err(message);
        }
        progress.update(&info);
        if let Some(fraction) = progress.fraction() {
            on_progress(fraction, &progress.text());
        }
    }
    Ok(())
}

// where the docker command line connects to: DOCKER_HOST or the endpoint of the current context
// (docker desktop, colima, rootless docker, …); tls is left to the command line
fn docker_api() -> Option<Docker> {
    let host = std::env::var("DOCKER_HOST")
        .ok()
        .filter(|host| !host.is_empty())
        .or_else(|| {
            output(
                "docker",
                &[
                    "context",
                    "inspect",
                    "--format",
                    "{{.Endpoints.docker.Host}}",
                ],
            )
            .ok()
        })?;
    if host.starts_with("unix://") {
        return Docker::connect_with_unix(&host, API_TIMEOUT_SECONDS, API_DEFAULT_VERSION).ok();
    }
    #[cfg(windows)]
    if host.starts_with("npipe://") {
        return Docker::connect_with_named_pipe(&host, API_TIMEOUT_SECONDS, API_DEFAULT_VERSION)
            .ok();
    }
    if host.starts_with("tcp://") && std::env::var_os("DOCKER_TLS_VERIFY").is_none() {
        return Docker::connect_with_http(&host, API_TIMEOUT_SECONDS, API_DEFAULT_VERSION).ok();
    }
    None
}

/// the bytes of the layers of a pull; the download and the extraction are each half. Layers that
/// exist already do not count
#[derive(Debug, Default)]
struct PullProgress {
    layers: HashMap<String, Layer>,
}

#[derive(Debug, Default)]
struct Layer {
    total: u64,
    downloaded: u64,
    extracted: u64,
}

impl PullProgress {
    fn update(&mut self, info: &CreateImageInfo) {
        let (Some(id), Some(status)) = (&info.id, &info.status) else {
            return;
        };
        let detail = info.progress_detail.as_ref();
        let current = detail.and_then(|d| d.current).unwrap_or(0).max(0) as u64;
        let total = detail.and_then(|d| d.total).unwrap_or(0).max(0) as u64;
        if status == "Already exists" {
            self.layers.remove(id);
            return;
        }
        let layer = self.layers.entry(id.clone()).or_default();
        if total > 0 && layer.total == 0 {
            layer.total = total;
        }
        match status.as_str() {
            "Downloading" => layer.downloaded = current.min(layer.total),
            "Verifying Checksum" | "Download complete" => layer.downloaded = layer.total,
            "Extracting" => {
                layer.downloaded = layer.total;
                layer.extracted = current.min(layer.total);
            }
            "Pull complete" => {
                layer.downloaded = layer.total;
                layer.extracted = layer.total;
            }
            _ => {}
        }
    }

    fn total(&self) -> u64 {
        self.layers.values().map(|layer| layer.total).sum()
    }

    fn fraction(&self) -> Option<f64> {
        let total = self.total();
        if total == 0 {
            return None;
        }
        let done: u64 = self
            .layers
            .values()
            .map(|layer| layer.downloaded + layer.extracted)
            .sum();
        Some(done as f64 / (2 * total) as f64)
    }

    fn text(&self) -> String {
        let megabytes = |bytes: u64| (bytes as f64 / 1_000_000.0).round() as u64;
        let downloaded = self.layers.values().map(|layer| layer.downloaded).sum();
        t!(
            "shared.checks.image.progress",
            downloaded = megabytes(downloaded),
            total = megabytes(self.total())
        )
        .to_string()
    }
}

// the trimmed stdout of a run that succeeded, why it did not otherwise
fn output(runtime: &str, args: &[&str]) -> Result<String, String> {
    let output = command(runtime)
        .args(args)
        .output()
        .map_err(|e| e.to_string())?;
    if !output.status.success() {
        return Err(String::from_utf8_lossy(&output.stderr).trim().to_string());
    }
    Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
}

pub(crate) fn command(program: &str) -> Command {
    #[allow(unused_mut)]
    let mut command = Command::new(program);
    // no console window flashing up for every process the windows release build starts
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        command.creation_flags(CREATE_NO_WINDOW);
    }
    command
}

#[cfg(test)]
mod tests {
    use super::*;
    use bollard::models::ProgressDetail;

    fn installed(runtime: &str) -> bool {
        Command::new(runtime).arg("--version").output().is_ok()
    }

    fn info(id: &str, status: &str, current: i64, total: i64) -> CreateImageInfo {
        CreateImageInfo {
            id: Some(id.to_string()),
            status: Some(status.to_string()),
            progress_detail: Some(ProgressDetail {
                current: Some(current),
                total: Some(total),
            }),
            ..Default::default()
        }
    }

    // each test runs the real runtime and is skipped when it is not installed; they need the
    // canary and the image on ghcr.io
    #[test]
    fn docker_runs_the_canary() {
        if !installed("docker") {
            eprintln!("docker is not installed, skipped");
            return;
        }
        assert!(canary("docker").is_ok(), "{:?}", canary("docker"));
    }

    // reports progress only when the image is not pulled already
    #[test]
    fn docker_pulls_the_image() {
        if !installed("docker") {
            eprintln!("docker is not installed, skipped");
            return;
        }
        let updates = Mutex::new(Vec::new());
        let on_progress = |fraction: f64, text: &str| {
            updates
                .lock()
                .unwrap()
                .push(format!("{fraction:.3} {text}"));
        };
        assert_eq!(pull("docker", &on_progress), Ok(()));
        let updates = updates.into_inner().unwrap();
        eprintln!("{} updates, the last {:?}", updates.len(), updates.last());
    }

    #[test]
    fn podman_runs_the_canary_and_pulls_the_image() {
        if !installed("podman") {
            eprintln!("podman is not installed, skipped");
            return;
        }
        assert!(canary("podman").is_ok(), "{:?}", canary("podman"));
        assert_eq!(pull("podman", &|_, _| {}), Ok(()));
    }

    #[test]
    fn the_next_runtime_is_tried_when_one_is_missing() {
        if !installed("docker") {
            eprintln!("docker is not installed, skipped");
            return;
        }
        let (runtime, _) = find(&["sakctl-no-such-runtime", "docker"]).unwrap();
        assert_eq!(runtime, "docker");
    }

    #[test]
    fn without_a_runtime_the_check_explains_what_is_needed() {
        let error = find(&["sakctl-no-such-docker", "sakctl-no-such-podman"]).unwrap_err();
        assert!(
            error.starts_with("SwissArmyKnife needs docker or podman"),
            "{error}"
        );
        assert!(
            error.contains("sakctl-no-such-docker does not work"),
            "{error}"
        );
        assert!(
            error.contains("sakctl-no-such-podman does not work"),
            "{error}"
        );
    }

    #[test]
    fn the_pull_progresses_with_the_bytes_downloaded_and_extracted() {
        let mut progress = PullProgress::default();
        progress.update(&info("a", "Pulling fs layer", 0, 0));
        progress.update(&info("b", "Already exists", 0, 0));
        assert_eq!(progress.fraction(), None);
        progress.update(&info("a", "Downloading", 50, 100));
        assert_eq!(progress.fraction(), Some(0.25));
        progress.update(&info("a", "Extracting", 50, 100));
        assert_eq!(progress.fraction(), Some(0.75));
        progress.update(&info("a", "Pull complete", 0, 0));
        assert_eq!(progress.fraction(), Some(1.0));
    }

    #[test]
    fn the_pull_progress_says_the_megabytes_downloaded() {
        let mut progress = PullProgress::default();
        progress.update(&info("a", "Downloading", 312_000_000, 740_000_000));
        assert_eq!(progress.text(), "312 MB of 740 MB");
    }
}
