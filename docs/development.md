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
The JVM tests cover WAV encoding, pairing cryptography, Bluetooth framing, wire envelopes, Noise sessions, reply tracking, and side-button gestures.
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

### Global side-button checks

Use a fresh, unpaired, rooted Android 14 **Google APIs** emulator, not a Play Store image.
Start it with `-no-audio` for the optional recording-lifecycle checks. Do not use a physical device or an emulator containing SDK tokens, pairing credentials, or an existing PIN.
The fixture sets and removes its own test PIN, restores accessibility enablement settings, and uses no live Muse transport.

Ordinary `adb shell input` and `UiAutomation` key injection bypass Android's accessibility input filter.
These checks send Linux key events through the emulator's `gpio-keys` device and InputReader instead.
Verify that `gpio-keys` uses `/dev/input/event0` with Linux power key `116` before configuring it.

On that disposable emulator only, map the kernel button using the same keylayout as the r1:

```sh
adb -s YOUR_EMULATOR_SERIAL root
adb -s YOUR_EMULATOR_SERIAL wait-for-device
adb -s YOUR_EMULATOR_SERIAL shell getevent -pl /dev/input/event0
adb -s YOUR_EMULATOR_SERIAL shell 'mkdir -p /data/system/devices/keylayout; chown system:system /data/system/devices /data/system/devices/keylayout; chmod 755 /data/system/devices /data/system/devices/keylayout'
adb -s YOUR_EMULATOR_SERIAL push hardware/mtk-kpd.kl /data/system/devices/keylayout/gpio-keys.kl
adb -s YOUR_EMULATOR_SERIAL shell 'chown system:system /data/system/devices/keylayout/gpio-keys.kl; chmod 644 /data/system/devices/keylayout/gpio-keys.kl; restorecon -RF /data/system/devices'
adb -s YOUR_EMULATOR_SERIAL reboot
adb -s YOUR_EMULATOR_SERIAL wait-for-device
```

Wait for Android to finish booting. In `dumpsys input`, confirm that `gpio-keys` uses `/data/system/devices/keylayout/gpio-keys.kl`.
Set the display to 480×640 at density 190, then install both APKs as described above.
Run the global routing checks without opening the microphone:

```sh
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w -e button true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

Check for `PASS`, not just a successful `adb` exit code. The test covers the disabled-service notice and settings link, no window-content capability, returning Home from a settings hold, a failed hold staying awake, secure taps in Muse and settings, wake to PIN, the unlock-press boundary, service disable/re-enable, and unrelated-key pass-through.

For the recording lifecycle, use an audio-disabled emulator and add `buttonRecording=true`:

```sh
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w -e button true -e buttonRecording true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

This grants emulator microphone permission, exercises local `AudioRecord`, and checks cancellation on canceled release, foreground loss, and service unbind without adding a voice turn.
It also checks that normal release reaches the send path. With no transport or credentials, that attempt produces the expected local `SEND FAILED` and cannot send audio to Muse.
The fixture can add a failed local display-history turn; use only disposable data. A missing microphone permission is tested when permission is not already granted.

Pull and inspect `files/side-button-controls.png` and `files/side-button-disabled.png` with `adb exec-out run-as` as in the history capture instructions.
After a button-service change, rerun the existing visual, display-history, and process-restoration checks too.
Physical button timing, LineageOS service recovery after reboot, speaker behavior on locking, and long-term reliability require separately authorized device checks.

### Volume gesture checks

Use the disposable, unpaired, rooted Android 14 Google APIs emulator configured for [global side-button checks](#global-side-button-checks), with `-no-audio`, a 480×640 screen, and density 190. The fixture requires `/system/bin/uinput` and the `gpio-keys` Linux 116 to `PAIRING` mapping. Install both APKs first. No physical device or paired emulator is allowed for this fixture.

```sh
adb -s YOUR_EMULATOR_SERIAL shell am instrument -w -e volume true \
  dev.cameronpak.muser1.test/dev.cameronpak.muser1.DeviceChecks
```

Require `PASS` in the output. Most cases dispatch side-button and wheel `DPAD_UP`/`DPAD_DOWN` events directly to the real service, forwarding unconsumed wheel events to the Activity.
A separate kernel-input case uses `gpio-keys` and a temporary `uinput` wheel to exercise InputReader and Android's accessibility filter. It verifies that the first wheel event during a side-button press changes volume without leaving touch mode, changing focus, or scrolling history; unheld input still enters navigation mode. Closing the input stream removes the temporary device. The fixture does not establish physical r1 timing or wheel direction.
It also exercises actual Android media-volume steps and limits, unchanged alarm volume, rendered square counts, wave thresholds and muted state, overlay expiry, matching wheel release after side-button release, continued local TTS, both playback transitions during a press, late wheel movement discarding local recording without a turn or lock, character interruption, and unheld wheel navigation.
Two real 20-second captures check that the microphone closes at the cap, no turn is added before release, the wheel can discard capped audio, and release can submit it. Allow about a minute for this fixture.
It grants emulator microphone permission and uses local `AudioRecord` and TTS, but sends no Muse turn. The capped-release check adds a failed local display-history turn and expects `SEND FAILED` without a transport. It restores media volume and accessibility enablement settings afterward. Use only disposable emulator data.

Pull and inspect the volume states and cap notice:

```sh
for state in idle speaking high muted capped; do
  adb -s YOUR_EMULATOR_SERIAL exec-out run-as dev.cameronpak.muser1 \
    cat "files/volume-$state.png" > ".amp/in/artifacts/volume-$state.png"
done
```

Check that the large centered overlay is readable, dims the character and conversation, has no numeric percentage, and shows the correct filled squares and speaker waves. Maximum volume has three waves; muted volume has an X, no waves, and no filled squares. Playback can remain active while muted; an audio-disabled emulator cannot verify audible speaker output.

## Physical-device checks

After authorization, install the app and test APK with `adb install -r` and select the intended device explicitly.
Enable **Side button controls** first. The default microphone and live voice-UI fixtures now route holds through that service rather than immediate activity key-down recording.
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

The larger volume overlay and first-wheel focus fix were verified locally on October 3, 2026:

- App and test APK builds and all 37 JVM tests passed.
- The disposable, unpaired, audio-disabled Android 14 emulator passed the volume fixture, including rendered square counts and both sides of the speaker-wave thresholds, muted state, continued TTS, recording cancellation, and capped release-to-send.
- Kernel side-button and wheel input passed the first-wheel check without changing touch mode, focus, or scroll position. Unheld wheel input still entered normal navigation mode.
- Global side-button routing and recording-lifecycle regressions, existing visual checks, display-history checks, and force-stop restoration passed.
- Low, medium, maximum, and muted volume screenshots were inspected at 480×640 without clipping or focus outlines.
- Android lint still failed with 15 errors in unchanged recording, pairing, and vendored Noise code.

With the owner's authorization on October 3, 2026, the verified refinement APK was installed on the physical r1 using `adb install -r`.
The signing key matched the previous installation, and the installed APK hash matched the verified local build. Encrypted credentials, display history, identity preferences, and the original first-install time were unchanged.
Accessibility enablement was unchanged, and Android reported **Side button controls** bound with key-filtering capability, no window-content capability, and no crashed services. The app launch request succeeded while PIN lock remained active; the installation did not bypass unlock.
This installation did not open the microphone, inject button or wheel events, run instrumentation or a live Muse test, clear app data, reboot, or change keylayouts.

This refinement is installed but not published as a release. Hands-on appearance and timing, audible speaker output, and long-term reliability remain unverified.

The original side-button and wheel volume shortcut was verified locally on October 3, 2026:

- App and test APK builds and all 37 JVM tests passed.
- The disposable, unpaired, audio-disabled Android 14 emulator passed the volume fixture, including playback transitions, local recording cancellation before and after the cap, capped release-to-send, and normal unheld wheel navigation.
- Global side-button routing and recording-lifecycle regressions, existing visual checks, display-history checks, and force-stop restoration passed.
- One regression rerun failed at emulator PIN unlock and left the later UI fixtures without Home focus. After dismissing the nonsecure emulator lock screen, all affected fixtures passed on rerun.
- Idle, speaking, and muted volume screenshots and the capped-recording notice were inspected at 480×640 without clipping or overlap.
- Android lint still failed with 15 errors in unchanged recording, pairing, and vendored Noise code.

With the owner's authorization on October 3, 2026, the verified volume APK was installed on the physical r1 using `adb install -r`.
The signing key matched the previous installation, and the installed APK hash matched the verified local build. Encrypted credentials, display history, identity preferences, and the original first-install time were unchanged across installation.
Accessibility enablement was unchanged, and Android reported **Side button controls** bound and running with key-filtering capability, no window-content capability, and no crashed services. Muse r1 returned to the foreground, and PIN protection remained active.
The existing side-button override remained loaded. The wheel input device used `/system/usr/keylayout/Generic.kl`, whose scan codes 103/108 were confirmed to map to `DPAD_UP`/`DPAD_DOWN`.
This installation did not open the microphone, inject button or wheel events, run instrumentation or a live Muse test, clear app data, reboot, or change keylayouts.

The owner subsequently confirmed the installed original shortcut works: "It works!" Its small percentage indicator and background-focus behavior prompted the refinement above. That confirmation does not verify the new overlay or input routing; measured physical timing, audible speaker output, and long-term reliability remain unverified here. Neither volume build is published as a release. Earlier installation evidence below describes the previous global-button build.

The global side-button change was verified locally on October 3, 2026:

- App and test APK builds and all 34 JVM tests passed.
- A disposable, unpaired, rooted Android 14 emulator passed global button checks using Linux input events: taps from Muse and settings, PIN-protected wake, the unlock-press boundary, settings holds returning Home, failed holds staying awake, service disable/re-enable, and unrelated-key pass-through.
- An audio-disabled emulator passed local recording-lifecycle checks, including cancellation and normal release reaching the send path without contacting Muse.
- Existing visual, display-history, and force-stop restoration checks passed. Side-button settings and disabled-service screenshots were inspected without clipping or tutorial overlays.
- Android lint failed with 15 errors in unchanged recording, pairing, and vendored Noise code. An APK build is not a clean lint result.

With the owner's authorization on October 3, 2026, the verified app APK was installed on the physical r1 using `adb install -r`, and **Side button controls** was enabled.
The signing key matched the previous installation, and the installed APK hash matched the verified local build.
Encrypted credentials, display history, identity preferences, and the original first-install time were unchanged.
Android reported the service bound and running with key-filtering capability, no window-content capability, and no crashed services. PIN protection remained active, and the existing button mapping remained loaded.
This installation did not open the microphone, run instrumentation or a live Muse test, clear app data, reboot, or change keylayouts.

These side-button changes are installed but not confirmed hands-on or published as a release.
Physical button feel, service recovery after reboot, playback stopping on lock, and long-term service reliability remain unverified.
The [RabbitMuseOS plan](rabbit-muse-os.md) records the firmware destination; no firmware image has been built by this change.

Earlier installation and implementation threads reported:

- LineageOS 21 booted on one Rabbit r1; the side-button override was loaded, and injected events woke the display while preserving PIN lock.
- The owner confirmed physical push-to-talk worked. Synthetic speech produced expected transcripts and replies and completed Android playback.
- Final app and test APK builds and all 29 JVM tests passed. Saved lint reports still show permission-analysis errors in recording and pairing code and indentation errors in vendored Noise code.
- Recording and transcription indicators passed emulator checks and were installed with pairing preserved. The publishing thread subsequently reported the owner's hands-on confirmation that the installed indicator flow works.
- Display history passed unpaired emulator history, force-stop restoration, and existing visual checks. The history thread inspected screenshots of two turns, reading earlier turns, restoration, and confirmation without overlap or clipping. No microphone or live Muse turn was used in these checks.
- With the owner's authorization, the history thread installed the verified APK using `adb install -r`. The installed APK hash matched the local build, encrypted credential hashes were unchanged, and the original first-install time was preserved. This installation did not record audio, send a live turn, clear app data, reboot, or change keylayouts.
- The history thread subsequently reported the owner's hands-on confirmation that the installed display-history flow works: "Worked like a charm".

Display history is verified locally, installed, and confirmed hands-on for the reported flow. Shake sensitivity tuning and long-term reliability remain unverified.
The earlier physical-device results were reported by those threads, not rerun during this side-button change.
Hands-on transcript display, speaker loudness, long-term battery behavior, stock restoration, and other firmware builds remain unverified here.

First-party code and documentation are licensed under [MIT](../LICENSE). Preserve the existing third-party licenses and notices.
The character illustration was generated from a supplied Muse avatar reference. It is not covered by the MIT license, and its redistribution rights are not established here.
Do not publish `.amp/in/`, credentials, personal conversations, or device identifiers.
