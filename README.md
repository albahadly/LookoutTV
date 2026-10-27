# SwannTV

An Android TV app for watching RTSP security cameras — built for a Swann NVR on a TCL Google TV, but it works with any camera that has an RTSP stream.

- **Live grid** of up to 9 cameras (1, 2×2 or 3×3), with a second page for cameras 10–16.
- **Settings on the TV**: add a whole NVR in one step or single cameras, test them, reorder, hide, rename; optional PIN lock.
- **Phone setup**: scan a QR code in Settings and do the same from your phone's browser, plus backup and restore.
- **Instant full screen**: the tile fills the screen in under 0.2 s, then the full-quality (4K) stream takes over within a second, with the camera's sound (the grid stays silent).
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

Settings also has the grid layout, a PIN lock for Settings, and reorder / hide / rename for each camera. Passwords are encrypted with a key kept in the TV's secure hardware storage.

Optional: `local.properties` (git-ignored) can pre-fill the camera list on a fresh install, without the password, which is never compiled into the app:

```properties
nvr.host=192.168.1.100
nvr.port=554
nvr.user=admin
nvr.names=Front door,Driveway,Back yard,Garage
nvr.mainPath=/rtsp/streaming?channel={ch}&subtype=0
nvr.subPath=/rtsp/streaming?channel={ch}&subtype=1
```

The `probe` module still reads `nvr.password` from `local.properties`, since it has no Settings screen.

## Phone setup and security

The phone page is served by the TV itself, only while Settings is open on screen:

- A new one-time key is made each time Settings opens, carried in the QR code; leaving Settings (or 15 minutes without use) stops the server and the key stops working.
- Only devices with home-network (private) addresses are answered, and the page must be opened by the TV's own address.
- Saved passwords are never sent to the phone, and a saved password is only reused for the address it was saved with.
- The connection is plain HTTP on your home network (a TV can't get a trusted certificate for a local address). Anyone who can watch your Wi-Fi traffic while you use it could see what you type, so use it on your own trusted network. Backups that include passwords are plain text: keep them private.

## Remote

| Where | Key | Does |
| --- | --- | --- |
| Grid | ◀ ▲ ▼ ▶ | Move between tiles; past the edge changes page |
| Grid | OK | Open camera full screen |
| Grid | 1–9 | Open that tile directly |
| Grid | CH + / CH − | Next / previous page |
| Grid | ▲ to the header | Layout and Settings |
| Full screen | ◀ ▶ | Previous / next camera (all cameras, across pages) |
| Full screen | OK or Back | Back to the grid |

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
