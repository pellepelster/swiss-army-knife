//! the events the playbook posts while it provisions a server (the callback `http_notify` of the
//! collection pelle_io.sak): a small http server on the loopback for one run, which takes an event
//! only with the token of the run (docs/development/fdr/FDR-006-provisioning-events.md)

use serde::Deserialize;
use std::collections::BTreeMap;
use std::io::Read;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

/// what the callback posts, one for each play, task start and task result, the counts at the end
#[derive(Clone, Debug, Deserialize, PartialEq, Eq)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum AnsibleEvent {
    PlayStart {
        play: String,
    },
    TaskStart {
        task: String,
    },
    Ok {
        host: String,
        task: String,
        #[serde(default)]
        changed: bool,
    },
    Failed {
        host: String,
        task: String,
        #[serde(default)]
        msg: Option<String>,
        #[serde(default)]
        ignore_errors: bool,
    },
    Skipped {
        host: String,
        task: String,
    },
    Unreachable {
        host: String,
        task: String,
        #[serde(default)]
        msg: Option<String>,
    },
    Stats {
        hosts: BTreeMap<String, HostStats>,
    },
}

/// the counts of one host at the end of the run
#[derive(Clone, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct HostStats {
    pub ok: u32,
    pub changed: u32,
    pub failures: u32,
    pub unreachable: u32,
    pub skipped: u32,
    pub rescued: u32,
    pub ignored: u32,
}

/// a task as it is shown: without the role ansible puts before the task of a role,
/// `pelle_io.sak.backend : Install the backend` is `Install the backend`
pub fn task_name(task: &str) -> String {
    task.split_once(" : ")
        .map_or(task, |(_, name)| name)
        .trim()
        .to_string()
}

/// the largest event taken, an event is a few hundred bytes
const MAX_BODY: usize = 64 * 1024;

/// the path the callback posts to
pub const EVENTS_PATH: &str = "/events";

/// the endpoint of one run: listens on the loopback until dropped, hands every event with the
/// token of the run to `on_event`
pub struct EventServer {
    address: SocketAddr,
    token: String,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl EventServer {
    /// listens on 127.0.0.1 on a port the system chooses, with a random token of its own
    pub fn start(on_event: impl Fn(AnsibleEvent) + Send + 'static) -> Result<Self, String> {
        let server = tiny_http::Server::http("127.0.0.1:0").map_err(|error| error.to_string())?;
        let address = server
            .server_addr()
            .to_ip()
            .ok_or_else(|| "the events endpoint has no address".to_string())?;
        let token = random_token()?;
        let stop = Arc::new(AtomicBool::new(false));
        let thread = {
            let (token, stop) = (token.clone(), stop.clone());
            std::thread::spawn(move || {
                while !stop.load(Ordering::Relaxed) {
                    match server.recv_timeout(Duration::from_millis(100)) {
                        Ok(Some(request)) => answer(request, &token, &on_event),
                        Ok(None) => {}
                        Err(_) => break,
                    }
                }
            })
        };
        Ok(EventServer {
            address,
            token,
            stop,
            thread: Some(thread),
        })
    }

    pub fn port(&self) -> u16 {
        self.address.port()
    }

    pub fn token(&self) -> &str {
        &self.token
    }
}

impl Drop for EventServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            let _ = thread.join();
        }
    }
}

/// 204 for an event taken, 404 for anything but a post to the path, 401 without the token, 400 for
/// a body that is too large or no event
fn answer(mut request: tiny_http::Request, token: &str, on_event: &impl Fn(AnsibleEvent)) {
    let status = if *request.method() != tiny_http::Method::Post || request.url() != EVENTS_PATH {
        404
    } else if !authorized(&request, token) {
        401
    } else {
        let mut body = Vec::new();
        let read = request
            .as_reader()
            .take(MAX_BODY as u64 + 1)
            .read_to_end(&mut body);
        match read
            .ok()
            .filter(|_| body.len() <= MAX_BODY)
            .and_then(|_| serde_json::from_slice::<AnsibleEvent>(&body).ok())
        {
            Some(event) => {
                on_event(event);
                204
            }
            None => 400,
        }
    };
    let _ = request.respond(tiny_http::Response::empty(status));
}

/// whether the request carries `Bearer <token>`, compared in constant time
fn authorized(request: &tiny_http::Request, token: &str) -> bool {
    let expected = format!("Bearer {token}");
    request
        .headers()
        .iter()
        .find(|header| header.field.equiv("Authorization"))
        .is_some_and(|header| {
            constant_time_eq(header.value.as_str().as_bytes(), expected.as_bytes())
        })
}

fn constant_time_eq(a: &[u8], b: &[u8]) -> bool {
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |diff, (x, y)| diff | (x ^ y)) == 0
}

/// 32 random bytes as hex
fn random_token() -> Result<String, String> {
    let mut bytes = [0u8; 32];
    getrandom::fill(&mut bytes).map_err(|error| error.to_string())?;
    Ok(bytes.iter().map(|byte| format!("{byte:02x}")).collect())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    fn started() -> (EventServer, Arc<Mutex<Vec<AnsibleEvent>>>) {
        let events = Arc::new(Mutex::new(Vec::new()));
        let received = events.clone();
        let server = EventServer::start(move |event| received.lock().unwrap().push(event)).unwrap();
        (server, events)
    }

    async fn post(server: &EventServer, path: &str, token: Option<&str>, body: String) -> u16 {
        let mut request = reqwest::Client::new()
            .post(format!("http://127.0.0.1:{}{path}", server.port()))
            .body(body);
        if let Some(token) = token {
            request = request.bearer_auth(token);
        }
        request.send().await.unwrap().status().as_u16()
    }

    #[tokio::test]
    async fn events_with_the_token_of_the_run_are_taken_in_order() {
        let (server, events) = started();
        let token = server.token().to_string();
        for body in [
            r#"{"event":"task_start","task":"Install the backend"}"#,
            r#"{"event":"ok","host":"alpha","task":"Install the backend","changed":true}"#,
            r#"{"event":"failed","host":"alpha","task":"Start the backend","msg":"no","ignore_errors":false}"#,
            r#"{"event":"stats","hosts":{"alpha":{"ok":3,"changed":1,"failures":1}}}"#,
        ] {
            assert_eq!(
                post(&server, EVENTS_PATH, Some(&token), body.to_string()).await,
                204
            );
        }

        let events = events.lock().unwrap().clone();
        assert_eq!(
            events[1],
            AnsibleEvent::Ok {
                host: "alpha".into(),
                task: "Install the backend".into(),
                changed: true
            }
        );
        assert!(matches!(&events[2], AnsibleEvent::Failed { msg: Some(msg), .. } if msg == "no"));
        assert!(matches!(&events[3], AnsibleEvent::Stats { hosts } if hosts["alpha"].changed == 1));
    }

    #[tokio::test]
    async fn anything_else_is_refused_and_handed_on_nowhere() {
        let (server, events) = started();
        let token = server.token().to_string();
        let event = r#"{"event":"task_start","task":"x"}"#.to_string();

        assert_eq!(post(&server, EVENTS_PATH, None, event.clone()).await, 401);
        assert_eq!(
            post(&server, EVENTS_PATH, Some("another"), event.clone()).await,
            401
        );
        assert_eq!(
            post(&server, "/other", Some(&token), event.clone()).await,
            404
        );
        assert_eq!(
            post(&server, EVENTS_PATH, Some(&token), "no event".into()).await,
            400
        );
        assert_eq!(
            post(&server, EVENTS_PATH, Some(&token), "x".repeat(MAX_BODY + 1)).await,
            400
        );
        assert!(events.lock().unwrap().is_empty());
        // a token of its own for every run
        assert_ne!(started().0.token(), token);
    }
}
