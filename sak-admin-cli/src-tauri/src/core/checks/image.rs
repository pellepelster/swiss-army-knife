use super::{CheckResult, Found, Progress};
use crate::core::container;
use rust_i18n::t;
use sak_desktop_shared::checks::Check;

/// pulls the image of the tools again; the progress is the megabytes pulled
pub struct ImageCheck;

impl Check<Found> for ImageCheck {
    fn id(&self) -> &'static str {
        "image"
    }

    fn name(&self) -> String {
        t!("common.checks.image.name").to_string()
    }

    // runs after the container runtime check, which stops the checks when it finds no runtime
    fn run(&self, found: &mut Found, progress: &Progress) -> CheckResult {
        let runtime = found
            .runtime
            .ok_or_else(|| t!("common.checks.container_runtime.needed").to_string())?;
        container::pull(runtime, &|fraction, text| progress.update(fraction, text))?;
        Ok(t!("common.checks.image.passed", image = container::image()).to_string())
    }
}
