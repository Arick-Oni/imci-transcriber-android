// JNI bridge for the IMCI transcriber.
//
// Mirrors the settings validated on the Poco F5 (mobile/phone_test.sh):
//   - greedy decoding, no temperature fallback (-bs 1 -nf)
//   - no timestamp tokens (the regional fine-tune was trained without them)
//   - encoder window (audio_ctx) sized to each chunk, chosen on the Kotlin side
// Text is returned as raw UTF-8 bytes: Whisper can emit a multi-byte character
// split across tokens, and NewStringUTF() aborts the app on invalid UTF-8.

#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>

#include "whisper.h"
#include "ggml-backend.h"

#define TAG "imci_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static atomic_bool g_cancel = false;

static bool abort_cb(void * user_data) {
    (void) user_data;
    return atomic_load(&g_cancel);
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_WhisperNative_loadBackends(JNIEnv * env, jobject thiz, jstring dir) {
    (void) thiz;
    const char * path = (*env)->GetStringUTFChars(env, dir, NULL);
    ggml_backend_load_all_from_path(path);
    LOGI("backends loaded from %s, devices: %zu", path, ggml_backend_dev_count());
    (*env)->ReleaseStringUTFChars(env, dir, path);
}

JNIEXPORT jstring JNICALL
Java_org_imci_transcriber_WhisperNative_systemInfo(JNIEnv * env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, whisper_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_org_imci_transcriber_WhisperNative_initModel(JNIEnv * env, jobject thiz, jstring model_path) {
    (void) thiz;
    const char * path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context * ctx = whisper_init_from_file_with_params(path, cparams);
    if (!ctx) LOGW("failed to load model %s", path);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) ctx;
}

// --- Loading straight from the APK (assets are stored uncompressed, see build.gradle) ---

static size_t asset_read(void * ctx, void * output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_eof(void * ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void * ctx) {
    AAsset_close((AAsset *) ctx);
}

static AAsset * open_asset(JNIEnv * env, jobject asset_manager, jstring name, struct whisper_model_loader * loader) {
    const char * path = (*env)->GetStringUTFChars(env, name, NULL);
    AAsset * asset = AAssetManager_open(AAssetManager_fromJava(env, asset_manager), path, AASSET_MODE_STREAMING);
    if (!asset) LOGW("asset not found: %s", path);
    (*env)->ReleaseStringUTFChars(env, name, path);
    loader->context = asset;
    loader->read    = asset_read;
    loader->eof     = asset_eof;
    loader->close   = asset_close;
    return asset;
}

JNIEXPORT jlong JNICALL
Java_org_imci_transcriber_WhisperNative_initModelFromAsset(JNIEnv * env, jobject thiz, jobject asset_manager, jstring name) {
    (void) thiz;
    struct whisper_model_loader loader = {0};
    if (!open_asset(env, asset_manager, name, &loader)) return 0;
    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    return (jlong) whisper_init_with_params(&loader, cparams);   // the loader closes the asset
}

JNIEXPORT jlong JNICALL
Java_org_imci_transcriber_WhisperNative_initVadFromAsset(JNIEnv * env, jobject thiz, jobject asset_manager, jstring name, jint n_threads) {
    (void) thiz;
    struct whisper_model_loader loader = {0};
    if (!open_asset(env, asset_manager, name, &loader)) return 0;
    struct whisper_vad_context_params vparams = whisper_vad_default_context_params();
    vparams.n_threads = n_threads;
    vparams.use_gpu = false;
    struct whisper_vad_context * vctx = whisper_vad_init_with_params(&loader, vparams);
    asset_close(loader.context);  // unlike whisper_init, the VAD loader does not close it
    return (jlong) vctx;
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_WhisperNative_freeModel(JNIEnv * env, jobject thiz, jlong ctx) {
    (void) env; (void) thiz;
    if (ctx) whisper_free((struct whisper_context *) ctx);
}

JNIEXPORT jlong JNICALL
Java_org_imci_transcriber_WhisperNative_initVad(JNIEnv * env, jobject thiz, jstring model_path, jint n_threads) {
    (void) thiz;
    const char * path = (*env)->GetStringUTFChars(env, model_path, NULL);
    struct whisper_vad_context_params vparams = whisper_vad_default_context_params();
    vparams.n_threads = n_threads;
    vparams.use_gpu = false;
    struct whisper_vad_context * vctx = whisper_vad_init_from_file_with_params(path, vparams);
    if (!vctx) LOGW("failed to load VAD model %s", path);
    (*env)->ReleaseStringUTFChars(env, model_path, path);
    return (jlong) vctx;
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_WhisperNative_freeVad(JNIEnv * env, jobject thiz, jlong vctx) {
    (void) env; (void) thiz;
    if (vctx) whisper_vad_free((struct whisper_vad_context *) vctx);
}

// Returns speech regions as [start0, end0, start1, end1, ...] in seconds.
JNIEXPORT jfloatArray JNICALL
Java_org_imci_transcriber_WhisperNative_vadSegments(JNIEnv * env, jobject thiz, jlong vctx,
                                                     jfloatArray samples, jfloat max_speech_s) {
    (void) thiz;
    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat * data = (*env)->GetFloatArrayElements(env, samples, NULL);

    struct whisper_vad_params params = whisper_vad_default_params();
    params.max_speech_duration_s = max_speech_s;
    params.min_silence_duration_ms = 400;   // same as the PC pipeline's VAD
    params.speech_pad_ms = 200;

    struct whisper_vad_segments * segs =
        whisper_vad_segments_from_samples((struct whisper_vad_context *) vctx, params, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    if (!segs) return (*env)->NewFloatArray(env, 0);

    int count = whisper_vad_segments_n_segments(segs);
    jfloatArray out = (*env)->NewFloatArray(env, count * 2);
    float * buf = malloc(sizeof(float) * (count * 2 + 1));
    for (int i = 0; i < count; i++) {
        buf[2 * i]     = whisper_vad_segments_get_segment_t0(segs, i) / 100.0f;  // centiseconds -> s
        buf[2 * i + 1] = whisper_vad_segments_get_segment_t1(segs, i) / 100.0f;
    }
    (*env)->SetFloatArrayRegion(env, out, 0, count * 2, buf);
    free(buf);
    whisper_vad_free_segments(segs);
    return out;
}

JNIEXPORT void JNICALL
Java_org_imci_transcriber_WhisperNative_cancel(JNIEnv * env, jobject thiz) {
    (void) env; (void) thiz;
    atomic_store(&g_cancel, true);
}

// Transcribes one chunk; returns UTF-8 bytes (possibly invalid - decode leniently).
JNIEXPORT jbyteArray JNICALL
Java_org_imci_transcriber_WhisperNative_transcribe(JNIEnv * env, jobject thiz, jlong ctx,
                                                    jfloatArray samples, jint n_threads, jint audio_ctx) {
    (void) thiz;
    atomic_store(&g_cancel, false);

    struct whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads        = n_threads;
    p.language         = "bn";
    p.translate        = false;
    p.no_context       = true;
    p.no_timestamps    = true;
    p.single_segment   = false;
    p.print_realtime   = false;
    p.print_progress   = false;
    p.print_timestamps = false;
    p.print_special    = false;
    p.temperature      = 0.0f;
    p.temperature_inc  = 0.0f;      // no fallback: loops end fast, cleanup collapses them
    p.audio_ctx        = audio_ctx; // encoder window sized to the chunk
    p.abort_callback   = abort_cb;

    jsize n = (*env)->GetArrayLength(env, samples);
    jfloat * data = (*env)->GetFloatArrayElements(env, samples, NULL);
    int rc = whisper_full((struct whisper_context *) ctx, p, data, n);
    (*env)->ReleaseFloatArrayElements(env, samples, data, JNI_ABORT);
    if (rc != 0) {
        LOGW("whisper_full failed or was cancelled: %d", rc);
        return (*env)->NewByteArray(env, 0);
    }

    struct whisper_context * c = (struct whisper_context *) ctx;
    int n_seg = whisper_full_n_segments(c);
    size_t total = 0;
    for (int i = 0; i < n_seg; i++) total += strlen(whisper_full_get_segment_text(c, i)) + 1;
    char * buf = malloc(total + 1);
    size_t pos = 0;
    for (int i = 0; i < n_seg; i++) {
        const char * t = whisper_full_get_segment_text(c, i);
        size_t len = strlen(t);
        memcpy(buf + pos, t, len);
        pos += len;
        buf[pos++] = ' ';
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize) pos);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) pos, (const jbyte *) buf);
    free(buf);
    return out;
}
