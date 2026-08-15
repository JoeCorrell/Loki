package com.thor.data.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenCaptureLayoutTest {

    private val top = CaptureDisplay(0, width = 1920, height = 1080, densityDpi = 320)

    @Test
    fun `two panels occupy separate stacked regions`() {
        val bottom = CaptureDisplay(1, width = 1280, height = 720, densityDpi = 320)
        val viewports = captureViewports(
            ScreenCaptureLayout(1920, 1800, 320, top, bottom),
        )

        assertThat(viewports.top).isEqualTo(CaptureViewport(0, 720, 1920, 1080))
        assertThat(viewports.bottom).isEqualTo(CaptureViewport(320, 0, 1280, 720))
    }

    @Test
    fun `one display fills a matching output frame`() {
        val viewports = captureViewports(
            ScreenCaptureLayout(1920, 1080, 320, top),
        )

        assertThat(viewports.top).isEqualTo(CaptureViewport(0, 0, 1920, 1080))
        assertThat(viewports.bottom).isNull()
    }

    @Test
    fun `encoder scaling keeps output proportions and even dimensions`() {
        val layout = ScreenCaptureLayout(
            outputWidth = 2560,
            outputHeight = 2880,
            outputDensityDpi = 480,
            top = top,
            bottom = top.copy(displayId = 1),
        ).scaledForEncoder()

        assertThat(layout.outputHeight).isEqualTo(2160)
        assertThat(layout.outputWidth).isEqualTo(1920)
        assertThat(layout.outputWidth % 2).isEqualTo(0)
        assertThat(layout.outputHeight % 2).isEqualTo(0)
    }

    @Test
    fun `invalid source dimensions cannot produce an invalid GL viewport`() {
        val invalid = CaptureDisplay(0, width = 0, height = -1, densityDpi = 0)
        val viewports = captureViewports(
            ScreenCaptureLayout(2, 2, 160, invalid),
        )

        assertThat(viewports.top.width).isAtLeast(1)
        assertThat(viewports.top.height).isAtLeast(1)
    }
}
