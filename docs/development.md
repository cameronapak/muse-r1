# Build and verify Muse r1

Build locally first. Use a disposable emulator for UI work; physical-device checks and live Muse tests need explicit authorization.

## Build

Install Java 17 and an Android SDK with platform 36. Set the SDK path in your local, ignored `local.properties` as `sdk.dir=/YOUR/ANDROID/SDK`, or configure `ANDROID_HOME`.
If you do not have the source yet, download it from [GitHub](https://github.com/cameronapak/muse-r1), or clone it:

```sh
git clone https://github.com/cameronapak/muse-r1.git
```

Open a terminal in the downloaded or cloned repository root and run:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
```

Expect `BUILD SUCCESSFUL`. The app APK is `app/build/outputs/apk/debug/app-debug.apk`.
The test APK is `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.
The JVM tests cover WAV encoding, pairing cryptography, Bluetooth framing, wire envelopes, Noise sessions, and reply tracking.
Follow [Set up Muse r1](setup.md) to install on a physical r1.

Run Android lint separately:

```sh
./gradlew :app:lintDebug
```

Lint has known failures in earlier reports. Report failures rather than treating an APK build as a clean lint result.
No CI configuration or active Git hook currently runs these checks automatically.

## Emulator UI checks

Create and start a disposable, unpaired Android 14 emulator using Android Studio's Device Manager.
Use `adb devices` to find its serial, then replace `YOUR_EMULATOR_SERIAL` below.
The visual test requires an emulator and a 480×640 screen at density 190.

```sh
export ANDROID_SERIAL=YOUR_EMULATOR_SERIAL
adb shell wm size 480x640
adb shell wm density 190
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e visual true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

Check the instrumentation output for success. The visual mode renders actual native views without recording audio or submitting a Muse turn.
Keep the emulator unpaired: launching the app on a paired device can connect to Muse even when the selected test does not submit a turn.
The fixtures exercise hidden system bars, layout, conversation scrolling, touch controls, indicators, and accessibility descriptions.

Before writing artifacts, ensure `/.amp/in/` is in the repository-local exclude file returned by `git rev-parse --git-path info/exclude`.
Pull the combined idle-and-reply capture from app-private `cache/` and inspect it:

```sh
mkdir -p .amp/in/artifacts
adb exec-out run-as dev.cameronpak.muser1 cat cache/ui-idle-and-reply.png \
  > .amp/in/artifacts/ui-idle-and-reply.png
unset ANDROID_SERIAL
```

For other captures, check their output path in `DeviceChecks.kt`; the tests use both `files/` and `cache/`.
For an appearance change, inspect affected states, not just the default screen. A capture without inspection is not visual verification.

### Display-history checks

On the same disposable, unpaired emulator, run the history fixtures, force-stop the app, then check restoration in a new process:

```sh
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w -e history true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
adb -s YOUR_EMULATOR_SERIAL shell am force-stop dev.cameronpak.muser1
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w -e history true -e restore true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

Both runs must report success. They check retention, disk restoration, reading position during streaming and late transcripts, jump-to-latest, canceled recording, clear guards and confirmations, stale events after clearing, and idle preservation.
They use no microphone recording or live Muse turns. The tests replace the emulator's local display history with fixtures.

History screenshots use app-private `files/` because Android can purge `cache/` during process-restart checks:

```sh
adb -s YOUR_EMULATOR_SERIAL exec-out run-as dev.cameronpak.muser1 \
  cat files/history-two-turns.png > .amp/in/artifacts/history-two-turns.png
adb -s YOUR_EMULATOR_SERIAL exec-out run-as dev.cameronpak.muser1 \
  cat files/history-reading-earlier.png > .amp/in/artifacts/history-reading-earlier.png
```

Inspect both captures for readable turns and unobstructed controls. Physical shake sensitivity and hands-on scrolling require separate device confirmation.

## Physical-device checks

After authorization, install the app and test APK with `adb install -r` and select the intended device explicitly.
The default instrumentation mode checks key dispatch, microphone samples, WAV lengths, and local audio playback:

```sh
adb -s YOUR_R1_SERIAL shell am instrument -w \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

It records microphone samples locally and discards them without submitting a voice note.
It launches the app, which can connect to Muse when paired. It is not an offline test of a paired device.
Injected key events verify the Android input path; a person must still check the physical button and audible speaker output.

### Live Muse tests

The explicit `cloud=true` and `voice=true` arguments use the paired account and send a Muse turn. Run only with authorization.
The `voice=true` UI path requires a synthetic 16 kHz, mono, PCM16 WAV at app-private `cache/test-voice.wav`; it replaces microphone samples before upload.
Use `expected` for the reply substring and `transcript` for the expected spoken prompt.

On macOS, create the same fixture used in the earlier session:

```sh
mkdir -p .amp/in
say -v Samantha -r 145 -o .amp/in/test-voice.aiff \
  'Please say the words Muse on Rabbit is working and nothing else.'
afconvert -f WAVE -d LEI16@16000 -c 1 \
  .amp/in/test-voice.aiff .amp/in/test-voice.wav
adb -s YOUR_R1_SERIAL shell \
  'run-as dev.cameronpak.muser1 sh -c "mkdir -p cache; cat > cache/test-voice.wav"' \
  < .amp/in/test-voice.wav
adb -s YOUR_R1_SERIAL shell am instrument -w -e voice true \
  -e expected 'Muse on Rabbit is working' \
  -e transcript 'Please say the words Muse on Rabbit is working and nothing else.' \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

Verify the transcript, reply content, and playback completion. An HTTP success or a spoken generic error is not a correct answer.
Remove the fixture after testing with `adb -s YOUR_R1_SERIAL shell run-as dev.cameronpak.muser1 rm cache/test-voice.wav`.

## Verification status

As of October 3, 2026, the installation and implementation threads reported:

- LineageOS 21 booted on one Rabbit r1; the side-button override was loaded, and injected events woke the display while preserving PIN lock.
- The owner confirmed physical push-to-talk worked. Synthetic speech produced expected transcripts and replies and completed Android playback.
- Final app and test APK builds and all 29 JVM tests passed. Saved lint reports still show permission-analysis errors in recording and pairing code and indentation errors in vendored Noise code.
- Recording and transcription indicators passed emulator checks and were installed with pairing preserved. The publishing thread subsequently reported the owner's hands-on confirmation that the installed indicator flow works.
- Display history passed unpaired emulator history, force-stop restoration, and existing visual checks. The history thread inspected screenshots of two turns, reading earlier turns, restoration, and confirmation without overlap or clipping. No microphone or live Muse turn was used in these checks.
- With the owner's authorization, the history thread installed the verified APK using `adb install -r`. The installed APK hash matched the local build, encrypted credential hashes were unchanged, and the original first-install time was preserved. This installation did not record audio, send a live turn, clear app data, reboot, or change keylayouts.
- The history thread subsequently reported the owner's hands-on confirmation that the installed display-history flow works: "Worked like a charm".

Display history is verified locally, installed, and confirmed hands-on for the reported flow. Shake sensitivity tuning and long-term reliability remain unverified.
These results were reported by the implementation threads, not rerun as part of documentation work.
Hands-on transcript display, speaker loudness, long-term battery behavior, stock restoration, and other firmware builds remain unverified here.

First-party code and documentation are licensed under [MIT](../LICENSE). Preserve the existing third-party licenses and notices.
The character illustration was generated from a supplied Muse avatar reference. It is not covered by the MIT license, and its redistribution rights are not established here.
Do not publish `.amp/in/`, credentials, personal conversations, or device identifiers.
