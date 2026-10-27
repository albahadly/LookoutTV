# SwannTV

An Android TV app for watching RTSP security cameras. Built for a Swann NVR on a TCL Google TV, but it works with any camera or recorder that has an RTSP stream (Swann, Hikvision, Dahua/Lorex/Amcrest presets included).

Kotlin · Jetpack Compose for TV · Media3 ExoPlayer (RTSP over TCP) · Android 9+ (minSdk 28) · no cloud, no account: everything stays on your home network.

**Install:** download `SwannTV-x.y.z.apk` from the [Releases](https://github.com/albahadly/SwannTV/releases) page and see [Installing](#installing). No build needed.

---

## Contents

- [Features](#features)
- [Installing](#installing)
- [First setup](#first-setup)
- [Using the app](#using-the-app)
  - [Remote keys](#remote-keys)
  - [Grid layouts and groups](#grid-layouts-and-groups)
  - [Sound](#sound)
  - [Full screen and zoom](#full-screen-and-zoom)
  - [Motion](#motion)
  - [Camera tour](#camera-tour)
  - [Floating camera window](#floating-camera-window)
- [Settings reference](#settings-reference)
- [Phone page](#phone-page)
- [Security and privacy](#security-and-privacy)
- [Troubleshooting](#troubleshooting)
- [Building from source](#building-from-source)
- [How it works on the TV](#how-it-works-on-the-tv)
- [Project layout](#project-layout)

---

## Features

- **Live grid** of up to 9 cameras at once (1, 2×2, 1+5 or 3×3), with more pages for up to 16 cameras.
- **Groups**: named sets of cameras ("Front" with just the front door and driveway, say), switched from the grid's header or the phone.
- **Instant full screen**: the tile fills the screen in under 0.2 s, then the full-quality (4K) stream takes over within a second, with sound. ◀ ▶ flip between cameras in about 0.2 s.
- **Zoom** 2×, 3×, 4× into the 4K picture, moving around with the D-pad.
- **Sound in the grid** for the camera you choose (press 0), and **mute** in full screen.
- **Always full quality** per camera in the 2×2 and 1+5 grids.
- **Motion highlight**: cameras with movement get an orange border and a MOTION label; optionally the camera opens full screen by itself. Sensitivity per camera, with an optional beep.
- **Camera tour**: each camera full screen in turn, 5 s to 1 min each.
- **Floating camera window**: press Home while a camera is full screen and it keeps playing in a corner over other apps (YouTube, live TV…).
- **Settings on the TV**: add a whole recorder in one step or single cameras, test them, reorder, hide, rename, group; optional 4-digit PIN lock.
- **Phone page**: scan a QR code and set everything up from your phone's browser (typing is far easier there), back up and restore, use the phone as a **remote**, and read the TV's **log**.
- **Stays up**: reconnects with backoff, rebuilds the player after decoder errors, restarts frozen streams, and shows each camera's last picture instead of a black tile.

## Installing

You need the TV's IP address and ADB (network debugging) enabled on the TV.

1. **Enable network debugging on the TV.** Settings → System → About → press **Android TV OS build** seven times to unlock Developer options, then Settings → System → Developer options → turn on **USB debugging** and **Network debugging** (names vary slightly by TV). Note the TV's IP address under Network.
2. **Get ADB on your computer.** It comes with Android Studio (`platform-tools` folder of the SDK) or on its own from [developer.android.com/tools/releases/platform-tools](https://developer.android.com/tools/releases/platform-tools).
3. **Download** the latest `SwannTV-x.y.z.apk` from the [Releases](https://github.com/albahadly/SwannTV/releases) page.
4. **Install:**
   ```
   adb connect 192.168.1.50:5555        (your TV's address)
   adb install -r SwannTV-1.0.0.apk
   ```
   The first time, the TV asks "Allow USB debugging?" — choose **Always allow**. `-r` keeps your cameras, passwords and PIN when updating.
5. SwannTV appears in the TV's apps row.

Updating: download the new APK and run `adb install -r` again. Builds from the Releases page and builds you make yourself install over each other as long as they are signed with the same key (see [Building from source](#building-from-source)).

## First setup

Open **Settings** (▲ to the header, right-hand chip) and add your cameras one of two ways:

- **Add from NVR**: pick the recorder brand (Swann, Hikvision, Dahua/Lorex/Amcrest), enter its IP address, login and the number of channels. One camera is created per channel with the right main and grid stream addresses. **Test channel 1** checks the login first.
- **Add camera**: for any RTSP camera, enter a name, the **main stream** (full quality) and ideally a **grid stream** (low resolution). **Test** connects and reports the codec and resolution before you save.

Typing is much easier on a phone: **Settings → Phone setup** shows a QR code. Scan it with a phone on the same Wi-Fi and do all of the above in the phone's browser; the TV updates straight away. No QR scanner? Type the address shown and enter the 6-digit code.

Camera streams that work best: grid stream around 640×360 at 15 fps (H.264 or H.265); main stream at whatever the TV can decode, with a 1-second keyframe interval so full screen sharpens fast.

## Using the app

### Remote keys

| Where | Key | Does |
| --- | --- | --- |
| Grid | ◀ ▲ ▼ ▶ | Move between tiles; ◀ ▶ past the left or right edge changes page |
| Grid | OK | Open the camera full screen |
| Grid | Hold OK, or 0 | Sound on / off for that camera |
| Grid | 1–9 | Open that tile directly |
| Grid | CH + / CH − (also Page Up/Down, Next/Previous) | Next / previous page |
| Grid | ▲ to the header | Layout chips, group chip, ▶ Tour, Settings |
| Anywhere | ▶ (Play) | Start / stop the camera tour |
| Full screen | ◀ ▶ | Previous / next camera (all cameras of the group, across pages) |
| Full screen | 0 | Mute / unmute |
| Full screen | ▲ | Zoom 2×; then OK zooms to 3×, 4× and back to 1× |
| Full screen, zoomed | ◀ ▲ ▼ ▶ | Move around the picture |
| Full screen | OK | Back to the grid (when not zoomed) |
| Full screen | Back | Zoom out, or back to the grid |
| Full screen | Home | Floating camera window (if turned on) |
| Settings | Back | Close a form, or leave Settings |

### Grid layouts and groups

- **Layouts**: 1, 2×2, 1+5 (one big tile and five small ones) and 3×3. **Automatic** picks by camera count. The grid never plays more than 9 streams at once: the TV can't decode more smoothly, so more cameras go on further pages.
- In **1+5** the first camera of each page gets the big tile; reorder the camera list to choose it.
- The **1-camera** layout plays the camera's full-quality stream with sound.
- **Groups**: Settings → Cameras → Groups (or the phone page). The **▣ All cameras** chip in the header cycles All → group → group → All. Full-screen ◀ ▶ and the tour stay within the group.

### Sound

- Grid tiles are silent by default. Press **0** (or hold **OK**) on a tile to hear that camera; a **♪ SOUND** badge shows which one. Press again to mute. The stream reopens briefly when sound is turned on (the RTSP library can't add audio to a running session).
- Full screen plays the camera's sound. **0** mutes and unmutes instantly, and the mute stays for the next cameras until you unmute or restart the app.
- If a camera is silent in full screen, audio is probably off for that channel on the recorder (Swann ships with it off), or the camera has no microphone.

### Full screen and zoom

OK on a tile grows that tile to full screen at once; the full-quality stream starts underneath and shows as soon as its first frame arrives. ◀ ▶ switch cameras on the same player. ▲ zooms into the picture (2×, then OK for 3× and 4×), the D-pad pans, Back zooms out. While the main stream retries after a dropout, the camera's last picture stays on screen.

### Motion

The grid compares each live tile with the picture from a second earlier (a 64×36 greyscale copy, so it costs almost nothing). Movement over more than the sensitivity's share of the picture, seen twice in a row, counts; a change over most of the picture (lights, night mode) is ignored.

- **Settings → Viewing → Motion**: Off, **Highlight** (orange border and MOTION label for 10 s) or **Open full screen** (the camera opens by itself and returns to the grid after 30 s unless a button is pressed; it won't open while you're using the remote).
- **Sensitivity**: Low / Medium / High, globally, and per camera in its form (rain, trees and headlights count as movement: lower it where that happens).
- **Beep on motion** per camera: a short beep on the TV.
- Motion works for the cameras on screen in the grid only.

### Camera tour

**▶ Tour** in the header, or the remote's **Play** button, shows each camera full screen in turn (default 10 s, set 5 s–1 min in Settings → Viewing). ◀ ▶ skip, and OK, Back or Play stop the tour.

### Floating camera window

With a camera full screen, press **Home** and it keeps playing in a corner over whatever you open next, on its light grid stream, without sound. Open SwannTV again to close it. Off by default: turn it on in **Settings → Viewing → Floating camera window**, and pick its size (small to half screen) and corner.

It needs one permission that TVs have no menu for; run once from a computer:

```
adb shell appops set com.albahadly.swanntv SYSTEM_ALERT_WINDOW allow
```

(Android's own picture-in-picture is refused by the TCL, so SwannTV draws its own window.) The remote controls the app underneath, not the window. While it plays, 4K video in another app may fail on this TV; open SwannTV to close the window if that happens.

## Settings reference

| Section | Contains |
| --- | --- |
| **Cameras** | The camera list (show/hide, ▲▼ reorder, Edit), Add camera, Add from NVR, Groups. A camera's form: name, main and grid stream addresses, username and password, TCP/UDP, show in grid, always full quality, motion setting, beep. **Test** checks a stream before saving. |
| **Phone setup** | QR code, typed address and 6-digit code, "Keep the phone connected after leaving Settings". |
| **Viewing** | Grid layout; motion mode and sensitivity; tour seconds; floating window on/off, size and corner. |
| **PIN lock** | Set, change or remove a 4-digit PIN for Settings. Five wrong tries lock entry for 30 s, doubling each time. Leaving the app re-locks Settings. |
| **About** | Version, camera count, log file location. |

Everything is saved at once; there is no Save button for Settings itself. The camera list is limited to 16 cameras and 8 groups.

## Phone page

Settings → **Phone setup** on the TV shows a QR code and, below it, the address (`http://<tv-ip>:8765`) with a 6-digit code for typing instead. The page offers:

- **TV**: Grid, ◀ ▶, ▶ Tour, a group picker, and a 📺 button on every camera and group to show it on the TV.
- **Cameras**: add, edit, test, reorder, hide, delete; Add from NVR.
- **Groups**: add, edit, delete.
- **Grid layout**.
- **Backup**: download the camera list as a file (tick **Include passwords** to put them in, as plain text), and load one back, adding to or replacing the current list.
- **Diagnostics**: show or download the TV's log.

With **Keep the phone connected** on (the default), leaving Settings keeps the phone connected as a remote (TV section, log, state) for 15 minutes of use. Changing cameras always needs Settings open on the TV. Leaving the app ends the connection; the next time Settings opens, a new QR code and code are made.

## Security and privacy

- Nothing leaves your home network. The app talks to your cameras and recorder directly and never to the internet.
- Camera passwords are encrypted with a key in the TV's Android Keystore (hardware-backed on most TVs). Addresses are stored without logins; logins are added only when a stream is played. Passwords never appear in the log file or on the phone page, and a saved password is only ever reused for the address it was saved with.
- The phone page's server runs only while Settings is open (plus the remote-only period described above), listens on port 8765–8775, answers only private (home-network) addresses, requires the one-time key from the QR code or the 6-digit code on every request, and checks that the page was opened by the TV's own address. Five wrong codes switch the code off until Settings is reopened.
- The phone page is plain HTTP on your own Wi-Fi (a TV can't get a trusted certificate for a local address): someone able to watch your Wi-Fi traffic while you use it could see what you type, so use it on a trusted network. A backup with passwords included is a plain-text file: keep it private.
- The optional PIN protects Settings only; the cameras can always be watched on the TV.

## Troubleshooting

- **The log** is the first thing to look at. On the phone page, **Diagnostics → Show log**, or from a computer:
  ```
  adb pull /sdcard/Android/data/com.albahadly.swanntv/files/swanntv-log.txt
  ```
  It rotates at 1 MB and never contains logins.
- **A tile says Connecting… / Reconnecting… / Offline**: the camera isn't answering. Use **Test** in its form: it tells you whether the address, port, path or login is wrong.
- **Sign-in failed**: wrong username or password; after changing the camera's address you must type the password again (by design).
- **Full screen is black or stuck on the still picture**: the TV's 4K decoder failed to start. The app retries by itself; if the TV keeps failing (even after a restart of the app), turn the TV off and on again at the mains — the decoder can jam until a power cycle.
- **No sound**: see [Sound](#sound).
- **Floating window doesn't appear**: run the `appops` command above, and make sure the setting is on. It only opens from full screen, and only for cameras that have a separate grid stream.
- **Phone page says "Not connected"**: Settings was closed on the TV (or 15 minutes passed). Open Settings → Phone setup again and scan the new code.
- **"Open Settings on the TV to change cameras"**: the phone is connected as a remote only; open Settings on the TV to edit.
- **A saved password can't be read** (warning in Settings → Cameras): the TV's secure storage didn't answer. Restart the TV; if it persists, edit the camera and type the password again.
- **Installing fails with "signatures do not match"**: the APK was signed with a different key than the installed app. Either install a build signed with the same key, or uninstall first (you lose cameras, passwords and PIN — make a backup from the phone page first).

## Building from source

Requirements: Android Studio (AGP 9.4, Kotlin 2.2, Compose BOM 2026.09, Media3 1.11, compileSdk 37). Open the project, let Gradle sync, choose the **app** run configuration and the **release** build variant (debug builds of Compose are far slower on a TV), connect the TV with `adb connect <tv-ip>:5555` and press Run.

**Signing.** Release builds are signed with `debug.keystore` in the project folder when that file exists, otherwise with the building machine's own Android debug key. The file is git-ignored. Copy Android Studio's key there once (`copy %USERPROFILE%\.android\debug.keystore <project folder>\debug.keystore` on Windows) and every build, from any machine, installs over the others without losing the TV's settings. The Releases APKs are signed with this project's key; a build of your own will need the same key or an uninstall.

**Pre-filled cameras (optional).** `local.properties` (git-ignored) can seed the camera list on a fresh install, without the password, which is never compiled into the app:

```properties
nvr.host=192.168.1.100
nvr.port=554
nvr.user=admin
nvr.names=Front door,Driveway,Back yard,Garage
nvr.mainPath=/rtsp/streaming?channel={ch}&subtype=0
nvr.subPath=/rtsp/streaming?channel={ch}&subtype=1
```

**Deploying from a command line:** `./gradlew :app:assembleRelease` then `adb install -r app/build/outputs/apk/release/app-release.apk`.

**Publishing a release.** Bump `versionName` (and `versionCode`) in `app/build.gradle.kts`, add a section to `CHANGELOG.md`, push, then on GitHub open **Actions → Release → Run workflow**. The workflow builds the APK, creates the tag `v<version>` and publishes a release with the APK and its checksum attached, using the changelog section as notes. It signs with the repository secret `DEBUG_KEYSTORE_BASE64` (the project's `debug.keystore` as base64), so the published APK installs over the copy on the TV; set that secret once under Settings → Secrets and variables → Actions.

**The probe module** is a separate test app that measures what a TV can decode (decoders, 4/9/16-tile load, full-screen switch times) and writes `probe-log.txt` to its files folder. It reads `nvr.password` from `local.properties` and compiles it into its APK as plain text, so never share a probe build; its camera list is fixed to four Swann channels in `ProbeConfig.kt`.

## How it works on the TV

Measured on the TCL 55C6L (MediaTek MT9689, Android 14, 32-bit), and built into the app's behaviour:

| Finding | Consequence in the app |
| --- | --- |
| 9 small streams decode at 15 fps each (~40% CPU); 16 drop to ~5 fps | The grid stops at 3×3; more cameras go on pages |
| The 4K HEVC decoder fails to start if any other video decoder is open, and repeated failures jam it until a power cycle | Before a 4K stream starts, every other stream is released (blocking, so it is really gone); a "full quality" grid tile starts first and the others wait for its first frame; no fallback stream plays next to a retrying 4K stream |
| `ExoPlayer.release()` blocks the main thread until the decoder closes | Routine releases happen one frame after the screen change; a stuck release waits up to 2 s rather than leaving a decoder open |
| Detaching a view from a stopping player blocks the main thread for up to 2 s | The app never calls `clearVideoSurface`; tiles are released instead |
| Android picture-in-picture is refused by the TV | The floating window is drawn by the app over other apps |

| Measured | Result |
| --- | --- |
| Tile fills the screen after OK | 150–196 ms (release build) |
| Full-quality 4K picture after OK | 0.72–0.93 s |
| Next / previous camera in 4K | 0.16–0.19 s |

Other TVs may allow more. Run the probe module on them to see.

## Project layout

```
app/src/main/java/com/albahadly/swanntv/
  MainActivity.kt            Activity: lifecycle, Home key
  AppLog.kt                  Rotating log file, login redaction
  data/   SettingsStore.kt   Settings model and JSON persistence (AtomicFile)
          SecretBox.kt       Password encryption (Android Keystore, AES-GCM)
  model/  Camera.kt          Cameras as the viewer plays them
  playback/ StreamPlayer.kt  One RTSP stream: retries, rebuilds, frozen detection, audio
            PlaybackManager.kt Owns every player: tiles, 4K-first ordering, motion, pictures
            CameraTester.kt  Tests a stream without opening a decoder
            RtspSockets.kt   Connect timeout for RTSP
  ui/     ViewerState.kt     Grid, full screen, zoom, tour, motion, groups logic
          ViewerScreen.kt    Grid layouts, header, footer, full-screen layer
          CameraTile.kt      One tile and its keys
          AppRoot.kt         App controller: screens, settings, phone server, floating window
          settings/          Settings screens (cameras, forms, groups, PIN, viewing, phone setup)
  setup/  PhoneSetupServer.kt Plain-socket HTTP server for the phone page
          PhoneSetupApi.kt   The page's JSON API
  floating/                  Floating camera window and its foreground service
app/src/main/assets/setup.html   The phone page (vanilla JS)
probe/                           The decoder probe app
```
