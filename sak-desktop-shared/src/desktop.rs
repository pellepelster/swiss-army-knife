//! the entry of a window in the desktop of linux: a desktop file and the icon below
//! `$XDG_DATA_HOME` (`~/.local/share`). The taskbar and the dock find the icon of a window only
//! through a desktop file of its app id (the name of the binary), and the binary comes without
//! one. Written when the window opens and its content differs, so it follows the binary when it
//! moves; a failure leaves the window as it is

use std::path::{Path, PathBuf};

/// what the desktop shows of an app
pub struct DesktopEntry {
    /// the name of the desktop file and of the icon, the app id of the window (the binary)
    pub app_id: &'static str,
    pub name: &'static str,
    pub comment: &'static str,
    /// the icon, a png of 512 pixels
    pub icon: &'static [u8],
}

impl DesktopEntry {
    /// a release build only, a build of the development would point the entry at itself
    pub fn install(&self) {
        if cfg!(debug_assertions) {
            return;
        }
        let (Ok(exe), Some(data)) = (std::env::current_exe(), data_home()) else {
            return;
        };
        if let Err(error) = self.write(&data, &exe) {
            eprintln!(
                "the desktop entry of {} was not written: {error}",
                self.app_id
            );
        }
    }

    fn write(&self, data: &Path, exe: &Path) -> std::io::Result<()> {
        let icon = data.join(format!("icons/hicolor/512x512/apps/{}.png", self.app_id));
        let entry = data.join(format!("applications/{}.desktop", self.app_id));
        write_if_changed(&icon, self.icon)?;
        write_if_changed(&entry, self.entry(exe).as_bytes())
    }

    /// the entry of the desktop entry specification; the path of the binary quoted, as it can
    /// hold spaces
    fn entry(&self, exe: &Path) -> String {
        let exe = exe
            .display()
            .to_string()
            .replace('\\', "\\\\\\\\")
            .replace('"', "\\\\\"")
            .replace('`', "\\\\`")
            .replace('$', "\\\\$");
        let DesktopEntry {
            app_id,
            name,
            comment,
            ..
        } = self;
        format!(
            "[Desktop Entry]\nType=Application\nName={name}\n\
             Comment={comment}\nExec=\"{exe}\"\nIcon={app_id}\n\
             Terminal=false\nCategories=Utility;\nStartupWMClass={app_id}\n"
        )
    }
}

fn data_home() -> Option<PathBuf> {
    std::env::var_os("XDG_DATA_HOME")
        .map(PathBuf::from)
        .filter(|path| path.is_absolute())
        .or_else(|| std::env::home_dir().map(|home| home.join(".local/share")))
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

#[cfg(test)]
mod tests {
    use super::*;

    const ENTRY: DesktopEntry = DesktopEntry {
        app_id: "sakadm",
        name: "Swiss Army Knife",
        comment: "Create, update and back up your Swiss Army Knife",
        icon: b"png",
    };

    #[test]
    fn the_entry_starts_the_binary_and_names_the_icon_of_the_window() {
        let entry = ENTRY.entry(Path::new("/home/alpha/.local/bin/sakadm"));
        assert!(entry.starts_with("[Desktop Entry]\n"));
        assert!(entry.contains("\nExec=\"/home/alpha/.local/bin/sakadm\"\n"));
        assert!(entry.contains("\nIcon=sakadm\n"));
        assert!(entry.contains("\nStartupWMClass=sakadm\n"));
        assert!(entry.contains("\nName=Swiss Army Knife\n"));
    }

    #[test]
    fn a_path_with_spaces_and_quotes_stays_one_argument() {
        let entry = ENTRY.entry(Path::new("/home/a b/my \"bin\"/sakadm"));
        assert!(
            entry.contains("\nExec=\"/home/a b/my \\\\\"bin\\\\\"/sakadm\"\n"),
            "{entry}"
        );
    }

    #[test]
    fn the_files_are_written_once_and_again_when_the_binary_moves() {
        let data = std::env::temp_dir().join(format!("sak-desktop-{}", std::process::id()));
        let entry = data.join("applications/sakadm.desktop");
        let icon = data.join("icons/hicolor/512x512/apps/sakadm.png");
        ENTRY.write(&data, Path::new("/opt/sakadm")).unwrap();
        assert_eq!(std::fs::read(&icon).unwrap(), ENTRY.icon);
        assert!(
            std::fs::read_to_string(&entry)
                .unwrap()
                .contains("Exec=\"/opt/sakadm\"")
        );
        ENTRY
            .write(&data, Path::new("/usr/local/bin/sakadm"))
            .unwrap();
        assert!(
            std::fs::read_to_string(&entry)
                .unwrap()
                .contains("Exec=\"/usr/local/bin/sakadm\"")
        );
        std::fs::remove_dir_all(&data).unwrap();
    }
}
