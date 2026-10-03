# Set up Muse r1

Install the app, pair it with your Muse account, and use the side button to talk.
These instructions use a source-built debug APK. A signed release installation path is not established here.

## Before you start

- Use a Rabbit r1 running the [tested LineageOS 21 setup](lineageos.md), connected to Wi-Fi.
- Set a device PIN. Enable USB debugging and authorize your computer.
- Install [Android platform tools](https://developer.android.com/tools/releases/platform-tools) and [build the app](development.md#build).
- Get a [Muse SDK token](https://gadgets.muse.ai/settings/sdk-tokens), review the [SDK terms](https://gadgets.muse.ai/sdk-terms), and have the Muse app on your phone.

Run commands from the repository root. Connect only the r1 while following this guide.
Run `adb devices` and check that its state is `device`. If several devices appear, add `-s YOUR_SERIAL` after every `adb`; use your own serial, not someone else's.

## 1. Install the app

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.cameronpak.muser1/.MainActivity
```

The app opens and asks for an SDK token if you have not provisioned one.
Keep `-r` for updates to preserve pairing. Uninstalling or clearing app data requires provisioning and pairing again.
Updates also need the same signing key; a debug APK built on another computer can fail to replace your installed APK.

## 2. Provision your SDK token

If the r1 is already paired, skip this step and the next one.
The debug APK supports `run-as` so you can transfer the token into app-private storage without root.
Run this block in **Bash** on your computer. The prompt hides your input; paste only the token and press Enter.

```bash
adb shell am force-stop dev.cameronpak.muser1
read -r -s -p 'Muse SDK token: ' MUSE_SDK_TOKEN
printf '\n'
printf '%s' "$MUSE_SDK_TOKEN" | adb shell \
  'run-as dev.cameronpak.muser1 sh -c "umask 077; mkdir -p files; cat > files/pending-sdk-token"'
unset MUSE_SDK_TOKEN
adb shell am start -n dev.cameronpak.muser1/.MainActivity
```

Check the import without printing the token:

```sh
adb shell 'run-as dev.cameronpak.muser1 sh -c "test ! -e files/pending-sdk-token && test -s no_backup/credentials.enc && echo ENCRYPTED_IMPORT_OK"'
```

Continue only if it prints `ENCRYPTED_IMPORT_OK` and the app does not show a secure-storage error.
The app encrypts the SDK token and pairing credentials with Android Keystore AES-GCM and deletes the pending plaintext file after import.
Android backup is disabled. Keep tokens out of files in the repo, shell command arguments, logs, and screenshots.

## 3. Pair with Muse

1. On the r1, hold the empty background and choose **Pair with Muse**. Grant the Bluetooth permissions and enable Bluetooth if prompted.
2. On your phone, open Muse's **Settings > Devices** and turn on **Developer mode**. Add the `MuseGadget…` device whose name matches the r1 screen.
3. Wait for the r1 to connect. The pairing window lasts two minutes; reopen it if it expires.

The r1 uses its existing Wi-Fi connection. Grant microphone permission when you first record.

## 4. Map the side button

**This replaces Android's native side-button power behavior.** The service in the next step supplies tap-to-lock; native power-menu behavior remains replaced.
It preserves PIN lock and does not change the scroll wheel or the separate PMIC power input.
The override lives in userdata, not the system partition. You can [restore the original mapping](#restore-the-side-button).
If this override is already loaded, keep it. The global button service does not need a new keylayout or reboot.

On the r1, enable **Settings > System > Developer options > Rooted debugging**, then run:

```sh
adb root
adb wait-for-device
adb shell 'mkdir -p /data/system/devices/keylayout; chown system:system /data/system/devices /data/system/devices/keylayout; chmod 755 /data/system/devices /data/system/devices/keylayout'
adb push hardware/mtk-kpd.kl /data/system/devices/keylayout/mtk-kpd.kl
adb shell 'chown system:system /data/system/devices/keylayout/mtk-kpd.kl; chmod 644 /data/system/devices/keylayout/mtk-kpd.kl; restorecon -RF /data/system/devices'
adb reboot
adb wait-for-device
adb shell dumpsys input
```

In the `mtk-kpd` device section, check that `KeyLayoutFile` is `/data/system/devices/keylayout/mtk-kpd.kl`.
The file maps Linux key `116` to Android `PAIRING`, a wake-capable key handled by the global button service.
Turn **Rooted debugging** off afterward.

## 5. Enable Side button controls

1. In Muse r1, hold the empty background to open device controls, then choose **Side button settings**.
2. In Android accessibility settings, select **Side button controls**, enable the service, and review Android's permission prompt.
3. Return to Muse r1. Grant microphone permission when you first record.

The service filters the side button across apps. It does not request window-content access or read personal conversations.
If Android blocks enabling a sideloaded service with a restricted-settings warning, open **Settings > Apps > Muse r1**, use its menu to allow restricted settings, then return to accessibility settings.
The exact Android labels can vary by build.

Tap-to-lock depends on the service staying enabled and running. If it is disabled, Muse r1 shows setup guidance when it receives the button release; character holds still work.
The global button behavior passed emulator checks. The verified APK is installed with the service bound on one physical r1; hands-on confirmation remains pending. See [verification status](development.md#verification-status).

## 6. Use Muse r1 as your home screen

```sh
adb shell cmd package set-home-activity dev.cameronpak.muser1/.MainActivity
```

Unlock the r1, hold the side button, speak, and release. Muse returns text, and Android reads it aloud.
Recording starts after a 300 ms hold. Recordings last between 0.3 and 20 seconds, so a usable voice note needs roughly 0.6 seconds of total button hold plus startup time.
Leaving the app cancels recording without sending it.
You can also hold the character to talk. A tap on the character or a hold on the empty background opens device controls.
Swipe from an edge to reveal Android's system bars temporarily.

With the volume shortcut build, hold the side button and turn the wheel while Muse r1 is open and unlocked. Up raises media volume; down lowers it. A small percentage indicator disappears after 1.5 seconds without adjustment. Alarms and other sound settings are unchanged.
Once the wheel moves, that press controls volume through release. Any side-button recording already started is discarded; release neither sends nor locks. Without the side button, the wheel still navigates normally.
A side-button hold beginning during playback keeps the reply playing and does not record, even if playback ends before release. Hold the character to interrupt playback and talk instead.
At the 20-second limit, the microphone stops and Muse r1 asks you to release to send. Turning the wheel can still discard that capped side-button recording. Character recording keeps its automatic submission at the limit.
These changes pass local checks but have not been installed or checked hands-on on the physical r1. They require a same-signed app update, not a new keylayout or reboot. See [verification status](development.md#verification-status).

Tap the side button to lock Android, including from Android settings or while Muse is replying or speaking. Locking stops playback and disconnects the foreground session; an unfinished reply might not appear after unlock.
If the screen is locked, press the side button to wake it, release, unlock with your PIN, then hold again to speak. That wake or locked press cannot become a recording gesture.
Holding in another app returns to Muse without recording on that press. A hold that cannot record shows why; it never becomes a lock tap.
Check the recording indicator, release-to-send, reply, and audible playback on your device.
Speech uses Android text-to-speech, not Muse's native voice.

## Recover or troubleshoot

- **Button does not lock or record:** Re-enable **Side button controls** from device controls. Hold the character to talk while recovering, or [restore the native button mapping](#restore-the-side-button) over USB. Disabling the service does not automatically restore native power behavior.
- **Not connected:** Open device controls and choose **Android settings** to check Wi-Fi, then **Reconnect to Muse**.
- **No sound:** Check Android media volume and text-to-speech settings. The app prefers an available offline US English voice.
- **No transcript:** Muse's reply and playback can still work. See [transcript limitations](architecture.md#transcripts).
- **USB device missing:** Use a data-capable cable, unlock the screen, authorize USB debugging, and close WebUSB browser tabs.
- **`adb root` fails:** Enable LineageOS's **Rooted debugging** switch. Ordinary USB debugging is not enough.
- **Leave the Muse home screen:** Open **Android settings** from device controls and select another installed Home app under default apps. Keep Muse installed to preserve pairing.

### Restore the side button

Enable **Rooted debugging**, then remove only this override:

```sh
adb root
adb wait-for-device
adb shell rm /data/system/devices/keylayout/mtk-kpd.kl
adb reboot
adb wait-for-device
adb shell dumpsys input
```

Check that `mtk-kpd` uses `/system/usr/keylayout/Generic.kl` again.
Turn **Rooted debugging** off afterward. This restores the mapping, not RabbitOS.
