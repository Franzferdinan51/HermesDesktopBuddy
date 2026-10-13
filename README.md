# Hermes Buddy (HermesDesktopBuddy)

Native Android companion for the Nous Research Hermes agent (`hermes-gadget.v1`).
Chat with Hermes over text or hold-to-talk voice, answer yes/no prompts,
receive notifications, and let Hermes vibrate / notify / read the battery on
your phone — all through the Hermes gateway's gadget endpoint.

UI/UX is heavily inspired by
[MuseCompanionApp](https://github.com/Franzferdinan51/MuseCompanionApp):
blue bubbles, glossy cards, status pills, diagnostics, and an offline outbox.

**Current version: 0.2.0** (versionCode 2)

## Features

- **Pairing** — server-issued code + approve command, copy buttons,
  how-it-works / done states, no reconnect needed after approval.
- **Chat** — streamed replies keyed by turn, timestamps, blue/navy bubbles
  with gloss, smart auto-scroll, empty state, IME send, link detection,
  long-press copy, clear-history with confirm.
- **Offline outbox** — messages sent while disconnected are queued (persisted)
  and flushed automatically on reconnect.
- **Chat persistence** — last 100 messages survive restarts.
- **Voice in** — hold-to-talk (16 kHz PCM), red recording state, haptics,
  transcripts shown as `Heard: …`.
- **Voice out** — server TTS audio streams play locally; falls back to
  on-device TTS when the server sends none (toggle in Settings).
- **Yes/No prompts** — themed dialog in chat, heads-up notification when
  the app is backgrounded.
- **Device actions** — `buddy.vibrate`, `buddy.notify`, `buddy.battery`
  with typed results / unknown-action rejection.
- **Background reply notifications** — replies notify only when no
  activity is visible.
- **Dashboard** — avatar stage, connection card, last-update caption,
  error banner, battery, pairing shortcut card, reconnect / new session.
- **Diagnostics (Settings)** — live state, paired, proto, server,
  session, battery, last error; host/device-name/TTS/mic-rate settings
  with validation; device-key reset & re-pair; tap-to-copy device ID.
- **Share to Hermes** — Android share sheet target prefills the composer.
- **Foreground service** — microphone type when the mic permission is
  granted, `specialUse` fallback when denied (chat keeps working).

## Protocol

`hermes-gadget.v1` over WebSocket (`ws://host:8765/gadget` by default):

- `hello` (proto, device_id, name, caps, actions, sensors) →
  `challenge` → `auth` (enroll with `key`, or `mac` HMAC over
  `hermes-gadget/v1|<device_id>|<nonce>`) → `welcome` (session, paired,
  heartbeat_s) → optional `pairing` (code, command) → `paired`.
- Text: `text` → `turn.start`, `reply.delta`, `reply`, `turn.end`.
- Voice: `audio.start` + binary PCM frames → `audio.end`/`audio.cancel` →
  `transcript` → reply turn; server audio arrives as `audio.start` +
  binary frames + `audio.end`.
- `prompt`/`prompt.reply`, `display`, `notice`, `status`, `cancel`,
  `session.new`, `ping`/`pong`, `action`/`action.result`, `error`,
  periodic `state` (battery_pct, charging).

Device key lives in `EncryptedSharedPreferences`; device_id is
`hg-` + first 16 hex of SHA-256(key).

## Build & test

```sh
./gradlew assembleDebug          # APK in app/build/outputs/apk/debug/
./gradlew testDebugUnitTest      # JVM unit tests (protocol + crypto)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17, Android SDK with platform 36
(`ANDROID_HOME` set), Gradle wrapper (jar + exec bit committed).

Unit tests: `app/src/test/…` — 14 tests covering message parsing,
builders, binary framing, and crypto vectors (generated independently).

## Artwork

Launcher icon, avatar, and headers use the official **Nous Girl**
artwork from [NousResearch/hermes-agent](https://github.com/NousResearch/hermes-agent)
(`apps/desktop/assets/icon.png`, MIT licensed), © Nous Research.

## What's stubbed

- `image.start`/`image.end` parse but don't render (server only sends
  images to devices advertising a display cap, which this app doesn't).
- `prompt.close` TTL auto-dismiss isn't implemented (dialog stays until
  answered/dismissed).
- No OTA support (`update` tool targets firmware gadgets).
