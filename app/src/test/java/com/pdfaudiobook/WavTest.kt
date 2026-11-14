/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package com.pdfaudiobook

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WavTest {
    @Test fun roundTrips() {
        val f = File.createTempFile("wav", ".wav")
        Wav.write(f, 16000, ByteArray(3200))
        val i = Wav.read(f)
        assertEquals(16000, i.rate)
        assertEquals(44L, i.dataOffset)
        assertEquals(3200L, i.dataLength)
        f.delete()
    }
}
