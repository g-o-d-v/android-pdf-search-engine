# OCR model provenance

The repository does **not** commit `cls.nb`, `det.nb` or `rec.nb` directly.
The first Gradle build downloads the official PaddlePaddle Paddle Lite models,
extracts the `.nb` files and places them in generated Android assets.

## Model set

| Generated asset | Official source archive |
|---|---|
| `cls.nb` | `ch_ppocr_mobile_v2.0_cls_slim_opt_for_cpu_v2_10_rc.tar.gz` |
| `det.nb` | `PP-OCRv4_mobile_det.tar.gz` |
| `rec.nb` | `PP-OCRv4_mobile_rec.tar.gz` |

Official download URLs are declared in the root `build.gradle`:

```text
https://paddlelite-demo.bj.bcebos.com/demo/ocr/models/ch_ppocr_mobile_v2.0_cls_slim_opt_for_cpu_v2_10_rc.tar.gz
https://paddlelite-demo.bj.bcebos.com/paddle-x/ocr/models/PP-OCRv4_mobile_det.tar.gz
https://paddlelite-demo.bj.bcebos.com/paddle-x/ocr/models/PP-OCRv4_mobile_rec.tar.gz
```

The detection and recognition models are PP-OCRv4 Mobile models. The
classification model is the official lightweight orientation classifier used
by the Paddle Lite OCR demo.

## Build-time behavior

Run:

```powershell
.\gradlew.bat prepareOfficialOcrModels
```

The archives are cached under:

```text
%USERPROFILE%\.gradle\caches\android-pdf-search-engine\
```

Generated models are written to:

```text
build/generated/officialOcrModels/
```

The same directory contains `MODEL_SHA256.txt` with the hash of the exact
models packaged in the AAR. The generated model files and downloaded archives
are not committed to Git.

## License

PaddleOCR, Paddle Lite and Paddle-Lite-Demo are distributed under the Apache
License 2.0. The project preserves the applicable notices in
[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and `LICENSES/`.
