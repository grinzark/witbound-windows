Per-OS native payload bundled into the installer (Compose `appResourcesRootDir`).
At runtime the app finds these next to itself; in dev it falls back to env vars / PATH.

windows/  (for the shipped .msi — provide these on the Windows build machine)
  bin/whisper-cli.exe   <- whisper.cpp built with CUDA (and/or Vulkan) for GPU speed
  bin/ffmpeg.exe        <- static ffmpeg
  models/ggml-base.en.bin  (or downloaded on first run)

macos/ , linux/  same idea (Metal / Vulkan builds).
