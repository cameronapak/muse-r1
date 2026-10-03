# Install LineageOS on a Rabbit r1

This guide records the LineageOS 21 / Android 14 installation tested on one Rabbit r1.
It starts with an **already-unlocked bootloader**. It is not an official Rabbit or LineageOS device guide.

**Flashing erases your data and can leave the device unable to boot.** Back up what you need first.
This procedure leaves the bootloader unlocked and disables Android Verified Boot verification.
Do not relock the bootloader with this image and `vbmeta` combination.
The original Rabbit vendor and kernel remain underneath the Android 14 system.
Successful boot does not establish long-term compatibility or current security support.

## Before you start

- Unlock the bootloader using the [r1 escape project's instructions](https://github.com/RabbitHoleEscapeR1/r1_escape). That prerequisite was not performed or verified in this project's installation session.
- Read the [Rabbit r1 LineageOS walkthrough](https://substrate.dougbelshaw.com/rabbit-r1-android) and [Android GSI guidance](https://source.android.com/docs/core/tests/vts/gsi). A generic system image (GSI) replaces Android's system partition; flashing commands depend on the device.
- Install [Android platform tools](https://developer.android.com/tools/releases/platform-tools). The tested host used macOS and platform-tools `37.0.0-14910828`.
- Connect only the r1 over USB. Close browser tabs using Rabbithole or other WebUSB flashers, which can claim the connection.
- Have at least 5 GB of free host disk space for the compressed and expanded images.
- Review the [stock firmware archive](https://github.com/rabbit-hmi-oss/firmware) and [stock web flasher](https://rabbit-hmi-oss.github.io/flashing) before writing anything. Stock restoration was not tested, and an exact backup of the previous firmware was not obtained.

Run the download commands in a separate working directory on your computer.
The examples use macOS `shasum`; on Linux, use `sha256sum` for SHA-256 checks.

## 1. Download and check the tested images

Download the [tested Andy Yan build](https://sourceforge.net/projects/andyyan-gsi/files/lineage-21-td/lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz/).
This is the June 21, 2025 unofficial ARM64 build with Google apps, not a claim that it is the latest or most secure build.

```sh
curl -fL --retry 2 --connect-timeout 20 --max-time 1800 \
  -o lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz \
  'https://downloads.sourceforge.net/project/andyyan-gsi/lineage-21-td/lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz' &&
echo '2ad81102b6902737c182d791f0f88c0c1e31aa11e7f7d0c3e3864c49b04a4aa6  lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz' | shasum -a 256 -c - &&
gzip -t lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz &&
gzip -dc lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img.gz \
  > lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img

curl -fL 'https://dl.google.com/developers/android/qt/images/gsi/vbmeta.img' \
  -o google-gsi-vbmeta.img &&
echo 'f6da5489fd877cb69cf61fa721cfd6d77e530084aefe9b96664f818947ff61f6  google-gsi-vbmeta.img' | shasum -a 256 -c -
```

Each checksum command must print `OK`, and `gzip -t` must finish without an error.
**Stop if any check fails.** These SHA-256 hashes were calculated in the original installation session, not obtained from a separate publisher signature.
The expanded system image was 3,090,542,592 bytes; Google's `vbmeta` image was 4,096 bytes with verification already disabled.

## 2. Check the device and flashing modes

Enter bootloader fastboot using the unlock guide. If Android is already running with authorized USB debugging, use `adb reboot bootloader`.

```sh
fastboot devices
fastboot getvar unlocked
fastboot getvar current-slot
fastboot reboot fastboot
fastboot getvar is-userspace
fastboot getvar current-slot
fastboot getvar is-logical:system_a
fastboot reboot bootloader
fastboot getvar current-slot
```

Continue only if one device appears, the bootloader is unlocked, `is-userspace` reports `yes` in fastbootd, `system_a` is logical, and both modes report slot `a`.
The tested r1 initially reported inconsistent slots; after moving between the modes, both reported `a`.
If your device differs, stop and investigate. These instructions do not cover slot B or a different partition layout.

Bootloader fastboot writes `vbmeta_a` and wipes userdata. Userspace fastboot, called **fastbootd**, writes the logical `system_a` partition.

## 3. Flash slot A

From bootloader fastboot, write the checked `vbmeta` image, then enter fastbootd:

```sh
fastboot flash vbmeta_a google-gsi-vbmeta.img
fastboot reboot fastboot
fastboot getvar is-userspace
fastboot getvar current-slot
```

Check for `yes` and `a` again before writing the system image:

```sh
fastboot -S 100M flash system_a \
  lineage-21.0-20250621-UNOFFICIAL-arm64_bgN-signed.img
```

Every write must return `OKAY`. The tested installation used 30 sparse transfers.
The initial `Invalid sparse file format at header magic` message was followed by successful conversion and writes; a `FAILED` result is not equivalent.
If resizing fails, stop. Deleting other logical partitions was not part of the tested procedure.

This procedure writes only `vbmeta_a` and `system_a` before the data wipe.
It does not flash `boot`, `vendor`, `vendor_boot`, modem, preloader, or slot B.
The tested host rejected `--disable-verity --disable-verification` with `Failed to find AVB_MAGIC at offset: 0` before sending anything.
The checked Google image already disables verification, so it was flashed unchanged.

## 4. Wipe and boot

**The next commands permanently erase userdata and encryption metadata.**
Return to bootloader fastboot and confirm slot `a` before wiping:

```sh
fastboot reboot bootloader
fastboot getvar current-slot
```

If it reports `a`, continue:

```sh
fastboot -w
fastboot reboot
```

The tested wipe erased and reformatted userdata and erased metadata.
It also reported `wipe task partition not found: cache`, then exited successfully.
Wait for Android setup. The first boot took longer while Android optimized apps.

Complete setup, connect Wi-Fi, set a PIN, and enable USB debugging in Developer options.
Authorize your computer, then check:

```sh
adb devices
adb shell getprop ro.build.version.release
adb shell getprop ro.lineage.version
adb shell getprop ro.boot.slot_suffix
```

The tested device returned Android `14`, Lineage `21.0-20250621-UNOFFICIAL-arm64_bgN`, and slot `_a`.
If Google reports an uncertified device, consult [Google's device registration page](https://www.google.com/android/uncertified). Registration was not verified in the installation session.

Continue with [Set up Muse r1](setup.md).
