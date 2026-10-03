---
status: accepted
date: 2026-10-03
---

# Keep Android TTS until Muse provides native gadget voice

Muse r1 requests `output_modality: "text"` and uses Android text-to-speech for playback because Muse's gadget voice-output path failed in earlier live tests.
Keep this approach for now: [SDK PR #12](https://github.com/facebookincubator/muse-gadget-sdk/pull/12) fixes replies by requesting text and removing the server TTS fetch, not by restoring Muse's native voice.
Android TTS already speaks completed replies and prefers an offline US English voice when available, so no additional TTS provider is needed; playback is not Muse's native voice.

Muse's native voice remains the desired output.
In [issue #14](https://github.com/facebookincubator/muse-gadget-sdk/issues/14#issuecomment-5965083645), Anant says the gadget voice pipeline is still being worked on and recommends a TTS API of your choice in the short term; no replacement endpoint or release date is announced as of this decision.
Revisit when Meta publishes a supported native gadget voice API, then verify correct replies, audible playback, interruption, and completion on the r1 before replacing Android TTS.
