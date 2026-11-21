package th.hansub.live;

/** Translation modes: real ML Kit packs and optional server-side models. */
public final class ModelCatalog {
    public static final String FAST = "fast";
    public static final String PIVOT = "pivot";
    public static final String CLOUD_FAST = "cloud_fast";
    public static final String CLOUD_QUALITY = "cloud_quality";
    public static final String SPEECH_DEFAULT = "default";
    public static final String SPEECH_OFFLINE = "offline";

    private ModelCatalog() {}

    public static String normalizeMode(String value) {
        if (PIVOT.equals(value) || CLOUD_FAST.equals(value) || CLOUD_QUALITY.equals(value)) return value;
        return FAST;
    }

    public static boolean isCloud(String mode) {
        return CLOUD_FAST.equals(mode) || CLOUD_QUALITY.equals(mode);
    }

    public static String[] requiredPacks(String mode) {
        if (PIVOT.equals(normalizeMode(mode))) return new String[] {"zh", "en", "th"};
        // Cloud modes use on-device zh+th for partial caption and failure fallback.
        return new String[] {"zh", "th"};
    }

    public static boolean isOfflineSpeech(String value) {
        return SPEECH_OFFLINE.equals(value);
    }
}
