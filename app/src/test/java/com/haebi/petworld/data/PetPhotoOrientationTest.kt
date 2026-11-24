package com.haebi.petworld.data

import android.media.ExifInterface
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PetPhotoOrientationTest {
    @Test fun allExifOrientationsMapPixelAxesCorrectly() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to floatArrayOf(2f, 3f),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to floatArrayOf(-2f, 3f),
            ExifInterface.ORIENTATION_ROTATE_180 to floatArrayOf(-2f, -3f),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to floatArrayOf(2f, -3f),
            ExifInterface.ORIENTATION_TRANSPOSE to floatArrayOf(3f, 2f),
            ExifInterface.ORIENTATION_ROTATE_90 to floatArrayOf(-3f, 2f),
            ExifInterface.ORIENTATION_TRANSVERSE to floatArrayOf(-3f, -2f),
            ExifInterface.ORIENTATION_ROTATE_270 to floatArrayOf(3f, -2f),
        )
        expected.forEach { (orientation, target) ->
            val point = floatArrayOf(2f, 3f)
            photoOrientationMatrix(orientation).mapPoints(point)
            assertArrayEquals(target, point, 0.001f)
        }
        assertTrue(photoOrientationMatrix(ExifInterface.ORIENTATION_UNDEFINED).isIdentity)
    }
}
