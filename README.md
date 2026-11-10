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

**Standalone executable (recommended).** Download the file for your OS from GitHub Releases and run it. A tray (menu bar) icon appears; its menu shows the PIN and lets you generate a new PIN, unpair phones and enable **Start at login**.

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

The server window (or tray menu) shows the name, address, **6-digit PIN** and **certificate fingerprint**.

### 2. Install the app

```bash
cd android
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
adb install app/build/outputs/apk/release/app-release.apk
```

### 3. Pair

Open the app on the same Wi-Fi and the PC shows up automatically → **Pair** → enter the PIN.
Check that the **fingerprint in the PIN dialog matches the one on the PC**; if it does, no other device is intercepting the connection.
After pairing, the app connects on its own every time. Add more PCs with **+** in the top bar (a `+ 1 new` badge appears when an unpaired PC is found).

## Usage

- **Edit mode**: tap the pencil → tap a button to edit it, `+` to add one, long-press and drag to reorder. Outside edit mode, long-press a button to edit it directly.
- **Folders**: choose *Folder* as the action type and save. In edit mode, tap the folder to open it and add buttons. Rename or recolor it with the pencil inside the folder.
- **Multi-actions**: *Multi* → add steps and set each step's delay and action. If a step fails, the error names it ("step N: …").
- **Targeting another PC**: with two or more PCs paired, the editor shows *Run on*. Buttons that target another PC show a small PC icon in the top-right corner.
- **Wake-on-LAN**: the app remembers the MAC address of every PC it has connected to once. Wake an offline PC with *Wake* in the banner or device list, or with a *Wake PC* button. Wake-on-LAN must be enabled in the PC's BIOS / network adapter settings.
- **Widget & tiles**: turn on *Show in widget & quick tiles* in the button editor, then add the *Deck buttons* widget to the home screen or the *Deck 1–3* tiles to Quick Settings (the first three pinned buttons become tiles).
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
| Tray icon | ✅ | Menu bar | Desktops with AppIndicator support (GNOME needs an extension) |

On Linux, if `python3 -m venv` fails run `sudo apt install python3-venv`; if building `evdev` fails run `sudo apt install python3-dev build-essential`.

## Server options

```
AndroidDeckServer [--tray | --console] [--port 47777] [--name NAME] [--new-pin] [--revoke-all]
                  [--autostart on|off|status] [--no-discovery] [--host 0.0.0.0] [--paths] [-v]
```

The executable starts in tray mode by default; running from source starts in console mode. Settings, certificate, deck and log (`config.json`, `tls.key/crt`, `profile.json`, `server.log`) live in:

| OS | Path |
|---|---|
| Windows | `%APPDATA%\AndroidDeck\` |
| macOS | `~/Library/Application Support/AndroidDeck/` |
| Linux | `~/.config/androiddeck/` |

Set the `ANDROIDDECK_HOME` environment variable to use a different folder (portable installs, tests).

## Security

- All traffic is **TLS (HTTPS / WSS)**. On first run the server creates a self-signed certificate; the phone **pins** its SHA-256 fingerprint when pairing and refuses any other certificate afterwards.
- The moment of first pairing is the only weak point (trust on first use). Comparing the fingerprint in the PIN dialog with the one on the PC removes that risk.
- Five wrong PINs block that IP for 60 seconds. The PC stores only hashes of device tokens. Manage pairing with `--new-pin`, `--revoke-all` or the tray menu.
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
| `GET/PUT /api/profile` | Deck layout |
| `POST /api/action` | One-shot action (widget and tiles) |
| `GET /ws` | Live channel: send `action` · `sub` · `mouse` · `scroll` · `click` · `button` · `type` · `key`; receive `welcome` · `ack` · `stats` · `volume` · `mic` · `mixer` · `media` · `profile` |

Every request except `/api/info` and `/api/pair` needs `Authorization: Bearer <token>`. Ports: TCP 47777 (HTTPS / WSS) and UDP 47778 (discovery).

## CI / releases (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| [`ci.yml`](.github/workflows/ci.yml) | Push to `main`, PRs, manual | Builds and lints the Android app; runs ruff and the [smoke test](server/tests/smoke_test.py) on Ubuntu, Windows and macOS, then **builds the standalone executable and smoke-tests that executable too** |
| [`release.yml`](.github/workflows/release.yml) | `v*` tag push, manual | Attaches a signed APK, per-OS server executables, a source zip and `SHA256SUMS.txt` to a **draft release**; a person publishes it |

The smoke test starts the server in an isolated folder and checks TLS pinning, discovery, pairing, the app list and icons, the profile, multi-actions, the mixer and the WebSocket channel. It never sends actions that affect the PC.

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

- Server smoke test, 25 checks passing (fresh venv, both from source and as the PyInstaller executable): TLS and wrong-fingerprint rejection, discovery, pairing, 67 apps / 66 icons, profile validation, multi-actions with failing-step reporting, mixer, stats
- Per-app mixer: listing, volume (0.42 round-trip) and mute on a real playback stream
- Now playing: title (quotes and Korean), artist, local album art and play/pause command against a fake MPRIS player
- Wake-on-LAN info (MAC, broadcast) extraction, tray mode startup, start-at-login on/off
- Android: screens rendered against two real servers (Robolectric): pairing with fingerprint shown, wrong-fingerprint rejection, switching between two PCs, cross-PC button run and acknowledged, multi-action, folder, audio (now playing, mic, mixer), Touch, System, Settings. Lint: 0 issues

Not yet verified: **running on Windows and macOS** (PowerShell icon extraction, pycaw mixer and mic, system media session, sips, PyInstaller builds) — the Windows/macOS CI jobs are the first place this runs; **installation on a real phone** (widget, tiles and trackpad feel can only be checked on a device); real app launches, keystrokes and mouse movement (deliberately not sent during testing so the PC wasn't controlled); an actual Wake-on-LAN wake-up.

### Verification checklist

| # | Step | Pass criterion |
|---|---|---|
| 1 | Start the server, open the app | PC appears within 2–3 s; fingerprint in the PIN dialog = fingerprint on the PC |
| 2 | Vol ± / Mute / Mic on the default deck | PC volume changes; Mic button dot turns red (muted) ↔ green |
| 3 | Audio tab | Current track and album art shown; an app slider changes only that app's volume |
| 4 | Add an App button → tap it | Real icon shown; the app launches on the PC |
| 5 | Folder and Multi buttons | Folder opens; multi-action steps run in order with their delays |
| 6 | Touch tab | Pointer move, click, two-finger scroll, right click; typing (including Korean) arrives on the PC |
| 7 | Pair a second PC | Top-bar switching, *Run on* button runs on the other PC, page copy works |
| 8 | Put the PC to sleep → *Wake* | PC wakes up and the app reconnects (requires WoL in BIOS) |
| 9 | Widget and tiles | Buttons run from the widget/tiles with the app closed |
| 10 | Tray *Start at login* on, reboot | Server starts after login; phone reconnects automatically |
| 11 | Export deck → import (add) | Pages are duplicated and appended |

If something goes wrong, run the server with `-v` or check `server.log`.

