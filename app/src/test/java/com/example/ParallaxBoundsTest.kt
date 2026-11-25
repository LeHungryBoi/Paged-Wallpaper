package com.example

import com.example.data.model.CropFillMode
import com.example.data.model.PageItem
import com.example.util.ParallaxBounds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ParallaxBoundsTest {

    @Test
    fun centered100PercentReachesBothEdgesExactly() {
        // 1000x1000 image in 100x200 viewport (baseCropWidth = 500, totalSlack = 500 source px = 100 target px)
        val travel = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 1f,
            panX = 0f,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 1f,
            symmetricParallax = false
        )
        assertEquals(50f, travel.leftTravelPx, 0.01f)
        assertEquals(50f, travel.rightTravelPx, 0.01f)
    }

    @Test
    fun panningInsideSlackKeepsBothSidesFixedAtConfiguredPercentage() {
        // 1000x1000 image in 100x200 viewport -> halfSlack = 50f target px.
        // At 58%, targetPerSide = 29f.
        val centered = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 1f,
            panX = 0f,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.58f,
            symmetricParallax = false
        )
        assertEquals(29f, centered.leftTravelPx, 0.01f)
        assertEquals(29f, centered.rightTravelPx, 0.01f)

        // Pan left by 30% of max pan -> availableLeft = 35f (> 29f), availableRight = 65f (> 29f)
        val pannedWithinSlack = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 1f,
            panX = 0.3f,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.58f,
            symmetricParallax = false
        )
        assertEquals(29f, pannedWithinSlack.leftTravelPx, 0.01f)
        assertEquals(29f, pannedWithinSlack.rightTravelPx, 0.01f)
    }

    @Test
    fun panningPastBorderClampsOnlyTouchingSideByDefaultAndBothSidesWhenSymmetric() {
        // 1000x1000 image in 100x200 viewport -> halfSlack = 50f, at 58% targetPerSide = 29f.
        // panX = 0.7 -> availableLeft = 50 * (1 - 0.7) = 15f, availableRight = 85f.
        val independent = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 1f,
            panX = 0.7f,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.58f,
            symmetricParallax = false
        )
        assertEquals(15f, independent.leftTravelPx, 0.01f)
        assertEquals(29f, independent.rightTravelPx, 0.01f)

        val symmetric = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 1f,
            panX = 0.7f,
            cropFillMode = CropFillMode.CROP,
            parallaxAmount = 0.58f,
            symmetricParallax = true
        )
        assertEquals(15f, symmetric.leftTravelPx, 0.01f)
        assertEquals(15f, symmetric.rightTravelPx, 0.01f)
    }

    @Test
    fun blurFillModeHonorsFixedReachAndSymmetricClamping() {
        // 1000x1000 image in 100x200 viewport at 2.0x zoom -> fitScale = 0.2, drawWidth = 200.
        // totalSlack = 100, halfSlack = 50. At 60%, targetPerSide = 30.
        val withinSlack = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 2f,
            panX = 0.2f,
            cropFillMode = CropFillMode.BLUR,
            parallaxAmount = 0.60f,
            symmetricParallax = false
        )
        assertEquals(30f, withinSlack.leftTravelPx, 0.01f)
        assertEquals(30f, withinSlack.rightTravelPx, 0.01f)

        // panX = -0.8 -> imageLeft = -90 -> availableLeft = 90, availableRight = 10
        val nearRightIndependent = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 2f,
            panX = -0.8f,
            cropFillMode = CropFillMode.GLASS,
            parallaxAmount = 0.60f,
            symmetricParallax = false
        )
        assertEquals(30f, nearRightIndependent.leftTravelPx, 0.01f)
        assertEquals(10f, nearRightIndependent.rightTravelPx, 0.01f)

        val nearRightSymmetric = ParallaxBounds.calculateTravel(
            sourceWidth = 1000,
            sourceHeight = 1000,
            targetWidth = 100f,
            targetHeight = 200f,
            scale = 2f,
            panX = -0.8f,
            cropFillMode = CropFillMode.GLASS,
            parallaxAmount = 0.60f,
            symmetricParallax = true
        )
        assertEquals(10f, nearRightSymmetric.leftTravelPx, 0.01f)
        assertEquals(10f, nearRightSymmetric.rightTravelPx, 0.01f)
    }

    @Test
    fun pageItemSerializesAndDefaultsSymmetricParallax() {
        val enabledPage = PageItem(pageIndex = 0, parallaxAmount = 0.58f, symmetricParallax = true)
        val restored = PageItem.fromJson(enabledPage.toJson())
        assertTrue(restored.symmetricParallax)

        val legacyJson = JSONObject().put("pageIndex", 1).put("parallaxAmount", 0.58)
        val legacyRestored = PageItem.fromJson(legacyJson)
        assertFalse(legacyRestored.symmetricParallax)
    }
}
