//! the sign-in of a desktop app at a SAK instance through the browser (SECURITY-3): the app
//! listens on `127.0.0.1` for one request, opens `<address>/connect-desktop` with its port, a
//! random state and the pkce challenge of a verifier only it knows. The web app signs the user in,
//! asks to confirm and sends the browser to `http://127.0.0.1:<port>/callback` with a connect
//! token, which the app exchanges with the verifier for a session. The session is kept in memory
//! only, a sign-out forgets it

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use url::{Host, Url};

use crate::secrets::{NoSecrets, SecretStore};

/// how long a sign-in waits for the browser: long enough for a login link by e-mail
pub const LOGIN_TIMEOUT: Duration = Duration::from_secs(15 * 60);

/// how long a request to the instance may take
const REQUEST_TIMEOUT: Duration = Duration::from_secs(15);

/// how often the wait for the browser looks whether it was cancelled
const POLL: Duration = Duration::from_millis(200);

/// the address of a SAK instance, the origin of its web app: https, or http on the loopback for
/// the development
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Address(Url);

impl Address {
    /// `sak.example.org` and `https://sak.example.org/notes` are `https://sak.example.org/`; a
    /// login in it is refused
    pub fn parse(text: &str) -> Result<Address, LoginError> {
        // http only in a build of the development, as the debug build of the android apps
        Address::parse_with(text, cfg!(debug_assertions))
    }

    /// `parse`, with http on the loopback (e.g. `localhost:4200` of local:run) only when `http`
    pub fn parse_with(text: &str, http: bool) -> Result<Address, LoginError> {
        let text = text.trim();
        if text.is_empty() {
            return Err(LoginError::InvalidAddress);
        }
        // without a scheme https, http for the loopback (e.g. localhost:4200 of local:run)
        let text = if text.contains("://") {
            text.to_string()
        } else if http
            && ["localhost", "127.0.0.1", "[::1]"].iter().any(|host| {
                text == *host
                    || text.starts_with(&format!("{host}:"))
                    || text.starts_with(&format!("{host}/"))
            })
        {
            format!("http://{text}")
        } else {
            format!("https://{text}")
        };
        let mut url = Url::parse(&text).map_err(|_| LoginError::InvalidAddress)?;
        let loopback = match url.host() {
            Some(Host::Domain(domain)) => domain == "localhost",
            Some(Host::Ipv4(ip)) => ip.is_loopback(),
            Some(Host::Ipv6(ip)) => ip.is_loopback(),
            None => return Err(LoginError::InvalidAddress),
        };
        if !secure(&url, http && loopback) || !url.username().is_empty() || url.password().is_some()
        {
            return Err(LoginError::InvalidAddress);
        }
        url.set_path("/");
        url.set_query(None);
        url.set_fragment(None);
        Ok(Address(url))
    }

    /// an address of the development, which `parse` takes on the loopback only; its api may be
    /// http too
    fn http(&self) -> bool {
        self.0.scheme() == "http"
    }

    /// `https://sak.example.org/`
    pub fn as_str(&self) -> &str {
        self.0.as_str()
    }

    fn join(&self, path: &str) -> Url {
        self.0.join(path).expect("a path below the address")
    }
}

/// https, or http where the development may have it
fn secure(url: &Url, http: bool) -> bool {
    url.scheme() == "https" || (url.scheme() == "http" && http)
}

/// who is signed in, as `GET <api>/auth/who-am-i` answers: `admin` for the admin password, else
/// the e-mail address, and the name an admin gave the user
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub username: String,
    pub name: Option<String>,
    pub expires_at: Option<String>,
    pub roles: Vec<String>,
}

/// why a sign-in did not end with a session
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum LoginError {
    /// the address is no address of a SAK instance (`Address::parse`)
    InvalidAddress,
    /// no SAK instance answers at the address: it names no api (SERVER-6)
    NoInstance,
    /// the app could not listen on the loopback
    Listener(String),
    /// the browser did not open
    Browser(String),
    /// the user cancelled, in the app or in the browser
    Cancelled,
    /// the browser did not come back within the timeout
    TimedOut,
    /// the instance refused the token: expired, used, or the user may no longer sign in
    Refused,
    /// only programs of other users of the machine asked, e.g. a proxy of the system: the browser
    /// of the user never reached the app
    OtherUser,
    /// the start page was asked for twice: another program may have the state, the sign-in ends
    Interfered,
    /// the instance did not answer as expected
    Failed(String),
}

impl LoginError {
    /// the name of the error, e.g. the last part of the key of its text
    pub fn kind(&self) -> &'static str {
        match self {
            LoginError::InvalidAddress => "invalid_address",
            LoginError::NoInstance => "no_instance",
            LoginError::Listener(_) => "listener",
            LoginError::Browser(_) => "browser",
            LoginError::Cancelled => "cancelled",
            LoginError::TimedOut => "timed_out",
            LoginError::Refused => "refused",
            LoginError::Interfered => "interfered",
            LoginError::OtherUser => "other_user",
            LoginError::Failed(_) => "failed",
        }
    }

    /// what the system said, for the errors that have it
    pub fn detail(&self) -> Option<&str> {
        match self {
            LoginError::Listener(detail)
            | LoginError::Browser(detail)
            | LoginError::Failed(detail) => Some(detail),
            _ => None,
        }
    }
}

/// why a session kept in the keychain was not taken up again
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum RestoreError {
    /// the instance refused it: it expired, or the user may no longer sign in; it is forgotten
    Expired,
    /// the instance did not answer; the session stays for the next start
    Unreachable,
    /// the address names another api than at the sign-in; the session is not sent to it
    Changed,
}

impl RestoreError {
    /// the name of the error, e.g. the last part of the key of its text
    pub fn kind(&self) -> &'static str {
        match self {
            RestoreError::Expired => "expired",
            RestoreError::Unreachable => "unreachable",
            RestoreError::Changed => "changed",
        }
    }
}

/// what the browser shows when it came back to the app, in the language of the app
#[derive(Clone, Debug)]
pub struct Pages {
    /// the title of the tab and what it says after the sign-in
    pub signed_in: (String, String),
    /// the same after the user cancelled in the browser
    pub cancelled: (String, String),
    /// the same when the start page was asked for a second time
    pub interfered: (String, String),
}

/// a session at a SAK instance; no Debug, it holds the session token
struct Session {
    address: Address,
    /// the api the address names (SERVER-6), with a trailing slash
    api: Url,
    token: String,
    user: User,
}

/// the session of the desktop app at its SAK instance: the one place it is started, kept and
/// forgotten. Its token is kept in `secrets` too, by the address of the instance, so a start of
/// the app takes it up again (`restore`); a store that fails leaves it in memory only
pub struct SessionManager {
    session: Mutex<Option<Session>>,
    /// the cancel flag of the sign-in that waits for the browser
    pending: Mutex<Option<Arc<AtomicBool>>>,
    timeout: Duration,
    client: reqwest::Client,
    secrets: Arc<dyn SecretStore>,
    /// whether the url the browser is started with has a secret (`START_SECRET`)
    start_secret: bool,
}

/// the url the browser is started with has a secret of its sign-in off linux. The url is an
/// argument of a process there (`/usr/bin/open` on macOS, the browser on windows), and both show the
/// arguments of a process to its user and an admin only, so no program of another user of the
/// machine gets the state or ends the sign-in (SECURITY-3). On linux every user reads the arguments
/// of a process (/proc/<pid>/cmdline): the url has none, and the app takes the connections of its own
/// user only (`peer_user`)
const START_SECRET: bool = !cfg!(target_os = "linux");

impl Default for SessionManager {
    fn default() -> Self {
        SessionManager::new(Arc::new(NoSecrets), LOGIN_TIMEOUT)
    }
}

impl SessionManager {
    pub fn new(secrets: Arc<dyn SecretStore>, timeout: Duration) -> Self {
        SessionManager {
            session: Mutex::new(None),
            pending: Mutex::new(None),
            timeout,
            client: reqwest::Client::builder()
                .timeout(REQUEST_TIMEOUT)
                // an answer that leads elsewhere is no answer: nothing of a sign-in follows it
                .redirect(reqwest::redirect::Policy::none())
                .build()
                .expect("a client of reqwest"),
            secrets,
            start_secret: START_SECRET,
        }
    }

    pub fn with_timeout(timeout: Duration) -> Self {
        SessionManager::new(Arc::new(NoSecrets), timeout)
    }

    /// a sign-in with or without the secret in the url of the browser, whatever the system
    #[cfg(test)]
    fn with_start_secret(self, start_secret: bool) -> Self {
        SessionManager {
            start_secret,
            ..self
        }
    }

    /// takes up the session the keychain has of `address`, checked with the instance: none when
    /// the keychain has none or fails. `api` is the api of the sign-in (`session_api`): the token
    /// goes to this api only, never to another one the address names since, and nowhere without it
    pub async fn restore(
        &self,
        address: Address,
        api: Option<String>,
    ) -> Result<Option<User>, RestoreError> {
        let Some(known) = api else {
            return Ok(None);
        };
        let Some(token) = self
            .secret(&address, |secrets, address| secrets.get(address))
            .await
            .flatten()
        else {
            return Ok(None);
        };
        let api = self
            .api(&address)
            .await
            .map_err(|_| RestoreError::Unreachable)?;
        if api.as_str() != known {
            return Err(RestoreError::Changed);
        }
        match self.take_up(address.clone(), api, token).await {
            Err(RestoreError::Expired) => {
                self.forget(&address).await;
                Err(RestoreError::Expired)
            }
            taken => taken.map(Some),
        }
    }

    /// takes up the session of `token` at `address`, e.g. one a script was given (`sakbackup` with
    /// SAK_BACKUP_SESSION_TOKEN): checked with the instance, never kept in the keychain
    pub async fn use_token(&self, address: Address, token: String) -> Result<User, RestoreError> {
        let api = self
            .api(&address)
            .await
            .map_err(|_| RestoreError::Unreachable)?;
        self.take_up(address, api, token).await
    }

    /// the session of `token` once `who-am-i` of `api` takes it
    async fn take_up(
        &self,
        address: Address,
        api: Url,
        token: String,
    ) -> Result<User, RestoreError> {
        let response = self
            .client
            .get(
                api.join("auth/who-am-i")
                    .map_err(|_| RestoreError::Unreachable)?,
            )
            .bearer_auth(&token)
            .send()
            .await
            .map_err(unreachable)?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err(RestoreError::Expired);
        }
        let user = response
            .error_for_status()
            .map_err(unreachable)?
            .json::<User>()
            .await
            .map_err(unreachable)?;
        *self.session.lock().unwrap() = Some(Session {
            address,
            api,
            token,
            user: user.clone(),
        });
        Ok(user)
    }

    /// removes the session the keychain has of `address`, e.g. of an instance no longer known
    pub async fn forget(&self, address: &Address) {
        self.secret(address, |secrets, address| secrets.delete(address))
            .await;
    }

    /// runs `operation` on the keychain away from the async threads (it may wait for d-bus); a
    /// failure is logged, never the token, and is none
    async fn secret<T: Send + 'static>(
        &self,
        address: &Address,
        operation: impl FnOnce(&dyn SecretStore, &Address) -> Result<T, String> + Send + 'static,
    ) -> Option<T> {
        let secrets = self.secrets.clone();
        let address = address.clone();
        let result = tokio::task::spawn_blocking(move || operation(secrets.as_ref(), &address))
            .await
            .map_err(|error| error.to_string())
            .and_then(|result| result);
        match result {
            Ok(value) => Some(value),
            Err(error) => {
                eprintln!("the keychain failed, the session is kept in memory only: {error}");
                None
            }
        }
    }

    /// signs in at `address` through the browser, which `open` opens on the url it is given (the
    /// start page of the app on the loopback, which leads the browser on to the instance); its
    /// second argument is the code the app shows, the page shows the same (`connect_code`). A
    /// sign-in that still waits is cancelled; the session replaces the one before
    pub async fn login(
        &self,
        address: Address,
        pages: Pages,
        open: impl FnOnce(&str, &str) -> Result<(), String>,
    ) -> Result<User, LoginError> {
        let cancel = Arc::new(AtomicBool::new(false));
        if let Some(older) = self.pending.lock().unwrap().replace(cancel.clone()) {
            older.store(true, Ordering::SeqCst);
        }
        let result = self.login_with(address, pages, open, cancel.clone()).await;
        let mut pending = self.pending.lock().unwrap();
        if pending
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, &cancel))
        {
            *pending = None;
        }
        result
    }

    async fn login_with(
        &self,
        address: Address,
        pages: Pages,
        open: impl FnOnce(&str, &str) -> Result<(), String>,
        cancel: Arc<AtomicBool>,
    ) -> Result<User, LoginError> {
        let server = tiny_http::Server::http("127.0.0.1:0")
            .map_err(|error| LoginError::Listener(error.to_string()))?;
        let port = server
            .server_addr()
            .to_ip()
            .map(|address| address.port())
            .ok_or_else(|| LoginError::Listener("no port".to_string()))?;
        // a wrong address fails before the browser opens
        let api = self.api(&address).await?;
        let verifier = random_base64::<32>()?;
        let state = random_base64::<16>()?;
        let challenge = pkce_challenge(&verifier);
        // the state is in the redirect of the first request of the start page, never in the url
        // the browser is started with; that one has a secret of its own where only the browser of
        // the user learns it (START_SECRET)
        let start = start_url(&address, port, &state, &challenge);
        let start_path = if self.start_secret {
            format!("{START_PATH}/{}", random_base64::<16>()?)
        } else {
            START_PATH.to_string()
        };
        open(
            &format!("http://127.0.0.1:{port}{start_path}"),
            &connect_code(&challenge),
        )
        .map_err(LoginError::Browser)?;

        let timeout = self.timeout;
        let token = tokio::task::spawn_blocking(move || {
            wait(
                &server,
                &start,
                &start_path,
                &state,
                &pages,
                &cancel,
                timeout,
            )
        })
        .await
        .map_err(|error| LoginError::Failed(error.to_string()))??;

        let session_token = self.exchange(&api, &token, &verifier).await?;
        let user = self.who_am_i(&api, &session_token).await?;
        let kept = session_token.clone();
        self.secret(&address, move |secrets, address| {
            secrets.set(address, &kept)
        })
        .await;
        *self.session.lock().unwrap() = Some(Session {
            address,
            api,
            token: session_token,
            user: user.clone(),
        });
        Ok(user)
    }

    /// stops the sign-in that waits for the browser
    pub fn cancel(&self) {
        if let Some(pending) = self.pending.lock().unwrap().take() {
            pending.store(true, Ordering::SeqCst);
        }
    }

    /// forgets the session, in memory and in the keychain, and stops a sign-in that waits
    pub async fn logout(&self) {
        self.cancel();
        let session = self.session.lock().unwrap().take();
        if let Some(session) = session {
            self.forget(&session.address).await;
        }
    }

    /// who is signed in, none before a sign-in and after the sign-out
    pub fn user(&self) -> Option<User> {
        self.session
            .lock()
            .unwrap()
            .as_ref()
            .map(|session| session.user.clone())
    }

    /// the api of the session, as the address named it at the sign-in; `restore` takes it
    pub fn session_api(&self) -> Option<String> {
        self.session
            .lock()
            .unwrap()
            .as_ref()
            .map(|session| session.api.to_string())
    }

    /// the address of the instance of the session
    pub fn address(&self) -> Option<Address> {
        self.session
            .lock()
            .unwrap()
            .as_ref()
            .map(|session| session.address.clone())
    }

    /// a request of the session to `path` below the api (e.g. `auth/who-am-i`), with its session
    /// token
    pub fn request(&self, method: reqwest::Method, path: &str) -> Option<reqwest::RequestBuilder> {
        let session = self.session.lock().unwrap();
        let session = session.as_ref()?;
        Some(
            self.client
                .request(method, session.api.join(path).ok()?)
                .bearer_auth(&session.token),
        )
    }

    /// the api of the address, from its `/.well-known/sak.json` (SERVER-6); https only, http too
    /// for an address on the loopback
    async fn api(&self, address: &Address) -> Result<Url, LoginError> {
        #[derive(Deserialize)]
        struct WellKnown {
            api: String,
        }
        let announced = self
            .client
            .get(address.join(".well-known/sak.json"))
            .send()
            .await
            .and_then(|response| response.error_for_status())
            .map_err(no_instance)?
            .json::<WellKnown>()
            .await
            .map_err(no_instance)?;
        let mut api = Url::parse(announced.api.trim()).map_err(|_| LoginError::NoInstance)?;
        if !secure(&api, address.http()) || api.host().is_none() {
            return Err(LoginError::NoInstance);
        }
        if !api.path().ends_with('/') {
            let path = format!("{}/", api.path());
            api.set_path(&path);
        }
        Ok(api)
    }

    async fn exchange(&self, api: &Url, token: &str, verifier: &str) -> Result<String, LoginError> {
        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct ExchangeResponse {
            session_token: String,
        }
        let response = self
            .client
            .post(
                api.join("auth/login/connect")
                    .map_err(|error| LoginError::Failed(error.to_string()))?,
            )
            .json(&serde_json::json!({ "token": token, "verifier": verifier }))
            .send()
            .await
            .map_err(failed)?;
        if response.status() == reqwest::StatusCode::UNAUTHORIZED {
            return Err(LoginError::Refused);
        }
        let response = response.error_for_status().map_err(failed)?;
        Ok(response
            .json::<ExchangeResponse>()
            .await
            .map_err(failed)?
            .session_token)
    }

    async fn who_am_i(&self, api: &Url, session_token: &str) -> Result<User, LoginError> {
        self.client
            .get(
                api.join("auth/who-am-i")
                    .map_err(|error| LoginError::Failed(error.to_string()))?,
            )
            .bearer_auth(session_token)
            .send()
            .await
            .and_then(|response| response.error_for_status())
            .map_err(failed)?
            .json::<User>()
            .await
            .map_err(failed)
    }
}

fn failed(error: reqwest::Error) -> LoginError {
    // with its causes; without the url, it names only the address the user typed anyway
    LoginError::Failed(crate::errors::with_causes(&error.without_url()))
}

/// the instance did not answer the sign-in kept: the user reads that it is not reached, the cause
/// is a line of the standard error, for who looks for it
fn unreachable(error: reqwest::Error) -> RestoreError {
    eprintln!("{}", crate::errors::with_causes(&error.without_url()));
    RestoreError::Unreachable
}

/// nothing at the address answers as a SAK instance, the cause as for `unreachable`
fn no_instance(error: reqwest::Error) -> LoginError {
    eprintln!("{}", crate::errors::with_causes(&error.without_url()));
    LoginError::NoInstance
}

/// the page of the web app that asks the user to connect the app
fn start_url(address: &Address, port: u16, state: &str, challenge: &str) -> Url {
    let mut url = address.join("connect-desktop");
    url.query_pairs_mut()
        .append_pair("port", &port.to_string())
        .append_pair("state", state)
        .append_pair("challenge", challenge);
    url
}

/// pkce S256 (rfc 7636): the sha-256 of the verifier as base64url without padding
pub fn pkce_challenge(verifier: &str) -> String {
    URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()))
}

/// the code the app and the page `/connect-desktop` both show, so the user sees that the page
/// asks for this app and not for another program (SEC-31): 6 characters of the sha-256 of the
/// challenge, none that reads as another (no 0, O, 1, I), as `ABC-DEF`. The page computes the same
pub fn connect_code(challenge: &str) -> String {
    const ALPHABET: &[u8] = b"ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    let digest = Sha256::digest(challenge.as_bytes());
    let code: String = digest[..6]
        .iter()
        .map(|byte| ALPHABET[usize::from(byte & 31)] as char)
        .collect();
    format!("{}-{}", &code[..3], &code[3..])
}

/// N random bytes as base64url; 32 bytes are a verifier of 43 characters
fn random_base64<const N: usize>() -> Result<String, LoginError> {
    let mut bytes = [0u8; N];
    getrandom::fill(&mut bytes).map_err(|error| LoginError::Listener(error.to_string()))?;
    Ok(URL_SAFE_NO_PAD.encode(bytes))
}

/// the page of the app the browser is started with, with its secret off linux (`START_SECRET`); its
/// first request is led on to the instance
const START_PATH: &str = "/start";

/// the page of the app the callback leads the browser on to, with no token in its url
const DONE_PATH: &str = "/done";

/// how long the app waits for the browser to ask for the page after the callback
const DONE_WAIT: Duration = Duration::from_secs(3);

/// what a request on the loopback is
#[derive(Debug, PartialEq, Eq)]
enum Callback {
    /// the start page
    Start,
    Token(String),
    Cancelled,
    /// anything else, e.g. the favicon or a wrong state: answered 404, the wait goes on
    Other,
}

/// the request `path`; `start` the path of the start page of this sign-in, a start page without its
/// secret is no start page
fn callback(method: &tiny_http::Method, path: &str, start: &str, state: &str) -> Callback {
    let Ok(url) = Url::parse(&format!("http://127.0.0.1{path}")) else {
        return Callback::Other;
    };
    if *method == tiny_http::Method::Get && url.path() == start && url.query().is_none() {
        return Callback::Start;
    }
    if *method != tiny_http::Method::Get || url.path() != "/callback" {
        return Callback::Other;
    }
    let param = |name: &str| {
        url.query_pairs()
            .find(|(key, _)| key == name)
            .map(|(_, value)| value.to_string())
    };
    if param("state").as_deref() != Some(state) {
        return Callback::Other;
    }
    match (param("token"), param("error")) {
        (Some(token), _) if !token.is_empty() => Callback::Token(token),
        (_, Some(error)) if error == "cancelled" => Callback::Cancelled,
        _ => Callback::Other,
    }
}

/// leads the first request of the start page (`start_path`) on to `start`, the page of the
/// instance, then waits for the browser to come back with the token of `state`. A second request
/// of the start page ends the sign-in: whoever asked first has the state, and only one of the two
/// is the browser of the user (SEC-33). Off linux only the browser of the user knows the start page
/// with its secret: any other request is answered 404 and ends nothing
fn wait(
    server: &tiny_http::Server,
    start: &Url,
    start_path: &str,
    state: &str,
    pages: &Pages,
    cancel: &AtomicBool,
    timeout: Duration,
) -> Result<String, LoginError> {
    let deadline = Instant::now() + timeout;
    let port = server.server_addr().to_ip().map(|address| address.port());
    let mut started = false;
    // a request of another user of the machine was refused
    let mut strangers = false;
    loop {
        if cancel.load(Ordering::SeqCst) {
            return Err(LoginError::Cancelled);
        }
        let left = deadline.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(if strangers {
                LoginError::OtherUser
            } else {
                LoginError::TimedOut
            });
        }
        let Some(request) = server
            .recv_timeout(left.min(POLL))
            .map_err(|error| LoginError::Listener(error.to_string()))?
        else {
            continue;
        };
        // the loopback is open to every user of the machine: only the user of the app counts, so
        // nobody else gets the state or ends the sign-in (SECURITY-3)
        let peer = request.remote_addr().map(|address| address.port());
        if let (Some(peer), Some(port)) = (peer, port) {
            if peer_user(peer, port) == PeerUser::Other {
                strangers = true;
                respond(request, 404, None);
                continue;
            }
        }
        match callback(request.method(), request.url(), start_path, state) {
            Callback::Start if !started => {
                started = true;
                redirect(request, start);
            }
            Callback::Start => {
                respond(request, 409, Some(&pages.interfered));
                return Err(LoginError::Interfered);
            }
            // nobody has the state before the start page gave it out
            Callback::Token(_) | Callback::Cancelled if !started => respond(request, 404, None),
            Callback::Token(token) => {
                // the page the browser stays on has no token in its url (SEC-37)
                redirect_to(request, DONE_PATH);
                serve_done(server, pages);
                return Ok(token);
            }
            Callback::Cancelled => {
                respond(request, 200, Some(&pages.cancelled));
                return Err(LoginError::Cancelled);
            }
            Callback::Other => respond(request, 404, None),
        }
    }
}

fn respond(request: tiny_http::Request, status: u16, page: Option<&(String, String)>) {
    let body = page
        .map(|(title, text)| html(title, text))
        .unwrap_or_default();
    let response = tiny_http::Response::from_string(body)
        .with_status_code(status)
        .with_header(header("Content-Type", "text/html; charset=utf-8"))
        .with_header(header("Cache-Control", "no-store"))
        .with_header(header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'",
        ))
        .with_header(header("Referrer-Policy", "no-referrer"));
    let _ = request.respond(response);
}

/// whose connection a request on the loopback came on
#[derive(Debug, PartialEq, Eq)]
enum PeerUser {
    /// the user of the app, or a system that does not tell (not linux, no /proc)
    Same,
    /// another user of the machine, or a connection that is gone already
    Other,
}

/// the user at the other end of the connection from the port `peer` to the port `port` of the
/// app, both on the loopback. linux tells the user of every tcp socket in /proc/net/tcp; macOS
/// and windows have nothing as simple, there every request counts
#[cfg(target_os = "linux")]
fn peer_user(peer: u16, port: u16) -> PeerUser {
    use std::os::unix::fs::MetadataExt;
    let (Ok(table), Ok(own)) = (
        std::fs::read_to_string("/proc/net/tcp"),
        std::fs::metadata("/proc/self").map(|process| process.uid()),
    ) else {
        return PeerUser::Same;
    };
    match socket_user(&table, peer, port) {
        Some(user) if user == own => PeerUser::Same,
        _ => PeerUser::Other,
    }
}

#[cfg(not(target_os = "linux"))]
fn peer_user(_: u16, _: u16) -> PeerUser {
    PeerUser::Same
}

/// the uid of the socket in `table` (/proc/net/tcp) that is connected from the local port `from`
/// to the remote port `to` on the loopback: the end of the client, not the one the app accepted
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn socket_user(table: &str, from: u16, to: u16) -> Option<u32> {
    const LOOPBACK: &str = "0100007F";
    let (local, remote) = (
        format!("{LOOPBACK}:{from:04X}"),
        format!("{LOOPBACK}:{to:04X}"),
    );
    table.lines().skip(1).find_map(|line| {
        let fields: Vec<&str> = line.split_whitespace().collect();
        (fields.get(1) == Some(&local.as_str()) && fields.get(2) == Some(&remote.as_str()))
            .then(|| fields.get(7)?.parse().ok())
            .flatten()
    })
}

/// answers the request of the browser that follows the callback with the page that says it is
/// signed in; a browser that does not come within DONE_WAIT gets none, the sign-in goes on
fn serve_done(server: &tiny_http::Server, pages: &Pages) {
    let deadline = Instant::now() + DONE_WAIT;
    loop {
        let left = deadline.saturating_duration_since(Instant::now());
        let Ok(Some(request)) = server.recv_timeout(left) else {
            return;
        };
        if *request.method() == tiny_http::Method::Get && request.url() == DONE_PATH {
            respond(request, 200, Some(&pages.signed_in));
            return;
        }
        respond(request, 404, None);
    }
}

fn redirect(request: tiny_http::Request, to: &Url) {
    redirect_to(request, to.as_str())
}

fn redirect_to(request: tiny_http::Request, to: &str) {
    let response = tiny_http::Response::empty(302)
        .with_header(header("Location", to))
        .with_header(header("Cache-Control", "no-store"))
        .with_header(header("Referrer-Policy", "no-referrer"));
    let _ = request.respond(response);
}

fn header(name: &str, value: &str) -> tiny_http::Header {
    tiny_http::Header::from_bytes(name.as_bytes(), value.as_bytes()).expect("a valid header")
}

/// the page the browser shows on the loopback: nothing loaded from elsewhere
fn html(title: &str, text: &str) -> String {
    let (title, text) = (escape(title), escape(text));
    format!(
        "<!doctype html><html><head><meta charset=\"utf-8\"><title>{title}</title>\
         <style>body{{font-family:system-ui,sans-serif;margin:4rem auto;max-width:32rem;padding:0 1rem}}</style>\
         </head><body><h1>{title}</h1><p>{text}</p></body></html>"
    )
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use wiremock::matchers::{header as has_header, method, path};
    use wiremock::{Mock, MockServer, ResponseTemplate};

    fn pages() -> Pages {
        Pages {
            signed_in: ("Signed in".to_string(), "Close <this> tab.".to_string()),
            cancelled: ("Cancelled".to_string(), "Nothing happened.".to_string()),
            interfered: ("Stopped".to_string(), "Opened twice.".to_string()),
        }
    }

    /// what the browser does: a GET of `url` on the loopback, the answer as text
    fn browse(url: &str) -> String {
        let url = Url::parse(url).unwrap();
        let mut stream = std::net::TcpStream::connect(("127.0.0.1", url.port().unwrap())).unwrap();
        let target = match url.query() {
            Some(query) => format!("{}?{query}", url.path()),
            None => url.path().to_string(),
        };
        write!(
            stream,
            "GET {target} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
        )
        .unwrap();
        let mut answer = String::new();
        stream.read_to_string(&mut answer).unwrap();
        answer
    }

    /// where the start page of the app leads the browser, the page of the instance
    fn started(url: &str) -> String {
        // nothing of the sign-in is in the url the browser is started with, off linux a secret of
        // its own in its path
        let path = Url::parse(url).unwrap().path().to_string();
        let secret = path.strip_prefix("/start/").unwrap_or_default();
        assert!(
            !url.contains('?') && (path == "/start" || secret.len() == 22),
            "{url}"
        );
        let answer = browse(url);
        assert!(answer.starts_with("HTTP/1.1 302"), "{answer}");
        answer
            .lines()
            .find_map(|line| line.strip_prefix("Location: "))
            .unwrap()
            .trim()
            .to_string()
    }

    /// the parameters of the page the start page of the app leads to
    fn opened(url: &str) -> (u16, String, String) {
        let url = Url::parse(&started(url)).unwrap();
        let param = |name: &str| {
            url.query_pairs()
                .find(|(key, _)| key == name)
                .unwrap()
                .1
                .to_string()
        };
        (
            param("port").parse().unwrap(),
            param("state"),
            param("challenge"),
        )
    }

    async fn instance(exchange: ResponseTemplate) -> MockServer {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/.well-known/sak.json"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(
                    serde_json::json!({ "api": format!("{}/api/v1", server.uri()) }),
                ),
            )
            .mount(&server)
            .await;
        Mock::given(method("POST"))
            .and(path("/api/v1/auth/login/connect"))
            .respond_with(exchange)
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/v1/auth/who-am-i"))
            .and(has_header("authorization", "Bearer session-token"))
            .respond_with(ResponseTemplate::new(200).set_body_json(serde_json::json!({
                "username": "anna@example.org",
                "name": "Anna",
                "expiresAt": "2026-12-20T08:00:00.000Z",
                "roles": ["user"],
            })))
            .mount(&server)
            .await;
        server
    }

    fn session_token() -> ResponseTemplate {
        ResponseTemplate::new(200).set_body_json(serde_json::json!({
            "sessionToken": "session-token",
            "email": "anna@example.org",
            "expiresAt": "2026-12-20T08:00:00.000Z",
            "roles": ["user"],
            "redirect": "/",
        }))
    }

    // http, as a build of the development takes it
    fn address(server: &MockServer) -> Address {
        Address::parse_with(&server.uri(), true).unwrap()
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_browser_hands_the_token_back_and_the_app_exchanges_it_with_its_verifier() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default();
        let browser = Arc::new(Mutex::new(None));
        let seen = browser.clone();
        let user = manager
            .login(address(&server), pages(), |url, code| {
                let (url, code) = (url.to_string(), code.to_string());
                std::thread::spawn(move || {
                    let (port, state, challenge) = opened(&url);
                    assert_eq!(challenge.len(), 43);
                    assert_eq!(code, connect_code(&challenge));
                    *seen.lock().unwrap() = Some(challenge);
                    // a request that is not the callback, and one of another state, change nothing
                    assert!(
                        browse(&format!("http://127.0.0.1:{port}/favicon.ico"))
                            .starts_with("HTTP/1.1 404")
                    );
                    let other =
                        format!("http://127.0.0.1:{port}/callback?state=other&token=stolen");
                    assert!(browse(&other).starts_with("HTTP/1.1 404"));
                    let callback = browse(&format!(
                        "http://127.0.0.1:{port}/callback?state={state}&token=connect-token"
                    ));
                    // the browser is led on to a page without the token in its url
                    assert!(callback.starts_with("HTTP/1.1 302"), "{callback}");
                    assert!(callback.contains("Location: /done\r\n"), "{callback}");
                    let page = browse(&format!("http://127.0.0.1:{port}/done"));
                    assert!(page.starts_with("HTTP/1.1 200"), "{page}");
                    assert!(
                        page.contains("<h1>Signed in</h1><p>Close &lt;this&gt; tab.</p>"),
                        "{page}"
                    );
                });
                Ok(())
            })
            .await
            .unwrap();
        assert_eq!(user.username, "anna@example.org");
        assert_eq!(user.name.as_deref(), Some("Anna"));
        assert_eq!(manager.user(), Some(user));

        let requests: Vec<_> = server
            .received_requests()
            .await
            .unwrap()
            .into_iter()
            .filter(|request| request.url.path() != "/.well-known/sak.json")
            .collect();
        let exchange: serde_json::Value = requests
            .iter()
            .find(|request| request.url.path() == "/api/v1/auth/login/connect")
            .unwrap()
            .body_json()
            .unwrap();
        assert_eq!(exchange["token"], "connect-token");
        let verifier = exchange["verifier"].as_str().unwrap();
        assert_eq!(
            Some(pkce_challenge(verifier)),
            browser.lock().unwrap().clone()
        );

        let request = manager
            .request(reqwest::Method::GET, "auth/who-am-i")
            .unwrap()
            .build()
            .unwrap();
        assert_eq!(
            request.url().as_str(),
            format!("{}/api/v1/auth/who-am-i", server.uri())
        );
        assert_eq!(request.headers()["authorization"], "Bearer session-token");
        manager.logout().await;
        assert_eq!(manager.user(), None);
        assert!(
            manager
                .request(reqwest::Method::GET, "auth/who-am-i")
                .is_none()
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_user_cancels_in_the_browser() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default();
        let result = manager
            .login(address(&server), pages(), |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    let (port, state, _) = opened(&url);
                    let page = browse(&format!(
                        "http://127.0.0.1:{port}/callback?state={state}&error=cancelled"
                    ));
                    assert!(page.contains("<h1>Cancelled</h1>"), "{page}");
                });
                Ok(())
            })
            .await;
        assert_eq!(result, Err(LoginError::Cancelled));
        // only the api was looked up
        assert_eq!(server.received_requests().await.unwrap().len(), 1);
        assert_eq!(manager.user(), None);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_user_cancels_in_the_app() {
        let server = instance(session_token()).await;
        let manager = Arc::new(SessionManager::default());
        let waiting = manager.clone();
        let login = tokio::spawn(async move {
            waiting
                .login(address(&server), pages(), |_, _| Ok(()))
                .await
        });
        tokio::time::sleep(Duration::from_millis(100)).await;
        manager.cancel();
        assert_eq!(login.await.unwrap(), Err(LoginError::Cancelled));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_browser_that_does_not_come_back_times_out() {
        let server = instance(session_token()).await;
        let manager = SessionManager::with_timeout(Duration::from_millis(300));
        let result = manager
            .login(address(&server), pages(), |_, _| Ok(()))
            .await;
        assert_eq!(result, Err(LoginError::TimedOut));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_browser_that_does_not_open_ends_the_sign_in() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default();
        let result = manager
            .login(address(&server), pages(), |_, _| {
                Err("no browser".to_string())
            })
            .await;
        assert_eq!(result, Err(LoginError::Browser("no browser".to_string())));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_refused_token_gives_no_session() {
        let server = instance(ResponseTemplate::new(401)).await;
        let manager = SessionManager::default();
        let result = manager
            .login(address(&server), pages(), |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    let (port, state, _) = opened(&url);
                    browse(&format!(
                        "http://127.0.0.1:{port}/callback?state={state}&token=t"
                    ));
                    browse(&format!("http://127.0.0.1:{port}/done"))
                });
                Ok(())
            })
            .await;
        assert_eq!(result, Err(LoginError::Refused));
        assert_eq!(manager.user(), None);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn an_address_without_an_api_or_with_an_insecure_one_opens_no_browser() {
        let manager = SessionManager::default();
        let empty = MockServer::start().await;
        let opened = |_: &str, _: &str| -> Result<(), String> { panic!("the browser opened") };
        assert_eq!(
            manager.login(address(&empty), pages(), opened).await,
            Err(LoginError::NoInstance)
        );

        // http is only good for an address on the loopback
        let insecure = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/.well-known/sak.json"))
            .respond_with(
                ResponseTemplate::new(200)
                    .set_body_json(serde_json::json!({ "api": "ftp://api.example.org/v1" })),
            )
            .mount(&insecure)
            .await;
        assert_eq!(
            manager.login(address(&insecure), pages(), opened).await,
            Err(LoginError::NoInstance)
        );
    }

    #[test]
    fn an_address_is_the_origin_of_a_sak_instance() {
        assert_eq!(
            Address::parse_with(" sak.example.org ", true)
                .unwrap()
                .as_str(),
            "https://sak.example.org/"
        );
        assert_eq!(
            Address::parse_with("https://sak.example.org/", true)
                .unwrap()
                .as_str(),
            "https://sak.example.org/"
        );
        assert_eq!(
            Address::parse_with("https://sak.example.org/notes?a=b#c", true)
                .unwrap()
                .as_str(),
            "https://sak.example.org/"
        );
        assert_eq!(
            Address::parse_with("localhost:4200", true)
                .unwrap()
                .as_str(),
            "http://localhost:4200/"
        );
        assert_eq!(
            Address::parse_with("http://localhost:4200", true)
                .unwrap()
                .as_str(),
            "http://localhost:4200/"
        );
        assert_eq!(
            Address::parse_with("http://127.0.0.1:8080", true)
                .unwrap()
                .as_str(),
            "http://127.0.0.1:8080/"
        );
        for refused in [
            "",
            "http://sak.example.org",
            "ftp://sak.example.org",
            "https://anna@sak.example.org",
            "https://",
        ] {
            assert_eq!(
                Address::parse_with(refused, true),
                Err(LoginError::InvalidAddress),
                "{refused}"
            );
        }
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_start_page_leads_to_the_instance_once_and_a_second_request_ends_the_sign_in() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default();
        let instance_address = address(&server);
        let result = manager
            .login(address(&server), pages(), move |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    // another program asks first and has the state
                    let first = started(&url);
                    assert!(first.starts_with(&format!(
                        "{}connect-desktop?port=",
                        instance_address.as_str()
                    )));
                    // the browser of the user is the second: it is told, the sign-in ends
                    let second = browse(&url);
                    assert!(second.starts_with("HTTP/1.1 409"), "{second}");
                    assert!(second.contains("<h1>Stopped</h1>"), "{second}");
                });
                Ok(())
            })
            .await;
        assert_eq!(result, Err(LoginError::Interfered));
        assert_eq!(manager.user(), None);
    }

    #[test]
    fn the_start_page_with_a_secret_is_the_only_start_page() {
        let get = tiny_http::Method::Get;
        let start = "/start/c2VjcmV0LW9mLXNpZ24taW4";
        assert_eq!(callback(&get, start, start, "s"), Callback::Start);
        // without its secret, with another one, or with more after it: no start page
        for other in [
            "/start",
            "/start/",
            "/start/c2VjcmV0LW9mLXNpZ24taW5",
            "/start/c2VjcmV0LW9mLXNpZ24taW4/x",
        ] {
            assert_eq!(
                callback(&get, other, start, "s"),
                Callback::Other,
                "{other}"
            );
        }
        assert_eq!(
            callback(&get, &format!("{start}?x=1"), start, "s"),
            Callback::Other
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn with_a_secret_another_program_neither_learns_the_state_nor_ends_the_sign_in() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default().with_start_secret(true);
        let result = manager
            .login(address(&server), pages(), move |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    // another program of the machine tries the start page without the secret
                    let port = Url::parse(&url).unwrap().port().unwrap();
                    for other in ["/start", "/start/guessed"] {
                        let answer = browse(&format!("http://127.0.0.1:{port}{other}"));
                        assert!(answer.starts_with("HTTP/1.1 404"), "{answer}");
                        assert!(!answer.contains("state="), "{answer}");
                    }
                    // the browser of the user with the url it was started with signs in
                    let (port, state, _) = opened(&url);
                    browse(&format!(
                        "http://127.0.0.1:{port}/callback?state={state}&token=t"
                    ));
                    browse(&format!("http://127.0.0.1:{port}/done"))
                });
                Ok(())
            })
            .await;
        assert!(result.is_ok(), "{result:?}");
        assert!(manager.user().is_some());
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn with_a_secret_the_browser_of_the_user_asking_twice_still_ends_the_sign_in() {
        let server = instance(session_token()).await;
        let manager = SessionManager::default().with_start_secret(true);
        let result = manager
            .login(address(&server), pages(), move |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    started(&url);
                    let second = browse(&url);
                    assert!(second.starts_with("HTTP/1.1 409"), "{second}");
                });
                Ok(())
            })
            .await;
        assert_eq!(result, Err(LoginError::Interfered));
    }

    #[test]
    fn the_user_of_a_connection_is_the_one_of_the_socket_of_the_client() {
        // the browser (uid 1000) from port 54321 to the app on 36901, and the end the app accepted
        let table = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n\
   0: 0100007F:9025 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1001        0 980357 2 0 100 0 0 10 0\n\
   1: 0100007F:D431 0100007F:9025 01 00000000:00000000 00:00000000 00000000  1000        0 980360 1 0 20 4 30 10 -1\n\
   2: 0100007F:9025 0100007F:D431 01 00000000:00000000 00:00000000 00000000  1001        0 980361 1 0 20 4 30 10 -1\n\
   3: C0A80005:D431 0100007F:9025 01 00000000:00000000 00:00000000 00000000     0        0 980362 1 0 20 4 30 10 -1\n";
        assert_eq!(socket_user(table, 0xD431, 0x9025), Some(1000));
        // no such connection
        assert_eq!(socket_user(table, 0xD432, 0x9025), None);
        assert_eq!(socket_user("", 0xD431, 0x9025), None);
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn a_connection_that_is_not_there_is_not_of_the_user() {
        assert_eq!(peer_user(1, 2), PeerUser::Other);
    }

    #[test]
    fn the_code_is_the_one_the_page_computes() {
        // the same vector as connect-desktop-page.spec.ts
        assert_eq!(
            connect_code("ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA"),
            "DCF-ACE"
        );
    }

    #[test]
    fn http_is_for_the_loopback_of_a_development_build_only() {
        for address in [
            "localhost:4200",
            "http://localhost:4200",
            "http://127.0.0.1:8080",
            "http://[::1]:8080",
        ] {
            assert!(Address::parse_with(address, true).is_ok(), "{address}");
        }
        // a release: https, also on the loopback
        assert_eq!(
            Address::parse_with("http://localhost:4200", false),
            Err(LoginError::InvalidAddress)
        );
        assert_eq!(
            Address::parse_with("http://127.0.0.1:8080", false),
            Err(LoginError::InvalidAddress)
        );
        assert_eq!(
            Address::parse_with("localhost:4200", false)
                .unwrap()
                .as_str(),
            "https://localhost:4200/"
        );
        assert!(!Address::parse_with("localhost:4200", false).unwrap().http());
        // never for another host
        assert_eq!(
            Address::parse_with("http://sak.example.org", true),
            Err(LoginError::InvalidAddress)
        );
    }

    #[test]
    fn the_challenge_is_the_base64url_sha_256_of_the_verifier() {
        // the same vector as ConnectTest of sak-auth
        assert_eq!(
            pkce_challenge(&"a".repeat(43)),
            "ZtNPunH49FD35FWYhT5Tv8I7vRKQJ8uxMaL0_9eHjNA"
        );
    }

    #[test]
    fn only_a_get_of_the_callback_with_the_state_counts() {
        let get = tiny_http::Method::Get;
        assert_eq!(
            callback(&get, "/callback?state=s&token=t", "/start", "s"),
            Callback::Token("t".to_string())
        );
        assert_eq!(
            callback(&get, "/callback?state=s&error=cancelled", "/start", "s"),
            Callback::Cancelled
        );
        assert_eq!(
            callback(&get, "/callback?state=x&token=t", "/start", "s"),
            Callback::Other
        );
        assert_eq!(
            callback(&get, "/callback?token=t", "/start", "s"),
            Callback::Other
        );
        assert_eq!(callback(&get, "/start", "/start", "s"), Callback::Start);
        assert_eq!(callback(&get, "/start?x=1", "/start", "s"), Callback::Other);
        assert_eq!(
            callback(&tiny_http::Method::Post, "/start", "/start", "s"),
            Callback::Other
        );
        assert_eq!(
            callback(&get, "/callback?state=s&token=", "/start", "s"),
            Callback::Other
        );
        assert_eq!(
            callback(&get, "/other?state=s&token=t", "/start", "s"),
            Callback::Other
        );
        assert_eq!(
            callback(
                &tiny_http::Method::Post,
                "/callback?state=s&token=t",
                "/start",
                "s"
            ),
            Callback::Other
        );
    }

    /// the api the instance of the tests names
    fn api_of(server: &MockServer) -> Option<String> {
        Some(format!("{}/api/v1/", server.uri()))
    }

    fn kept(secrets: &Arc<crate::secrets::Memory>, server: &MockServer) -> Option<String> {
        secrets
            .tokens
            .lock()
            .unwrap()
            .get(address(server).as_str())
            .cloned()
    }

    /// signs in at the instance with the browser of the tests
    async fn sign_in(manager: &SessionManager, server: &MockServer) -> Result<User, LoginError> {
        manager
            .login(address(server), pages(), |url, _| {
                let url = url.to_string();
                std::thread::spawn(move || {
                    let (port, state, _) = opened(&url);
                    browse(&format!(
                        "http://127.0.0.1:{port}/callback?state={state}&token=t"
                    ));
                    browse(&format!("http://127.0.0.1:{port}/done"))
                });
                Ok(())
            })
            .await
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_sign_in_is_kept_in_the_keychain_and_taken_up_by_the_next_start() {
        let server = instance(session_token()).await;
        let secrets = Arc::new(crate::secrets::Memory::default());
        let first = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        sign_in(&first, &server).await.unwrap();
        assert_eq!(kept(&secrets, &server).as_deref(), Some("session-token"));

        // the next start of the app
        let next = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        assert_eq!(first.session_api(), api_of(&server));
        let user = next
            .restore(address(&server), first.session_api())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(user.username, "anna@example.org");
        assert_eq!(next.user(), Some(user));
        assert!(
            next.request(reqwest::Method::GET, "auth/who-am-i")
                .is_some()
        );

        next.logout().await;
        assert_eq!(kept(&secrets, &server), None);
        let after = SessionManager::new(secrets, LOGIN_TIMEOUT);
        assert_eq!(
            after.restore(address(&server), api_of(&server)).await,
            Ok(None)
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_token_given_is_taken_up_and_never_kept() {
        let server = instance(session_token()).await;
        let secrets = Arc::new(crate::secrets::Memory::default());
        let manager = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        let user = manager
            .use_token(address(&server), "session-token".to_string())
            .await
            .unwrap();
        assert_eq!(user.username, "anna@example.org");
        assert!(
            manager
                .request(reqwest::Method::GET, "auth/who-am-i")
                .is_some()
        );
        assert_eq!(kept(&secrets, &server), None);
        // a token the instance does not take gives no session
        let other = SessionManager::default();
        assert!(
            other
                .use_token(address(&server), "wrong".to_string())
                .await
                .is_err()
        );
        assert_eq!(other.user(), None);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_the_instance_refuses_is_forgotten() {
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/.well-known/sak.json"))
            .respond_with(
                ResponseTemplate::new(200).set_body_json(
                    serde_json::json!({ "api": format!("{}/api/v1", server.uri()) }),
                ),
            )
            .mount(&server)
            .await;
        Mock::given(method("GET"))
            .and(path("/api/v1/auth/who-am-i"))
            .respond_with(ResponseTemplate::new(401))
            .mount(&server)
            .await;
        let secrets = Arc::new(crate::secrets::Memory::default());
        secrets
            .tokens
            .lock()
            .unwrap()
            .insert(address(&server).as_str().to_string(), "old".to_string());
        let manager = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        assert_eq!(
            manager.restore(address(&server), api_of(&server)).await,
            Err(RestoreError::Expired)
        );
        assert_eq!(kept(&secrets, &server), None);
        assert_eq!(manager.user(), None);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_of_an_instance_that_does_not_answer_is_kept() {
        let server = MockServer::start().await;
        let secrets = Arc::new(crate::secrets::Memory::default());
        secrets
            .tokens
            .lock()
            .unwrap()
            .insert(address(&server).as_str().to_string(), "kept".to_string());
        let manager = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        assert_eq!(
            manager.restore(address(&server), api_of(&server)).await,
            Err(RestoreError::Unreachable)
        );
        assert_eq!(kept(&secrets, &server).as_deref(), Some("kept"));
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn without_a_keychain_the_session_is_in_memory_only() {
        let server = instance(session_token()).await;
        let secrets = Arc::new(crate::secrets::Memory {
            failing: true,
            ..Default::default()
        });
        let manager = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        sign_in(&manager, &server).await.unwrap();
        assert!(manager.user().is_some());
        assert_eq!(
            manager.restore(address(&server), api_of(&server)).await,
            Ok(None)
        );
        manager.logout().await;
        assert_eq!(manager.user(), None);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_is_sent_only_to_the_api_of_its_sign_in() {
        let server = instance(session_token()).await;
        let secrets = Arc::new(crate::secrets::Memory::default());
        secrets
            .tokens
            .lock()
            .unwrap()
            .insert(address(&server).as_str().to_string(), "kept".to_string());
        let manager = SessionManager::new(secrets.clone(), LOGIN_TIMEOUT);
        // the address names another api than at the sign-in
        let other = Some("https://api.other.example/v1/".to_string());
        assert_eq!(
            manager.restore(address(&server), other).await,
            Err(RestoreError::Changed)
        );
        // no api of a sign-in is known
        assert_eq!(manager.restore(address(&server), None).await, Ok(None));
        assert_eq!(kept(&secrets, &server).as_deref(), Some("kept"));
        let asked = server.received_requests().await.unwrap();
        assert!(
            asked
                .iter()
                .all(|request| request.url.path() == "/.well-known/sak.json")
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_redirect_is_not_followed() {
        let elsewhere = instance(session_token()).await;
        let server = MockServer::start().await;
        Mock::given(method("GET"))
            .and(path("/.well-known/sak.json"))
            .respond_with(ResponseTemplate::new(307).insert_header(
                "Location",
                format!("{}/.well-known/sak.json", elsewhere.uri()),
            ))
            .mount(&server)
            .await;
        let manager = SessionManager::default();
        let opened = |_: &str, _: &str| -> Result<(), String> { panic!("the browser opened") };
        assert_eq!(
            manager.login(address(&server), pages(), opened).await,
            Err(LoginError::NoInstance)
        );
        assert!(elsewhere.received_requests().await.unwrap().is_empty());
    }
}
