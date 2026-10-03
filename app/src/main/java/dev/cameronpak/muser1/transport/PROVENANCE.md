# Protocol provenance

The protobuf-wire envelope, Noise transport framing, and Muse session behavior
were ported from Meta's Muse Gadget SDK at commit
`7e7123e2815d3e7e3c0f2ca330f576290ae6a6a9`:

- `linux/src/musegadget/noise/{_proto,envelope,framing,transport,noise_xx}.py`
- `linux/src/musegadget/muse_api.py`
- `esp32/components/muse/muse_chat_session.cpp`
- `esp32/components/muse/muse_chat_priv.h`
- `esp32/components/muse/muse_chat_link.c` (voice-note transcription from history)

Those portions are licensed under Apache License 2.0. See
`LICENSE-APACHE-2.0.txt`.
