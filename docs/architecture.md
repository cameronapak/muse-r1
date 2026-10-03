# How Muse r1 works

Muse r1 is a native Android Home app for a Rabbit r1 running LineageOS.
It uses Android views, not a WebView or Compose. The device keeps PIN lock; push-to-talk runs in the foreground after unlock.
Use [CONTEXT.md](../CONTEXT.md) for the glossary and [Development](development.md#verification-status) for delivery status.

## Voice path

1. `SideButtonService` filters side-button `KEYCODE_PAIRING` events across apps. After a 300 ms hold in unlocked, foreground Muse r1, it asks `MainActivity` to begin recording unless the press began during playback or wheel movement claimed it for volume. A character hold starts recording directly and can interrupt playback.
2. `VoiceRecorder` records 16 kHz, mono, PCM16 samples. `Wave` wraps them in a WAV file in memory.
3. Release sends a voice note through `MuseConnection`. Recordings under 0.3 seconds are discarded; capture stops at 20 seconds. Capped side-button audio stays in memory until release sends it or cancellation discards it. Character recording retains its automatic submission at the cap.
4. Muse supplies reply text. `MuseScreen` renders it, and `SpeechOutput` reads the completed reply through Android text-to-speech.

Leaving the foreground cancels recording, stops playback, and disconnects the session.
This is voice-note input, not live streaming dictation.

## Global side button

`SideButtonService` is an Android accessibility service enabled by the device owner.
It requests key filtering, not window-content access. Unrelated keys pass through unchanged.
The existing keylayout maps the physical button to the wake-capable `PAIRING` key; the service adds tap-to-lock without changing the mapping.

`SideButtonGesture` distinguishes taps from holds and tracks the original press through release or cancellation.
A tap requests Android's global lock-screen action, including outside Muse r1 and while a reply is pending or speaking.
Locking stops playback and cancels recording; the app disconnects when it stops. An unfinished reply might not appear after unlock.
A hold outside Muse r1 returns to the Home activity without recording on that press.

A press that starts asleep or PIN-locked cannot record across unlock.
Screen-off, wake, unlock, foreground loss, service interruption, and unbinding cancel pending or active button gestures.
The service invokes recording only through an in-process activity reference; there is no public recording intent.
A failed hold never becomes a lock tap. With the service disabled, the foreground app shows setup guidance and still supports character holds.
Native power-menu behavior remains replaced, and tap-to-lock depends on the service being enabled and running.

These controls have local and emulator evidence and are installed with the service bound on one physical r1. Hands-on button confirmation remains pending. See [Development](development.md#verification-status).
[RabbitMuseOS](rabbit-muse-os.md) records the firmware destination and this service's role as a stepping stone.

### Side-button and wheel volume

In unlocked, foreground Muse r1, hold the side button and turn the wheel. Up increases Android media volume; down decreases it. Alarms and other sound settings are unchanged.
`MainActivity` routes wheel `DPAD_UP` and `DPAD_DOWN` key events to `SideButtonService`, which checks the press origin, foreground activity, window focus, and lock state.
The [published r1 wheel driver](rabbit-muse-os.md#research-references) emits Linux up/down key events rather than Android scroll motion events. This change does not remap the wheel.

The first wheel movement cancels and discards any recording started by that side-button press and claims it through release. Release cannot send or lock. Matching wheel key-ups are consumed even if the side button is released first; wheel events without an eligible side-button press retain normal navigation.
`MuseScreen` shows a volume percentage for 1.5 seconds after the last adjustment and hides it on pause.

`SpeechOutput.hasPlayback` includes both queued and active speech. A side-button hold beginning during playback preserves it and cannot record later on that press, even if playback ends. A tap still locks; a character hold still interrupts playback to record.
Playback starting after button-down does not change an ordinary press into a playback-preserving press.
The volume change passes local and emulator checks and is installed on one physical r1 with pairing preserved. Its wheel keylayout maps scan codes 103/108 to `DPAD_UP`/`DPAD_DOWN`. Physical wheel events, direction, and audible speaker output remain unverified there.

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
The [Android TTS decision](adr/0001-android-tts-until-muse-gadget-voice.md) records why this stays in place and when to revisit native gadget voice.

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
