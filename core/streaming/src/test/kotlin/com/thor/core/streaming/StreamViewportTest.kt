package com.thor.core.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests pointer mapping independently of Android views and input dispatch. */
class StreamViewportTest {

    @Test
    fun `matching aspect ratio maps the whole surface`() {
        val point = StreamViewport.map(
            viewX = 960f,
            viewY = 540f,
            viewWidth = 1920,
            viewHeight = 1080,
            videoSize = StreamVideoSize(1920, 1080),
        )

        assertThat(point).isEqualTo(StreamPosition(960, 540, 1920, 1080))
    }

    @Test
    fun `pillarbox bars clamp to the nearest video edge`() {
        val leftBar = StreamViewport.map(
            viewX = 100f,
            viewY = 500f,
            viewWidth = 2000,
            viewHeight = 1000,
            videoSize = StreamVideoSize(1000, 1000),
        )
        val rightBar = StreamViewport.map(
            viewX = 1900f,
            viewY = 500f,
            viewWidth = 2000,
            viewHeight = 1000,
            videoSize = StreamVideoSize(1000, 1000),
        )

        assertThat(leftBar).isEqualTo(StreamPosition(0, 500, 1000, 1000))
        assertThat(rightBar).isEqualTo(StreamPosition(1000, 500, 1000, 1000))
    }

    @Test
    fun `letterbox bars do not stretch the vertical coordinate`() {
        val topEdge = StreamViewport.map(
            viewX = 500f,
            viewY = 250f,
            viewWidth = 1000,
            viewHeight = 1000,
            videoSize = StreamVideoSize(1000, 500),
        )
        val centre = StreamViewport.map(
            viewX = 500f,
            viewY = 500f,
            viewWidth = 1000,
            viewHeight = 1000,
            videoSize = StreamVideoSize(1000, 500),
        )

        assertThat(topEdge).isEqualTo(StreamPosition(500, 0, 1000, 500))
        assertThat(centre).isEqualTo(StreamPosition(500, 250, 1000, 500))
    }

    @Test
    fun `portrait negotiated mode maps correctly on a landscape surface`() {
        val point = StreamViewport.map(
            viewX = 1000f,
            viewY = 500f,
            viewWidth = 2000,
            viewHeight = 1000,
            videoSize = StreamVideoSize(500, 1000),
        )

        assertThat(point).isEqualTo(StreamPosition(250, 500, 500, 1000))
    }

    @Test
    fun `invalid geometry is rejected`() {
        assertThat(
            StreamViewport.map(0f, 0f, 0, 1080, StreamVideoSize(1920, 1080)),
        ).isNull()
        assertThat(
            StreamViewport.map(0f, 0f, 1920, 1080, StreamVideoSize(0, 1080)),
        ).isNull()
    }
}
