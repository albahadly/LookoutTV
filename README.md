# SwannTV

An Android TV app for watching RTSP security cameras — built for a Swann NVR on a TCL Google TV, but it works with any camera that has an RTSP stream.

- **Live grid** of up to 9 cameras (1, 2×2 or 3×3), with a second page for cameras 10–16.
- **1-camera view in full quality**: with one camera on screen it plays the main (4K) stream with sound; flipping cameras stops the old stream first, so the TV's 4K decoder never runs twice.
- **Settings on the TV**: add a whole NVR in one step or single cameras, test them, reorder, hide, rename; optional PIN lock.
- **Phone setup**: scan a QR code in Settings and do the same from your phone's browser, plus backup and restore.
- **Instant full screen**: the tile fills the screen in under 0.2 s, then the full-quality (4K) stream takes over within a second, with the camera's sound (the grid stays silent).
- **Motion highlight**: grid cameras with movement get an orange border and a MOTION label; optionally the camera opens full screen and returns to the grid after 30 s.
- **Zoom**: ▲ in full screen zooms 2×, 3×, 4× into the 4K picture; the D-pad moves around, Back zooms out.
- **Camera tour**: ▶ Tour (or the Play button) shows each camera full screen in turn, 5 s to 1 min each.
- **Floating camera window** (off by default, Settings → Viewing): press Home while a camera is full screen and it keeps playing in a corner over other apps; pick its size and corner in Settings. Needs a one-time `adb shell appops set com.albahadly.swanntv SYSTEM_ALERT_WINDOW allow` (the TCL refuses Android picture-in-picture).
- **Built for the remote**: D-pad, OK, Back, number keys 1–9, channel ± for pages.
- **Stays up**: reconnects with backoff, rebuilds the player on decoder errors, restarts frozen streams, and shows each camera's last picture instead of a black tile.

Kotlin · Jetpack Compose for TV · Media3 ExoPlayer (RTSP over TCP) · minSdk 28.

## Modules

| Module | What it is |
| --- | --- |
| `app` | SwannTV, the camera viewer |
| `probe` | Test tool that measures what a TV can play (decoders, 4/9/16-tile load, full-screen switch times) and writes a report |

## Setup

1. Open the project in Android Studio and let Gradle sync.
2. Connect to the TV with `adb connect <tv-ip>:5555`, choose the **app** run configuration (release variant for real speed) and press Run.
3. In the app, open **Settings** (top right of the grid) and either:
   - **Add from NVR**: pick your recorder (Swann, Hikvision, Dahua/Lorex/Amcrest), enter its IP address and login, and the number of channels; or
   - **Add camera**: enter any camera's RTSP address (main stream and, ideally, a low-resolution grid stream).

   Use **Test** to check a camera answers before saving.

   Typing addresses is easier on a phone: **Settings → Phone setup** shows a QR code. Scan it with a phone on the same Wi-Fi and manage cameras from the phone's browser; the TV updates straight away.

Settings also has the grid layout, a PIN lock for Settings, and reorder / hide / rename for each camera. Passwords are encrypted with an Android Keystore key, which is hardware-backed on most TVs.

Optional: `local.properties` (git-ignored) can pre-fill the camera list on a fresh install, without the password, which is never compiled into the app:

```properties
nvr.host=192.168.1.100
nvr.port=554
nvr.user=admin
nvr.names=Front door,Driveway,Back yard,Garage
nvr.mainPath=/rtsp/streaming?channel={ch}&subtype=0
nvr.subPath=/rtsp/streaming?channel={ch}&subtype=1
```

The `probe` module still reads `nvr.password` from `local.properties`, since it has no Settings screen. That password (and the NVR address) is compiled into the probe APK as plain text, so never share a probe build. Its camera list is fixed to four Swann channels in `ProbeConfig.kt`; edit that to probe another recorder.

Release builds are signed with the debug key, so installing a build from another computer needs the old one uninstalled first.

## Phone setup and security

The phone page is served by the TV itself, only while Settings is open on screen:

- It listens on port 8765 (or the next free one up to 8775). A new one-time key is made each time Settings opens, carried in the QR code; leaving Settings (or 15 minutes without use) stops the server and the key stops working.
- No QR scanner? Type the address shown on the TV and enter the 6-digit code next to it. Five wrong codes switch the code off until Settings is reopened.
- Only devices with home-network (private) addresses are answered, and the page must be opened by the TV's own address.
- Saved passwords are never shown on the phone; a saved password is only reused for the address it was saved with. Only a backup you tick **Include passwords** for contains them, as plain text.
- The connection is plain HTTP on your home network (a TV can't get a trusted certificate for a local address). Anyone who can watch your Wi-Fi traffic while you use it could see what you type, so use it on your own trusted network.

## Remote

| Where | Key | Does |
| --- | --- | --- |
| Grid | ◀ ▲ ▼ ▶ | Move between tiles; ◀ ▶ past the left or right edge changes page |
| Grid | OK | Open camera full screen |
| Grid | 1–9 | Open that tile directly |
| Grid | CH + / CH − (also Page Up/Down, Next/Previous) | Next / previous page |
| Grid | ▲ to the header | Layout, Tour and Settings |
| Anywhere | ▶ (Play) | Start / stop the camera tour |
| Full screen | ◀ ▶ | Previous / next camera (all cameras, across pages) |
| Full screen | ▲ | Zoom 2×; then OK zooms to 3×, 4× and back |
| Full screen, zoomed | ◀ ▲ ▼ ▶ | Move around the picture |
| Full screen | OK | Back to the grid (when not zoomed) |
| Full screen | Back | Zoom out, or back to the grid |
| Full screen | Home | Floating camera window (if turned on and the camera has a grid stream) |

## Camera settings that matter

- **Sub-streams** (used in the grid): 640×360, 15 fps, H.264 or H.265.
- **Main streams** (full screen): any resolution the TV can decode; a keyframe interval of 1 s makes full screen sharpen faster.

## Measured on a TCL 55C6L (MediaTek MT9689, Android 14, 32-bit)

| Test | Result |
| --- | --- |
| 9 live tiles | 15 fps each, stable, ~40% CPU |
| 16 live tiles | ~5 fps each at any resolution — why the grid stops at 3×3 |
| Tile fills the screen after OK | 150–196 ms (release build) |
| Full-quality 4K picture after OK | 0.72–0.93 s |
| Next / previous camera in 4K | 0.16–0.19 s |
| Decoder failures | 0 (the 4K stream always runs alone; with other streams it could fail) |

Run the `probe` module on another TV to see its limits; results are saved on the TV:

```
adb pull /sdcard/Android/data/com.albahadly.swanntv.probe/files/probe-log.txt
```
