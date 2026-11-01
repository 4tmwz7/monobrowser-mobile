# THIRD-PARTY NOTICES

MonoBrowser Mobile uses the following third-party components. Each keeps its own licence.

## Ad blocking engine

### adblock-rust (crate `adblock` 0.13)
- Copyright: Brave Software
- License: Mozilla Public License, Version 2.0 (MPL-2.0) - https://www.mozilla.org/MPL/2.0/
- Source: https://github.com/brave/adblock-rust

The crate is used unmodified as a Cargo dependency of `native/adblock` (JNI bridge, which is part of this app).
Together with its own dependencies (`jni`, `log`, `android_logger`, `regex`, `serde` ... under MIT / Apache-2.0)
it is compiled into `libmonoadblock.so`.

## Filter lists (downloaded at runtime)

The lists keep the licence chosen by their authors; the licences cover the list content, not the application code.

- EasyList, EasyPrivacy, EasyList Cookie - CC BY-SA 3.0 (dual GPLv3) - https://easylist.to/
- EasyList Polish - CC BY-SA 3.0 - https://easylist-downloads.adblockplus.org/easylistpolish.txt
- uBlock Origin filter lists (filters, badware, privacy, quick-fixes, resource-abuse, unbreak, annoyances) -
  GPLv3 - https://github.com/uBlockOrigin/uAssets
- Peter Lowe's server and tracker blocklist - https://pgl.yoyo.org/adservers/
- Lists are fetched through the Ghostery adblocker assets mirror (MPL-2.0) -
  https://github.com/ghostery/adblocker

## Libraries

- AndroidX (Core, Activity, Lifecycle, Compose, WebKit) - Apache-2.0
- Kotlin, kotlinx.coroutines, kotlinx.serialization - Apache-2.0

## Color theme palettes

The colour themes use only palette values (hex codes) of the following themes; no theme code or assets are
bundled. Some values (muted text) were adjusted slightly to keep a text contrast of at least 4.5:1.

| Theme | Author | Source | License |
| --- | --- | --- | --- |
| Rose Pine (Main, Moon, Dawn) | Rose Pine contributors | https://github.com/rose-pine/rose-pine-palette | MIT |
| Everforest (Dark/Light) | sainnhe | https://github.com/sainnhe/everforest | MIT |
| Gruvbox (Dark, Light) | Pavel Pertsev (morhetz) | https://github.com/morhetz/gruvbox | MIT |
| Nord | Arctic Ice Studio / Sven Greb | https://github.com/nordtheme/nord | Apache-2.0 / MIT |
| Dracula | Dracula Theme | https://github.com/dracula/dracula-theme | MIT |
| Tokyo Night | enkia | https://github.com/enkia/tokyo-night-vscode-theme | MIT |
| Solarized (Dark, Light) | Ethan Schoonover | https://github.com/altercation/solarized | MIT |
| Kanagawa (Wave) | Tommaso Laurenzi (rebelot) | https://github.com/rebelot/kanagawa.nvim | MIT |
| One Dark | GitHub Inc. (Atom) | https://github.com/atom/one-dark-syntax | MIT |

The desktop edition lists the full licence texts in its own THIRD-PARTY-NOTICES.md.
