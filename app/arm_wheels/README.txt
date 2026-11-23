Nanobot 0.4.1 dependency wheels for Chaquopy.
CPython 3.12, Android arm64-v8a, API 24 or later. NDK r27d.
Use the extracted directory as a pip --find-links source and constraints-arm64.txt
with -c. Use the existing Nanobot wheel. Other dependencies come from PyPI and
Chaquopy. This archive contains additional wheels, not a complete offline index.
Pillow is built with JPEG and PNG support. Optional WebP, TIFF and FreeType
extensions are disabled. RapidFuzz is pure Python.
The ELF LOAD segments in this archive are aligned to at least 16 KiB. This does
not establish 16 KiB compatibility of other APK components. Imports on Android
must still be checked in the APK.
