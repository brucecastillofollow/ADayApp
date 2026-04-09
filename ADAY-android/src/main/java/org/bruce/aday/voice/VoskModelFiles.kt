package org.bruce.aday.voice

import android.content.Context
import java.io.File

/** On-disk layout for the English Vosk lgraph model (~128 MB zip → unpacked). */
object VoskModelFiles {
    const val MODEL_ZIP_NAME = "vosk-model-en-us-0.22-lgraph.zip"
    const val UNPACKED_DIR_NAME = "vosk-model-en-us-0.22-lgraph"

    const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_ZIP_NAME"

    /** Reject obviously incomplete unpacks (folder is tens of MB). */
    const val MIN_UNPACKED_DIR_BYTES = 40_000_000L

    fun workDir(context: Context): File = File(context.filesDir, "vosk")

    fun zipFile(context: Context): File = File(workDir(context), MODEL_ZIP_NAME)

    fun unpackedModelDir(context: Context): File = File(workDir(context), UNPACKED_DIR_NAME)
}
