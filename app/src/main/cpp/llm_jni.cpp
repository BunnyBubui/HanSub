// JNI bridge from th.hansub.live.LlmNative to llama.cpp.
// Only llama.h is used (no "common" library), so the native build stays small.
#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <string>
#include <vector>

#include "llama.h"
#include "ggml-backend.h"

#pragma clang diagnostic ignored "-Wdeprecated-declarations"

#define TAG "HanSubLlm"

namespace {

struct Llm {
    llama_model   *model = nullptr;
    llama_context *ctx   = nullptr;
    std::atomic<bool> cancel{false};
};

std::string g_last_error;
bool g_backend_ready = false;

void log_callback(enum ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", text);
        std::string line(text);
        while (!line.empty() && (line.back() == '\n' || line.back() == '\r')) line.pop_back();
        if (!line.empty()) g_last_error = line;
    } else if (level == GGML_LOG_LEVEL_WARN) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "%s", text);
    }
}

bool abort_callback(void *data) {
    return static_cast<Llm *>(data)->cancel.load();
}

std::string to_std(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// The model carries its own chat template. If llama.cpp cannot apply it, fall back to the Hunyuan layout,
// which is what Hy-MT uses: user turn, then the assistant marker.
std::string build_prompt(llama_model *model, const std::string &user) {
    const char *tmpl = llama_model_chat_template(model, nullptr);
    if (tmpl) {
        llama_chat_message message{"user", user.c_str()};
        std::vector<char> buf(user.size() * 2 + 1024);
        int n = llama_chat_apply_template(tmpl, &message, 1, true, buf.data(), (int32_t) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n);
            n = llama_chat_apply_template(tmpl, &message, 1, true, buf.data(), (int32_t) buf.size());
        }
        if (n > 0) return std::string(buf.data(), n);
    }
    return "<\xEF\xBD\x9Chy_User\xEF\xBD\x9C>" + user + "<\xEF\xBD\x9Chy_Assistant\xEF\xBD\x9C>";
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_th_hansub_live_LlmNative_init(JNIEnv *env, jclass, jstring libDir) {
    if (g_backend_ready) return JNI_TRUE;
    llama_log_set(log_callback, nullptr);
    std::string dir = to_std(env, libDir);
    // The CPU code comes as several variants (plain, dot-product, i8mm...). This picks the best one for the phone.
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    g_backend_ready = true;
    return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_th_hansub_live_LlmNative_load(JNIEnv *env, jclass, jstring path, jint threads, jint nCtx) {
    g_last_error.clear();
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(to_std(env, path).c_str(), mp);
    if (!model) return 0;

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) nCtx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        llama_model_free(model);
        return 0;
    }
    auto *llm = new Llm();
    llm->model = model;
    llm->ctx = ctx;
    llama_set_abort_callback(ctx, abort_callback, llm);
    return (jlong) (intptr_t) llm;
}

// Returns the generated text as UTF-8 bytes (a token can end in the middle of a character, so the
// bytes are joined first and decoded in Java). Returns null on failure.
JNIEXPORT jbyteArray JNICALL
Java_th_hansub_live_LlmNative_generate(JNIEnv *env, jclass, jlong handle, jstring prompt, jint maxNew) {
    auto *llm = (Llm *) (intptr_t) handle;
    if (!llm) return nullptr;
    llm->cancel = false;
    const llama_vocab *vocab = llama_model_get_vocab(llm->model);

    std::string text = build_prompt(llm->model, to_std(env, prompt));
    std::vector<llama_token> tokens(text.size() + 16);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(),
                           (int32_t) tokens.size(), true, true);
    if (n < 0) {
        tokens.resize(-n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(),
                           (int32_t) tokens.size(), true, true);
    }
    if (n <= 0) return nullptr;
    tokens.resize(n);
    if ((uint32_t) (n + maxNew + 1) > llama_n_ctx(llm->ctx)) {
        g_last_error = "prompt too long";
        return nullptr;
    }

    // Each sentence is independent, so start from an empty memory.
    llama_memory_clear(llama_get_memory(llm->ctx), true);

    // Sampling follows the model card: top-k 20, top-p 0.6, temperature 0.7, repetition penalty 1.05.
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::string out;
    bool ok = true;

    // Read the prompt in slices no bigger than the batch size.
    const int slice = 512;
    for (int i = 0; i < n && ok; i += slice) {
        int count = std::min(slice, n - i);
        if (llama_decode(llm->ctx, llama_batch_get_one(tokens.data() + i, count)) != 0) ok = false;
    }

    char piece[256];
    for (int step = 0; ok && step < maxNew; step++) {
        if (llm->cancel) break;
        llama_token next = llama_sampler_sample(smpl, llm->ctx, -1);
        if (llama_vocab_is_eog(vocab, next)) break;
        int len = llama_token_to_piece(vocab, next, piece, sizeof(piece), 0, false);
        if (len > 0) out.append(piece, len);
        if (llama_decode(llm->ctx, llama_batch_get_one(&next, 1)) != 0) ok = false;
    }
    llama_sampler_free(smpl);

    if (!ok && out.empty()) return nullptr;
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    if (bytes) env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), (const jbyte *) out.data());
    return bytes;
}

JNIEXPORT void JNICALL
Java_th_hansub_live_LlmNative_cancel(JNIEnv *, jclass, jlong handle) {
    auto *llm = (Llm *) (intptr_t) handle;
    if (llm) llm->cancel = true;
}

JNIEXPORT void JNICALL
Java_th_hansub_live_LlmNative_free(JNIEnv *, jclass, jlong handle) {
    auto *llm = (Llm *) (intptr_t) handle;
    if (!llm) return;
    if (llm->ctx) llama_free(llm->ctx);
    if (llm->model) llama_model_free(llm->model);
    delete llm;
}

JNIEXPORT jstring JNICALL
Java_th_hansub_live_LlmNative_info(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL
Java_th_hansub_live_LlmNative_lastError(JNIEnv *env, jclass) {
    return env->NewStringUTF(g_last_error.c_str());
}

}  // extern "C"
