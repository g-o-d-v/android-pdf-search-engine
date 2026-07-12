# Third-party notices

This project is licensed under Apache-2.0. The following third-party components
keep their own licenses and copyright notices.

## Components packaged in the AAR

### Paddle Lite runtime

- Project: PaddlePaddle/Paddle-Lite
- Recorded runtime line: 2.14-rc
- Purpose: mobile OCR inference runtime
- License: Apache License 2.0
- Bundled files: `libpaddle_light_api_shared.so`
- Maintainer provenance: downloaded from the official Paddle Lite distribution and adapted for this project
- SHA-256:
  - `arm64-v8a`: `b9c201046aa7bf74222e19a170831e5a23dd2be3ae131cf23621a9ab5a1cc198`
  - `armeabi-v7a`: `ac4c37a408426ba40f474404fd1813c3e4f901374e1ff9c352011384fdf1fa64`
- License text: [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)

### PaddleOCR / Paddle-Lite-Demo OCR models

- Projects: PaddlePaddle/PaddleOCR, PaddlePaddle/Paddle-Lite-Demo and
  PaddlePaddle/PaddleX-Lite-Deploy
- Purpose: text detection, text recognition and orientation classification
- License: Apache License 2.0
- Delivery: downloaded from official PaddlePaddle URLs during the first build
- Details: [`MODEL_PROVENANCE.md`](MODEL_PROVENANCE.md)

### OpenCV 4.2.0 Android runtime

- Project: OpenCV
- Purpose: image preprocessing and geometry operations
- License for OpenCV 4.2.0: 3-Clause BSD
- Bundled file: `libopencv_java4.so`
- Maintainer provenance: downloaded from the official OpenCV Android distribution and adapted for this project
- SHA-256:
  - `arm64-v8a`: `8c2a880f9cd1f5d98208ab0a6d10234389b8e49dae1cfde36ba33b8a888a9461`
  - `armeabi-v7a`: `62cf6347b32ecfc7db08848ad86d077a6dbd7a6a00d33cf71c31019c8f28b167`
- License text: [`LICENSES/OpenCV-BSD-3-Clause.txt`](LICENSES/OpenCV-BSD-3-Clause.txt)

### LLVM libc++ runtime

- Project: LLVM / Android NDK
- Purpose: C++ runtime required by the native libraries
- Recorded NDK line: 21.4.7075529
- License: Apache License 2.0 with LLVM exception
- Bundled file: `libc++_shared.so`
- SHA-256:
  - `arm64-v8a`: `38815e321b404e8363aba1c0a5a86649eed45ce31007740039dddb780ea1d653`
  - `armeabi-v7a`: `2826d9b28afa97165b0076f097001b15502aa3d84b568676de511fef9fd5a594`
- License texts:
  - [`LICENSES/Apache-2.0.txt`](LICENSES/Apache-2.0.txt)
  - [`LICENSES/LLVM-exception.txt`](LICENSES/LLVM-exception.txt)

## Compile-time / consumer-side dependencies

### AndroidPdfViewer

- Coordinate: `com.github.mhiew:android-pdf-viewer:3.2.0-beta.3`
- Transitive PDFium: `com.github.mhiew:pdfium-android:1.9.2`
- Purpose: optional viewer adapter used by the sample app
- License: Apache License 2.0
- Not bundled as a transitive dependency because the library declares it as
  `compileOnly`; the consuming application selects the viewer version.

### PDFium

- Purpose: PDF text extraction through the viewer/PDFium runtime supplied by
  the consuming application
- License: BSD-style license
- License text: [`LICENSES/PDFium-BSD-3-Clause.txt`](LICENSES/PDFium-BSD-3-Clause.txt)

## Project owner statement

The project-specific Java, C++ and documentation changes are maintained by
g-o-d-v. Some changes were produced with AI assistance and then reviewed,
modified and accepted by the maintainer. AI assistance does not change the
licenses of third-party code, models or binaries.
