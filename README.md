# Muse r1

Turn a Rabbit r1 into a push-to-talk Muse gadget. Hold the side button, speak, and release.
Muse replies with text, and Android reads it aloud.

Muse r1 is a native Android Home app tested on LineageOS 21 / Android 14.
It uses Meta's [Muse gadget SDK protocol](https://github.com/facebookincubator/muse-gadget-sdk).
It is not RabbitOS or an official Rabbit or Meta app.

## Get started

1. [Install LineageOS](docs/lineageos.md). Start with an unlocked bootloader; the guide links to external unlock instructions and records the build tested on one r1.
2. [Set up Muse r1](docs/setup.md). Build and install the app, provision your SDK token, pair from your phone, and map the side button.
3. Unlock the r1 with your PIN, hold the button, and speak. Release to send.

**Installing LineageOS wipes your device and reduces boot-chain security.** Read the warnings before flashing.
The button mapping replaces normal side-button lock and power-menu behavior; the setup guide includes rollback.

## What to expect

- Voice notes, not streaming dictation. Recordings last between 0.3 and 20 seconds.
- A full-screen character and reply text. Hold the character to talk, or tap it to open device controls.
- Android text-to-speech, not Muse's native voice. Missing transcripts do not block replies or playback.
- Pairing preserved by same-signed app updates. Uninstalling or clearing app data requires pairing again.

This project currently uses source-built debug APKs. Persistent display history passes local tests and is installed on the r1; physical scrolling and shake sensitivity still need hands-on confirmation.
See [verification status and limitations](docs/development.md#verification-status) before relying on a feature or firmware configuration.

## For contributors

- [Build and verify](docs/development.md)
- [How the app works](docs/architecture.md)
- [Project glossary](CONTEXT.md)
- [Agent guidance](AGENTS.md)

## License

First-party code and documentation are licensed under [MIT](LICENSE). Vendored code retains its existing licenses and notices.
The character illustration is not covered by this license; its redistribution rights are not established here.
