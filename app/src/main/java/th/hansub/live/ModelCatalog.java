package th.hansub.live;

/** Describes real installed language packs, not fictitious interchangeable AI models. */
public final class ModelCatalog {
    public static final String FAST = "fast";
    public static final String PIVOT = "pivot";
    public static final String SPEECH_DEFAULT = "default";
    public static final String SPEECH_OFFLINE = "offline";

    private ModelCatalog() {}

    public static String normalizeMode(String value) {
        return PIVOT.equals(value) ? PIVOT : FAST;
    }

    public static String[] requiredPacks(String mode) {
        if (PIVOT.equals(normalizeMode(mode))) return new String[] {"zh", "en", "th"};
        return new String[] {"zh", "th"};
    }

    public static boolean isOfflineSpeech(String value) {
        return SPEECH_OFFLINE.equals(value);
    }
}
