# Verbalogix Companion

A small Android APK that exposes the device's accessibility tree and gesture dispatch over `127.0.0.1` so the [Verbalogix Engine](https://github.com/Eyalio84/verbalogix-engine) can drive the Screen-Reading Agent without ADB.

Not an assistant. Not an AI. No model inside. The engine is the brain — this app is a thin, opinionated bridge between the engine's HTTP client and the operating system's AccessibilityService API.

## What it replaces

The engine's Screen-Reading Agent currently uses ADB over wireless debugging to read the view hierarchy (`uiautomator dump`) and dispatch taps (`input tap X Y`). That works, but:

- Wireless debugging needs re-pairing after most reboots.
- Each ADB call is a TCP round-trip via the ADB server — about 100–300 ms.
- Developer Options must be on; not something you want to demo in every room.

This companion app runs as an Android `AccessibilityService` and exposes the same capabilities over a local HTTP server:

- `GET /tree` — the current window as structured JSON (same surface as uiautomator XML, plus node-targeted actions).
- `POST /tap`, `/swipe`, `/text`, `/action`, `/global` — gesture and action dispatch, routed through the Android action framework where possible (~2 ms per tap when the node ID is known).
- `GET /events` (WebSocket) — throttled AccessibilityEvent stream so the engine can react to UI transitions instead of polling.

All loopback-only, all gated by a Bearer token generated at first install.

## What this is not

- Not a system-wide automation framework. It only ever does what the engine asks for.
- Not a Play Store accessibility tool. `isAccessibilityTool="false"` is honest — this is power-user automation, not a disability aid. Don't install it unless that's what you want.
- Not network-reachable. Binds to `127.0.0.1` only. No LAN exposure.
- Not standalone. Without the engine running, the app does nothing.

## Android version support

- `minSdk` 26 (Android 8.0)
- `targetSdk` 34 (Android 14)
- Android 17+ with **Advanced Protection Mode** active: the service cannot run (Google policy). The companion detects this via `AdvancedProtectionManager` and notifies the engine, which falls back to its ADB Screen Agent automatically.

## Build

Requires Android Studio Hedgehog (2023.1.1) or newer, JDK 17, Kotlin 2.0+.

```bash
./gradlew :app:assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

Signed release builds go through `./gradlew :app:assembleRelease` with keystore properties configured in `keystore.properties` (not committed).

## Install

**Sideload (development / personal):**

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app once after install to grant the Accessibility permission (Settings → Accessibility → Verbalogix Companion). The app's status screen shows the server URL and Bearer token — copy both into the engine's `COMPANION_*` environment variables.

**F-Droid:** planned.
**GitHub Releases:** signed APK will be attached to tagged releases once v0.1.0 is ready.
**Play Store:** aspirational; requires "prominent disclosure" declaration.

## Security model

- Single Bearer token, generated at first install with `SecureRandom(32)`, stored in app-private preferences, displayed in the UI for copy-paste into the engine config.
- Binds only to `127.0.0.1`. No LAN, no WAN.
- No analytics, no crash reporting, no telemetry.
- Source-available under Apache 2.0. Build it yourself; F-Droid reproducible builds are the target once v0.1.0 stabilizes.
- No data leaves the device. Logs are local; export is user-initiated.

## Prior art we referenced (clean-room)

Nothing in this repository was copied from the projects below — but each shaped a decision.

- [`callstackincubator/agent-device`](https://github.com/callstackincubator/agent-device) (MIT) — API surface pattern: `snapshot`, `click`, `fill`, `scroll`, `type` with reference IDs.
- [`jwlilly/Accessibility-Inspector-Service`](https://github.com/jwlilly/Accessibility-Inspector-Service) (GPL-3.0) — the closest real-world match; read for pattern, not copied (GPL would force this project to GPL too).
- [`openatx/uiautomator2`](https://github.com/openatx/uiautomator2) (MIT) — companion-APK-plus-host-library architecture; their instrumentation is different but the shape is familiar.

## License

Apache License 2.0 — see [LICENSE](./LICENSE).

Copyright 2026 Eyal Nof (Verbalogix).
