/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The zip cannot be restored; the message is shown to the user as it is. */
class BackupException(message: String) : Exception(message)

/** One zip of the PDF, audio, transcript, notes, highlights and details, handed to whatever app the user picks in the share sheet. */
object Backup {
    private val entries = listOf(Files.PDF, Files.EPUB, Files.AUDIO, Files.TRANSCRIPT, Files.NOTES, Files.PARAGRAPH_NOTES, Files.HIGHLIGHTS, Files.META)
    private val required = listOf(Files.AUDIO, Files.TRANSCRIPT)

    fun zip(ctx: Context, id: String, title: String): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", zipFile(ctx, id, File(ctx.cacheDir, "backup/${fileSafe(title)}.zip")))

    private fun fileSafe(title: String) = title.replace(Regex("[^\\w .-]"), "_")

    private fun zipFile(ctx: Context, id: String, out: File): File {
        val dir = Library.dir(ctx, id)
        out.parentFile!!.mkdirs()
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            for (name in entries) {
                val f = File(dir, name)
                if (!f.exists()) continue
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return out
    }

    /**
     * One zip holding the single-book backup of every finished book, so [restore] brings the whole library back. A book
     * still being made has no audio to back up and is left out.
     */
    fun zipLibrary(ctx: Context): Uri {
        val out = File(ctx.cacheDir, "backup/Screenplay library.zip")
        out.parentFile!!.mkdirs()
        val scratch = File(ctx.cacheDir, "backup/library-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            ZipOutputStream(out.outputStream().buffered()).use { library ->
                for ((position, book) in Library.books.value.filter { it.status == Status.READY }.withIndex()) {
                    // The position keeps two books with one title apart.
                    val bookZip = zipFile(ctx, book.id, File(scratch, "${position + 1} ${fileSafe(book.title)}.zip"))
                    library.putNextEntry(ZipEntry(bookZip.name))
                    bookZip.inputStream().use { it.copyTo(library) }
                    library.closeEntry()
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", out)
    }

    fun share(ctx: Context, uri: Uri) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Back up audiobooks").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Writes the known entries of a backup into [dest] and returns their names. Other entries are ignored; a name that
     * could leave [dest] (`../x`, `/x`, `a\\b`) rejects the whole zip, as does a missing PDF, audio or transcript.
     */
    fun extract(input: InputStream, dest: File): Set<String> {
        val found = mutableSetOf<String>()
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (e.name.startsWith("/") || e.name.split('/', '\\').contains("..")) throw BackupException("Not a valid backup: unsafe file name \"${e.name}\".")
                if (e.name !in entries) continue
                File(dest, e.name).outputStream().use { z.copyTo(it) }
                found += e.name
            }
        }
        // The book's source is its PDF, or its EPUB when it was imported as one.
        val missing = (required - found) + listOfNotNull(Files.PDF.takeIf { Files.PDF !in found && Files.EPUB !in found })
        if (missing.isNotEmpty()) throw BackupException("Not an audiobook backup: missing ${missing.joinToString()}.")
        return found
    }

    /**
     * Restores the zip at [uri] as new books, never touching an existing one, and returns their titles. The zip is one
     * book's backup, or a library backup ([zipLibrary]) holding one backup per book.
     */
    fun restore(ctx: Context, uri: Uri): List<String> {
        val shown = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "Untitled"
        val isLibrary = ctx.contentResolver.openInputStream(uri)?.use { ZipInputStream(it).nextEntry?.name?.endsWith(".zip") == true } ?: throw BackupException("Could not read the file.")
        if (!isLibrary) return listOf(ctx.contentResolver.openInputStream(uri)?.use { restoreBook(ctx, it, shown) } ?: throw BackupException("Could not read the file."))
        val titles = mutableListOf<String>()
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(input).use { library ->
                while (true) {
                    val entry = library.nextEntry ?: break
                    // extract closes the stream it reads, which would end the library zip with it.
                    titles += restoreBook(ctx, object : java.io.FilterInputStream(library) { override fun close() {} }, entry.name)
                }
            }
        }
        return titles
    }

    private fun restoreBook(ctx: Context, input: InputStream, shown: String): String {
        val tmp = File(ctx.cacheDir, "restore-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val found = extract(input, tmp)
            val meta = try {
                val segments = JSONArray(File(tmp, Files.TRANSCRIPT).readText())
                if (Files.NOTES in found) JSONArray(File(tmp, Files.NOTES).readText()) else File(tmp, Files.NOTES).writeText("[]")
                // A backup made before details were included has none: the file name stands in for the title.
                if (Files.META in found) JSONObject(File(tmp, Files.META).readText())
                else JSONObject().put("title", shown.removeSuffix(".zip")).put("duration", segments.optJSONObject(segments.length() - 1)?.optLong("end") ?: 0L)
            } catch (e: JSONException) {
                throw BackupException("Not an audiobook backup: ${e.message}")
            }
            if (meta.optString("title").isBlank()) throw BackupException("Not an audiobook backup: no title.")
            meta.put("status", Status.READY.name).remove("error")
            File(tmp, Files.META).writeText(meta.put("created", System.currentTimeMillis()).toString())
            val dest = Library.dir(ctx, UUID.randomUUID().toString().take(8))
            dest.parentFile!!.mkdirs()
            if (!tmp.renameTo(dest)) throw BackupException("Could not save the book.")
            Library.refresh(ctx)
            return meta.getString("title")
        } finally {
            tmp.deleteRecursively()
        }
    }
}
