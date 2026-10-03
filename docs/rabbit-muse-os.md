# RabbitMuseOS direction and side-button plan

RabbitMuseOS is the intended Rabbit r1 firmware distribution built around Muse.
The goal is a device that feels like an OS just for Muse, with consistent hardware controls outside the app.
An APK on LineageOS is a stepping stone, not the final distribution goal.

## Status

This document records the owner's direction and agreed button behavior from the [design discussion](https://ampcode.com/threads/T-01a0ffb6-bb56-722d-a55a-9ce963753509) on October 3, 2026.
The owner confirmed the service-first stepping stone and documented USB recovery.
The service is implemented and installed with the owner's authorization on one physical r1. Android reports the service bound, with pairing data and PIN protection preserved.
Builds, gesture tests, and rooted Android 14 emulator checks cover global routing, PIN boundaries, and recording cancellation. Physical-button hands-on confirmation remains pending.
See [Development](development.md#global-side-button-checks) for repeatable checks and [verification status](development.md#verification-status) for delivery limitations.

The current project is a native Android Home app on an unofficial LineageOS 21 / Android 14 image.
Its side-button mapping uses Android `PAIRING` instead of `POWER`.
The button wakes the display, and the enabled service supplies tap-to-lock. Native power-menu behavior remains replaced.
Recording starts after the hold threshold, not immediately on button down.
See [architecture](architecture.md), [setup](setup.md), and [verification status](development.md#verification-status) for current behavior and delivery evidence.
There is no RabbitMuseOS firmware image in this repository.

## Agreed button behavior

- A side-button tap locks Android and turns off the display, including while you are outside Muse r1.
- A press from screen-off wakes to the PIN screen. It does not bypass PIN authentication.
- A hold after unlock records a voice note while Muse r1 is in the foreground. Release sends it.
- A hold outside Muse r1, such as in Android settings, returns to Muse without recording on that press. The next hold can record.
- A press that begins asleep or PIN-locked cannot become a recording gesture, even if you unlock before releasing it. Release, unlock, then press again to record.
- A tap can lock while Muse is preparing a reply or speaking. Locking stops playback. The current foreground-only session disconnects when the app stops, so an unfinished reply might not appear after unlock.
- If a hold cannot record because Muse is disconnected, permission is missing, or another recording guard applies, it must not become a lock tap. Keep the screen on and show why recording could not start.
- Keep PIN protection, pairing credentials, and saved display history intact.

Use 300 ms as the initial tap-versus-hold threshold, subject to hands-on adjustment.
A quick tap must not open the microphone.
Android's default power long-press timeout is 500 ms; 300 ms favors faster voice-note input.
The threshold is not the minimum recording duration: recording starts after the threshold, and the existing recorder discards audio shorter than 0.3 seconds.
At the initial threshold, a usable voice note therefore needs roughly 0.6 seconds of total button hold, plus any startup delay.

## First stepping stone: a global button service

The Muse r1 APK contains an OS-managed Android accessibility service, retaining the current button mapping on compatible LineageOS devices.
The service routes the side-button gesture across apps and requests Android's lock-screen action for taps.
Its access is limited to button routing; it does not request window-content access or read personal conversations.
Unrelated keys retain their normal behavior.

This route avoids flashing modified firmware for each button change.
Distribution still requires an APK, enabling the service, and the existing one-time device button setup. It is not APK-only installation on stock RabbitOS.
Android service management does not guarantee uninterrupted availability: disabling, failure, restart, and wake-event delivery need testing.
Do not claim dependable OS-level button behavior until those checks pass.

Recovery: re-enable the service through device controls, or use the [USB rollback that restores the original side-button mapping](setup.md#restore-the-side-button).
Do not claim automatic restoration of native power behavior when the service is disabled or fails.
Physical-device installation, settings changes, keylayout changes, rebooting, and live Muse tests require separate authorization.

## Firmware destination

The eventual distribution is a RabbitMuseOS firmware image with Muse r1 as the primary device experience and button support included in device setup.
The button contract above should survive the transition from APK installation to firmware distribution.
Do not assume the accessibility service must remain the firmware implementation.

A LineageOS framework change is the alternative if the service cannot meet the contract, or if native power handling is needed for the firmware.
That approach restores the side button to `POWER` and adds a guarded Muse hold action with matching release and cancellation handling.
Stock LineageOS power-button settings alone do not expose the full press-and-release lifecycle needed for push-to-talk.

Before distributing firmware, resolve the base image and build process, signing and updates, device compatibility, recovery and power-menu access, and applicable redistribution rights.
Existing vendor and kernel dependencies, bootloader and flashing risks, and the character illustration's unestablished redistribution rights remain relevant.
Do not bundle SDK tokens, paired credentials, personal display history, or device identifiers in an image.
Neither a public firmware release nor flashing a physical device is authorized by this plan.

## Verification gates

For the service stepping stone:

- Check both sides of the hold threshold, repeated key-down events, cancellation, and orphaned key-up events.
- Check that failed recording never triggers locking and a quick tap never opens the microphone.
- On a disposable, unpaired emulator, exercise tap-to-lock in Muse and Android settings, wake to PIN, and the no-recording boundary across unlock.
- Check that a settings hold returns to Muse without recording, foreground loss cancels recording, locking stops playback, and unrelated keys remain unaffected.
- Exercise service enablement, disablement, restart, and recovery. Report any gap rather than treating foreground-only success as global success.
- After authorization, verify the physical side button, tap and hold feel, boot and wake behavior, and preservation of pairing and PIN protection.

For firmware, additionally verify reproducible builds, installation and recovery on the supported hardware, update behavior, and preservation or explicit migration of pairing and display history.
Keep local checks, device installation, hands-on confirmation, and release status separate in [Development](development.md#verification-status).

## Research references

- [Android accessibility key-event filtering and global lock-screen action](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).
- [Android keyguard dismissal](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback)): a secure PIN is not bypassed by requesting dismissal.
- [LineageOS 21 power-button policy](https://github.com/LineageOS/android_frameworks_base/blob/lineage-21.0/services/core/java/com/android/server/policy/PhoneWindowManager.java): native power gestures and the fixed set of long-press actions.
- [LineageOS 21 gesture detector](https://github.com/LineageOS/android_frameworks_base/blob/lineage-21.0/services/core/java/com/android/server/policy/SingleKeyGestureDetector.java) and [default configuration](https://github.com/LineageOS/android_frameworks_base/blob/lineage-21.0/core/res/res/values/config.xml): platform hold timing and gesture handling.
- [LineageOS 21 accessibility input filter](https://github.com/LineageOS/android_frameworks_base/blob/lineage-21.0/services/accessibility/java/com/android/server/accessibility/AccessibilityInputFilter.java): key delivery depends on input policy, not just service enablement.
- [Android input dispatcher](https://github.com/LineageOS/android_frameworks_native/blob/lineage-21.0/services/inputflinger/dispatcher/InputDispatcher.cpp): ordinary injected key events bypass accessibility input filtering. Emulator verification uses Linux input events through InputReader instead.
