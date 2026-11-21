# HanSub — Live Chinese → Thai Subtitles

Native Android app for near-real-time Mandarin speech recognition, Thai translation, and draggable subtitles above other apps.

## Features
- **Fast (recommended):** Android SpeechRecognizer (zh-CN) → on-device Google ML Kit Translation (zh → th).
- **Pivot (experimental):** Chinese → English → Thai in two explicit ML Kit translation stages. Slower and **not guaranteed to be more accurate**. ML Kit itself may pivot via English for non-English pairs.
- **Model Manager:** list/download/delete real ML Kit language packs (Chinese, Thai, optional English); select translation mode.
- **In-app comparison:** run a one-sentence Fast/Pivot latency and sample-output test on the phone (not a rigorous accuracy benchmark).
- **Speech mode:** default recognition or device-local Chinese recognizer on Android 12+ (when available); request its system speech pack on Android 13+ (when supported).
- Persistent foreground microphone service and movable Chinese/Thai overlay captions.
- No built-in third-party cloud API or API keys.

## Limitations
The MVP takes sound **only from the microphone**. It does not capture internal playback from streaming apps or bypass DRM. Same-device playback through the speaker may not be heard reliably on some phones. The recognizer service can use a cloud connection in default mode. Live latency and subtitle quality depend on the phone, scene acoustics, and language pack. This is utterance-level translation, not frame-aligned captions.

## Build / APK
Requirements: JDK 17, Android SDK 35, Gradle 8.11.1 (Android Studio can provision it). Open the repo in Android Studio and run `:app:assembleDebug`; the output is `app/build/outputs/apk/debug/app-debug.apk`. Alternatively use **Actions → Build HanSub Debug APK → Run workflow** and download the APK artifact after a successful run.

1. Open HanSub → **จัดการโมเดล** to download Chinese + Thai.
2. Choose **Fast** (recommended); optionally download English for the experimental Pivot translation.
3. Grant microphone and overlay permission. Press **เริ่มแปล** and play Chinese speech from another speaker.
4. Use the notification STOP action or main screen to exit.

See [model notes](docs/MODELS.md) and [Google ML Kit attribution guidelines](https://developers.google.com/ml-kit/language/translation/translation-terms). ML Kit translation is provided by Google (Powered by Google Translate).

The project has automated tests for selection logic but has not yet been verified on a physical phone. Apache/Google dependency licenses apply to their respective packages.
