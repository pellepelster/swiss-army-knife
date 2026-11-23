fn main() {
    // the version of SAK (VERSION of mise.toml, the tag of a release without its v) as SAK_VERSION,
    // latest for a build that is no release; the desktop apps read it as sak_desktop_shared::VERSION
    println!("cargo:rerun-if-env-changed=VERSION");
    let version = std::env::var("VERSION").unwrap_or_default();
    let version = version.trim().trim_start_matches('v');
    let version = if version.is_empty() {
        "latest"
    } else {
        version
    };
    println!("cargo:rustc-env=SAK_VERSION={version}");
}
