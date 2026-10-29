//! the ssh key of a deployment, derived from its name and password: the same name and
//! password give the same key on every machine, so nothing has to be kept

use argon2::Argon2;
use base64::Engine;
use base64::engine::general_purpose::{STANDARD, URL_SAFE_NO_PAD};
use ed25519_dalek::{SigningKey, VerifyingKey};

/// an ed25519 key derived from the password of a deployment: the client key the server
/// is created with, or the host key the server identifies itself with
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

fn derive(password: &str, salt: &str, comment: &str) -> Result<DerivedKey, String> {
    let mut seed = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt.as_bytes(), &mut seed)
        .map_err(|e| e.to_string())?;
    Ok(DerivedKey {
        key: SigningKey::from_bytes(&seed),
        comment: comment.to_string(),
    })
}

/// the client key of a deployment, derived from the password with argon2id, the name its
/// salt; the server is created with its public half, the wizard logs in with the private
pub fn derive_key(name: &str, password: &str) -> Result<DerivedKey, String> {
    derive(
        password,
        &format!("sak.pelle.io/{name}"),
        &format!("sak-{name}"),
    )
}

/// the public key, `ssh-ed25519 <base64> sak-<name>`, of the client key
pub fn derive_public_key(name: &str, password: &str) -> Result<String, String> {
    Ok(derive_key(name, password)?.public_key())
}

/// the host key of the server of a deployment, derived like the client key with a salt of
/// its own: cloud-init installs it, so the server is known before it ever boots and the
/// first connection is verified
pub fn derive_host_key(name: &str, password: &str) -> Result<DerivedKey, String> {
    derive(
        password,
        &format!("sak.pelle.io/host-key/{name}"),
        &format!("sak-{name}-host"),
    )
}

/// the hash of the password that goes on the server of the deployment as the label
/// sak.pelle.io/password-hash: argon2id over the password with a salt of its own, 30 bytes
/// as hex, short enough for one label value. The wizard checks a password against it
pub fn password_hash(name: &str, password: &str) -> Result<String, String> {
    let salt = format!("sak.pelle.io/password-hash/{name}");
    let mut hash = [0u8; 30];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt.as_bytes(), &mut hash)
        .map_err(|e| e.to_string())?;
    Ok(hash.iter().map(|byte| format!("{byte:02x}")).collect())
}

/// the admin api token of a deployment: argon2id over the password with a salt of its own (the
/// version in it lets a new token follow without a new password), 32 bytes as `sak_<base64url>`.
/// The server keeps only its sha-256 (`api_token_sha256`), the token travels as a bearer token
/// and is never written anywhere
pub fn api_token(name: &str, password: &str) -> Result<String, String> {
    let salt = format!("sak.pelle.io/api-token/v1/{name}");
    let mut token = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt.as_bytes(), &mut token)
        .map_err(|e| e.to_string())?;
    Ok(format!("sak_{}", URL_SAFE_NO_PAD.encode(token)))
}

/// the sha-256 of the api token in hex, what the backend compares a bearer token with; the token
/// has 256 bits of its own, a fast hash is enough
pub fn api_token_sha256(token: &str) -> String {
    sak_cloud::hetzner::labels::sha256_hex(token)
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

/// the openssh private key format (PROTOCOL.key of openssh), unencrypted: the magic, no
/// cipher and kdf, one key, its public blob and the private section with the check
/// values, the key, the comment and the padding
fn encode_private_key(key: &SigningKey, comment: &str) -> String {
    let public = key.verifying_key();
    // the two check values only have to be equal, there is nothing to hide in a key that
    // is derived anyway
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
    fn the_api_token_is_its_own_and_the_server_gets_its_hash() {
        let (name, password) = ("alpha", "a password of sixteen");
        let token = api_token(name, password).unwrap();
        // the same on every machine, 32 bytes as base64url after the prefix
        assert_eq!(token, api_token(name, password).unwrap());
        assert!(token.starts_with("sak_"));
        assert_eq!(token.len(), 4 + 43);
        assert!(!token[4..].contains(['+', '/', '=']));
        // another name or password, another token; nothing of the password hash in it
        assert_ne!(token, api_token("beta", password).unwrap());
        assert_ne!(token, api_token(name, "another password here").unwrap());
        assert!(!password_hash(name, password).unwrap().contains(&token[4..]));
        // what the backend compares with: the sha-256 in hex
        let hash = api_token_sha256(&token);
        assert_eq!(hash.len(), 64);
        assert!(hash.bytes().all(|byte| byte.is_ascii_hexdigit()));
        // a known vector of sha-256
        assert_eq!(
            api_token_sha256("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
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
