package com.haebi.petworld.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PetPhotoSizingTest {
    @Test fun samplingKeepsEnoughPixelsForFinalScale() {
        assertEquals(1, photoSampleSize(1200, 900))
        assertEquals(1, photoSampleSize(2399, 1000))
        assertEquals(2, photoSampleSize(2400, 1800))
        assertEquals(8, photoSampleSize(10000, 6000))
        assertTrue(runCatching { photoSampleSize(10001, 1) }.isFailure)
    }

    @Test fun targetDimensionsPreserveAspectRatioAndNeverUpscale() {
        assertEquals(1200 to 900, photoTargetSize(4000, 3000))
        assertEquals(900 to 1200, photoTargetSize(3000, 4000))
        assertEquals(1 to 1200, photoTargetSize(1, 10000))
        assertEquals(400 to 300, photoTargetSize(400, 300))
    }

    @Test fun unknownProviderSizeIsBoundedByActualBytes() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val accepted = ByteArrayOutputStream()
        assertEquals(4L, copyPhotoWithinLimit(ByteArrayInputStream(bytes), accepted, 4))
        assertArrayEquals(bytes, accepted.toByteArray())
        val rejected = ByteArrayOutputStream()
        assertTrue(runCatching {
            copyPhotoWithinLimit(ByteArrayInputStream(bytes), rejected, 3)
        }.isFailure)
        assertEquals(0, rejected.size())
        assertTrue(runCatching {
            copyPhotoWithinLimit(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 4)
        }.isFailure)
    }
}
