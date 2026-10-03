# Muse r1

Turn a Rabbit r1 into a push-to-talk Muse gadget. Hold the side button, speak, and release.
Muse replies with text, and Android reads it aloud.

Muse r1 is a native Android Home app tested on LineageOS 21 / Android 14.
It uses Meta's [Muse gadget SDK protocol](https://github.com/facebookincubator/muse-gadget-sdk).
It is not RabbitOS or an official Rabbit or Meta app.

## Get started

1. [Install LineageOS](docs/lineageos.md). Start with an unlocked bootloader; the guide links to external unlock instructions and records the build tested on one r1.
2. [Set up Muse r1](docs/setup.md). Build and install the app, provision your SDK token, pair from your phone, map the side button, and enable Side button controls.
3. Unlock the r1 with your PIN, hold the button, and speak. Release to send.

**Installing LineageOS wipes your device and reduces boot-chain security.** Read the warnings before flashing.
The button mapping replaces native power behavior. The enabled global service supplies tap-to-lock; native power-menu behavior remains replaced. The setup guide includes rollback.

## What to expect

- Voice notes, not streaming dictation. Recordings last between 0.3 and 20 seconds.
- Tap to lock Android; press to wake to PIN. Recording starts after a 300 ms side-button hold. These global controls pass emulator checks and are installed with the service enabled on one physical r1; hands-on confirmation remains pending.
- Hold the side button and turn the wheel to adjust media volume while Muse r1 is open and unlocked. Playback continues; release neither sends a voice note nor locks. A hold beginning during playback does not record; hold the character to interrupt and talk. These volume changes pass local checks and are installed on one physical r1; hands-on confirmation remains pending.
- A full-screen character and reply text. Hold the character to talk, or tap it to open device controls.
- Android text-to-speech, not Muse's native voice. Missing transcripts do not block replies or playback.
- Pairing preserved by same-signed app updates. Uninstalling or clearing app data requires pairing again.

This project currently uses source-built debug APKs. Persistent display history passes local tests, is installed on the r1, and has the owner's hands-on confirmation that the installed flow works.
Shake sensitivity and long-term reliability remain unverified.
See [verification status and limitations](docs/development.md#verification-status) before relying on a feature or firmware configuration.

## For contributors

- [Build and verify](docs/development.md)
- [How the app works](docs/architecture.md)
- [RabbitMuseOS direction and side-button plan](docs/rabbit-muse-os.md)
- [Project glossary](CONTEXT.md)
- [Agent guidance](AGENTS.md)

## License

First-party code and documentation are licensed under [MIT](LICENSE). Vendored code retains its existing licenses and notices.
The character illustration is not covered by this license; its redistribution rights are not established here.
