# OCR models

Place the three PP-OCRv5 ONNX weights here (same files as the iOS `models/` tree):

- `PP-OCRv5_mobile_det.onnx`
- `PP-OCRv5_mobile_rec.onnx`
- `PP-LCNet_x1_0_textline_ori.onnx`

When this tree lives next to the iOS app, Gradle also falls back to `../models/`.

Download (pinned release used by beanbeaver-core):

```bash
./scripts/fetch-models.sh
```
