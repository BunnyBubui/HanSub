package th.hansub.live;

/** Small text helpers for recognizer output. Kept apart from LocalAsr so tests need no native code. */
final class AsrText {
    private AsrText() {}

    /** Removes the tags SenseVoice may leave, like <|zh|>, and stray spaces. */
    static String clean(String text) {
        if (text == null) return "";
        return text.replaceAll("<\\|[^|]*\\|>", "").trim();
    }
}
