# RX-8 Dash v2

Android dashboard for a Mazda RX-8 (Series 1 / Series 2) using an ELM327 Bluetooth Classic adapter. Built for a landscape tablet or head unit, with rotary-knob navigation. Kotlin 2.1, Jetpack Compose, Material3, Room, DataStore.

## Features
- Live gauges (RPM, speed, coolant, intake air, load, throttle, voltage, fuel trims and more) with a configurable gauge layout and palettes.
- Rotary engine health monitoring (compression-related trends, coolant and startup behaviour).
- Trip logging, fuel economy, and maintenance reminders.
- Diagnostics: read stored/pending DTCs, freeze frame, readiness monitors. Read-only.
- Media panel: now-playing and transport controls via notification access.
- Demo mode (no adapter needed), JSON/CSV backup and restore.

The app never sends write, coding, or flash commands.

## Hardware notes
- The RX-8 has no oil temperature sensor. Any oil temperature shown is not a measurement.
- Oil pressure is a switch (low-pressure warning), not a gauge value.
- Extended (Mazda-specific) PIDs are unvalidated and off by default. Values may be wrong or unsupported on your car.
- ELM327 clones vary widely: many fake v2.1 firmware, drop connections, ignore `AT` options, or mishandle multi-frame responses. Prefer a genuine or well-reviewed adapter.
- Poll-rate realism: the OBD-II CAN bus through an ELM327 gives roughly 10-30 PID reads per second total. Each additional gauge lowers every gauge's refresh rate; fast-changing values (RPM, throttle) are prioritized.

## Install (sideload from CI)
1. Open the repository's Actions tab, pick the latest successful `build` run.
2. Download the `rx8-dash-debug` artifact and unzip it to get the APK.
3. Copy the APK to the device and open it (allow "Install unknown apps" for your file manager or browser).
4. Optionally download `room-schemas` and commit `app/schemas` to the repo.

## First run
1. Pair the ELM327 in Android Bluetooth settings first (PIN is commonly 1234 or 0000).
2. Grant Bluetooth (nearby devices) permission and, on older Android, location permission, which Bluetooth scanning requires.
3. Disable battery optimization for the app so logging and the connection service are not killed.
4. Grant notification access (Settings > Notifications > Notification access) to enable the media panel.
5. Pick the adapter in the device picker, or enable Demo mode to try the UI.

## Knob mapping
Default mapping (remappable in Settings): rotate = next/previous focus, press = select, back key = back. Rotary scroll events and D-pad/key events are both handled.

## Safety
This app is not a substitute for the factory gauges or the service manual. Do not interact with it while driving; configure it while parked. Clearing DTCs also resets readiness monitors, so the car may fail an emissions inspection until the drive cycles complete again. Rotary engines are sensitive to low compression, overheating and oil starvation; heed the factory warnings.

## On-car validation checklist
- [ ] Adapter connects and `ATI`/`STI` identify as expected; no repeated reconnects.
- [ ] RPM, speed, coolant, IAT match the factory cluster.
- [ ] Battery voltage matches a multimeter at idle and running.
- [ ] Fuel trims and load look plausible at warm idle.
- [ ] DTC read matches another scanner; no write commands are sent.
- [ ] Extended PIDs (if enabled) cross-checked against a known-good source.
- [ ] Reconnect works after ignition off/on and after the adapter is unplugged.
- [ ] Media panel appears and survives a long drive; battery optimization exemption holds.
- [ ] Knob rotation, press and back behave as mapped.
- [ ] Refresh rate acceptable with your chosen gauge count.

## Getting CI running
```
gh auth login
cd /srv/RX8/app
git init            # if not already a repo
git add -A          # includes .github/ and app/src/main/AndroidManifest.xml, res/
git commit -m "Initial import"
gh repo create rx8-dash --private --source=. --push
```
CI runs `gradle --no-daemon :core:test :app:testDebugUnitTest :app:assembleDebug`. There is no Gradle wrapper jar in the repo; CI installs Gradle 8.11.1 directly. An optional signed release job runs only if the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` (and `KEY_PASSWORD`) repository secrets exist.
