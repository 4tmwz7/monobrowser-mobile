# MonoBrowser Mobile

A minimalist monochrome browser for Android, the mobile sibling of [MonoBrowser](../monobrowser) (desktop, Electron). Native rewrite, not a port.

- Kotlin, Jetpack Compose, Android System WebView (`androidx.webkit`)
- Strictly black/white/grey, rectangular UI; address bar at the bottom
- Tabs and private tabs (separate WebView `Profile` when supported)
- Package / applicationId: `com.voluene.monobrowser`, minSdk 26, target SDK 36

## Install

Download `MonoBrowser-<version>.apk` (and optionally `MonoBrowser-<version>.apk.sha256`, check with `sha256sum -c`) from the [latest release](https://github.com/4tmwz7/monobrowser-mobile/releases/latest), open it and allow installing from that app when Android asks. Requires Android 8.0+.

## Updates

Release builds look for a newer GitHub release on start and every 6 hours (Settings → About → "Check for updates automatically", also a manual button). Nothing is downloaded until you tap **Update** in the banner (or in Settings → About); the APK is verified (SHA-256, package, signing key) and installed through the system installer. The first time, Android asks you to allow MonoBrowser to "install unknown apps". Debug builds never check for updates.

Release builds are signed with the project key, which differs from the debug key: switching from a debug build to a release build requires uninstalling once (this deletes app data).

## Build

```sh
export JAVA_HOME=/opt/android-studio/jbr
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/`.

UI tests (opt-in): Robolectric renders the real Compose screens on the JVM, without a device or emulator, drives the app (menu, settings, tabs, back gestures, reading list, dark pages) and saves screenshots to `build/screenshots/`:

```sh
./gradlew testDebugUnitTest -Pui.tests=true -Padblock.skipNative=true
```

The first run downloads Robolectric's Android image (~200 MB), which is why CI does not run them on every push. The Rust ad blocker needs `cargo-ndk`, the Android rust targets and NDK 27.1 (`-Padblock.skipNative=true` skips it).

### Release build and signing

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/MonoBrowser-<version>.apk
```

The version comes from `monobrowser.version` in `gradle.properties` (`versionCode = major*10000 + minor*100 + patch`). The release is signed when `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored) exists in the project root or the env vars `MONO_KEYSTORE_FILE`, `MONO_KEYSTORE_PASSWORD`, `MONO_KEY_ALIAS`, `MONO_KEY_PASSWORD` are set; otherwise the APK is left unsigned. Create a keystore with `keytool -genkeypair -keystore release.jks -alias monobrowser -keyalg RSA -keysize 4096 -validity 10000` and keep it (and its passwords) backed up: updates only install over builds signed with the same key.

Tagging `vX.Y.Z` runs `.github/workflows/release.yml`, which builds, signs and publishes the release with the APK and its `.sha256` (secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; `scripts/print-keystore-secret.sh` prints the first). To test the updater against a local server, build with `-Pupdate.baseUrl=http://10.0.2.2:PORT/repos/<owner>/<repo>`.

## Features

Browsing
- Start page on new tabs: clock, search, pinned and most visited sites (long-press a tile: pin / remove), reading list, recently closed tabs
- Address bar shows the site with a lock; tap the lock for site information (certificate, ad blocker and desktop site for the site, remembered permissions)
- Search suggestions (Google, DuckDuckGo, Bing, Ecosia; never in private tabs), recent pages in the empty palette; also Brave Search and Startpage
- Reader view (article text only; text size, serif/sans; offered in the address bar on article pages), read aloud (text-to-speech, page language, speed), and a reading list saved for offline reading
- Translate page (Google Translate, into the phone's language)
- Read aloud keeps going after reader view closes and is a regular media session: controls in the notification, on the lock screen, in Samsung's Now Bar (One UI 7+) and on a paired watch (Galaxy Watch media controls)
- Home screen search widget (search bar + private tab)
- Tracking parameters (utm_*, fbclid, gclid ...) are removed from links you open, copy and share
- Desktop site per tab, page text size, pinch-to-zoom, print / save as PDF, full screen video
- `target=_blank` / `window.open` open new tabs (pop-ups without a tap are blocked)
- `blob:` downloads (generated files); messages with actions ("Downloaded x · Open", "Undo", "Switch")
- Long-press the address bar for copy / paste and go / pin; long-press back or forward for the tab's history
- Tab grid with greyscale page previews; tabs and private tabs (separate WebView `Profile` when supported); swipe the address bar sideways to switch tabs; tabs survive restarts (normal tabs only, with back/forward state)
- At most 5 live WebViews: least-recently-used tabs go to sleep and wake on demand; per-tab "Never sleep"
- Workspaces: named groups of tabs (create, rename, icon, reorder, delete, move a tab between them); the tab switcher shows one workspace at a time
- Glance: long-press a link for a quick preview in a bottom sheet (own WebView, same profile, ad blocking on); "Open in tab" turns it into a real tab without reloading. A link menu (new / background / private tab, copy, share, preview) is available instead if you prefer
- Command palette (address bar): search, open tabs of all workspaces, bookmarks, history and commands in one place
- Bookmarks with folders (import/export HTML and JSON compatible with the desktop app), history, "Reopen closed tab", Downloads through the system DownloadManager
- Find in page, pull-to-refresh (a thin line instead of a spinner), address bar at the bottom or the top that hides while scrolling
- Notes: Markdown notes with a live preview, stored as plain `.md` files; "Add page to note" appends the title and URL

Privacy and control
- Built-in ad blocker (adblock-rust via JNI, desktop filter lists, per-site exceptions, counter)
- Per-site camera / microphone / location prompts (decisions are remembered; private tabs only for the session), JS dialogs, file upload, safe handling of `mailto:` / `tel:` / `intent:` links
- Clear browsing data, per-site permission list, onboarding with search engine and toolbar choice

Look and feel
- Strictly black / white / grey and rectangular, light and dark; optional flat colour themes from the desktop app
- Dark mode for websites follows the app (sites with a dark design use it; optionally darken all sites)
- Corners that touch the phone's rounded screen corners follow them (concentric radius, Android 12+); everything else stays square
- Predictive back (Android 14+): overlays shrink and reveal what is underneath while you swipe; back to the home screen preview when the app has nothing to go back to
- Animated overlays; swipe the tab list rows to close (with Undo); swipe up on the address bar for the tab list; long-press the tab counter for a new tab
- Back on a tab opened from a link returns to the tab it came from
- Search in settings; a "Gestures and shortcuts" page (also in the first-run flow)
- Launcher shortcuts (New tab, Private tab), Quick Settings tile (private tab), "Search in MonoBrowser" in the text selection menu, share links or text to the browser
- English and Polish, launcher icon identical to the desktop app (adaptive + themed)

Performance
- Page previews are copied from the screen on the GPU (PixelCopy), stored at half size, dropped under memory pressure
- Tabs that are not on screen are paused and their renderers deprioritised; in the background JavaScript timers stop - unless a page plays media
- Load progress and blocked-request counts do not recompose the whole UI; palette search keeps a per-list index (well under 1 ms per keystroke over thousands of bookmarks)
- Baseline profile for the app's own code; bookmarks load off the main thread at startup

## Status

Phases 1-4 are implemented (browsing core, settings, ad blocker, bookmarks, palette, Glance, workspaces, notes). Not implemented yet: certificate exceptions, a custom theme editor. Everything was developed without a connected phone, so on-device testing is still pending.
