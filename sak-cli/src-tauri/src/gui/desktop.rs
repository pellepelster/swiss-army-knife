//! the entry of the window in the desktop of linux: a desktop file and the icon in the icons of the
//! user, below `$XDG_DATA_HOME` (`~/.local/share`). The taskbar and the dock find the icon of a
//! window only through a desktop file of its app id, and sakctl comes as one binary without one.
//! The app id of the window is the name of the binary, `sakctl`. Written when the window opens and
//! its content differs, so it follows sakctl when it moves (an update keeps its place); a failure
//! leaves the window as it is

use std::path::{Path, PathBuf};

/// the name of the desktop file and of the icon, the app id of the window
const APP_ID: &str = "sakctl";

/// the icon of the bundles, 512 pixels
const ICON: &[u8] = include_bytes!("../../icons/icon.png");

/// a release build only, a build of the development would point the entry at itself
pub fn install() {
    if cfg!(debug_assertions) {
        return;
    }
    let (Ok(exe), Some(data)) = (std::env::current_exe(), data_home()) else {
        return;
    };
    if let Err(error) = write(&data, &exe) {
        eprintln!("the desktop entry of sakctl was not written: {error}");
    }
}

fn data_home() -> Option<PathBuf> {
    std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .or_else(|| std::env::home_dir().map(|home| home.join(".local/share")))
}

fn write(data: &Path, exe: &Path) -> std::io::Result<()> {
    let icon = data.join(format!("icons/hicolor/512x512/apps/{APP_ID}.png"));
    let entry = data.join(format!("applications/{APP_ID}.desktop"));
    write_if_changed(&icon, ICON)?;
    write_if_changed(&entry, desktop_entry(exe).as_bytes())
}

fn write_if_changed(path: &Path, content: &[u8]) -> std::io::Result<()> {
    if std::fs::read(path).is_ok_and(|existing| existing == content) {
        return Ok(());
    }
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    std::fs::write(path, content)
}

/// the entry of the desktop entry specification; the path of the binary quoted, as it can hold
/// spaces
fn desktop_entry(exe: &Path) -> String {
    let exe = exe
        .display()
        .to_string()
        .replace('\\', "\\\\\\\\")
        .replace('"', "\\\\\"")
        .replace('`', "\\\\`")
        .replace('$', "\\\\$");
    format!(
        "[Desktop Entry]\nType=Application\nName=Swiss Army Knife\n\
         Comment=Create, update and back up your Swiss Army Knife\nExec=\"{exe}\"\nIcon={APP_ID}\n\
         Terminal=false\nCategories=Utility;\nStartupWMClass={APP_ID}\n"
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_entry_starts_the_binary_and_names_the_icon_of_the_window() {
        let entry = desktop_entry(Path::new("/home/alpha/.local/bin/sakctl"));
        assert!(entry.starts_with("[Desktop Entry]\n"));
        assert!(entry.contains("\nExec=\"/home/alpha/.local/bin/sakctl\"\n"));
        assert!(entry.contains("\nIcon=sakctl\n"));
        assert!(entry.contains("\nStartupWMClass=sakctl\n"));
        assert!(entry.contains("\nName=Swiss Army Knife\n"));
    }

    #[test]
    fn a_path_with_spaces_and_quotes_stays_one_argument() {
        let entry = desktop_entry(Path::new("/home/a b/my \"bin\"/sakctl"));
        assert!(
            entry.contains("\nExec=\"/home/a b/my \\\\\"bin\\\\\"/sakctl\"\n"),
            "{entry}"
        );
    }

    #[test]
    fn the_files_are_written_once_and_again_when_sakctl_moves() {
        let data = std::env::temp_dir().join(format!("sakctl-desktop-{}", std::process::id()));
        let entry = data.join("applications/sakctl.desktop");
        let icon = data.join("icons/hicolor/512x512/apps/sakctl.png");
        write(&data, Path::new("/opt/sakctl")).unwrap();
        assert_eq!(std::fs::read(&icon).unwrap(), ICON);
        assert!(
            std::fs::read_to_string(&entry)
                .unwrap()
                .contains("Exec=\"/opt/sakctl\"")
        );
        write(&data, Path::new("/usr/local/bin/sakctl")).unwrap();
        assert!(
            std::fs::read_to_string(&entry)
                .unwrap()
                .contains("Exec=\"/usr/local/bin/sakctl\"")
        );
        std::fs::remove_dir_all(&data).unwrap();
    }
}
