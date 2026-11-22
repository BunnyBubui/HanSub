package th.hansub.live;

/** Native side of the local translator: llama.cpp behind a small JNI bridge (app/src/main/cpp/llm_jni.cpp). */
final class LlmNative {
    private static boolean loaded, failed;

    private LlmNative() {}

    /**
     * True when the native library is in this APK and loads. It is built for 64-bit ARM only, so older
     * 32-bit phones get false and keep using ML Kit.
     */
    static synchronized boolean available() {
        if (loaded) return true;
        if (failed) return false;
        try {
            System.loadLibrary("hansub_llm");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            failed = true;
        }
        return loaded;
    }

    /** Loads the CPU code variants from the app's native library folder. Call once. */
    static native boolean init(String nativeLibDir);

    /** Returns a handle, or 0 when the model cannot be loaded (see {@link #lastError()}). */
    static native long load(String modelPath, int threads, int contextSize);

    /** Runs one request. Returns UTF-8 bytes of the answer, or null on failure. Not safe to call twice at once. */
    static native byte[] generate(long handle, String prompt, int maxNewTokens);

    /** Stops a running {@link #generate}. May be called from any thread. */
    static native void cancel(long handle);

    static native void free(long handle);

    static native String info();

    static native String lastError();
}
