use super::{Check, CheckResult, Found, Progress};
use crate::core::container;
use rust_i18n::t;

/// pulls the image of the tools again; the progress is the megabytes pulled
pub struct ImageCheck;

impl Check for ImageCheck {
    fn id(&self) -> &'static str {
        "image"
    }

    fn name(&self) -> String {
        t!("shared.checks.image.name").to_string()
    }

    // runs after the container runtime check, which stops the checks when it finds no runtime
    fn run(&self, found: &mut Found, progress: &Progress) -> CheckResult {
        let runtime = found
            .runtime
            .ok_or_else(|| t!("shared.checks.container_runtime.needed").to_string())?;
        container::pull(runtime, &|fraction, text| progress.update(fraction, text))?;
        Ok(t!("shared.checks.image.passed", image = container::IMAGE).to_string())
    }
}
