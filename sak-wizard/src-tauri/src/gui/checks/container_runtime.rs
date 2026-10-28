use super::{Check, CheckResult, Progress};
use crate::core::container::{self, ContainerRuntime};
use rust_i18n::t;
use tauri::{AppHandle, Manager};

/// docker or podman, proven by the canary; the one found is used by the checks after it
pub struct ContainerRuntimeCheck;

impl Check for ContainerRuntimeCheck {
    fn id(&self) -> &'static str {
        "container_runtime"
    }

    fn name(&self) -> String {
        t!("checks.container_runtime.name").to_string()
    }

    fn run(&self, app: &AppHandle, _: &Progress) -> CheckResult {
        let (runtime, version) = container::find(container::RUNTIMES)?;
        app.state::<ContainerRuntime>().set(runtime);
        Ok(t!(
            "checks.container_runtime.passed",
            runtime = runtime,
            version = version
        )
        .to_string())
    }
}
