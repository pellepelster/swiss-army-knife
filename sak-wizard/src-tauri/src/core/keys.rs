//! the ssh key of a deployment, derived from its name and password: the same name and
//! password give the same key on every machine, so nothing has to be kept

use argon2::Argon2;
use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use ed25519_dalek::{SigningKey, VerifyingKey};

/// the public key, `ssh-ed25519 <base64> sak-<name>`, of the key derived from the
/// password with argon2id, the name its salt
pub fn derive_public_key(name: &str, password: &str) -> Result<String, String> {
    let salt = format!("sak.pelle.io/{name}");
    let mut seed = [0u8; 32];
    Argon2::default()
        .hash_password_into(password.as_bytes(), salt.as_bytes(), &mut seed)
        .map_err(|e| e.to_string())?;
    let key = SigningKey::from_bytes(&seed).verifying_key();
    Ok(encode_public_key(&key, &format!("sak-{name}")))
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

/// the openssh form of a public key, `ssh-ed25519 <base64 blob> <comment>`
pub fn encode_public_key(key: &VerifyingKey, comment: &str) -> String {
    let mut blob = Vec::new();
    for part in [b"ssh-ed25519".as_slice(), key.as_bytes()] {
        blob.extend_from_slice(&(part.len() as u32).to_be_bytes());
        blob.extend_from_slice(part);
    }
    format!("ssh-ed25519 {} {comment}", STANDARD.encode(blob))
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
