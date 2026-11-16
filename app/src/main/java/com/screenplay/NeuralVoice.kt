/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.URL

/** Who speaks an import. Chosen in the settings sheet; the neural ones are downloaded on first use. */
enum class Voice(val label: String, val archive: String?, val lanes: Int) {
    PHONE("Phone's own voice", null, 3),
    /** Kokoro (Apache-2.0): the most natural, and the slowest. 103 MB. */
    KOKORO("Kokoro: most natural, slowest (103 MB)", "kokoro-int8-en-v0_19", 2),
    /** Piper libritts_r medium (CC BY 4.0, voices from LibriTTS-R): plainer, much faster. 23 MB. */
    PIPER("Piper: faster, plainer (23 MB)", "vits-piper-en_US-libritts_r-medium-int8", 3);

    val neural get() = archive != null
}

/**
 * On-device neural voices run by sherpa-onnx (Apache-2.0). A model is too big for the APK, so [ensure] downloads it once
 * into app storage.
 */
object NeuralVoice {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    /** Voice number inside the model: Kokoro's af_bella; Piper's speaker 0 of the 904 in libritts_r. */
    private fun speaker(v: Voice) = if (v == Voice.KOKORO) 1 else 0

    private fun dir(ctx: Context, v: Voice) = File(ctx.filesDir, "voices/${v.archive}")

    /** The files a voice needs, as paths inside its folder. */
    private fun files(v: Voice) = when (v) {
        Voice.KOKORO -> listOf("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data")
        else -> listOf("en_US-libritts_r-medium.onnx", "tokens.txt", "espeak-ng-data")
    }

    fun installed(ctx: Context, v: Voice) = !v.neural || files(v).all { File(dir(ctx, v), it).exists() }

    /** One download at a time: the settings sheet and an import can both ask, and they share one unpacking folder. */
    private val download = Mutex()

    /** Downloads and unpacks the model unless it is already there. [onProgress] gets 0..1 of the download. */
    suspend fun ensure(ctx: Context, v: Voice, onProgress: (Float) -> Unit = {}) = download.withLock { withContext(Dispatchers.IO) {
        if (installed(ctx, v)) return@withContext
        val target = dir(ctx, v)
        val part = File(target.parentFile, "${v.archive}.part").apply { deleteRecursively(); mkdirs() }
        val conn = URL("$BASE/${v.archive}.tar.bz2").openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
        val total = conn.contentLengthLong.coerceAtLeast(1)
        val counted = object : java.io.FilterInputStream(conn.getInputStream().buffered()) {
            var bytes = 0L
            var reported = -1
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, len).also {
                if (it > 0) bytes += it
                val percent = (bytes * 100 / total).toInt()
                if (percent != reported) { reported = percent; onProgress(percent / 100f) }
            }
        }
        TarArchiveInputStream(BZip2CompressorInputStream(counted)).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val out = File(part, entry.name.substringAfter('/'))
                require(out.canonicalPath.startsWith(part.canonicalPath)) { "Unsafe path in the voice download" }
                if (entry.isDirectory) out.mkdirs() else { out.parentFile!!.mkdirs(); out.outputStream().use { tar.copyTo(it) } }
            }
        }
        target.deleteRecursively()
        check(part.renameTo(target)) { "Could not store the voice" }
    } }

    /**
     * Runs [block] at background priority, then restores the caller's. A model's worker threads start at the priority of
     * the thread that loads it, so loading and speaking here keep the import from starving the UI thread of cores.
     */
    internal fun <T> inBackground(block: () -> T): T {
        val tid = android.os.Process.myTid()
        val before = android.os.Process.getThreadPriority(tid)
        android.os.Process.setThreadPriority(tid, android.os.Process.THREAD_PRIORITY_BACKGROUND)
        try { return block() } finally { android.os.Process.setThreadPriority(tid, before) }
    }

    /** One loaded model, made on first use. Models run side by side, each on its own cores. */
    class Lane(private val ctx: Context, private val voice: Voice) : Synthesizer {
        private var tts: OfflineTts? = null

        private fun load(): OfflineTts {
            check(installed(ctx, voice)) { "The ${voice.name.lowercase()} voice is not downloaded" }
            val d = dir(ctx, voice)
            val threads = (Runtime.getRuntime().availableProcessors() / voice.lanes).coerceIn(2, 4)
            val model = if (voice == Voice.KOKORO) {
                OfflineTtsModelConfig(numThreads = threads, kokoro = OfflineTtsKokoroModelConfig(
                    model = File(d, "model.int8.onnx").path, voices = File(d, "voices.bin").path,
                    tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path,
                ))
            } else {
                OfflineTtsModelConfig(numThreads = threads, vits = OfflineTtsVitsModelConfig(
                    model = File(d, "en_US-libritts_r-medium.onnx").path,
                    tokens = File(d, "tokens.txt").path, dataDir = File(d, "espeak-ng-data").path,
                ))
            }
            return OfflineTts(assetManager = null, config = OfflineTtsConfig(model = model))
        }

        override suspend fun speak(text: String, wav: File) {
            val engine = tts ?: withContext(Dispatchers.IO) { inBackground { load() } }.also { tts = it }
            val audio = withContext(Dispatchers.Default) { inBackground { engine.generate(text, speaker(voice), 1.0f) } }
            if (audio.samples.isEmpty()) throw NoSpeechEngine("the natural voice made no sound for: ${text.take(60)}")
            val pcm = ByteArray(audio.samples.size * 2)
            for ((i, f) in audio.samples.withIndex()) {
                val v = (f * 32767f).toInt().coerceIn(-32768, 32767)
                pcm[2 * i] = v.toByte(); pcm[2 * i + 1] = (v shr 8).toByte()
            }
            Wav.write(wav, audio.sampleRate, pcm)
        }

        override fun close() { tts?.release(); tts = null }
    }
}
