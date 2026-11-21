# Model selection and practical trade-offs

| Option | Runtime | Packs | Speed | Translation quality |
| --- | --- | --- | --- | --- |
| **Fast (recommended)** | ML Kit on-device | zh + th | One translate() per speech hypothesis | Best default for latency; zh → th may be routed internally via English |
| **Pivot (experimental)** | ML Kit on-device | zh + en + th | Two sequential translate() calls | For comparison only; **not proven better** |
| **System speech recognition** | Android selected recognizer | Provider dependent | Device/network dependent | Mandarin recognition can send audio to provider |
| **Offline speech recognition** | Android local recognizer | OS-provided speech pack (device-dependent) | Device dependent | Requires Android 12+ local recognizer; Chinese model availability not guaranteed |

The Model Manager downloads actual **ML Kit per-language translation packs**, not arbitrary language models. It shows installed statuses. ML Kit does **not** expose a catalog of interchangeable high-quality Chinese→Thai model families. Deleting a pack while translating may cause the service to fail or redownload it, so stop translation first.

Speech pack download is requested through Android's `SpeechRecognizer.triggerModelDownload()` where available. That pack is managed by the device's recognition service, so HanSub **cannot delete it with ML Kit**.

For higher-quality dramatic dialogue translation a future version can add a secure server-side contextual model, measure Chinese WER/CER, Thai translation adequacy, CPU/RAM and end-to-end P50/P95 subtitle latency. Online models cannot honestly be presented as downloadable offline packs.

See:
- https://developers.google.com/ml-kit/language/translation/android
- https://developers.google.com/ml-kit/language/translation/
- https://developer.android.com/reference/android/speech/SpeechRecognizer
