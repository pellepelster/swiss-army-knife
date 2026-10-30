//! the pull of the image of the tools: through the api of docker with the bytes pulled, else
//! through the command line of docker or podman, which reports nothing

use super::{API_TIMEOUT_SECONDS, IMAGE_USER, image, output};
use crate::core::runtime::block_on;
use bollard::models::CreateImageInfo;
use bollard::query_parameters::CreateImageOptionsBuilder;
use bollard::{API_DEFAULT_VERSION, Docker};
use futures_util::StreamExt;
use rust_i18n::t;
use std::collections::HashMap;

/// pulls the image again and runs it to prove it. The api of docker reports the bytes pulled, the
/// command line of docker and podman reports nothing
pub fn pull(runtime: &str, on_progress: &dyn Fn(f64, &str)) -> Result<(), String> {
    let (pulled, api_error) = if runtime == "docker" {
        match block_on(pull_with_api(on_progress)) {
            Ok(()) => (true, None),
            Err(error) => (false, Some(error)),
        }
    } else {
        (false, None)
    };
    if !pulled {
        output(runtime, &["pull", image()]).map_err(|error| {
            let error = match &api_error {
                Some(api_error) => format!(
                    "{error}\n{}",
                    t!("shared.checks.image.api_failed", error = api_error)
                ),
                None => error,
            };
            t!(
                "shared.checks.image.not_pulled",
                image = image(),
                runtime = runtime,
                error = error
            )
            .to_string()
        })?;
    }
    let answer = output(runtime, &["run", "--rm", image(), "id", "-un"]).map_err(|error| {
        t!(
            "shared.checks.image.not_run",
            image = image(),
            runtime = runtime,
            error = error
        )
        .to_string()
    })?;
    if answer != IMAGE_USER {
        return Err(t!(
            "shared.checks.image.wrong_answer",
            image = image(),
            answer = answer,
            expected = IMAGE_USER
        )
        .to_string());
    }
    Ok(())
}

// on an error `pull` uses the command line, and shows both errors
async fn pull_with_api(on_progress: &dyn Fn(f64, &str)) -> Result<(), String> {
    let docker = docker_api().ok_or_else(|| t!("shared.checks.image.no_api").to_string())?;
    let (image, tag) = image().rsplit_once(':').ok_or("no tag")?;
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
    #[cfg(unix)]
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

#[cfg(test)]
mod tests {
    use super::*;
    use bollard::models::ProgressDetail;

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
