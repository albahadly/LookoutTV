# Changelog

## 1.2.4 — 2026-10-31

- The update banner takes the remote's focus when it appears, so Update now and Later can be pressed. Later now hides the banner straight away.

## 1.2.3 — 2026-10-31

- Bump version to 1.2.3 and update version code to 7

## 1.2.2 — 2026-10-31

- Update check: runs when the app opens and every hour while it's on screen, instead of once a day. A check that fails (Wi-Fi still connecting after the TV wakes) is retried after 5 minutes, and an update already found is no longer forgotten when Android closes the app.
- Settings → About: the GitHub token buttons line up with the field.

## 1.2.1 — 2026-10-31

- Floating camera window no longer appears over the app when the TV puts its own prompt on screen during a long full-screen view, and no longer opens as the TV goes to sleep.

## 1.2.0 — 2026-10-28

- Release workflow: type the version (and optional notes) when running it; it updates the build files and changelog, commits, tags and publishes.

## 1.1.0 — 2026-10-28

- Renamed to **Lookout TV** (package `com.albahadly.lookouttv`), with a new logo. It installs as a new app next to the old SwannTV: move your cameras over with the phone page's backup (Include passwords) and import, then uninstall the old one.
- In-app updates: the TV checks GitHub Releases once a day and offers new versions in the grid (Update now / Later); downloads are checksum-verified and handed to Android's installer. Controls in Settings → About and on the phone page, including a GitHub token for a private repository.
- Pressing OK on a Settings menu item while a form is open closes the form and switches section.
- Full-screen mute with 0.
- Release workflow (GitHub Actions) that builds, tags and publishes the APK.

## 1.0.0 — 2026-10-28

First release.

- Live grid of up to 9 cameras (1, 2×2, 1+5, 3×3) with pages for up to 16, and camera groups.
- Instant full screen with the full-quality stream and sound; ◀ ▶ camera switching; zoom 2–4×.
- Sound for a chosen grid tile (0 / hold OK); mute in full screen (0).
- "Always full quality" per camera in the 2×2 and 1+5 grids.
- Motion highlight and auto-open, with per-camera sensitivity and beep.
- Camera tour.
- Floating camera window over other apps (Home from full screen).
- Settings on the TV: add from NVR (Swann, Hikvision, Dahua), single cameras, test, reorder, hide, groups, PIN lock.
- Phone page: setup, backup and restore, TV remote, log viewer, 6-digit code pairing.
- Encrypted passwords (Android Keystore), redacted logs, hardened phone-page server.
- Tuned for the TCL 55C6L: the 4K decoder always starts alone.
