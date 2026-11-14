/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16-bit mono PCM in a WAV container: the format Android speech engines write. */
object Wav {
    class Info(val rate: Int, val dataOffset: Long, val dataLength: Long)

    fun read(file: File): Info = RandomAccessFile(file, "r").use { f ->
        val head = ByteArray(12).also { f.readFully(it) }
        require(String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") { "Not a WAV file" }
        var rate = 0
        while (f.filePointer + 8 <= f.length()) {
            val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).also { f.readFully(it.array()) }
            val id = String(h.array(), 0, 4)
            val size = h.getInt(4).toLong() and 0xFFFFFFFFL
            if (id == "fmt ") {
                val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).also { f.readFully(it.array()) }
                require(fmt.getShort(0).toInt() == 1 && fmt.getShort(2).toInt() == 1 && fmt.getShort(14).toInt() == 16) { "Speech must be mono 16-bit PCM" }
                rate = fmt.getInt(4)
                f.seek(f.filePointer + size - 16 + (size and 1))
            } else if (id == "data") {
                val offset = f.filePointer
                return Info(rate, offset, minOf(size, f.length() - offset))
            } else {
                f.seek(f.filePointer + size + (size and 1))
            }
        }
        error("WAV has no data")
    }

    /** The PCM bytes of [info]'s data chunk. */
    fun data(file: File, info: Info): ByteArray = RandomAccessFile(file, "r").use { f ->
        ByteArray(info.dataLength.toInt()).also { f.seek(info.dataOffset); f.readFully(it) }
    }

    fun write(file: File, rate: Int, pcm: ByteArray) {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(b.array()); it.write(pcm) }
    }
}
