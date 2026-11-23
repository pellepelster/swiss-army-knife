//! the icon of sakbackup in the tray of the panel, while the window is open: its menu shows the
//! window, what runs and quits. While a backup runs, the icon is the box with a bar of its progress
//! below (sak-theme/images/sources/sakbackup-tray-run.svg), redrawn when the percent changes. A
//! window closed while a backup, a restore or a change of the snapshots runs is hidden instead, so
//! the run goes on; that needs a tray that shows the icon, without one (e.g. GNOME without an
//! extension) the window closes as without the tray. Quit asks first while something runs

use crate::core::run::{self, RunEvent, Runs};
use rust_i18n::t;
use std::sync::Mutex;
use tauri::image::Image;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIcon, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, Window, Wry};
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons, MessageDialogKind};

/// the icon while nothing runs: the box with the knife
const IDLE: &[u8] = include_bytes!("../../../../sak-theme/images/sakbackup/tray.png");
/// the icon of a backup: the box with an empty bar below, 64 pixels
const RUNNING: &[u8] = include_bytes!("../../../../sak-theme/images/sakbackup/tray-run.png");

/// the inside of the bar of `RUNNING`, filled from its left; its corners are round, only its light
/// pixels are filled (`LIGHT`), its outline stays
const BAR_LEFT: u32 = 6;
const BAR_WIDTH: u32 = 52;
const BAR_TOP: u32 = 53;
const BAR_BOTTOM: u32 = 59;
/// the red of a pixel of the light track, more than the one of its dark outline
const LIGHT: u8 = 0x80;
/// the green of the theme's accent, darker than the box
const BAR_FILL: [u8; 4] = [0x2f, 0x9e, 0x62, 0xff];

/// the label of the window of tauri.conf.json, the default one
const WINDOW: &str = "main";

/// the icon and the line of its menu that tells what runs
pub struct Tray {
    icon: TrayIcon,
    status: MenuItem<Wry>,
    /// the percent the icon shows, none while no backup runs
    shown: Mutex<Option<u8>>,
}

/// puts the icon in the tray; a system without a tray gets none, the window works as without it
pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let status = MenuItem::with_id(app, "status", t!("ui.tray.idle"), false, None::<&str>)?;
    let show = MenuItem::with_id(app, "show", t!("ui.tray.show"), true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", t!("ui.tray.quit"), true, None::<&str>)?;
    let menu = Menu::with_items(
        app,
        &[&status, &PredefinedMenuItem::separator(app)?, &show, &quit],
    )?;
    let icon = TrayIconBuilder::with_id("sakbackup")
        .icon(picture(IDLE))
        .tooltip(t!("ui.title"))
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id().as_ref() {
            "show" => show_window(app),
            "quit" => quit_app(app),
            _ => {}
        })
        // a click of the icon brings the window back; linux has the menu only
        .on_tray_icon_event(|icon, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                show_window(icon.app_handle());
            }
        })
        .build(app)?;
    app.manage(Tray {
        icon,
        status,
        shown: Mutex::new(None),
    });
    Ok(())
}

/// the progress of a backup in the icon, its tooltip and its menu, once a percent
pub fn progress(app: &AppHandle, event: &RunEvent) {
    let (Some(tray), RunEvent::Progress { percent, .. }) = (app.try_state::<Tray>(), event) else {
        return;
    };
    let percent = (percent * 100.0).floor().clamp(0.0, 100.0) as u8;
    let mut shown = tray.shown.lock().unwrap();
    if *shown == Some(percent) {
        return;
    }
    *shown = Some(percent);
    let base = picture(RUNNING);
    let rgba = with_bar(base.rgba(), base.width(), percent);
    let text = t!("ui.tray.backing_up", percent = percent).to_string();
    let _ = tray
        .icon
        .set_icon(Some(Image::new_owned(rgba, base.width(), base.height())));
    let _ = tray
        .icon
        .set_tooltip(Some(format!("{} · {text}", t!("ui.title"))));
    let _ = tray.status.set_text(text);
}

/// the icon of nothing running again, after a backup ended in whatever way
pub fn idle(app: &AppHandle) {
    let Some(tray) = app.try_state::<Tray>() else {
        return;
    };
    if tray.shown.lock().unwrap().take().is_none() {
        return;
    }
    let _ = tray.icon.set_icon(Some(picture(IDLE)));
    let _ = tray.icon.set_tooltip(Some(t!("ui.title")));
    let _ = tray.status.set_text(t!("ui.tray.idle"));
}

/// the window is hidden instead of closed while something runs and the tray shows the icon
pub fn close_requested(window: &Window, api: &tauri::CloseRequestApi) {
    let app = window.app_handle();
    if app.try_state::<Tray>().is_some() && app.state::<Runs>().is_running() && has_tray() {
        api.prevent_close();
        let _ = window.hide();
        // what runs, when no backup said its percent yet, e.g. a restore
        if let Some(tray) = app
            .try_state::<Tray>()
            .filter(|tray| tray.shown.lock().unwrap().is_none())
        {
            let _ = tray.status.set_text(t!("ui.tray.running"));
        }
    }
}

fn show_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window(WINDOW) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// quits; while something runs only when the user confirms, and the run is stopped first
fn quit_app(app: &AppHandle) {
    if !app.state::<Runs>().is_running() {
        app.exit(0);
        return;
    }
    show_window(app);
    let app = app.clone();
    app.dialog()
        .message(t!("ui.tray.quit_running"))
        .title(t!("ui.title"))
        .kind(MessageDialogKind::Warning)
        .buttons(MessageDialogButtons::OkCancelCustom(
            t!("ui.tray.quit").to_string(),
            t!("ui.tray.keep_running").to_string(),
        ))
        .show({
            let app = app.clone();
            move |confirmed| {
                if confirmed {
                    run::cancel(&app.state::<Runs>());
                    app.exit(0);
                }
            }
        });
}

/// a png of sak-theme as the pixels of an icon: 8 bits of rgba, which the build checks (tests); one
/// it can not read is an empty pixel
fn picture(png: &[u8]) -> Image<'static> {
    decode(png).unwrap_or_else(|| Image::new_owned(vec![0; 4], 1, 1))
}

fn decode(png: &[u8]) -> Option<Image<'static>> {
    let mut reader = png::Decoder::new(std::io::Cursor::new(png))
        .read_info()
        .ok()?;
    let mut rgba = vec![0; reader.output_buffer_size()?];
    let info = reader.next_frame(&mut rgba).ok()?;
    if info.color_type != png::ColorType::Rgba || info.bit_depth != png::BitDepth::Eight {
        return None;
    }
    rgba.truncate(info.buffer_size());
    Some(Image::new_owned(rgba, info.width, info.height))
}

/// the pixels of the icon of a backup with its bar filled to `percent`, 64 pixels wide
fn with_bar(base: &[u8], width: u32, percent: u8) -> Vec<u8> {
    let mut rgba = base.to_vec();
    let filled = BAR_WIDTH * u32::from(percent.min(100)) / 100;
    for y in BAR_TOP..=BAR_BOTTOM {
        for x in BAR_LEFT..BAR_LEFT + filled {
            let at = ((y * width + x) * 4) as usize;
            if let Some(pixel) = rgba.get_mut(at..at + 4).filter(|pixel| pixel[0] >= LIGHT) {
                pixel.copy_from_slice(&BAR_FILL);
            }
        }
    }
    rgba
}

/// whether a tray shows the icon: on linux a host of the StatusNotifierItems of freedesktop (the
/// tray of COSMIC, KDE, an extension of GNOME), which libappindicator needs; macOS and windows
/// always have one
#[cfg(target_os = "linux")]
fn has_tray() -> bool {
    use gio::prelude::*;
    let Ok(bus) = gio::bus_get_sync(gio::BusType::Session, gio::Cancellable::NONE) else {
        return false;
    };
    bus.call_sync(
        Some("org.kde.StatusNotifierWatcher"),
        "/StatusNotifierWatcher",
        "org.freedesktop.DBus.Properties",
        "Get",
        Some(
            &(
                "org.kde.StatusNotifierWatcher",
                "IsStatusNotifierHostRegistered",
            )
                .to_variant(),
        ),
        None,
        gio::DBusCallFlags::NONE,
        500,
        gio::Cancellable::NONE,
    )
    .ok()
    .and_then(|reply| reply.child_value(0).as_variant())
    .and_then(|registered| registered.get::<bool>())
    .unwrap_or(false)
}

#[cfg(not(target_os = "linux"))]
fn has_tray() -> bool {
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn running() -> Image<'static> {
        decode(RUNNING).unwrap()
    }

    fn pixel(rgba: &[u8], width: u32, x: u32, y: u32) -> [u8; 4] {
        let at = ((y * width + x) * 4) as usize;
        rgba[at..at + 4].try_into().unwrap()
    }

    /// the row in the middle of the bar, where it is light from its left to its right
    const MIDDLE: u32 = (BAR_TOP + BAR_BOTTOM) / 2;

    #[test]
    fn the_bar_is_filled_to_the_percent_and_nothing_else_changes() {
        let base = running();
        assert_eq!((base.width(), base.height()), (64, 64));
        let empty = with_bar(base.rgba(), 64, 0);
        assert_eq!(empty, base.rgba());
        let half = with_bar(base.rgba(), 64, 50);
        // the left half of the inside of the bar is green, the right half as it was
        assert_eq!(pixel(&half, 64, BAR_LEFT, MIDDLE), BAR_FILL);
        assert_eq!(pixel(&half, 64, BAR_LEFT + 25, MIDDLE), BAR_FILL);
        assert_eq!(pixel(&half, 64, BAR_LEFT + 10, BAR_BOTTOM), BAR_FILL);
        assert_eq!(
            pixel(&half, 64, BAR_LEFT + 26, MIDDLE),
            pixel(base.rgba(), 64, BAR_LEFT + 26, MIDDLE)
        );
        // only pixels of the inside of the bar change, never its outline or the box
        for (at, (new, old)) in half.chunks(4).zip(base.rgba().chunks(4)).enumerate() {
            if new != old {
                let (x, y) = (at as u32 % 64, at as u32 / 64);
                assert!(
                    (BAR_LEFT..BAR_LEFT + 26).contains(&x) && (BAR_TOP..=BAR_BOTTOM).contains(&y),
                    "{x},{y}"
                );
                assert!(old[0] >= LIGHT, "{x},{y} was outline");
            }
        }
        // full: the whole inside, never past it
        let full = with_bar(base.rgba(), 64, 100);
        assert_eq!(pixel(&full, 64, BAR_LEFT + BAR_WIDTH - 1, MIDDLE), BAR_FILL);
        assert_ne!(pixel(&full, 64, BAR_LEFT + BAR_WIDTH, MIDDLE), BAR_FILL);
        assert_eq!(with_bar(base.rgba(), 64, 250), full);
    }

    #[test]
    fn the_inside_of_the_bar_is_the_empty_track_of_the_icon() {
        // the light inside of sakbackup-tray-run.svg from its left to its right and from its top
        // to its bottom, so the green fills the track
        let base = running();
        let light = |x, y| {
            let [r, g, b, a] = pixel(base.rgba(), 64, x, y);
            r > 0xd0 && g > 0xd0 && b > 0xd0 && a == 0xff
        };
        assert!(light(BAR_LEFT, MIDDLE) && light(BAR_LEFT + BAR_WIDTH - 1, MIDDLE));
        assert!(light(BAR_LEFT + 20, BAR_TOP) && light(BAR_LEFT + 20, BAR_BOTTOM));
        // and dark around it, its outline
        assert!(!light(BAR_LEFT - 1, MIDDLE) && !light(BAR_LEFT + BAR_WIDTH, MIDDLE));
        assert!(!light(BAR_LEFT + 20, BAR_TOP - 1) && !light(BAR_LEFT + 20, BAR_BOTTOM + 1));
        let idle = decode(IDLE).unwrap();
        assert_eq!((idle.width(), idle.height()), (64, 64));
    }
}
