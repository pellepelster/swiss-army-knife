//! everything a deployment derives from its password: the ssh client key, the ssh host key, the
//! password hash label, the api key, the admin password hash and the provisioned-config label.
//! The same name and password give the same values on every machine, so sakctl keeps nothing.
//!
//! One strong argon2id of the password (`MASTER_PARAMS`) gives the master key of the deployment.
//! hkdf-sha256 expands each value from it with a purpose of its own, so no value gives away
//! another, and a run computes the argon2id one time only. Whoever reads the project (the label,
//! the public keys) must compute it for every guess. The admin password hash is the exception:
//! the backend checks a typed password against it, so it is an argon2id of the password itself

use argon2::{Algorithm, Argon2, Params, Version};
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use ed25519_dalek::{SigningKey, VerifyingKey};
use hkdf::Hkdf;
use sha2::{Digest, Sha256};
use std::sync::Mutex;
use unicode_normalization::UnicodeNormalization;

/// the argon2id of the master key: 256 MiB, 3 passes, one lane; about half a second
const MASTER_PARAMS: (u32, u32, u32) = (256 * 1024, 3, 1);

/// the argon2id of the admin password hash: 64 MiB, 3 passes, one lane. Less than the master key,
/// because the backend computes it on a small server for every password login
const ADMIN_PASSWORD_PARAMS: (u32, u32, u32) = (64 * 1024, 3, 1);

/// what the salts and purposes start with; a new version gives every value anew
const VERSION: &str = "sak.pelle.io/v2";

fn argon2((memory, passes, lanes): (u32, u32, u32)) -> Result<Argon2<'static>, String> {
    let params = Params::new(memory, passes, lanes, None).map_err(|e| e.to_string())?;
    Ok(Argon2::new(Algorithm::Argon2id, Version::V0x13, params))
}

/// the password as nfc: the same characters give the same bytes, typed on whatever system
fn normalized(password: &str) -> String {
    password.nfc().collect()
}

/// the master keys this process derived, by the name and a sha-256 of the password. A run asks for
/// several values of the same deployment, the argon2id runs one time for all of them
#[allow(clippy::type_complexity)]
static MASTER_KEYS: Mutex<Vec<(String, [u8; 32], [u8; 32])>> = Mutex::new(Vec::new());

/// the most master keys kept; the oldest goes first
const MASTER_KEYS_KEPT: usize = 16;

fn master_key(name: &str, password: &str) -> Result<[u8; 32], String> {
    let password = normalized(password);
    let id: [u8; 32] = Sha256::digest(format!("{VERSION}/master-key-id/{name}\0{password}")).into();
    if let Some((.., key)) = MASTER_KEYS
        .lock()
        .ok()
        .and_then(|keys| keys.iter().find(|(n, i, _)| n == name && *i == id).cloned())
    {
        return Ok(key);
    }
    let mut key = [0u8; 32];
    argon2(MASTER_PARAMS)?
        .hash_password_into(
            password.as_bytes(),
            format!("{VERSION}/master-key/{name}").as_bytes(),
            &mut key,
        )
        .map_err(|e| e.to_string())?;
    if let Ok(mut keys) = MASTER_KEYS.lock() {
        if keys.len() >= MASTER_KEYS_KEPT {
            keys.remove(0);
        }
        keys.push((name.to_string(), id, key));
    }
    Ok(key)
}

/// `N` bytes of the master key for `purpose`
fn expand<const N: usize>(name: &str, password: &str, purpose: &str) -> Result<[u8; N], String> {
    let master = master_key(name, password)?;
    let mut out = [0u8; N];
    Hkdf::<Sha256>::from_prk(&master)
        .map_err(|e| e.to_string())?
        .expand(format!("{VERSION}/{purpose}/{name}").as_bytes(), &mut out)
        .map_err(|e| e.to_string())?;
    Ok(out)
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|byte| format!("{byte:02x}")).collect()
}

/// an ed25519 ssh key derived from the master key: the client key the server is created with,
/// or the host key the server identifies itself with
pub struct DerivedKey {
    key: SigningKey,
    comment: String,
}

impl DerivedKey {
    /// `ssh-ed25519 <base64> <comment>`
    pub fn public_key(&self) -> String {
        encode_public_key(&self.key.verifying_key(), &self.comment)
    }

    /// the private key as openssh writes it, unencrypted
    pub fn private_key(&self) -> String {
        encode_private_key(&self.key, &self.comment)
    }
}

fn derive(name: &str, password: &str, purpose: &str, comment: &str) -> Result<DerivedKey, String> {
    Ok(DerivedKey {
        key: SigningKey::from_bytes(&expand::<32>(name, password, purpose)?),
        comment: comment.to_string(),
    })
}

/// the client key of a deployment: the server is created with its public half, sakctl logs in
/// with its private half through an ssh-agent (`core::provision`)
pub fn derive_key(name: &str, password: &str) -> Result<DerivedKey, String> {
    derive(name, password, "client-key", &format!("sak-{name}"))
}

/// the public key, `ssh-ed25519 <base64> sak-<name>`, of the client key
pub fn derive_public_key(name: &str, password: &str) -> Result<String, String> {
    Ok(derive_key(name, password)?.public_key())
}

/// the host key of the server of a deployment: cloud-init installs it, so sakctl knows the key
/// before the server first boots and verifies the first connection
pub fn derive_host_key(name: &str, password: &str) -> Result<DerivedKey, String> {
    derive(name, password, "host-key", &format!("sak-{name}-host"))
}

/// the label sak.pelle.io/password-hash on every resource of the deployment: 30 bytes of the
/// master key as hex, short enough for one label value. sakctl checks a password against it
pub fn password_hash(name: &str, password: &str) -> Result<String, String> {
    Ok(hex(&expand::<30>(name, password, "password-hash")?))
}

/// the ed25519 key sakctl signs its requests to the admin api of a deployment with
/// (`config::signed_text`). The backend knows only its public half, so no request carries a
/// secret. No Debug, the key never shows
pub struct ApiKey(SigningKey);

impl ApiKey {
    /// the public half, 32 bytes as base64, as the configuration of the backend holds it
    pub fn public_key(&self) -> String {
        STANDARD.encode(self.0.verifying_key().as_bytes())
    }

    /// the ed25519 signature of `message`, its 64 bytes as base64
    pub fn sign(&self, message: &[u8]) -> String {
        use ed25519_dalek::Signer;
        STANDARD.encode(self.0.sign(message).to_bytes())
    }
}

/// the key of the admin api of a deployment
pub fn api_key(name: &str, password: &str) -> Result<ApiKey, String> {
    Ok(ApiKey(SigningKey::from_bytes(&expand::<32>(
        name, password, "api-key",
    )?)))
}

/// the hash of the admin password (the deployment password as nfc) that the backend checks a
/// password login against: argon2id as a phc string (`$argon2id$v=19$m=…,t=…,p=…$<salt>$<hash>`).
/// The salt comes from the name, so every deploy gives the same hash and the configuration of the
/// backend does not change
pub fn admin_password_hash(name: &str, password: &str) -> Result<String, String> {
    use argon2::password_hash::{PasswordHasher, SaltString};
    // 16 bytes of salt, the recommended length
    let digest = Sha256::digest(format!("{VERSION}/admin-password/{name}"));
    let salt = SaltString::encode_b64(&digest[..16]).map_err(|e| e.to_string())?;
    Ok(argon2(ADMIN_PASSWORD_PARAMS)?
        .hash_password(normalized(password).as_bytes(), &salt)
        .map_err(|e| e.to_string())?
        .to_string())
}

/// the label `sak.pelle.io/provisioned-config` of the server: hmac-sha256 over `config` (the
/// configuration the playbook writes) with a key from the master key, 30 bytes as hex. The same
/// configuration gives the same value on every machine, another configuration another value.
/// Without the password nobody can find the configuration from it, also not a weak password of
/// the mail server in it
pub fn provisioned_config(name: &str, password: &str, config: &str) -> Result<String, String> {
    use hmac::{Hmac, Mac};
    let key = expand::<32>(name, password, "provisioned-config")?;
    let mut mac = Hmac::<Sha256>::new_from_slice(&key).map_err(|e| e.to_string())?;
    mac.update(config.as_bytes());
    Ok(hex(&mac.finalize().into_bytes()[..30]))
}

/// the lowest zxcvbn score (0 to 4) of a new password: 3 needs at least 10^8 guesses
const MIN_PASSWORD_SCORE: u8 = 3;

/// what zxcvbn suggests when a new password is too easy to guess, none otherwise. The name of
/// the deployment counts as known
pub fn weak_password(name: &str, password: &str) -> Option<String> {
    let estimate = zxcvbn::zxcvbn(password, &[name, "sak", "pelle"]);
    (u8::from(estimate.score()) < MIN_PASSWORD_SCORE).then(|| {
        estimate
            .feedback()
            .and_then(|feedback| feedback.suggestions().first().map(|s| s.to_string()))
            .unwrap_or_default()
    })
}

/// the openssh form of a public key, `ssh-ed25519 <base64 blob> <comment>`
pub fn encode_public_key(key: &VerifyingKey, comment: &str) -> String {
    format!(
        "ssh-ed25519 {} {comment}",
        STANDARD.encode(public_blob(key))
    )
}

// a string of the ssh wire format: its length, then its bytes
fn put(buffer: &mut Vec<u8>, bytes: &[u8]) {
    buffer.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    buffer.extend_from_slice(bytes);
}

fn public_blob(key: &VerifyingKey) -> Vec<u8> {
    let mut blob = Vec::new();
    put(&mut blob, b"ssh-ed25519");
    put(&mut blob, key.as_bytes());
    blob
}

/// the openssh private key format (PROTOCOL.key of openssh), unencrypted: the magic, no cipher
/// and no kdf, one key, its public blob, then the private section with the check values, the key,
/// the comment and the padding
fn encode_private_key(key: &SigningKey, comment: &str) -> String {
    let public = key.verifying_key();
    // the two check values must only be equal; a derived key has nothing to hide with them
    let check = 0x5a5a_5a5au32.to_be_bytes();
    let mut private = Vec::new();
    private.extend_from_slice(&check);
    private.extend_from_slice(&check);
    put(&mut private, b"ssh-ed25519");
    put(&mut private, public.as_bytes());
    let mut secret = key.to_bytes().to_vec();
    secret.extend_from_slice(public.as_bytes());
    put(&mut private, &secret);
    put(&mut private, comment.as_bytes());
    let mut padding = 1u8;
    while private.len() % 8 != 0 {
        private.push(padding);
        padding += 1;
    }

    let mut blob = b"openssh-key-v1\0".to_vec();
    put(&mut blob, b"none");
    put(&mut blob, b"none");
    put(&mut blob, b"");
    blob.extend_from_slice(&1u32.to_be_bytes());
    put(&mut blob, &public_blob(&public));
    put(&mut blob, &private);

    let encoded = STANDARD.encode(blob);
    let lines: Vec<&str> = encoded
        .as_bytes()
        .chunks(70)
        .map(|chunk| std::str::from_utf8(chunk).unwrap_or_default())
        .collect();
    format!(
        "-----BEGIN OPENSSH PRIVATE KEY-----\n{}\n-----END OPENSSH PRIVATE KEY-----\n",
        lines.join("\n")
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_same_name_and_password_give_the_same_key() {
        let key = derive_public_key("alpha", "a password of sixteen").unwrap();

        assert_eq!(
            derive_public_key("alpha", "a password of sixteen").unwrap(),
            key
        );
        assert!(key.starts_with("ssh-ed25519 "));
        assert!(key.ends_with(" sak-alpha"));
    }

    /// ssh-keygen reads the private key back and derives the same public key from it;
    /// without ssh-keygen the test checks the file's shape only
    #[test]
    fn the_private_key_is_what_openssh_reads() {
        let key = derive_key("alpha", "a password of sixteen").unwrap();
        let private = key.private_key();
        assert!(private.starts_with("-----BEGIN OPENSSH PRIVATE KEY-----\n"));
        assert!(private.ends_with("-----END OPENSSH PRIVATE KEY-----\n"));

        let dir = std::env::temp_dir().join(format!("sakctl-keys-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("id_ed25519");
        std::fs::write(&path, &private).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        }
        let read = std::process::Command::new("ssh-keygen")
            .args(["-y", "-f"])
            .arg(&path)
            .output();
        std::fs::remove_dir_all(&dir).unwrap();
        if let Ok(output) = read {
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            let public = String::from_utf8_lossy(&output.stdout);
            assert_eq!(public.trim(), key.public_key());
        }
    }

    #[test]
    fn the_host_key_differs_from_the_client_key() {
        let client = derive_key("alpha", "a password of sixteen").unwrap();
        let host = derive_host_key("alpha", "a password of sixteen").unwrap();
        assert_ne!(client.public_key(), host.public_key());
        assert!(host.public_key().ends_with(" sak-alpha-host"));
        assert_eq!(
            derive_host_key("alpha", "a password of sixteen")
                .unwrap()
                .public_key(),
            host.public_key()
        );
    }

    #[test]
    fn the_password_hash_fits_one_label_and_tells_names_apart() {
        let hash = password_hash("alpha", "a password of sixteen").unwrap();

        assert_eq!(hash.len(), 60);
        assert!(hash.bytes().all(|byte| byte.is_ascii_hexdigit()));
        assert_eq!(
            password_hash("alpha", "a password of sixteen").unwrap(),
            hash
        );
        assert_ne!(
            password_hash("beta", "a password of sixteen").unwrap(),
            hash
        );
        assert_ne!(
            password_hash("alpha", "another password here").unwrap(),
            hash
        );
    }

    #[test]
    fn the_api_key_is_its_own_and_the_server_gets_its_public_half() {
        let (name, password) = ("alpha", "a password of sixteen");
        let key = api_key(name, password).unwrap();
        // the same on every machine, 32 bytes as base64
        assert_eq!(
            key.public_key(),
            api_key(name, password).unwrap().public_key()
        );
        assert_eq!(STANDARD.decode(key.public_key()).unwrap().len(), 32);
        // another name or password, another key
        assert_ne!(
            key.public_key(),
            api_key("beta", password).unwrap().public_key()
        );
        assert_ne!(
            key.public_key(),
            api_key(name, "another password here").unwrap().public_key()
        );
        // a signature the public half verifies, of 64 bytes
        use ed25519_dalek::Verifier;
        let signature = STANDARD.decode(key.sign(b"message")).unwrap();
        let signature = ed25519_dalek::Signature::from_slice(&signature).unwrap();
        assert!(key.0.verifying_key().verify(b"message", &signature).is_ok());
        assert!(key.0.verifying_key().verify(b"other", &signature).is_err());
    }

    /// the public half of the api key of `alpha` with `a password of sixteen`: the same constant
    /// is in the tests of the backend (`ApiSignatureTest`), which checks the signature of
    /// `SIGNATURE_VECTOR` in core/config.rs against it
    pub const API_PUBLIC_KEY_VECTOR: &str = "zQsVaUWSE58kHpvnSflQK13SMb1VdS23SZ9PAf7DvGQ=";

    /// a change of a salt, a parameter or an encoding changes these values and locks every
    /// deployment out of its servers, its labels and its api. The values are pinned, so such a
    /// change fails here
    #[test]
    fn the_derived_values_are_pinned() {
        let (name, password) = ("alpha", "a password of sixteen");
        assert_eq!(
            api_key(name, password).unwrap().public_key(),
            API_PUBLIC_KEY_VECTOR
        );
        assert_eq!(
            password_hash(name, password).unwrap(),
            "f626bbc390d38eadf6ee6576b4a717f76e323180e1c88656fede1d6265d0"
        );
        assert_eq!(
            derive_public_key(name, password).unwrap(),
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBiDxOhcFnVmeF0YFh/GLVuXgpwr90d5my8JgIIHY8Lv sak-alpha"
        );
        assert_eq!(
            derive_host_key(name, password).unwrap().public_key(),
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIEzpXNtaORpHCLULOXhT2P1xI/Rfz5sO8QWnF8s60izX sak-alpha-host"
        );
    }

    /// the api key, the seeds of both ssh keys and the password hash come from the same master
    /// key, each with a purpose of its own, so no value gives away another
    #[test]
    fn the_api_key_shares_no_bytes_with_the_ssh_keys_or_the_hash() {
        let (name, password) = ("alpha", "a password of sixteen");
        let token = api_key(name, password).unwrap().0.to_bytes();
        let client = derive_key(name, password).unwrap().key.to_bytes();
        let host = derive_host_key(name, password).unwrap().key.to_bytes();
        let hash: Vec<u8> = (0..30)
            .map(|index| {
                let hex = password_hash(name, password).unwrap();
                u8::from_str_radix(&hex[index * 2..index * 2 + 2], 16).unwrap()
            })
            .collect();
        for other in [&client[..], &host[..], &hash[..]] {
            assert_ne!(&token[..other.len()], other);
            // not even a run of 8 bytes in common
            assert!(
                !token
                    .windows(8)
                    .any(|window| other.windows(8).any(|part| part == window))
            );
        }
    }

    /// the hash of the admin password of `alpha` with `a password of sixteen`: the same constant is
    /// in the tests of the backend (`AdminPasswordTest`), which checks a password against it
    pub const ADMIN_PASSWORD_VECTOR: &str = "$argon2id$v=19$m=65536,t=3,p=1$al/AGZtQJD5CTnS21yvIXg$L7n0a0d/Gu4awCZm7tuz99SWhVvUedmj0UABpTBu6l4";

    #[test]
    fn the_admin_password_hash_is_a_stable_argon2id_phc_string() {
        let hash = admin_password_hash("alpha", "a password of sixteen").unwrap();
        assert!(
            hash.starts_with("$argon2id$v=19$m=65536,t=3,p=1$"),
            "{hash}"
        );
        assert_eq!(
            hash,
            admin_password_hash("alpha", "a password of sixteen").unwrap()
        );
        assert_ne!(
            hash,
            admin_password_hash("beta", "a password of sixteen").unwrap()
        );
        assert_eq!(hash, ADMIN_PASSWORD_VECTOR);
    }

    #[test]
    fn the_provisioned_config_fits_one_label_and_follows_the_configuration() {
        let (name, password) = ("alpha", "a password of sixteen");
        let value = provisioned_config(name, password, "sak_name: alpha").unwrap();
        assert_eq!(value.len(), 60);
        assert_eq!(
            provisioned_config(name, password, "sak_name: alpha").unwrap(),
            value
        );
        assert_ne!(
            provisioned_config(name, password, "sak_name: beta").unwrap(),
            value
        );
        assert_ne!(
            provisioned_config("beta", password, "sak_name: alpha").unwrap(),
            value
        );
        assert_ne!(
            provisioned_config(name, "another password here", "sak_name: alpha").unwrap(),
            value
        );
        // it is no plain hash of the configuration
        assert!(!sak_cloud::hetzner::labels::sha256_hex("sak_name: alpha").starts_with(&value));
    }

    /// the same characters give the same values, composed or with combining marks (as macos may
    /// type them); the backend takes a password login as nfc too (`AdminPasswordTest`)
    #[test]
    fn the_password_counts_as_nfc() {
        let (composed, combining) = (
            "Gr\u{fc}\u{df}e aus dem S\u{fc}den, sechzehn",
            "Gru\u{308}\u{df}e aus dem Su\u{308}den, sechzehn",
        );
        assert_eq!(
            derive_public_key("alpha", composed).unwrap(),
            derive_public_key("alpha", combining).unwrap()
        );
        assert_eq!(
            admin_password_hash("alpha", composed).unwrap(),
            admin_password_hash("alpha", combining).unwrap()
        );
        assert_eq!(
            admin_password_hash("alpha", composed).unwrap(),
            "$argon2id$v=19$m=65536,t=3,p=1$al/AGZtQJD5CTnS21yvIXg$SpIVgiuAa4G3pa2GALp3BEwmy4mqb2K4iHx7SxgijdA"
        );
    }

    #[test]
    fn a_password_easy_to_guess_is_weak_the_name_counts_as_known() {
        assert!(weak_password("alpha", "a password of sixteen").is_none());
        assert!(weak_password("alpha", "correct horse battery staple").is_none());
        for weak in [
            "aaaaaaaaaaaaaaaa",
            "password12345678",
            "alphaalphaalpha1",
            "1234567890123456",
        ] {
            assert!(weak_password("alpha", weak).is_some(), "{weak}");
        }
    }

    /// one argon2id for all values of a deployment, also when many values are asked for
    #[test]
    fn the_master_key_is_derived_once() {
        let password = "a password only this test uses";
        let start = std::time::Instant::now();
        derive_key("gamma", password).unwrap();
        let first = start.elapsed();
        let start = std::time::Instant::now();
        for _ in 0..5 {
            password_hash("gamma", password).unwrap();
            api_key("gamma", password).unwrap();
        }
        assert!(start.elapsed() < first, "{:?} {first:?}", start.elapsed());
    }

    #[test]
    fn another_name_or_password_gives_another_key() {
        let key = derive_public_key("alpha", "a password of sixteen").unwrap();

        assert_ne!(
            derive_public_key("beta", "a password of sixteen").unwrap(),
            key
        );
        assert_ne!(
            derive_public_key("alpha", "another password here").unwrap(),
            key
        );
    }
}
