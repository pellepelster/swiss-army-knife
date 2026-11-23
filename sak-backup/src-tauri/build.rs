fn main() {
    // the texts rust_i18n compiles in: a change of locales/ alone rebuilds, as any rerun-if of a build
    // script ends the default of rerunning on every change of the package
    println!("cargo:rerun-if-changed=locales");
    tauri_build::build()
}
