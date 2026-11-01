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

The APK lands in `app/build/outputs/apk/debug/`. The Rust ad blocker needs `cargo-ndk`, the Android rust targets and NDK 27.1 (`-Padblock.skipNative=true` skips it).

### Release build and signing

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/MonoBrowser-<version>.apk
```

The version comes from `monobrowser.version` in `gradle.properties` (`versionCode = major*10000 + minor*100 + patch`). The release is signed when `keystore.properties` (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored) exists in the project root or the env vars `MONO_KEYSTORE_FILE`, `MONO_KEYSTORE_PASSWORD`, `MONO_KEY_ALIAS`, `MONO_KEY_PASSWORD` are set; otherwise the APK is left unsigned. Create a keystore with `keytool -genkeypair -keystore release.jks -alias monobrowser -keyalg RSA -keysize 4096 -validity 10000` and keep it (and its passwords) backed up: updates only install over builds signed with the same key.

Tagging `vX.Y.Z` runs `.github/workflows/release.yml`, which builds, signs and publishes the release with the APK and its `.sha256` (secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`; `scripts/print-keystore-secret.sh` prints the first). To test the updater against a local server, build with `-Pupdate.baseUrl=http://10.0.2.2:PORT/repos/<owner>/<repo>`.

## Features

Browsing
- Tabs and private tabs (separate WebView `Profile` when supported); swipe the address bar sideways to switch tabs; tabs survive restarts (normal tabs only, with back/forward state)
- At most 5 live WebViews: least-recently-used tabs go to sleep and wake on demand; per-tab "Never sleep"
- Workspaces: named groups of tabs (create, rename, icon, reorder, delete, move a tab between them); the tab switcher shows one workspace at a time
- Glance: long-press a link for a quick preview in a bottom sheet (own WebView, same profile, ad blocking on); "Open in tab" turns it into a real tab without reloading. A link menu (new / background / private tab, copy, share, preview) is available instead if you prefer
- Command palette (address bar): search, open tabs of all workspaces, bookmarks, history and commands in one place
- Bookmarks with folders (import/export HTML and JSON compatible with the desktop app), history, "Reopen closed tab", Downloads through the system DownloadManager
- Find in page, pull-to-refresh (a thin line instead of a spinner), address bar at the bottom or the top that hides while scrolling
- Notes: Markdown notes with a live preview, stored as plain `.md` files; "Add page to note" appends the title and URL

Privacy and control
- Built-in ad blocker (adblock-rust via JNI, desktop filter lists, per-site exceptions, counter)
- Per-site camera / microphone / location prompts, JS dialogs, file upload, safe handling of `mailto:` / `tel:` / `intent:` links
- Clear browsing data, per-site permission list, onboarding with search engine and toolbar choice

Look
- Strictly black / white / grey and rectangular, light and dark; optional flat colour themes from the desktop app
- English and Polish, launcher icon identical to the desktop app (adaptive + themed)

## Status

Phases 1-4 are implemented (browsing core, settings, ad blocker, bookmarks, palette, Glance, workspaces, notes). Not implemented yet: `blob:` downloads, certificate exceptions, a custom theme editor, persisted site-permission decisions. Everything was developed without a connected phone, so on-device testing is still pending.
