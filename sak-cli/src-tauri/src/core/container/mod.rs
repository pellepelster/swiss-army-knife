//! the container runtime the tools run with, docker or podman, and the image of the tools

mod pull;

pub use pull::pull;

use rust_i18n::t;
use std::io::Read;
use std::process::{Command, Stdio};
use std::sync::Mutex;
use std::time::{Duration, Instant};

// tried in this order; sakctl uses the first one that works
pub const RUNTIMES: &[&str] = &["docker", "podman"];
// a small image that shows that a runtime pulls and runs images (canary/Dockerfile). The name
// is fully qualified, because without a tty podman refuses a short name
fn canary_image() -> &'static str {
    static CANARY: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
        format!(
            "ghcr.io/pellepelster/sak-cli-docker-canary:{}",
            crate::core::image_tag()
        )
    });
    &CANARY
}
const CANARY_ANSWER: &str = "sak-cli-docker-canary";
// the image of the tools (Dockerfile) of the tag of sakctl (`core::image_tag`, its version unless
// SAK_DOCKER_IMAGE_TAG says otherwise), pulled on every start
pub fn image() -> &'static str {
    static IMAGE: std::sync::LazyLock<String> = std::sync::LazyLock::new(|| {
        format!(
            "ghcr.io/pellepelster/sak-cli-tools:{}",
            crate::core::image_tag()
        )
    });
    &IMAGE
}
// the user of the image; only this image answers `id -un` with it
const IMAGE_USER: &str = "sak";
// the timeout of each request to the docker api, the pull of the image is one request, and of
// each run of the command line of the checks
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
    let answer =
        output(runtime, &["run", "--rm", "--pull=always", canary_image()]).map_err(not_working)?;
    if answer != CANARY_ANSWER {
        return Err(not_working(
            t!(
                "shared.checks.container_runtime.wrong_answer",
                image = canary_image(),
                answer = answer,
                expected = CANARY_ANSWER
            )
            .to_string(),
        ));
    }
    Ok(version)
}

// the trimmed stdout of a run that succeeded, why it did not otherwise
fn output(runtime: &str, args: &[&str]) -> Result<String, String> {
    output_within(runtime, args, Duration::from_secs(API_TIMEOUT_SECONDS))
}

// as the api, the command line gets `limit` to answer; a runtime that accepts and never answers
// is stopped then
fn output_within(runtime: &str, args: &[&str], limit: Duration) -> Result<String, String> {
    let mut child = command(runtime)
        .args(args)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|e| e.to_string())?;
    // read while it runs, a full pipe would block the child
    let read = |pipe: Option<Box<dyn Read + Send>>| {
        std::thread::spawn(move || {
            let mut bytes = Vec::new();
            if let Some(mut pipe) = pipe {
                let _ = pipe.read_to_end(&mut bytes);
            }
            String::from_utf8_lossy(&bytes).trim().to_string()
        })
    };
    let stdout = read(child.stdout.take().map(|pipe| Box::new(pipe) as _));
    let stderr = read(child.stderr.take().map(|pipe| Box::new(pipe) as _));
    let deadline = Instant::now() + limit;
    let status = loop {
        if let Some(status) = child.try_wait().map_err(|e| e.to_string())? {
            break status;
        }
        if Instant::now() >= deadline {
            let _ = child.kill();
            let _ = child.wait();
            return Err(t!(
                "shared.checks.container_runtime.no_answer",
                command = format!("{runtime} {}", args.join(" ")),
                seconds = limit.as_secs()
            )
            .to_string());
        }
        std::thread::sleep(Duration::from_millis(50));
    };
    let stdout = stdout.join().unwrap_or_default();
    let stderr = stderr.join().unwrap_or_default();
    if !status.success() {
        return Err(stderr);
    }
    Ok(stdout)
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

    fn installed(runtime: &str) -> bool {
        Command::new(runtime).arg("--version").output().is_ok()
    }

    #[cfg(unix)]
    #[test]
    fn a_command_that_does_not_answer_in_time_is_stopped() {
        let started = Instant::now();
        let error = output_within("sh", &["-c", "sleep 30"], Duration::from_secs(1)).unwrap_err();
        assert_eq!(
            error,
            "`sh -c sleep 30` did not answer within 1 seconds and was stopped."
        );
        assert!(started.elapsed() < Duration::from_secs(10));
        assert_eq!(
            output_within("sh", &["-c", "echo ' ok '"], Duration::from_secs(10)),
            Ok("ok".into())
        );
        assert_eq!(
            output_within(
                "sh",
                &["-c", "echo why >&2; exit 1"],
                Duration::from_secs(10)
            ),
            Err("why".into())
        );
    }

    #[test]
    fn without_a_runtime_the_check_explains_what_is_needed() {
        let error = find(&["sakctl-no-such-docker", "sakctl-no-such-podman"]).unwrap_err();
        assert!(
            error.starts_with("Swiss Army Knife needs docker or podman"),
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

    /// the integration tests (`mise run cli:test:integration`): the real runtimes, docker
    /// required, podman skipped when it is not installed; they pull the canary and the image
    /// from ghcr.io
    mod integration {
        use super::*;

        #[test]
        fn docker_runs_the_canary() {
            assert!(installed("docker"), "the integration tests need docker");
            assert!(canary("docker").is_ok(), "{:?}", canary("docker"));
        }

        // reports progress only when the image is not pulled already
        #[test]
        fn docker_pulls_the_image() {
            assert!(installed("docker"), "the integration tests need docker");
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
            assert!(installed("docker"), "the integration tests need docker");
            let (runtime, _) = find(&["sakctl-no-such-runtime", "docker"]).unwrap();
            assert_eq!(runtime, "docker");
        }
    }
}
