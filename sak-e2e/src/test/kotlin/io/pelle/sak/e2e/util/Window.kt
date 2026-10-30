package io.pelle.sak.e2e.util

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Route
import com.microsoft.playwright.options.ColorScheme
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

// the page of sakctl's window (sak-cli/ui) and the answers of rust for it, the fixtures that
// `cargo test window_fixtures -- --ignored` writes (core/deployments/window_fixtures.rs)
private val UI: Path = Paths.get(System.getProperty("sak.e2e.window.ui") ?: "../sak-cli/ui").toAbsolutePath().normalize()
private val FIXTURES: Path = Paths.get(System.getProperty("sak.e2e.window.fixtures") ?: "../build/ui-fixtures/window.json")

// where the page is served from, no server listens there
private const val ORIGIN = "http://sakctl.test"

// the size of the window (sak-cli/src-tauri/tauri.conf.json)
const val WINDOW_WIDTH = 736
const val WINDOW_HEIGHT = 686

private val TYPES =
    mapOf(
        "html" to "text/html",
        "js" to "text/javascript",
        "css" to "text/css",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "woff2" to "font/woff2",
        "woff" to "font/woff",
        "ttf" to "font/ttf",
    )

// window.__TAURI__ as the page uses it: invoke answers each command with its fixtures one after
// the other (the last one again and again), a command with a channel first sends its events with
// their delays. `__sakPace` scales the delays, `__sakHold` stops a channel before the event of the
// given index until `__sakResume` is called, so a screenshot can be taken while it runs
private val BRIDGE =
    """
    (() => {
      const fixtures = __FIXTURES__;
      const used = {};
      const holds = {};
      window.__sakPace = window.__sakPace ?? 1;
      window.__sakHold = window.__sakHold ?? {};
      window.__sakHeld = {};
      window.__sakResume = (command) => { holds[command]?.(); delete holds[command]; };
      const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms * window.__sakPace));
      class Channel { constructor() { this.onmessage = () => {}; } }
      // how long rust takes for a command without a channel
      const busy = { plan_deployment: 900, check_deployment: 900, add_provider_token: 1200, list_deployments: 300 };
      async function invoke(command, args = {}) {
        const events = fixtures.channels[command] ?? [];
        const channel = Object.values(args).find((value) => value instanceof Channel);
        for (const [index, { delayMs, message }] of events.entries()) {
          if (window.__sakHold[command] === index) {
            window.__sakHeld[command] = true;
            await new Promise((resolve) => { holds[command] = resolve; });
          }
          await sleep(delayMs);
          channel?.onmessage(message);
        }
        const answers = fixtures.answers[command];
        if (!answers) throw new Error('no fixture for ' + command);
        const index = Math.min(used[command] ?? 0, answers.length - 1);
        used[command] = (used[command] ?? 0) + 1;
        await sleep(busy[command] ?? 50);
        return structuredClone(answers[index]);
      }
      window.__TAURI__ = { core: { invoke, Channel } };
    })();
    """.trimIndent()

// a context that shows the window: its files from sak-cli/ui, rust replaced by the fixtures
fun Browser.window(
    scheme: ColorScheme = ColorScheme.LIGHT,
    options: Browser.NewContextOptions.() -> Unit = {},
): BrowserContext {
    check(Files.isDirectory(UI.resolve("theme"))) { "$UI/theme is missing, run mise run cli:theme" }
    check(Files.exists(FIXTURES)) { "$FIXTURES is missing, run cargo test window_fixtures -- --ignored in sak-cli/src-tauri" }
    val context =
        newContext(
            Browser
                .NewContextOptions()
                .setViewportSize(WINDOW_WIDTH, WINDOW_HEIGHT)
                .setColorScheme(scheme)
                .apply(options),
        )
    context.route("$ORIGIN/**") { route: Route ->
        val path = route.request().url().removePrefix(ORIGIN).substringBefore('?').trimStart('/').ifEmpty { "index.html" }
        val file = UI.resolve(path).normalize()
        if (!file.startsWith(UI) || !Files.isRegularFile(file)) {
            route.fulfill(Route.FulfillOptions().setStatus(404))
        } else {
            route.fulfill(
                Route
                    .FulfillOptions()
                    .setStatus(200)
                    .setContentType(TYPES[path.substringAfterLast('.')] ?: "application/octet-stream")
                    .setBodyBytes(Files.readAllBytes(file)),
            )
        }
    }
    context.addInitScript(BRIDGE.replace("__FIXTURES__", Files.readString(FIXTURES)))
    return context
}

// the page of the window, the scripts of the page run as in the app
fun BrowserContext.openWindow(
    pace: Double = 1.0,
    hold: Map<String, Int> = emptyMap(),
): Page {
    val holds = hold.entries.joinToString(",") { (command, index) -> "'$command': $index" }
    addInitScript("window.__sakPace = $pace; window.__sakHold = {$holds};")
    return newPage().also { it.navigate("$ORIGIN/") }
}

// waits until the channel of the command stopped at its hold
fun Page.waitForHold(command: String) {
    waitForFunction("command => window.__sakHeld[command] === true", command)
}

fun Page.resume(command: String) {
    evaluate("command => window.__sakResume(command)", command)
}
