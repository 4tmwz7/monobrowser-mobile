# MonoBrowser Mobile

A minimalist, strictly monochrome browser for Android: the mobile sibling of [MonoBrowser](../monobrowser) (desktop, Electron). It is a native rewrite, not a port.

- Kotlin, Jetpack Compose and Android System WebView (`androidx.webkit`), with an ad blocker in Rust (adblock-rust over JNI)
- Black / white / grey and rectangular, light and dark, English and Polish
- Package `com.voluene.monobrowser`, minSdk 26 (Android 8.0), target SDK 36 (Android 16)
- Current version: **0.2.0** ([release notes](.github/release-notes/v0.2.0.md))

## Contents

- [Install](#install)
- [Features](#features)
- [Address bar commands](#address-bar-commands)
- [Gestures](#gestures)
- [Samsung Now Bar and Live Updates](#samsung-now-bar-and-live-updates)
- [Updates](#updates)
- [Build](#build)
- [Releasing](#releasing)
- [Project layout](#project-layout)
- [Sync file format](#sync-file-format)
- [Status and known limitations](#status-and-known-limitations)
- [License](#license)

## Install

1. Download `MonoBrowser-<version>.apk` from the [latest release](https://github.com/4tmwz7/monobrowser-mobile/releases/latest). You can also download `MonoBrowser-<version>.apk.sha256` and check the APK with `sha256sum -c`.
2. Open the APK and allow installing from that app when Android asks.

MonoBrowser is not in the Play Store, so **Google Play Protect** may offer to scan it. Choose "Scan app", or install without scanning.

Release builds are signed with the project key, which differs from a debug build's key. To switch from a debug build to a release build, uninstall once. This deletes the app's data, so export bookmarks or sync to a file first.

## Features

### Browsing

- **Start page** on new tabs:
  - clock, search field, pinned and most visited sites (long-press a tile to pin or remove it);
  - reading list and recently closed tabs;
  - private tabs get an inverted page explaining what is not kept.
- **Address bar** at the bottom or at the top, hiding while you scroll:
  - shows the site with a lock (the full URL is a setting);
  - tap the lock for **site information**: connection and certificate, ad blocker and desktop site for this site, remembered permissions;
  - a reader-view chip appears on article pages.
- **Tabs and private tabs.** Private tabs use a separate WebView `Profile` when the WebView supports it. Normal tabs survive restarts with their back/forward history.
- **Tab grid** with greyscale page previews:
  - swipe a card to close it (with Undo), "close all", a filter field;
  - per-tab "Never sleep" and "move to workspace".
- **Workspaces:** named groups of tabs (create, rename, icon, reorder, delete). The tab grid shows one workspace at a time.
- **Memory:** at most 5 live WebViews. The least recently used tabs sleep and wake when shown. Tabs playing media and the floating video never sleep.
- **Glance:** long-press a link for a quick preview in a bottom sheet (own WebView, same profile, ad blocking on). "Open in tab" turns it into a real tab without reloading. A classic link menu (new / background / private tab, copy, share, preview) is available instead.
- **Links:** `target=_blank` and `window.open` open new tabs, and pop-ups without a tap are blocked. Back on a tab opened from a link returns to the tab it came from.
- **Page tools:**
  - find in page;
  - desktop site per tab, text size, pinch-to-zoom;
  - translate page (Google Translate, into the phone's language);
  - print / save as PDF;
  - full-screen video.
- **Pull to refresh** shows a thin line instead of a spinner. It only starts when the page itself is pulled past its top, so scrolling a list inside a page never reloads it. It also respects `overscroll-behavior`.
- **Bookmarks** with folders: import and export as HTML and as JSON, compatible with the desktop app.
- **History**, "Reopen closed tab", and **downloads** through the system DownloadManager, including `blob:` and `data:` downloads.
- **Notes:** Markdown notes with a live preview, stored as plain `.md` files. "Add page to note" appends the page's title and URL.
- **Messages with actions:** "Downloaded x · Open", "Undo", "Switch". They slide in and can be swiped away.

### Reading

- **Reader view:** article text only, with text size and serif / sans options. It handles long Wikipedia-style pages, collapsed sections included.
- **Read aloud:** text-to-speech in the page's language, with speed control. The current paragraph is highlighted and followed.
  - It keeps going after reader view closes.
  - It is a regular media session: notification, lock screen, Now Bar, paired watch.
- **"Read later" list:** articles are saved with their text, so they open offline.

### Video and music

- **Every page's audio and video becomes a media session**, like a music app's:
  - cover, title and artist, with previous / play-pause / next / stop and a seek bar;
  - shown in the notification, on the lock screen, in Samsung's Now Bar and on a paired watch (e.g. Galaxy Watch);
  - private tabs show no title or cover on the lock screen.
- **Where the metadata comes from:**
  - the page's Media Session;
  - YouTube's own player data, with the video thumbnail in the best available size;
  - otherwise the page title without the site suffix and the video poster or `og:image`.
  - Players that never put their element in the page (`new Audio()`: SoundCloud and many music sites) are detected too, and so are Web Audio players that declare their playback through the Media Session.
- **Background playback:** with the screen off, after leaving the app and after switching to another tab. The page always sees itself as visible.
  - While nobody can see it (background, floating window), a page cannot pause its own playback.
  - Pauses you ask for (notification, Now Bar, lock screen, window buttons) always work.
- **Picture in picture:**
  - Leaving the app while a video plays in the current tab shrinks it into the system floating window (setting, on by default).
  - The window shows only the video. Optionally it shows the whole page made small, with the site's own player controls.
  - Buttons: back 10 s / play-pause / forward 10 s, or previous / next track for playlists.
  - Closing the window pauses the video.
- **Floating video window inside the app** ("Video window" in the menu while a video plays), similar to Chromium browsers:
  - The playing tab moves into a window over the browser. Drag it anywhere and it settles into the nearest corner.
  - Tap it for back-to-tab, play-pause and close; double-tap to go back to the tab.
  - The same page opens underneath without autoplay, so comments and links can be read meanwhile.
  - Leaving the app turns it into the system window.

### Downloads

- Progress with the percentage, the size and a Cancel button in one notification.
- Android 16+: the notification is a **Live Update** (status bar chip, lock screen).
- Samsung devices also get the **Now Bar** (see [below](#samsung-now-bar-and-live-updates)).
- The first download asks for permission to post notifications. Without it, the system's own download notification shows the progress.

### Sync

- Sync goes through a file in your own cloud (Google Drive, OneDrive, Dropbox, Nextcloud ...). There is no account or server; the system file picker chooses the file.
- It covers bookmarks with folders, pinned sites, the reading list (with the saved text) and notes.
- It runs when the app opens and is left, and with "Sync now". Status: last sync, which device wrote the file, errors.
- Three-way merge with deletion records, so a deleted bookmark does not come back from another device. See the [file format](#sync-file-format).
- Setup: Settings → Sync → "Create a new sync file" on the first device, "Use an existing file" on the others.

### Privacy and control

- **Built-in ad blocker:** adblock-rust over JNI, the desktop app's filter lists (including cookie banner lists), per-site exceptions and a blocked counter. Lists update in the background, optionally only on Wi-Fi.
- **Tracking parameters** (`utm_*`, `fbclid`, `gclid` ...) are removed from links you open, copy and share.
- **Search engines:** Google, DuckDuckGo, Bing, Brave Search, Ecosia, Startpage or a custom URL. Search suggestions are optional and never used in private tabs.
- **Per-site permissions:**
  - camera, microphone and location prompts; decisions are remembered, and in private tabs only for the session;
  - a list of decisions in Settings.
- **Pages and links:** JS dialogs, file upload, and safe handling of `mailto:`, `tel:` and `intent:` links.
- **Clear browsing data;** first-run onboarding with search engine and toolbar choice.

### Look and feel

- Strictly black / white / grey and rectangular, light and dark, with optional flat colour themes from the desktop app.
- **Page theme** independent of the browser theme (for example a dark browser with light pages):
  - the global setting is system / light / dark, and each site can override it from the menu or the palette (applies to its subdomains too);
  - sites with a dark design use it, and sites without one can be darkened automatically (Android 13+).
- **Corners** that touch the phone's rounded screen corners follow them (concentric radius, Android 12+). Everything else stays square.
- **Motion:**
  - sheets rise from the bottom, dialogs pop in with a soft spring, menu tiles arrive one after another;
  - the tab grid zooms out of the tab counter and into the tab you pick;
  - the address-bar palette rises from the bar, reader view slides up, settings pages and the note editor slide in from the side.
  - Animations only change how layers are drawn (no layout work per frame), so they run at the display's full refresh rate.
- **Predictive back** (Android 14+, also on Samsung): pages lift off and follow the finger, and sheets and dialogs shrink towards their edge. Releasing completes, cancelling springs back. With nothing to go back to, the system shows its back-to-home preview.
- **Menu:** two rows of the main actions and "More" for the rest. While a video plays, "Video window" is one of the main tiles.
- **Settings:**
  - search field;
  - "Gestures and shortcuts" page (also in the first-run flow).

### Home screen and system integration

- **Home screen search widget:** a search bar plus a private-tab button, using the launcher's corner radius on Android 12+.
- **Launcher shortcuts:** New tab and Private tab.
- **Quick Settings tile:** private tab.
- **Text selection menu:** "Search in MonoBrowser".
- **Sharing:** share links or text to the browser.

### Performance

- **Page previews** are copied from the screen on the GPU (PixelCopy), stored at half size and dropped under memory pressure.
- **Tabs off screen** are paused and their renderers deprioritised. In the background, JavaScript timers stop unless a page plays media.
- **Fewer recompositions:** load progress and blocked-request counts do not recompose the whole UI.
- **Palette search** keeps a per-list index: well under 1 ms per keystroke over thousands of bookmarks.
- **Start-up:** baseline profile for the app's own code; bookmarks and settings load off the main thread.

## Address bar commands

Tapping the address bar gives an ordinary URL box with go / search, the search engine's suggestions, bookmarks and history. The page stays visible behind the results, and a tap on it closes them.

Typing **`/`** lists the commands; open tabs that match follow them. A command matches its name typed without spaces, in English or in Polish, and a prefix is enough (`/nowak`, `/sett`):

| Command | English | Polish |
| --- | --- | --- |
| New tab / private tab | `/newtab`, `/newprivatetab` | `/nowakarta`, `/nowakartaprywatna` |
| Settings | `/opensettings` | `/otworzustawienia` (also `/ustawienia`) |
| History, downloads, bookmarks | `/history`, `/downloads`, `/bookmarks` | `/historia`, `/pobrane`, `/zakladki` |
| Notes, new note, add page to note | `/notes`, `/newnote`, `/addpagetonote` | `/notatki`, `/nowanotatka`, `/dodajstronedonotatki` |
| Find in page, reader view | `/findinpage`, `/readerview` | `/znajdznastronie`, `/trybczytania` |
| Print / PDF | `/printpdf` (also `/print`) | `/drukujpdf` (also `/drukuj`) |
| Translate page, text size | `/translatepage`, `/textsize` | `/przetlumaczstrone`, `/rozmiartekstu` |
| Reading list, save for offline reading | `/readinglist`, `/saveforofflinereading` | `/doprzeczytania`, `/zapiszdoczytaniaoffline` |
| Reload, close tab, reopen closed tab, share | `/reload`, `/closetab`, `/reopenclosedtab`, `/sharelink` | `/odswiez`, `/zamknijkarte`, `/przywroczamknietakarte`, `/udostepnijlink` |
| New workspace, switch to a workspace | `/newworkspace`, `/switchtoworkspace<name>` | `/nowaprzestrzen`, `/przelacznaprzestrzen<nazwa>` |

Every command also has keyword aliases in both languages, for example `/ustawienia` or `/preferences`.

## Gestures

| Where | Gesture | Does |
| --- | --- | --- |
| Address bar | swipe sideways | previous / next tab |
| Address bar | swipe up | tab grid |
| Address bar | long-press | copy, paste and go, pin, edit, translate, share |
| Back / forward buttons | long-press | the tab's history |
| Tab counter | long-press | new tab |
| Link | long-press | Glance preview (or the link menu) |
| Start page tile | long-press | open in new tab, copy, pin / unpin, remove |
| Tab card, message | swipe sideways | close / dismiss |
| Floating video | drag / tap / double-tap | move / buttons / back to the tab |
| Screen edge | back gesture | predictive back on pages, sheets and dialogs |

## Samsung Now Bar and Live Updates

- **Media and read aloud** use a regular media session. They appear in the Now Bar on One UI 7+ without anything else.
- **Downloads** appear as an Android 16 Live Update with Samsung's own Live Notification fields (title, percentage, size, progress bar), which the Now Bar displays. The manifest declares `com.samsung.android.support.ongoing_activity`.
- **Third-party apps in the Now Bar:** One UI 8 only shows these when *Developer options → Live notifications for all apps* is on. Samsung's documentation is not public, so this follows what other apps found.
- Before Android 16, download progress is an ordinary notification with a progress bar.

## Updates

- **When it checks:** release builds look for a newer GitHub release on start and every 6 hours. It can be turned off in Settings → About, which also has a manual check.
- **When it downloads:** nothing is downloaded until you tap **Update** in the banner or in Settings → About.
- **Verification:** the APK's SHA-256, package name and signing key are checked before the system installer opens.
- The first time, Android asks you to allow MonoBrowser to "install unknown apps".
- Debug builds never check for updates.

## Build

Requirements:
- JDK 17 (Gradle toolchain) and JDK 21;
- Android SDK platform 36, build-tools 36.0.0, NDK 27.1.12297006;
- for the ad blocker: Rust with `cargo-ndk` and the Android targets (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`).

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Add `-Padblock.skipNative=true` to build without the Rust library; the ad blocker is then off. Rust tests: `cargo test --manifest-path native/adblock/Cargo.toml`.

### UI tests (opt-in)

Robolectric renders the real Compose screens on the JVM, without a device or emulator. The tests drive the app (menu, settings, tabs, back gestures, reading list, dark pages, sync between two "devices", widget through `RemoteViews`, media services) and save screenshots to `build/screenshots/`:

```sh
./gradlew testDebugUnitTest -Pui.tests=true -Padblock.skipNative=true
```

The first run downloads Robolectric's Android image (~200 MB), which is why CI does not run them on every push.

## Releasing

The version comes from `monobrowser.version` in `gradle.properties` (`versionCode = major*10000 + minor*100 + patch`). Release notes live in `.github/release-notes/vX.Y.Z.md`.

```sh
./gradlew assembleRelease   # -> app/build/outputs/apk/release/MonoBrowser-<version>.apk
```

**Signing:**
- The release is signed when `keystore.properties` exists in the project root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; git-ignored).
- Alternatively, set the env vars `MONO_KEYSTORE_FILE`, `MONO_KEYSTORE_PASSWORD`, `MONO_KEY_ALIAS`, `MONO_KEY_PASSWORD`.
- Otherwise the APK is left unsigned.
- Create a keystore with `keytool -genkeypair -keystore release.jks -alias monobrowser -keyalg RSA -keysize 4096 -validity 10000`. Keep it and its passwords backed up: updates only install over builds signed with the same key.

**Publishing** runs `.github/workflows/release.yml`, which tests, builds, signs, verifies (package, version, signature) and publishes the APK with its `.sha256`. It starts in either of two ways:
- push a tag `vX.Y.Z` that matches `monobrowser.version`, or
- **Actions → Release → Run workflow** on `main` with the version. The workflow creates the tag on the commit it built.

Secrets: `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`. `scripts/print-keystore-secret.sh` prints the first one.

To test the updater against a local server, build with `-Pupdate.baseUrl=http://10.0.2.2:PORT/repos/<owner>/<repo>`.

## Project layout

```
app/src/main/kotlin/com/voluene/monobrowser/
  MainActivity.kt        window, picture in picture, system pickers, PixelCopy
  MonoApp.kt             process-wide read aloud and page media
  browser/               TabManager (tabs, WebViews, sleep, media bridge, floating video), WebView client, intents
  adblock/               filter lists, Rust engine (JNI), cosmetic filtering, exceptions
  media/                 media sessions (notification, Now Bar, watch), page media script, cover art
  download/              DownloadManager, blob: downloads, progress notification / Live Update / Now Bar
  reader/                reader extraction, read aloud, reading list
  sync/                  sync file (SAF), three-way merge, bookmark mapping
  data/                  bookmarks, history, session, top sites, workspaces
  notes/  search/  settings/  update/  util/
  ui/                    Compose screens: browser (address bar, menu, sheets, floating video), tabs, palette,
                         start page, reader, settings, bookmarks, history, downloads, notes, common (motion,
                         modals, predictive back, screen corners)
native/adblock/          Rust JNI bridge around adblock-rust
app/src/test/            unit tests;  app/src/uiTest/  Robolectric UI tests (opt-in)
```

## Sync file format

`monobrowser-sync.json` is plain JSON, so other MonoBrowser builds can share it (desktop, e.g. through Google Drive for desktop):

```json
{"format": "monobrowser-sync", "version": 1, "updatedAt": 1730000000000, "updatedBy": "Samsung SM-S921B",
 "collections": {
   "bookmarks": {"f\u001EWork": {"v": "", "t": 1730000000000},
                 "b\u001EWork\u001F\u001Fhttps://kotlinlang.org/": {"v": "Kotlin", "t": 1730000000000}},
   "pinned":    {"https://github.com/": {"v": "GitHub", "t": 1730000000000}},
   "reading":   {"https://example.com/a": {"v": "<saved article JSON>", "t": 1730000000000}},
   "notes":     {"n-abc123": {"v": "# Markdown text", "t": 1730000000000}}}}
```

- **Entries:** every entry is `{v: value, t: last change (epoch ms)}`. `v: null` records a deletion, kept for 90 days.
- **Bookmark keys:**
  - a folder's key is its path (folder names joined by U+001F) after the prefix `f` + U+001E;
  - a bookmark's key is the folder path, then U+001F U+001F and the URL, after the prefix `b` + U+001E.
- **Merging:** each device keeps the state of its last sync and merges three-way. Its own changes since then win; everything else comes from the file.

## Status and known limitations

The browsing core, settings, ad blocker, bookmarks, palette, Glance, workspaces, notes, reader, media, picture in picture, sync and widget are implemented. Parts have been tried on a Samsung phone (One UI 8); a lot has only been tested with unit and Robolectric tests.

Known limitations:
- **Floating window:** the page under the floating video is a second copy of the page. Actions there (likes, comments) do not know the video's position.
- **Whole-page picture in picture:** shows the page as it is scrolled. Pages whose player is not fixed at the top may show other content.
- **Downloads on Samsung:** the Now Bar and the standard Live Update chip may not show at the same time (Samsung moves the notification to its own Live Notification lane).
- **Play Protect:** offers to scan the app on install because it is not in the Play Store.
- **Not implemented yet:** certificate exceptions, a custom theme editor.

## License

Apache License 2.0 (see [LICENSE](LICENSE)). You may use, modify and redistribute the code, also commercially, as long as you keep the copyright notice and the [NOTICE](NOTICE) file and credit the original project in your documentation and in your app's credits / licences screen. Third-party components keep their own licences ([THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)).
