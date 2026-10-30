//! what the window keeps between its commands: the work that runs and the deploy the last check
//! planned

use crate::core::deployments::{DeploymentRequest, Seen};
use futures_util::future::{AbortHandle, Abortable};
use rust_i18n::t;
use std::sync::Mutex;

/// the work of the window that runs, an apply or a maintenance: cancel stops it, a restart waits
/// for it
#[derive(Default)]
pub struct RunningWork(pub(crate) Mutex<Option<AbortHandle>>);

impl RunningWork {
    pub fn is_running(&self) -> bool {
        self.0.lock().is_ok_and(|running| running.is_some())
    }

    /// `work` until it ends, or until cancel stops it: then the error is the text `cancelled`
    pub async fn run<T, E: From<String>>(
        &self,
        work: impl std::future::Future<Output = Result<T, E>>,
        cancelled: &str,
    ) -> Result<T, E> {
        let (abort, registration) = AbortHandle::new_pair();
        if let Ok(mut running) = self.0.lock() {
            *running = Some(abort);
        }
        let result = Abortable::new(work, registration).await;
        if let Ok(mut running) = self.0.lock() {
            *running = None;
        }
        result.unwrap_or_else(|_| Err(t!(cancelled).to_string().into()))
    }
}

/// the deploy the last check of the page of a deployment planned, as `apply_checked` applies it.
/// Each check has its number; only the newest one is kept, and a deploy names the check it applies.
/// So a check that ends after a newer one (of another deployment, after the user went back and
/// opened it) is never what a deploy applies
#[derive(Default)]
pub struct CheckedDeployment(Mutex<Checks>);

#[derive(Default)]
struct Checks {
    /// the number of the newest check that started
    newest: u64,
    /// the newest check that ended, with its number and what its plan showed
    done: Option<(u64, DeploymentRequest, Seen)>,
}

impl CheckedDeployment {
    /// the number of a check that starts; what an older check kept goes
    pub(crate) fn start(&self) -> u64 {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        checks.newest += 1;
        checks.done = None;
        checks.newest
    }

    /// keeps the deploy of the check `number` and what its plan showed, unless a newer check
    /// started meanwhile
    pub(crate) fn done(&self, number: u64, deployment: DeploymentRequest, seen: Seen) {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if checks.newest == number {
            checks.done = Some((number, deployment, seen));
        }
    }

    /// the deploy of the check `number` and what its plan showed, once; none when it is not the
    /// newest check that ended
    pub(crate) fn take(&self, number: u64) -> Option<(DeploymentRequest, Seen)> {
        let mut checks = self
            .0
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        match checks.done.take() {
            Some((done, deployment, seen)) if done == number => Some((deployment, seen)),
            other => {
                checks.done = other;
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn deployment(name: &str) -> DeploymentRequest {
        DeploymentRequest::as_it_is(name, "", sak_cloud::hetzner::api::Location::nbg1)
    }

    #[test]
    fn a_deploy_applies_only_the_newest_check_it_names() {
        let checked = CheckedDeployment::default();
        // the page of alpha checks, the user goes back and opens beta, which checks too
        let alpha = checked.start();
        let beta = checked.start();
        // beta ends first, alpha after it: alpha is not kept
        checked.done(beta, deployment("beta"), Vec::new());
        checked.done(alpha, deployment("alpha"), Vec::new());
        assert!(checked.take(alpha).is_none());
        assert_eq!(checked.take(beta).unwrap().0.name, "beta");
        // once
        assert!(checked.take(beta).is_none());

        // a deploy that names an older check leaves the newest one as it is
        let gamma = checked.start();
        checked.done(gamma, deployment("gamma"), Vec::new());
        assert!(checked.take(beta).is_none());
        assert_eq!(checked.take(gamma).unwrap().0.name, "gamma");

        // a check that starts drops what the one before kept
        let delta = checked.start();
        checked.done(delta, deployment("delta"), Vec::new());
        checked.start();
        assert!(checked.take(delta).is_none());
    }
}
