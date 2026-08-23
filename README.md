# Witbound for Windows (and Linux) — desktop companion

Kotlin + Compose Desktop app that does the slow part of Witbound on a computer —
transcribe + align an ebook with its audiobook — then sends the finished book to
the phone over the LAN, already synced. Same pairId + RASM map + `_witbound._tcp`
protocol as the Mac app, so the phone can't tell which computer sent it.

## Code signing
Windows builds are signed through the [SignPath Foundation](https://signpath.org)'s
free code-signing program for open-source projects (certificate by
[SignPath.io](https://signpath.io)). Signed installers ship once the project is approved.

## Layout
- `src/main/kotlin/app/witbound/core/` — reused verbatim from the Android app
  (Aligner, Tokenizer, SyncMapBuilder, EpubParser) + extracted models, SyncMap
  (RASM), SyncNet (pairId). Pure JVM; unit-tested (`CoreTest`).
- `engine/` — `Transcriber` (drives a whisper.cpp binary; GPU comes from the
  binary's build) and `SyncPipeline` (hash → optional network → transcribe →
  align → RASM → optional upload).
- `net/` — `WitboundLink` (wire types), `LanServer` (JDK HTTP file server:
  /pairs, /file/<sha> with Range, /map/<pairId>, POST /delivered), `PhoneBrowser`
  (jmDNS discovery), `Lan` (offer POST + local IPs).
- `Model.kt` / `Main.kt` — the app + Compose UI.

## The GPU speed lever
Speed = which whisper.cpp binary you bundle. Build whisper.cpp with:
- **CUDA** for NVIDIA (fastest; the 100×-class path) — `-DGGML_CUDA=ON`
- **Vulkan** for any DX12 GPU (AMD/Intel/NVIDIA) — `-DGGML_VULKAN=ON`
- CPU fallback otherwise.
The app just runs `whisper-cli`; it offloads to the GPU automatically. Pick the
model for the speed/accuracy trade-off (base.en is a good default; small.en for
better timings; a distil model for max speed).

## Dev run (any OS with a whisper build + ffmpeg)
    WITBOUND_WHISPER=/path/to/whisper-cli \
    WITBOUND_FFMPEG=/path/to/ffmpeg \
    WITBOUND_MODEL=/path/to/ggml-base.en.bin \
    ./gradlew run

## Tests
    ./gradlew test            # core (RASM, pairId vector, align+build)
    # real end-to-end (needs a whisper build):
    WITBOUND_WHISPER=… WITBOUND_FFMPEG=… WITBOUND_MODEL=… \
    WB_EPUB=… WB_AUDIO=… ./gradlew test --tests "*IntegrationTest*"
