# How Muse r1 works

Muse r1 is a native Android Home app for a Rabbit r1 running LineageOS.
It uses Android views, not a WebView or Compose. The device keeps PIN lock; push-to-talk runs in the foreground after unlock.
Use [CONTEXT.md](../CONTEXT.md) for the glossary and [Development](development.md#verification-status) for delivery status.

## Voice path

1. `MainActivity` receives a side-button `KEYCODE_PAIRING` event or a character hold.
2. `VoiceRecorder` records 16 kHz, mono, PCM16 samples. `Wave` wraps them in a WAV file in memory.
3. Release sends a voice note through `MuseConnection`. Recordings under 0.3 seconds are discarded; recordings stop at 20 seconds.
4. Muse supplies reply text. `MuseScreen` renders it, and `SpeechOutput` reads the completed reply through Android text-to-speech.

Leaving the foreground cancels recording, stops playback, and disconnects the session.
This is voice-note input, not live streaming dictation.

## Pairing and credentials

`PairingServer`, `PairingCrypto`, and `BleFraming` implement phone pairing over Bluetooth Low Energy.
`DeviceIdentity` gives the gadget its `MuseGadget…` pairing name.
Pairing uses the SDK token provisioned over USB and the r1's existing Wi-Fi connection.

`CredentialStore` encrypts the SDK token and paired device credentials with Android Keystore AES-GCM in app-private, non-backed-up storage.
It also imports and deletes `files/pending-sdk-token`.
Pairing state survives a same-signed `adb install -r`, but not uninstalling or clearing app data.

## Transport and speech

`MuseConnection` discovers the account's Muse VM, refreshes device credentials when needed, and opens a Noise-encrypted WebSocket session.
`NoiseSession` handles the handshake and cipher states; `WireCodec` handles the wire envelopes.
`ReplyTracker` associates acknowledgments, user transcripts, and reply events with the current turn.

The client requests text output because Muse voice-output requests returned generic server errors in earlier live tests.
Android text-to-speech is the fallback. An offline US English voice is preferred when available; the voice is not Muse's native voice.
See [Muse SDK issue 6](https://github.com/facebookincubator/muse-gadget-sdk/issues/6) for related reports.

### Transcripts

Live `message.user` events supply the voice-note transcript, matched to its acknowledged message ID.
Those events can carry a stored sequence older than live status events, so matching relies on message ID rather than rejecting them by sequence alone.
The SDK chat-history route is a fallback when allowed. It returned HTTP 403 for the account used in earlier tests.
Missing transcription does not block a reply or playback.

The screen shows a pulsing waveform while recording and a loading icon after release until transcription arrives.
If no transcript arrives within 30 seconds after the send completes, it shows **Transcript unavailable**. A late transcript replaces that fallback.
These indicators passed emulator checks, were installed on the r1, and received the owner's hands-on confirmation, as reported by the publishing thread on October 3, 2026.

## Display history

Display history is local to the r1. Clearing it does not delete Meta Muse history or reset Muse's conversation context.
`DisplayHistory` saves transcript and reply text in app-private `noBackupFilesDir/display-history.json`; it does not retain audio files. Android backup is disabled.

The screen follows new replies unless you scroll to earlier turns. A circular jump-to-latest button returns to the newest turn and resumes following.
The top-right clear icon and a shake gesture open the same confirmation. Clearing is disabled while recording or awaiting a reply.
Canceling keeps history; confirming clears the local display and saved text. Returning to idle hides history without clearing it.

As of October 3, 2026, the implementation thread reports passing local builds, JVM tests, emulator history and process-restoration checks, and screenshot inspection.
The verified build is installed on the physical r1 with pairing preserved. The history thread reports the owner's hands-on confirmation that the installed display-history flow works.
Shake sensitivity and long-term reliability remain unverified.

## Upstream code

Transport and session behavior were ported from Meta's [Muse gadget SDK](https://github.com/facebookincubator/muse-gadget-sdk).
Keep its [protocol provenance](../app/src/main/java/dev/cameronpak/muser1/transport/PROVENANCE.md) and Apache-2.0 license intact.
The vendored Java Noise implementation has separate [provenance](../app/src/main/java/com/southernstorm/noise/PROVENANCE.md) and an MIT license.
