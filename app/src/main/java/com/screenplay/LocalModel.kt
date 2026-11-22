/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.screenplay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.security.MessageDigest

/**
 * The small language model that writes the chapter summaries, run on the device so no text leaves it. Qwen 2.5 1.5B Instruct
 * (Apache 2.0, so it needs no account to download), in the 8-bit LiteRT build with a 4096-token window. The download is pinned to
 * one revision and checked against its SHA-256 before it is stored.
 */
object LocalModel {
    private const val NAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task"
    private const val REVISION = "60257f9f1d00ca1f24cc5ce3b829b4139be1ca2b"
    private const val SIZE = 1_598_556_720L
    private const val SHA256 = "82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3"
    private const val URL_BASE = "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve"

    private val download = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val progressState = MutableStateFlow<Float?>(null)
    private val failureState = MutableStateFlow<String?>(null)

    /** How much of the download is done, 0..1, or null when none is running. */
    val progress: StateFlow<Float?> = progressState

    /** Why the last download failed, or null. */
    val failure: StateFlow<String?> = failureState

    fun file(ctx: Context) = File(ctx.filesDir, "models/$NAME")

    fun installed(ctx: Context) = file(ctx).length() == SIZE

    /** Starts the download for the settings, which keep it going after the panel closes. Does nothing if the model is there. */
    fun start(ctx: Context) {
        if (installed(ctx) || progressState.value != null) return
        failureState.value = null
        progressState.value = 0f
        scope.launch {
            try {
                Engines.prepareSummaryModel(ctx) { progressState.value = it }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                failureState.value = e.message ?: "failed"
            } finally {
                progressState.value = null
            }
        }
    }

    /** Downloads the model unless it is already there. [onProgress] gets 0..1. */
    suspend fun ensure(ctx: Context, onProgress: (Float) -> Unit = {}) = download.withLock { withContext(Dispatchers.IO) {
        if (installed(ctx)) return@withContext
        val target = file(ctx)
        val part = File(target.parentFile, "$NAME.part").apply { parentFile!!.mkdirs(); delete() }
        val digest = MessageDigest.getInstance("SHA-256")
        val conn = URL("$URL_BASE/$REVISION/$NAME").openConnection().apply { connectTimeout = 15_000; readTimeout = 30_000 }
        var bytes = 0L
        var reported = -1
        conn.getInputStream().use { input ->
            part.outputStream().use { out ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    bytes += n
                    val percent = (bytes * 100 / SIZE).toInt().coerceAtMost(100)
                    if (percent != reported) { reported = percent; onProgress(percent / 100f) }
                }
            }
        }
        val sum = digest.digest().joinToString("") { "%02x".format(it) }
        if (bytes != SIZE || sum != SHA256) { part.delete(); throw java.io.IOException("the download is not the expected model") }
        target.delete()
        check(part.renameTo(target)) { "Could not store the model" }
    } }
}
