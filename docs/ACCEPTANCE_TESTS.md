# HanSub v0.3 acceptance checklist

This branch exists to trigger a pull-request build and verify the Android CI pipeline.

## Automated
- Compile Android 35 Java project, unit tests for language-model selections, dialogue context, P50/P95.
- Build debug APK and attach Actions artifact.
- Check Python backend source with compileall.

## Manual on physical Android
1. Grant microphone, notifications and overlay; test Android 13/14/16 foreground microphone service.
2. Download zh/th offline packs and select Fast.
3. Feed Chinese drama dialogue, watch overlay; rotate screen, drag captions.
4. Confirm final transcript is not dropped if identical to partial transcript.
5. Add character name glossary; choose Cloud Lite and Quality with own HTTPS backend.
6. Disconnect network during Cloud: ML Kit provisional captions remain visible.
7. Check Speech on-device availability; unsupported devices explain the limitation.
8. Confirm Stop button removes overlay and active microphone indicator.
9. Compare P50/P95 across at least 50 utterances; rate translation adequacy separately.
10. Test playback app restrictions; no DRM bypass.

Do not claim Android runtime validation from this checklist alone.
