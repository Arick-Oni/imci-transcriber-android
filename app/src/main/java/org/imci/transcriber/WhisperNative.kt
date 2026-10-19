package org.imci.transcriber

/** JNI bindings - see app/src/main/cpp/imci_jni.c */
object WhisperNative {
    init {
        System.loadLibrary("imci_jni")
    }

    /** Loads ggml backends (the CPU variant best suited to this phone) from the app's native lib dir. */
    external fun loadBackends(nativeLibDir: String)
    external fun systemInfo(): String

    external fun initModel(modelPath: String): Long
    external fun initModelFromAsset(assets: android.content.res.AssetManager, name: String): Long
    external fun freeModel(ctx: Long)

    external fun initVad(modelPath: String, nThreads: Int): Long
    external fun initVadFromAsset(assets: android.content.res.AssetManager, name: String, nThreads: Int): Long
    external fun freeVad(vctx: Long)

    /** Speech regions as [start0, end0, start1, end1, ...] in seconds. */
    external fun vadSegments(vctx: Long, samples: FloatArray, maxSpeechSec: Float): FloatArray

    /** Transcribes one chunk; returns raw UTF-8 bytes (may contain split characters). */
    external fun transcribe(ctx: Long, samples: FloatArray, nThreads: Int, audioCtx: Int): ByteArray

    /** Aborts the transcribe() call in progress. */
    external fun cancel()
}
