# AndroidDeck

Turn an Android phone into a stream deck for any PC (Windows / macOS / Linux) on the same network.
One app stays connected to several PCs at once; switch between them from the top bar.

```
android/   Android app (Kotlin · Jetpack Compose · Material 3 · Glance widget)
server/    PC server (Python 3.10+ · aiohttp · psutil · pynput · pystray)
```

## Features

| Area | What it does |
|---|---|
| Deck | Pages of buttons, long-press and drag to reorder, colors / symbols / app icons, **folder buttons** (2×2 preview) |
| Launch apps | Browse and search the PC's installed apps **with their real icons** from the phone |
| Hotkeys | Key-combo editor plus per-OS presets |
| Multi-actions | Run several actions in order with a per-step delay (0–5 s), e.g. launch an app, then send a hotkey |
| Audio | Master volume, **microphone mute** (state dot on the button), **per-app mixer**, **now playing** (title, artist, album art) |
| Trackpad | Use the phone as a mouse: move, tap to click, two-finger scroll / tap for right click, hold to drag, live keyboard input (IME text included) |
| Monitoring | CPU (per core, temperature, clock), RAM, GPU, disks, network, top processes, 60 s charts |
| Multiple PCs | All PCs connected at once, top-bar switcher, **buttons that target another PC**, copy a page to another PC |
| Wake-on-LAN | Wake a sleeping or powered-off PC from the phone (offline banner, device list, deck button) |
| Discovery | Finds PCs by UDP broadcast and reconnects automatically, even after an IP change |
| Widget & tiles | Home-screen widget (up to 8 buttons) and 3 Quick Settings tiles, usable without opening the app |
| Backup | Export / import the deck as JSON (add pages or replace) |
| Security | TLS with certificate pinning, PIN pairing |
| PC server | Tray icon, start at login, standalone executable that needs no Python |

The deck layout is **stored on each PC** (each PC has different apps installed). Several phones paired with the same PC share its layout and stay in sync.

## Quick start

### 1. Run the server on the PC

**Standalone executable (recommended).** Download the file for your OS from GitHub Releases and run it. The **control panel** opens in your browser with the 6-digit PIN, paired phones and *Start when I sign in*, and a tray (menu bar) icon appears. Start at sign-in is turned on the first time, so the server comes back after a reboot.

To see the PIN again later, click the tray icon (*Pair a phone / show PIN…*) or just start AndroidDeck Server again: a second launch opens the panel of the one already running.

| OS | File | First run |
|---|---|---|
| Windows | `AndroidDeckServer-x.y.z-windows.exe` | SmartScreen warning → *More info → Run anyway*; allow *Private networks* in the firewall prompt |
| macOS | `AndroidDeckServer-x.y.z-macos.zip` | Unzip, then right-click the app → *Open* (the app is unsigned) |
| Linux | `AndroidDeckServer-x.y.z-linux-x64.tar.gz` | `tar xzf …` then `./AndroidDeckServer` |

**From source.** Requires Python 3.10+. The first run installs dependencies into `server/.venv`.

| OS | Run |
|---|---|
| Windows | `server\start-windows.bat` |
| macOS | `server/start-macos.command` |
| Linux | `./server/start-linux.sh` (add `--tray` for tray mode) |

The control panel (or the console window when run from source) shows the name, address, **6-digit PIN** and **certificate fingerprint**. In console mode the panel URL is printed in the log.

### 2. Install the app

```bash
cd android
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. Pair

Open the app on the same Wi-Fi and the PC shows up automatically → **Pair** → enter the PIN.
Check that the **fingerprint in the PIN dialog matches the one on the PC**; if it does, no other device is intercepting the connection.
After pairing, the app connects on its own every time. Add more PCs from the PC name at the top left → *Add or manage PCs* (a dot on the name means an unpaired PC was found).

## Usage

- **Navigation**: swipe left and right. The deck's pages come first, then Audio, System and Touch (last, since the trackpad takes every touch); swiping the top bar also changes screens. The icons at the top show where you are and jump there when tapped. Dots under the deck show its pages, and the page name appears briefly when you swipe to it. Back leaves edit mode, then returns to the first deck page, before leaving the app.
- **Highlight color**: Settings → *Highlight color* picks the accent (Lime by default, seven other presets, or a custom color from the hue slider or a hex code). It colors selection, sliders, the widget and buttons set to the highlight color, including the default Play button.
- **Layout**: landscape by default (Settings → *Orientation*: Landscape / Portrait / Auto). Buttons are sized so the whole page fits and stays centered on the screen, with equal margins around the camera cutout (tuned for the Galaxy Z Fold cover display). Settings → *Button size* switches from *Auto* to Small/Medium/Large with scrolling; *Full screen* hides the status and navigation bars (swipe from an edge to show them). Audio and System split their sections into as many columns as the screen needs, so they fit without scrolling where possible (only long lists such as many mixer apps or the System details scroll).
- **Edit mode**: ⋮ (top right) → *Edit deck* → tap a button to edit it, `+` to add one, long-press and drag to reorder, *Done* to finish. Page tabs appear in edit mode: *+ Page* adds a page, tapping the selected tab renames, moves, copies or deletes it. Outside edit mode, long-press a button to edit it directly. Every page uses the same grid, sized for the fullest page, so buttons keep their size between pages and when edit mode starts.
- **Lock deck**: ⋮ → *Lock deck* stops long presses from opening the editor and hides *Edit deck* (for example while streaming); ⋮ → *Unlock deck* undoes it.
- **Organizing**: the editor's *Place* moves a button to another page or into or out of a folder; *Duplicate* copies it next to the original; a deleted button can be restored with *Undo* in the message that follows.
- **Toggle buttons**: *Two states → Toggle button* makes presses alternate between the button's action (turns it on) and a second action (turns it off). While on, the tile lights up with an *ON* badge and an optional second label. The on/off state is kept on the phone.
- **Push-to-talk**: a *Hotkey* button with *Hold while pressed* keeps its keys down while your finger is on the button. The PC releases them if the phone disconnects.
- **Long press**: *Long press → Long-press action* runs a second action on a long press instead of opening the editor (edit those buttons from ⋮ → *Edit deck*).
- **Folders**: choose *Folder* as the action type and save. In edit mode, tap the folder to open it and add buttons. Rename or recolor it with the pencil inside the folder.
- **Multi-actions**: *Multi* → add steps and set each step's delay and action. If a step fails, the error names it ("step N: …").
- **Targeting another PC**: with two or more PCs paired, the editor shows *Run on*. Buttons that target another PC show a small PC icon in the top-right corner.
- **Wake-on-LAN**: the app remembers the MAC address of every PC it has connected to once. Wake an offline PC with *Wake* in the banner or device list, or with a *Wake PC* button. Wake-on-LAN must be enabled in the PC's BIOS / network adapter settings.
- **Sizes**: in the button editor, *Size* sets rows × columns (up to 4 × 4) for any button or widget, wide or tall. Items wider than the screen's columns are narrowed; items keep their order, and a tall item leaves room beside it for the next ones.
- **Widgets**: in the editor, *Type → Widget* adds a live control instead of a button: *Volume* or *Mic level* (a fader), *App volume* (one app's level and mute; found again by name after the app restarts), *Now playing* (art, title, previous / play-pause / next) and *PC monitor* (CPU, RAM, plus GPU and disk when tall enough). Faders: drag along the long side, tap to mute; wide ones slide sideways, square and tall ones up and down. Widgets need the updated PC server (older servers drop them when the deck is saved).
- **Live state**: Mic and Mute buttons turn red with a *MUTED* badge while muted; play/pause shows when media plays. When the PC goes offline the deck dims and live state is cleared until it reconnects. A press that fails, or gets no answer within about 3 s (longer for multi-actions with delays), flashes the tile red and buzzes differently from a normal press.
- **Mic actions**: *Toggle* flips the mic, *Mute* always mutes and *Unmute* always unmutes, so a "going AFK" multi-action can never turn the mic on. The volume *Mute* button stays a toggle.
- **Sliders**: drag to change a level; the change is relative to where your finger lands, and a plain tap changes nothing. Use the − / + buttons for single steps.
- **Widget & tiles**: turn on *Show in widget & quick tiles* in the button editor, then add the *Deck buttons* widget to the home screen or the *Deck 1–3* tiles to Quick Settings (the first three pinned buttons become tiles; reorder them in Settings → *Widget & quick tiles*).
- **PC controls widget**: a home-screen widget for the PC selected in the app: volume (− / level / + / mute) at 4 × 1, plus mic, media (previous / play-pause / next, title) when taller, and CPU / RAM at full height. It updates after each press, on its refresh button and every 30 minutes; home-screen widgets cannot drag, so levels change in steps.
- **Touch tab**: the screen becomes a trackpad. The keyboard button sends what you type on the phone to the PC as you type. Pointer speed and scroll direction are in Settings.
- **Backup**: Settings → *Deck backup* to export or import. To send a single page to another PC, tap the selected page tab again in edit mode → *Copy this page to*.

## Platform notes

| | Windows | macOS | Linux |
|---|---|---|---|
| App list & icons | Start menu "All apps" incl. Store apps (PowerShell) | `.app` bundles in `/Applications` etc. (`sips`) | `.desktop` files + icon theme |
| Volume & mic | Core Audio (pycaw) | AppleScript (mic mute = input volume 0) | wpctl → pactl → amixer |
| Per-app mixer | ✅ | ❌ (no public API) | ✅ PipeWire / PulseAudio |
| Now playing | ✅ System media session (Python 3.11+) | Spotify and Music only (asks for Automation permission once) | ✅ MPRIS players |
| Keyboard & mouse | ✅ | Needs *Accessibility* permission | X11 ✅ / **Wayland needs `ydotool` + `ydotoold`** |
| Tray icon | ✅ Left click opens the control panel | Menu bar | AppIndicator / XEmbed trays (GNOME needs an extension). The standalone build may show the icon without a menu; left click opens the control panel |

On Linux, if `python3 -m venv` fails run `sudo apt install python3-venv`; if building `evdev` fails run `sudo apt install python3-dev build-essential`.

## Server options

```
AndroidDeckServer [--tray | --console] [--port 47777] [--name NAME] [--new-pin] [--revoke-all]
                  [--autostart on|off|status] [--login] [--no-discovery] [--host 0.0.0.0] [--paths] [-v]
```

The executable starts in tray mode by default and opens the control panel; running from source starts in console mode. `--login` (used by the start-at-login entry) stays in the tray without opening a window.

**Start at sign-in** is turned on the first time the tray app runs (Windows Run key, macOS LaunchAgent, Linux XDG autostart) and its path is refreshed on every start, so moving the executable keeps it working. Turn it off in the control panel or tray menu and it stays off. It starts with your desktop session, not at boot: the server needs a signed-in session for keyboard, mouse and audio, so enable automatic sign-in if the PC must be usable right after power-on. It is not turned on automatically when `ANDROIDDECK_HOME` is set.

Settings, certificate, deck and log (`config.json`, `tls.key/crt`, `profile.json`, `server.log`, `panel.json`) live in:

| OS | Path |
|---|---|
| Windows | `%APPDATA%\AndroidDeck\` |
| macOS | `~/Library/Application Support/AndroidDeck/` |
| Linux | `~/.config/androiddeck/` |

Set the `ANDROIDDECK_HOME` environment variable to use a different folder (portable installs, tests).

## Security

- All traffic is **TLS (HTTPS / WSS)**. On first run the server creates a self-signed certificate; the phone **pins** its SHA-256 fingerprint when pairing and refuses any other certificate afterwards.
- The moment of first pairing is the only weak point (trust on first use). Comparing the fingerprint in the PIN dialog with the one on the PC removes that risk.
- Five wrong PINs block that IP for 60 seconds. The PC stores only hashes of device tokens. Manage pairing in the control panel (new PIN, unpair one or all phones; an unpaired phone is disconnected at once) or with `--new-pin` / `--revoke-all`.
- The control panel listens on `127.0.0.1` only, on a random port under a random secret path (stored in `panel.json`, readable only by your user), and rejects foreign `Host` and `Origin` headers. It is not reachable from the network.
- A paired phone can launch apps, type, move the mouse and run shell commands. If you don't need shell commands, set `"allow_commands": false` in `config.json`.
- Deleting the PC's certificate (`tls.key`) changes its fingerprint, so every phone has to pair again.

## Protocol (v2)

| Endpoint | Description |
|---|---|
| UDP 47778 | `{"t":"androiddeck.discover"}` → `{"t":"androiddeck.here", id, name, os, port, tls, fp, …}` |
| `GET /api/info` | Server info (no auth) |
| `POST /api/pair` | `{pin, device}` → `{token, server, wol}` |
| `GET /api/apps[?refresh=1]`, `GET /api/icon/{id}` | App list, icons |
| `GET /api/media/art` | Now-playing album art (local file or system thumbnail) |
| `GET/PUT /api/profile` | Deck layout; buttons may carry `cols` / `rows` (1–4), a `widget` (`volume`, `mic`, `mixer`, `media`, `monitor`), a `toggle` (second action, label, color), a `longAction`, and hotkey actions `hold: true` |
| `POST /api/action` | One-shot action (widget and tiles) |
| `GET /api/state` | Volume, mic, media and CPU / RAM in one response (home-screen controls widget) |
| `GET /ws` | Live channel: send `action` · `sub` · `mouse` · `scroll` · `click` · `button` · `type` · `key` · `hold` (key combo down/up, released on disconnect); receive `welcome` · `ack` · `stats` · `volume` · `mic` · `mixer` · `media` · `profile` |

Every request except `/api/info` and `/api/pair` needs `Authorization: Bearer <token>`. Ports: TCP 47777 (HTTPS / WSS) and UDP 47778 (discovery).

## CI / releases (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | Push to `main`, PRs, manual | Builds, unit-tests (JVM + Robolectric layout smoke tests) and lints the Android app; runs ruff and the [smoke test](server/tests/smoke_test.py) on Ubuntu, Windows and macOS, then **builds the standalone executable and smoke-tests that executable too** |
| [`release.yml`](.github/workflows/release.yml) | `v*` tag push, manual | Attaches a signed APK, per-OS server executables, a source zip and `SHA256SUMS.txt` to a **draft release**; a person publishes it |

The smoke test starts the server in an isolated folder and checks TLS pinning, discovery, pairing, the app list and icons, the profile, multi-actions, the mixer, the WebSocket channel and the control panel (secret path, Host/Origin checks, new PIN, unpair closes the session). It never sends actions that affect the PC.

```bash
cd server && python tests/smoke_test.py --min-apps 1 --min-icons 1
python packaging/build.py && python tests/smoke_test.py --exe dist/AndroidDeckServer   # test the executable
```

Every release must be signed with the same key, or phones cannot update the app in place. Add `ANDROIDDECK_KEYSTORE_BASE64`, `ANDROIDDECK_KEYSTORE_PASSWORD`, `ANDROIDDECK_KEY_ALIAS` and `ANDROIDDECK_KEY_PASSWORD` to the repository secrets (without them the APK is signed with a throwaway debug key and the workflow warns).

```bash
keytool -genkeypair -keystore release.jks -alias androiddeck -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # → ANDROIDDECK_KEYSTORE_BASE64
```

Back up the key file and passwords outside the repository. If they are lost, existing installs can no longer be updated.

## Verification status

Verified in this repository (Linux, Ubuntu 24.04 GNOME Wayland / PipeWire):

- Android unit tests, 29 passing (`./gradlew :app:testDebugUnitTest`): deck grid fit at Fold cover and inner sizes, section column split (checked against brute force), highlight colors and text contrast ≥ 4.5:1, span packing (order kept, no overlaps), one grid for every page with no change when edit mode starts, JSON round trips of sizes / toggles / hold / long press and of the live-state response, home-widget control actions, section layout in landscape/portrait, muted/offline tile states and every widget at four sizes (Robolectric)
- Server smoke test, 48 checks passing (fresh venv, both from source and as the PyInstaller executable): TLS and wrong-fingerprint rejection, discovery, pairing, 67 apps / 66 icons, profile validation, multi-actions with failing-step reporting, mixer, stats
- Per-app mixer: listing, volume (0.42 round-trip) and mute on a real playback stream
- Now playing: title (quotes and Korean), artist, local album art and play/pause command against a fake MPRIS player
- Wake-on-LAN info (MAC, broadcast) extraction, tray mode startup, start-at-login on/off
- Start at sign-in on by default on first tray run, opt-out respected, moved path refreshed; a second launch exits at once and opens the running panel (isolated config folder, browser call stubbed). Control panel rendered in Chrome (dark and light, desktop and narrow widths)
- Android: screens rendered against two real servers (Robolectric): pairing with fingerprint shown, wrong-fingerprint rejection, switching between two PCs, cross-PC button run and acknowledged, multi-action, folder, audio (now playing, mic, mixer), Touch, System, Settings. Lint: 0 issues

Not yet verified: **running on Windows and macOS** (PowerShell icon extraction, pycaw mixer and mic, system media session, sips, PyInstaller builds) — the Windows/macOS CI jobs are the first place this runs; **installation on a real phone** (widget, tiles and trackpad feel can only be checked on a device); real app launches, keystrokes and mouse movement (deliberately not sent during testing so the PC wasn't controlled); an actual Wake-on-LAN wake-up.

### Verification checklist

| # | Step | Pass criterion |
|---|---|---|
| 1 | Start the server, open the app | PC appears within 2–3 s; fingerprint in the PIN dialog = fingerprint on the PC |
| 2 | Vol ± / Mute / Mic on the default deck | PC volume changes; Mic tile turns red with *MUTED* (muted) ↔ green dot |
| 2a | A *Mic: Mute* button while the mic is already muted, on each OS | Mic stays muted |
| 3 | Audio tab | Current track and album art shown; an app slider changes only that app's volume |
| 4 | Add an App button → tap it | Real icon shown; the app launches on the PC |
| 5 | Folder and Multi buttons | Folder opens; multi-action steps run in order with their delays |
| 6 | Touch tab | Pointer move, click, two-finger scroll, right click; typing (including Korean) arrives on the PC |
| 7 | Pair a second PC | Top-bar switching, *Run on* button runs on the other PC, page copy works |
| 8 | Put the PC to sleep → *Wake* | PC wakes up and the app reconnects (requires WoL in BIOS) |
| 9 | Widget and tiles | Buttons run from the widget/tiles with the app closed |
| 10 | Reboot the PC (start at sign-in is on by default) | Server starts after sign-in; phone reconnects automatically |
| 11 | Click the tray icon, or start AndroidDeck Server again | Control panel opens with the current PIN; *New PIN* and *Unpair* work |
| 12 | Export deck → import (add) | Pages are duplicated and appended |
| 13 | A push-to-talk button: hold it, then turn the phone's Wi-Fi off | The key is released on the PC when the connection drops |
| 14 | *PC controls* widget on the home screen | Volume, mute and play/pause act on the selected PC; the widget shows the new state after each press |

If something goes wrong, run the server with `-v` or check `server.log`.

