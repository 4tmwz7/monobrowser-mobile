# MonoBrowser Mobile

A minimalist monochrome browser for Android, the mobile sibling of [MonoBrowser](../monobrowser) (desktop, Electron). Native rewrite, not a port.

- Kotlin, Jetpack Compose, Android System WebView (`androidx.webkit`)
- Strictly black/white/grey, rectangular UI; address bar at the bottom
- Tabs and private tabs (separate WebView `Profile` when supported)
- Package / applicationId: `com.voluene.monobrowser`, minSdk 26, target SDK 36

## Build

```sh
export JAVA_HOME=/opt/android-studio/jbr
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew assembleDebug testDebugUnitTest
```

The APK lands in `app/build/outputs/apk/debug/`.

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
