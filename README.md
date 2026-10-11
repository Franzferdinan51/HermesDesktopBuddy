# HermesDesktopBuddy

Native Android (Kotlin) companion app for **Hermes Agent** — the Hermes
equivalent of MuseCompanionApp. The phone acts as a Hermes gadget: it
connects to the Hermes gateway's Gadget plugin over WebSocket, pairs,
chats, talks, and answers device actions.

Protocol: `hermes-gadget.v1` (`ws://<hermes-host>:8765/gadget`).

## Features

- **Pairing** — full `hello` → `challenge` → `auth` → `welcome` handshake.
  First contact sends the 32-byte device key (base64); later connections
  use `HMAC-SHA256(key, "hermes-gadget/v1|" + device_id + "|" + nonce)`.
  The pairing code + approve command are shown in the app; approval needs
  no reconnect.
- **Dashboard** (`MainActivity`) — connection status dot, last caption /
  status line, pairing code while unpaired, Chat / Pair / Settings /
  Reconnect / New session buttons.
- **Chat** (`ChatActivity`) — streaming replies (`reply.delta`), typed
  messages, hold-to-talk voice (PCM16 mono, `audio.start` → binary frames →
  `audio.end`), Stop (sends `cancel`), and yes/no prompt dialogs.
- **Voice out** — server `audio.start`/`audio.end` streams play through
  `AudioTrack`; when the server only sends text, Android TTS speaks the
  reply (toggle in Settings).
- **Device actions** (registered in `hello`): `buddy.vibrate`, `buddy.notify`
  (title/body notification), `buddy.battery` (battery %). Every `action`
  is answered exactly once with `action.result`.
- **Telemetry** — `state` with `battery_pct` every 60 s.
- **Settings** — host URL (default `ws://100.68.208.113:8765/gadget`),
  device name, mic rate, TTS toggle; device ID viewer (for
  `hermes gadget forget`).
- The 32-byte device key lives in **EncryptedSharedPreferences** and is
  never committed or hardcoded.

## Build

Requirements: JDK 17, Android SDK (platform 36, build-tools).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Pair

1. Open the app, go to Settings, set the Hermes host URL if it isn't the
   default, and go back — the service connects automatically.
2. Open **Pair**. The app shows a pairing code and the approve command.
3. On the Hermes host run the approve command (e.g.
   `hermes gadget pair <code>` — use the exact command shown).
4. The app flips to "Paired ✓" — chat and voice work immediately.

## Protocol version

Implements `hermes-gadget.v1`, `proto: 1`. Message types handled:
`challenge`, `welcome`, `pairing`, `paired`, `unpaired`, `ping`,
`turn.start`, `transcript`, `status`, `reply.delta`, `reply`, `turn.end`,
`notice`, `prompt`, `prompt.close`, `display`, `audio.start`, `audio.end`,
`audio.abort`, `action`, `error`. Sent: `hello`, `auth`, `pong`/`ping`,
`text`, `audio.start`/`audio.end`/`audio.cancel`, `cancel`, `session.new`,
`prompt.reply`, `action.result`, `state`.

## What's stubbed / not yet built

- `image.start`/`image.end` (RGB565 display) — frames are ignored; no
  `display` caps are advertised so the server shouldn't send them.
- OTA firmware update flow (`ota.*`) — not advertised, not implemented.
- `event` messages (device → server) — not sent.
- `transcript` is shown as a system line; no separate STT UI.
- Chat history is in-memory (last 100), not persisted.
- No avatar/pixel-stage UI (MuseCompanionApp has one; not ported).

## Project layout

```
app/src/main/java/com/duckets/hermesbuddy/
├── Crypto.kt            # device key, device_id, HMAC auth
├── DeviceStore.kt       # EncryptedSharedPreferences
├── GadgetProtocol.kt    # message model + JSON builders + binary frames
├── GadgetConnection.kt  # OkHttp WebSocket: handshake, pairing, heartbeat, reconnect
├── GadgetService.kt     # foreground service: connection owner, actions, TTS, chat state
├── AudioPlayer.kt       # AudioTrack playback + TtsSpeaker fallback
├── MainActivity.kt      # dashboard
├── ChatActivity.kt      # chat + hold-to-talk
├── ChatAdapter.kt       # message list
├── SettingsActivity.kt  # host URL, name, TTS, mic rate
└── PairingActivity.kt   # pairing code display
```

## License

Apache-2.0.
