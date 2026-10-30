//! the async code the blocking code of sakctl waits for: the checks, the provisioning and the
//! command line run on threads that may block, the requests of the providers are async

/// runs the future to its end: on the runtime of this thread (a blocking task of the window or of
/// the command line), else on a runtime of its own (the tests). In an async task of a runtime with
/// several threads the task first gives its thread to the others (`block_in_place`); an async task
/// of a runtime with one thread cannot wait, which only a test could do, so it panics there
pub(crate) fn block_on<F: std::future::Future>(future: F) -> F::Output {
    match tokio::runtime::Handle::try_current() {
        Ok(handle) => match handle.runtime_flavor() {
            tokio::runtime::RuntimeFlavor::MultiThread => {
                tokio::task::block_in_place(|| handle.block_on(future))
            }
            _ => handle.block_on(future),
        },
        Err(_) => tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("a runtime of its own")
            .block_on(future),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    async fn answer() -> u32 {
        tokio::task::yield_now().await;
        42
    }

    #[test]
    fn without_a_runtime_the_future_runs_on_one_of_its_own() {
        assert_eq!(block_on(answer()), 42);
    }

    #[test]
    fn a_blocking_task_and_an_async_task_wait_on_the_runtime_of_their_thread() {
        let runtime = tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .unwrap();
        let blocking = runtime.block_on(runtime.spawn_blocking(|| block_on(answer())));
        assert_eq!(blocking.unwrap(), 42);
        let in_task = runtime.block_on(runtime.spawn(async { block_on(answer()) }));
        assert_eq!(in_task.unwrap(), 42);
    }
}
