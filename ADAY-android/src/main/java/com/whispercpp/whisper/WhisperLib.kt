package com.whispercpp.whisper

/**
 * JNI bridge to whisper.cpp (see `src/main/cpp/jni.c`). Loaded library name: `whisper`.
 */
@Suppress("unused", "KotlinJniMissingFunction")
class WhisperLib private constructor() {
    companion object {
        init {
            System.loadLibrary("whisper")
        }

        // No @JvmStatic: JNI names must match jni.c (…WhisperLib_$Companion_…), not statics on WhisperLib.
        external fun initContext(modelPath: String): Long

        external fun freeContext(contextPtr: Long)

        external fun fullTranscribe(contextPtr: Long, numThreads: Int, audioData: FloatArray)

        external fun getTextSegmentCount(contextPtr: Long): Int

        external fun getTextSegment(contextPtr: Long, index: Int): String

        external fun getSystemInfo(): String
    }
}
