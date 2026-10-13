# SpotifyQuiet

A lightweight, offline-only Android companion app that monitors Spotify playback,
investigates suspected advertisement events, and evaluates whether reopening
Spotify can restore music after a suspected ad.

> Unofficial companion app. Not affiliated with Spotify AB.

## Overview

SpotifyQuiet exists to test a specific observation: on an Honor 90 running Android
15, swiping Spotify out of Recent Apps and reopening it sometimes skips an
advertisement and resumes the song. That gesture cannot be reliably automated on
Android (see [Limitations](#limitations)), so the app's job is to capture the
evidence needed to study the behaviour, and to offer a clearly labelled manual
reopening action plus a tightly gated, opt-in experiment.

The app is event-driven: it never polls in the background, holds no wake locks,
performs no network requests, and has no account, analytics or backend. All state
lives in memory and dies with the process.

## Features

- **Minimal dark-themed UI** — Start/Stop monitoring, connection and playback
  status, current track title/artist, event count, recent event log with evidence,
  clear-log action, privacy note, and a button that opens Android's notification
  access settings.
- **Notification-driven monitoring** — a `NotificationListenerService` and
  `MediaSessionManager` observe Spotify's media session. Events are recorded
  locally (bounded in-memory log, max 200 entries).
- **Heuristic ad suspicion** — `PlaybackEventAnalyzer` classifies each event as
  `NORMAL_PLAYBACK`, `TRACK_TRANSITION`, `POSSIBLE_AD`,
  `BUFFERING_OR_INTERRUPTION` or `UNKNOWN`, and records the evidence behind
  every classification. Suspicion is never presented as proof.
- **Manual diagnostic labels** — mark any recorded event as a real ad or not an
  ad, so captured evidence can be compared against reality on the device.
- **Recovery experiment (off by default)** — an optional controller that may
  reopen Spotify only after a HIGH-confidence `POSSIBLE_AD`, with a 60-second
  cooldown and at most 5 attempts per session, plus an emergency stop control.
- **Automated builds** — GitHub Actions produces a debug APK on every push to
  `main`; an optional release workflow signs and publishes tagged APKs.

## Project layout

```
app/src/main/java/com/spotifyquiet/app/
├── MainActivity.kt            # single Compose activity
├── ui/                        # Compose screen, theme
├── monitoring/                # coordinator, MediaSessionBridge, parser, engine
├── analysis/                  # classifier (pure Kotlin, unit tested)
├── recovery/                  # recovery controller, throttle, Spotify launcher
├── data/                      # event model, bounded log, diagnostic store
├── permissions/               # notification-access status + settings intents
└── service/                   # NotificationListenerService
app/src/test/java/...           # 48 JVM unit tests
.github/workflows/               # android.yml (debug APK), release.yml (signed APK)
```

## Requirements

- Android Studio Ladybug (2024.2.1) or newer
- JDK 17+ for building
- Android SDK: platform 35, build-tools 35.0.0
- Device running Android 10 (API 29) or newer
- Spotify installed on the device for monitoring to have anything to observe

## Build locally

```bash
# from the repository root
./gradlew assembleDebug          # debug APK -> app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # unit tests
./gradlew lintDebug              # Android lint
```

## GitHub Actions: automated debug APK

The workflow at `.github/workflows/android.yml` runs on pushes to `main`, pull
requests targeting `main`, and manual `workflow_dispatch`. It checks out the
repository, sets up JDK 17, validates the Gradle wrapper, runs unit tests, runs
lint, builds the debug APK, and uploads artifacts.

### Download and install the debug APK

1. Push to `main` (or run the workflow manually from the **Actions** tab).
2. Open the completed run and download the artifact named
   **SpotifyQuiet-debug-apk**.
3. Unzip it to obtain `app-debug.apk`.
4. Transfer the APK to your phone, open it, and allow installation from this
   source if prompted.
5. Open SpotifyQuiet, tap **Start monitoring**, and grant notification access when
   asked (or via the in-app button that opens Android's notification access
   settings).

The debug APK is suitable for initial testing. It is not signed with a release
key.

## Release signing (optional)

The release workflow (`.github/workflows/release.yml`) builds, verifies, signs,
and optionally publishes a tagged release APK. It reads four secrets from
**Settings → Secrets and variables → Actions**:

| Secret               | Contents                                             |
| -------------------- | ---------------------------------------------------- |
| `KEYSTORE_BASE64`     | Base64-encoded Android keystore (`.jks`)             |
| `KEYSTORE_PASSWORD`   | Keystore password                                    |
| `KEY_ALIAS`           | Key alias inside the keystore                        |
| `KEY_PASSWORD`        | Key password                                         |

The keystore is decoded to a temporary runner file, used by Gradle, and deleted
after the build. The workflow never falls back to an unsigned APK: if signing
secrets are missing or the APK cannot be verified, the build fails. Debug builds
never require these secrets.

Publish a release:

```bash
git tag v1.0.0
git push origin v1.0.0
```

The workflow then verifies the APK signature with `jarsigner`, uploads it as the
**SpotifyQuiet-release-apk** artifact, and attaches it to a GitHub Release for the
tag.

## Notification access: grant and revoke

Notification access is a **special access setting**, not an ordinary runtime
permission. Grant it via **Settings → Apps → Special app access → Notification
access** (path varies by OEM), or tap the in-app **Grant notification access**
button. Revoking it at any time stops monitoring immediately — the app checks on
every resume and stops itself gracefully.

## Limitations

- **No guaranteed ad detection.** Spotify does not publish an authoritative
  advertisement flag to other apps. `POSSIBLE_AD` is a heuristic suspicion
  supported by recorded evidence; `UNKNOWN` is returned when evidence is
  insufficient. Missing, blank or changing metadata is *not* treated as proof of
  an advertisement.
- **No automated "swipe away".** Android does not expose a supported API for a
  background app to silently swipe another application out of Recent Apps.
  Background activity starts are restricted since Android 10 and can be refused
  silently by the system. The recovery experiment therefore reopens Spotify via a
  normal launcher intent, only after a HIGH-confidence detection, and only while
  the user explicitly enables it. Outcomes must be verified on the device.
- **No root, hidden APIs, accessibility automation or privileged permissions.**
  The app never force-stops Spotify, never loops restarts, and never interrupts
  playback on uncertain detections.
- **In-memory only.** Events are lost when the app process is killed. Nothing is
  uploaded anywhere.
- **OEM variance.** Honor/Huawei devices, battery savers and aggressive app
  killers can affect background delivery of media session callbacks. Results
  measured on one device may not transfer to another.

## On-device test checklist

Run this on the target device (Honor 90, Android 15) and record actual results —
CI cannot verify any of it:

- [ ] Normal music playback: events log as `NORMAL_PLAYBACK`/`TRACK_TRANSITION`.
- [ ] An actual advertisement: check what metadata/evidence appears during it.
- [ ] Switching between tracks.
- [ ] Bluetooth audio (does the media session still arrive?).
- [ ] Screen locked.
- [ ] Spotify swiped out of Recent Apps and reopened manually — compare the log
      before/after.
- [ ] Spotify force-stopped from Settings.
- [ ] Monitoring stopped by the user via the app.
- [ ] Notification access revoked from system settings.
- [ ] Battery optimization enabled for SpotifyQuiet.
- [ ] App process killed (swipe away) while monitoring — restart the app and
      confirm monitoring resumes cleanly.

## Privacy

Everything stays on the device: no network requests, no account, no analytics, no
backend. Only the playback metadata needed for diagnosis is kept, in memory, for
as long as the app process lives. The app contains no advertisements of its own
and contains no Spotify credentials or API keys.

## License

Provided as-is for personal study. No affiliation with Spotify AB is implied.
