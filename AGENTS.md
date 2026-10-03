# Muse r1

Read [CONTEXT.md](CONTEXT.md) for the project's language. It defines concepts, not delivery status.

## Read when needed

- App behavior or protocol changes: [Architecture](docs/architecture.md).
- Builds, tests, UI captures, or verification claims: [Development](docs/development.md).
- App installation, SDK tokens, pairing, or side-button recovery: [Set up Muse r1](docs/setup.md).
- Firmware, bootloader, partitions, or stock recovery: [Install LineageOS](docs/lineageos.md).

## Device safety

Local builds and disposable, unpaired emulator checks can run without asking.
Ask before physical-device operations or live Muse account tests, including instrumentation that opens the microphone.
Preserve pairing with `adb install -r`; clearing app data or uninstalling loses credentials.
Keep PIN lock intact. Treat keylayout changes, rebooting, flashing, and wiping as separately authorized device operations.
Keep tokens, credentials, device serials, and personal conversations out of source, logs, screenshots, and public docs.

## Delivery claims

Distinguish local code, executed checks, installation, and hands-on confirmation.
Treat a glossary definition or planned feature as intent until implementation and verification support it.
Preserve vendored licenses and provenance when changing pairing or transport code.
