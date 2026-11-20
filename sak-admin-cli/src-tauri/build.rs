use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

fn main() {
    // the version of SAK (VERSION of mise.toml, the tag of a release without its v) as SAK_VERSION,
    // latest for a build that is no release
    println!("cargo:rerun-if-env-changed=VERSION");
    let version = std::env::var("VERSION").unwrap_or_default();
    let version = version.trim().trim_start_matches('v');
    let version = if version.is_empty() {
        "latest"
    } else {
        version
    };
    println!("cargo:rustc-env=SAK_VERSION={version}");
    role_digests();
    tauri_build::build()
}

/// the digest of each role of ../../ansible/roles as `role_digests.rs` in OUT_DIR: the sha-256
/// over the relative path and the bytes of each of its files, in the order of their paths, without
/// its tests. The image of the tools of the same build holds the same files (core/roles.rs)
fn role_digests() {
    let roles = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../ansible/roles");
    println!("cargo:rerun-if-changed={}", roles.display());
    let mut names: Vec<String> = std::fs::read_dir(&roles)
        .expect("the roles of ansible/roles")
        .filter_map(|entry| entry.ok())
        .filter(|entry| entry.path().is_dir())
        .map(|entry| entry.file_name().to_string_lossy().to_string())
        .collect();
    names.sort();
    let mut code = String::from(
        "/// the digest of each role of ansible/roles, by its name (build.rs)\npub const ROLE_DIGESTS: &[(&str, &str)] = &[\n",
    );
    for name in names {
        let dir = roles.join(&name);
        let mut files = Vec::new();
        collect(&dir, &dir, &mut files);
        files.sort();
        let mut digest = Sha256::new();
        for (path, file) in files {
            println!("cargo:rerun-if-changed={}", file.display());
            digest.update(path.as_bytes());
            digest.update(std::fs::read(&file).expect("a file of a role"));
        }
        let hex: String = digest
            .finalize()
            .iter()
            .map(|byte| format!("{byte:02x}"))
            .collect();
        code.push_str(&format!("    ({name:?}, {hex:?}),\n"));
    }
    code.push_str("];\n");
    let out = PathBuf::from(std::env::var("OUT_DIR").expect("OUT_DIR"));
    std::fs::write(out.join("role_digests.rs"), code).expect("role_digests.rs");
}

/// the files below `dir` by their path relative to `root`, `/` between the parts on every
/// platform; the tests of a role do not change what it does
fn collect(root: &Path, dir: &Path, files: &mut Vec<(String, PathBuf)>) {
    // a file that cannot be read stops the build: left out, it would change the digest of its role
    for entry in std::fs::read_dir(dir).expect("a directory of a role") {
        let entry = entry.expect("an entry of a directory of a role");
        let path = entry.path();
        if path.is_dir() {
            if dir == root && entry.file_name() == "tests" {
                continue;
            }
            collect(root, &path, files);
        } else {
            let relative = path
                .strip_prefix(root)
                .expect("below the role")
                .components()
                .map(|part| part.as_os_str().to_string_lossy().to_string())
                .collect::<Vec<_>>()
                .join("/");
            files.push((relative, path));
        }
    }
}
