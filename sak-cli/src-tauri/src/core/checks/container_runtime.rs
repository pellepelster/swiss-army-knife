use super::{Check, CheckResult, Found, Progress};
use crate::core::container;
use rust_i18n::t;

/// docker or podman, proven by the canary; the one found is used by the checks after it
pub struct ContainerRuntimeCheck;

impl Check for ContainerRuntimeCheck {
    fn id(&self) -> &'static str {
        "container_runtime"
    }

    fn name(&self) -> String {
        t!("shared.checks.container_runtime.name").to_string()
    }

    fn run(&self, found: &mut Found, _: &Progress) -> CheckResult {
        let (runtime, version) = container::find(container::RUNTIMES)?;
        found.runtime = Some(runtime);
        Ok(t!(
            "shared.checks.container_runtime.passed",
            runtime = runtime,
            version = version
        )
        .to_string())
    }
}
