/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class NoSpeechEngine(why: String) : Exception("No text-to-speech engine available: $why")

/** The engine hung on this one sentence, even after a restart. */
class SentenceStalled(text: String) : Exception("the engine stopped responding on: ${text.take(60)}")

/** Turns one sentence into a WAV file. */
interface Synthesizer {
    suspend fun speak(text: String, wav: File)
    fun close()
}

/** Where tests swap in a synthesizer: the emulator image has no speech engine. */
object Engines {
    var synthesizer: (Context) -> Synthesizer = { AndroidSynthesizer(it) }

    /** Runs on the saving thread before a moved book's order is written to disk; a test makes saving slow, or fails it, to see the shelf in the meantime. */
    var beforeMoveWrite: () -> Unit = {}

    /** Runs on the importing thread before a picked file is copied into the app; a test holds it to see the file's row while it is being saved. */
    var beforeCopy: () -> Unit = {}

    /** Where tests swap in the chapter summariser, so no call goes to the Claude API. */
    var summariser: (Context) -> Summariser = { LocalSummariser(it) }

    /** Where tests swap in the download of the summary model, so no 1.6 GB file is fetched. Does nothing if the model is there. */
    var prepareSummaryModel: suspend (Context, (Float) -> Unit) -> Unit = { c, progress -> LocalModel.ensure(c, progress) }

    /**
     * The speech lanes for the voice the user chose, its model downloaded first if it is missing (the settings sheet downloads it when it is picked;
     * this covers one removed since, and the default voice, which nobody picked). Without a network the phone's voice speaks rather than nothing.
     */
    suspend fun lanes(ctx: Context, wanted: Voice): List<Synthesizer> {
        val voice = if (!wanted.neural) wanted else try {
            prepareNeural(ctx, wanted) {}
            wanted
        } catch (e: java.io.IOException) {
            android.util.Log.w("Engines", "The ${wanted.name.lowercase()} voice could not be downloaded; the phone's voice speaks instead", e)
            Voice.PHONE
        }
        return List(voice.lanes) { if (voice.neural) neural(ctx, voice) else synthesizer(ctx) }
    }

    /** The opt-in neural voice; a test swaps it as it does [synthesizer]. */
    var neural: (Context, Voice) -> Synthesizer = { ctx, voice -> NeuralVoice.Lane(ctx, voice) }

    /** Makes the neural voice ready (downloads its model); a test swaps in one that downloads nothing. */
    var prepareNeural: suspend (Context, Voice, (Float) -> Unit) -> Unit = { ctx, voice, progress -> NeuralVoice.ensure(ctx, voice, progress) }

    /** Runs before a page is recognised; a test holds a page here to see what happens while it is unfinished. */
    var beforeRecognise: suspend (page: Int, total: Int) -> Unit = { _, _ -> }

    /**
     * Stands in for OCR when set: a page's paragraphs by page number. OCR costs about ten seconds a page on the emulator and does
     * not depend on the screen, so a test that is not about OCR sets this.
     */
    var recognisePage: ((page: Int) -> List<String>)? = null

    /**
     * Stands in for the chapter pre-pass's OCR ([PageSurvey.read], which [recognisePage] does not replace) when set: a test that is not about chapters
     * returns no pages, so the book has no estimated chapters and the import skips about ten seconds a page.
     */
    var surveyPages: (suspend (pdf: File) -> List<OcrPage>)? = null

    /** Looks up a book's title and author by ISBN; a test swaps in one that needs no network. Null when the catalogue has no such book. */
    var lookupBook: suspend (isbn: String) -> BookInfo? = { isbn -> withContext(Dispatchers.IO) { Isbn.openLibrary(isbn) } }

    /**
     * The one place the "Catalogue" screen touches the network: a GET of [url], the body copied to the stream. A test swaps in one that serves canned answers.
     * It calls `onProgress` with the bytes received so far and the bytes the server announced (0 or less when it did not) as the body arrives.
     */
    var httpGet: suspend (url: String, userAgent: String, out: java.io.OutputStream, onProgress: (received: Long, expected: Long) -> Unit) -> Unit = Gutenberg::fetch

    /** Whether the site at [url] answers: the one other network call "Catalogue" makes, a HEAD request per book site each time the screen opens. A test swaps in one that answers as the test says. */
    var httpProbe: suspend (url: String, userAgent: String) -> Boolean = Gutenberg::headSucceeds
}

class AndroidSynthesizer(private val context: Context) : Synthesizer {
    private companion object {
        const val SENTENCE_TIMEOUT_MS = 20_000L
    }

    private var tts: TextToSpeech? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun engine(): TextToSpeech {
        tts?.let { return it }
        val ready = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            val t = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) ready.complete(Unit)
                else ready.completeExceptionally(NoSpeechEngine("init status $status"))
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { pending.remove(id)?.complete(Unit) }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { pending.remove(id)?.completeExceptionally(NoSpeechEngine("utterance failed")) }
                override fun onError(id: String, code: Int) { pending.remove(id)?.completeExceptionally(NoSpeechEngine("utterance error $code")) }
            })
            tts = t
        }
        ready.await()
        return tts!!
    }

    override suspend fun speak(text: String, wav: File) {
        // The engine can stop answering without an error, so a sentence gets a deadline, then one retry on a fresh engine.
        repeat(2) {
            try {
                withTimeout(SENTENCE_TIMEOUT_MS) { synthesize(text, wav) }
                return
            } catch (e: TimeoutCancellationException) {
                close()
                tts = null
                pending.clear()
            }
        }
        throw SentenceStalled(text)
    }

    private suspend fun synthesize(text: String, wav: File) {
        val engine = engine()
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>().also { pending[id] = it }
        wav.delete()
        if (engine.synthesizeToFile(text, Bundle(), wav, id) != TextToSpeech.SUCCESS) {
            pending.remove(id)
            throw NoSpeechEngine("synthesizeToFile refused the text")
        }
        done.await()
    }

    override fun close() {
        tts?.shutdown()
    }
}

object Speech {
    const val GAP_MS = 250
    /** Engines run side by side; each speaks its own sentences while the writer copies finished ones in order. */
    const val LANES = 3
    /** Sentences between flushes of the growing audio, so a reader can play what exists so far. */
    const val CHUNK = 25
    private const val STALLED_MS = 1000
    /** How long the writer waits with unreported audio before it reports it. */
    const val FLUSH_MS = 1000L

    private fun ms(bytes: Long, rate: Int) = bytes * 500 / rate

    /** The speech a stopped book already made: [segments], whole in the first [bytes] of its audio, at [rate]. */
    class Kept(val segments: List<Segment>, val rate: Int, val bytes: Long) {
        companion object { val NONE = Kept(emptyList(), 0, 0) }
    }

    /**
     * The part of a stopped book's speech to carry on from: the [segments] whose audio is whole in the [pcmBytes] of audio on disk
     * (the audio can run past the transcript, which is saved after it, but not fall short). Nothing when there is no usable audio.
     */
    fun kept(segments: List<Segment>, rate: Int, pcmBytes: Long): Kept {
        if (rate <= 0) return Kept.NONE
        // The fewest whole samples that last to the segment's end, so the next sentence starts at that very millisecond.
        fun bytesTo(end: Long) = ((end * rate + 499) / 500 + 1) and 1L.inv()
        val whole = segments.takeWhile { bytesTo(it.end) <= pcmBytes }
        if (whole.isEmpty()) return Kept.NONE
        return Kept(whole, rate, bytesTo(whole.last().end))
    }

    /** True when the audio of [segments] is in the order of the book's text, as it is unless the reader jumped. */
    fun inBookOrder(segments: List<Segment>) = segments.withIndex().all { (i, s) -> s.at < 0 || s.at == i }

    /**
     * Copies the audio of [segments] (which follow the audio in [pcm]) into [out] in the order of the book's text, and returns the
     * segments as they sit in [out], in that order and with their [Segment.at] cleared. Every sentence must have audio.
     */
    fun inBookOrder(segments: List<Segment>, rate: Int, pcm: File, out: File): List<Segment> {
        fun bytesTo(end: Long) = ((end * rate + 499) / 500 + 1) and 1L.inv()
        val ranges = LongArray(segments.size + 1) { if (it == 0) 0L else bytesTo(segments[it - 1].end) }
        val order = segments.indices.sortedBy { if (segments[it].at >= 0) segments[it].at else it }
        val result = ArrayList<Segment>(segments.size)
        var bytes = 0L
        java.io.RandomAccessFile(pcm, "r").use { input ->
            FileOutputStream(out).buffered().use { output ->
                val buf = ByteArray(64 * 1024)
                for (i in order) {
                    input.seek(ranges[i])
                    var left = ranges[i + 1] - ranges[i]
                    val start = bytes
                    while (left > 0) {
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        output.write(buf, 0, n)
                        left -= n
                        bytes += n
                    }
                    result += Segment(ms(start, rate), ms(bytes, rate), segments[i].text)
                }
            }
        }
        return result
    }

    /** Where the audio at [ms] of the old [from] segments sits in the same audio laid out as [to] (the same sentences, in the order of the text). */
    fun moved(ms: Long, from: List<Segment>, to: List<Segment>): Long {
        val i = Timeline.indexAt(from, ms)
        val s = from.getOrNull(i) ?: return ms
        val order = from.indices.sortedBy { if (from[it].at >= 0) from[it].at else it }
        val place = order.indexOf(i)
        return (to.getOrNull(place)?.start ?: return ms) + (ms - s.start).coerceAtLeast(0)
    }

    /** One spoken sentence held in memory; [pcm] is null when the engine stalled on it. */
    private class Spoken(val rate: Int, val pcm: ByteArray?)

    /**
     * Speaks every sentence into [pcm] and returns where each one sits, plus the sample rate.
     * Sentences are spoken on all [synths] at once but written in order. [onChunk] gets the segments so far, after the audio
     * for them has been flushed to disk.
     */
    suspend fun render(
        synths: List<Synthesizer>,
        sentences: List<String>,
        scratch: File,
        pcm: File,
        onChunk: (segments: List<Segment>, rate: Int) -> Unit = { _, _ -> },
        onSentence: (done: Int) -> Unit,
    ): Pair<List<Segment>, Int> {
        val queue = Channel<String>(Channel.UNLIMITED)
        sentences.forEach { queue.trySend(it) }
        queue.close()
        return render(synths, queue, scratch, pcm, onChunk, onSentence = onSentence)
    }

    /**
     * As above, for sentences that arrive while it runs: it speaks each as it comes and ends when [sentences] is closed.
     * Spoken audio is flushed every [CHUNK] sentences, and also when the writer has had nothing new for [flushMs],
     * so a reader can play what exists while the next page is still being read. With [resume], the sentences it covers are
     * already in [pcm] (cut to [Kept.bytes]): new ones are added after them and the segments returned include them.
     */
    suspend fun render(
        synths: List<Synthesizer>,
        sentences: ReceiveChannel<String>,
        scratch: File,
        pcm: File,
        onChunk: (segments: List<Segment>, rate: Int) -> Unit = { _, _ -> },
        flushMs: Long = FLUSH_MS,
        resume: Kept = Kept.NONE,
        onSentence: (done: Int) -> Unit,
    ): Pair<List<Segment>, Int> {
        var next = resume.segments.size
        val source: suspend () -> Pair<Int, String>? = {
            val got = sentences.receiveCatching()
            got.exceptionOrNull()?.let { throw it }
            got.getOrNull()?.let { next++ to it }
        }
        return render(synths, source, scratch, pcm, onChunk, flushMs, resume) { _, done -> onSentence(done) }
    }

    /**
     * As above, for sentences that [source] hands out in the order to speak them, each with its place in the book's text ([SpeakOrder.take]);
     * null ends the run. The audio is written in that order, so after a jump it is not in text order: each segment keeps its place in
     * [Segment.at]. [onSentence] gets that place and the number of sentences spoken so far.
     */
    suspend fun render(
        synths: List<Synthesizer>,
        source: suspend () -> Pair<Int, String>?,
        scratch: File,
        pcm: File,
        onChunk: (segments: List<Segment>, rate: Int) -> Unit = { _, _ -> },
        flushMs: Long = FLUSH_MS,
        resume: Kept = Kept.NONE,
        onSentence: (at: Int, done: Int) -> Unit,
    ): Pair<List<Segment>, Int> = coroutineScope {
        // Sentences are queued in the order they are taken; the writer drops each once written: a book's audio is larger than the heap.
        val order = Channel<Triple<Int, String, CompletableDeferred<Spoken>>>(Channel.UNLIMITED)
        // A bounded lead keeps finished sentences from piling up in memory while the writer waits on a slow one.
        val lead = Semaphore(synths.size * 3)
        val take = Mutex()
        val lanes = synths.mapIndexed { lane, synth ->
            launch {
                val file = File(scratch.parentFile, "${scratch.name}.$lane")
                while (true) {
                    lead.acquire()
                    val job = take.withLock {
                        source()?.let { (at, text) -> Triple(at, text, CompletableDeferred<Spoken>().also { d -> order.trySend(Triple(at, text, d)) }) }
                    }
                    if (job == null) { lead.release(); break }
                    val (_, text, result) = job
                    try {
                        // A sentence the engine cannot speak becomes a pause, so one bad line does not lose the book.
                        val spoken = try {
                            synth.speak(SpokenText.of(text), file)
                            withContext(Dispatchers.IO) { Wav.read(file).let { Spoken(it.rate, Wav.data(file, it)) } }
                        } catch (e: SentenceStalled) { Spoken(0, null) }
                        result.complete(spoken)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        result.completeExceptionally(e)
                    }
                }
                file.delete()
            }
        }
        launch { lanes.joinAll(); order.close() }

        var rate = resume.rate
        var bytes = resume.bytes
        val segments = ArrayList(resume.segments)
        var flushed = segments.size
        val writing = Mutex()
        FileOutputStream(pcm, resume.segments.isNotEmpty()).buffered().use { out ->
            // Under [writing]: flushes the audio, then reports the segments it holds.
            fun flush() {
                if (segments.size == flushed) return
                out.flush()
                flushed = segments.size
                onChunk(segments, rate)
            }
            val idle = launch {
                while (true) { delay(flushMs); writing.withLock { flush() } }
            }
            var done = segments.size
            for ((at, text, result) in order) {
                val r = result.await()
                lead.release()
                writing.withLock {
                    if (r.pcm != null && rate == 0) rate = r.rate
                    if (rate == 0) throw SentenceStalled(text)
                    check(r.pcm == null || rate == r.rate) { "Speech engine changed sample rate mid-book" }
                    val data = r.pcm ?: ByteArray((rate * 2L * STALLED_MS / 1000).toInt() and 1.inv())
                    val start = ms(bytes, rate)
                    out.write(data)
                    bytes += data.size
                    val gap = (rate * 2L * GAP_MS / 1000).toInt() and 1.inv()
                    out.write(ByteArray(gap))
                    bytes += gap
                    segments += Segment(start, ms(bytes, rate), text, at)
                    if (segments.size % CHUNK == 0) flush()
                }
                onSentence(at, ++done)
            }
            idle.cancelAndJoin()
        }
        segments to rate
    }
}

/** Raw 16-bit mono PCM to an AAC .m4a, which is about a tenth of the size of WAV. */
object AacEncoder {
    private const val CHUNK = 64 * 1024

    fun encode(pcm: File, rate: Int, out: File, onProgress: (Float) -> Unit = {}) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(CHUNK)
        val total = pcm.length().coerceAtLeast(1)
        var reported = 0
        var bytes = 0L
        var inputDone = false
        var outputDone = false
        FileInputStream(pcm).use { input ->
            while (!outputDone) {
                // Fill every free input buffer before draining: each round trip to the hardware codec costs more than the encoding.
                while (!inputDone) {
                    val i = codec.dequeueInputBuffer(0)
                    if (i < 0) break
                    val b = codec.getInputBuffer(i)!!
                    b.clear()
                    val n = input.read(buf, 0, minOf(buf.size, b.capacity()))
                    val pts = bytes / 2 * 1_000_000 / rate
                    if (n < 0) {
                        codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        b.put(buf, 0, n)
                        codec.queueInputBuffer(i, 0, n, pts, 0)
                        bytes += n
                    }
                }
                val percent = (bytes * 100 / total).toInt()
                if (percent > reported) {
                    reported = percent
                    onProgress(percent / 100f)
                }
                // One input buffer yields many AAC frames; drain them all, or each pass waits out the timeout for one frame.
                var o = codec.dequeueOutputBuffer(info, 5_000)
                while (o != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                    } else if (o >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(o)!!, info)
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                            break
                        }
                    }
                    o = codec.dequeueOutputBuffer(info, 0)
                }
            }
        }
        codec.stop()
        codec.release()
        muxer.stop()
        muxer.release()
    }
}
