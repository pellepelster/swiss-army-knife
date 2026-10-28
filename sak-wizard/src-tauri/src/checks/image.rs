use super::{Check, CheckResult, Progress};
use crate::container::{self, ContainerRuntime};
use rust_i18n::t;
use tauri::{AppHandle, Manager};

/// the image of the tools pulled anew, its progress the megabytes pulled
pub struct ImageCheck;

impl Check for ImageCheck {
    fn id(&self) -> &'static str {
        "image"
    }

    fn name(&self) -> String {
        t!("checks.image.name").to_string()
    }

    // runs after the container runtime check, which ended the checks when it found none
    fn run(&self, app: &AppHandle, progress: &Progress) -> CheckResult {
        let runtime = app
            .state::<ContainerRuntime>()
            .get()
            .ok_or_else(|| t!("checks.container_runtime.needed").to_string())?;
        container::pull(runtime, &|fraction, text| progress.update(fraction, text))?;
        Ok(t!("checks.image.passed", image = container::IMAGE).to_string())
    }
}
