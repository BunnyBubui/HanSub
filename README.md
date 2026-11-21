# HanSub v0.3 — Chinese → Thai Live Subtitles

A native Android preview for near-real-time Mandarin subtitle translation.

## Download APK

When [Android Build](https://github.com/BunnyBubui/HanSub/actions/workflows/android-build.yml)
passes on `main`, the public **pre-release debug APK** is published at:

**[HanSub-debug.apk](https://github.com/BunnyBubui/HanSub/releases/download/v0.3.0-preview/HanSub-debug.apk)**

If the asset is missing, check the workflow logs; do **not** consider a failed or
unverified build downloadable. The debug APK is test-only, not a production release.

## Translation modes

| Mode | Text translator | How it works | Requires |
| --- | --- | --- | --- |
| **Fast (default)** | Google ML Kit | Mandarin speech → zh/th downloaded language packs → Thai subtitles | On-device translation packs |
| Pivot (experiment) | Google ML Kit | zh → English → Thai, two calls; **not guaranteed more accurate** | zh/en/th packs |
| Cloud Lite (optional) | Gemini 3.5 Flash-Lite | Fast ML Kit caption first; revised contextual Thai later | Own HTTPS HanSub backend + API subscription |
| Cloud Quality (optional) | Gemini 3.8 Flash | Same hybrid flow, different remote model | Same backend |

Cloud models **are not downloadable Android models**. The Model Manager downloads
real ML Kit Chinese/Thai/English language packs and can request Android OS
Mandarin on-device recognition packs on supported devices. A future on-device
GenAI speech translation integration would depend on high-end compatible hardware
and is not falsely presented as available everywhere.

### Context-aware cloud translation

Optional [FastAPI backend](backend/README.md) sends the final Chinese utterance,
up to 6 prior utterances, and a 30-name Chinese → Thai glossary to Gemini.
These words are **sent off-device only when a Cloud mode is selected**. The
application never contains your Gemini API key. Add a trusted HTTPS
`/translate` URL and a custom bearer client token via Model Manager.
If your backend fails or is not configured, ML Kit remains available as fallback.
A cloud model is **not** necessarily faster than on-device ML Kit.

**Performance:** In Model Manager, view P50/P95 measurements from final subtitle
translations by engine on your own device, and run a sample Fast/Pivot test.
These are response-time measurements, not automatic quality scores. To claim
better accuracy, measure human-rated drama dialogue quality with a labeled corpus.

## First use

1. Install debug APK on Android 8+ (minSDK 26). Grant microphone + floating overlay permissions.
2. Open **AI Model Manager** and download Chinese + Thai ML Kit language packs.
3. Keep **Fast** selected for low-latency captions.
4. Start HanSub and play Mandarin speech through a speaker (another device
   recommended for early tests).
5. Drag the floating Chinese/Thai subtitle box, or use notification **หยุด**.

This MVP uses the **microphone**, *not* Android internal playback capture; it does
not circumvent DRM or protected streaming apps. System speech recognition may use
cloud services even if ML Kit translation is local unless you choose a supported
on-device speech recognizer.

## Build

Install JDK 17 + Android SDK 35 + Gradle 8.11.1 and run
`gradle :app:testDebugUnitTest :app:assembleDebug` (or open in Android Studio).
The APK is normally `app/build/outputs/apk/debug/app-debug.apk`.

GitHub Actions builds/tests a debug APK and, only on successful push builds,
publishes it as a public GitHub pre-release asset. Source is under `app/`,
server example under `backend/`.

## Privacy and attribution

- Transcripts are held in volatile memory only. Only response-time samples
  are saved locally.
- Cloud mode sends text, history and glossary to **your configured HTTPS server**.
  You control its deployment and any costs.
- Back up neither cloud client credentials nor raw dialogue. `allowBackup=false`
  is set in AndroidManifest.
- **Powered by Google Translate**: Google ML Kit translation terms and attribution
  requirements apply: https://developers.google.com/ml-kit/language/translation/translation-terms
- This is an early preview with no physical-device validation yet.
