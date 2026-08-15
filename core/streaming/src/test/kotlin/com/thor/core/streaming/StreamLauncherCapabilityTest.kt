package com.thor.core.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tests the backward-compatible second-video capability parser. */
class StreamLauncherCapabilityTest {

    @Test
    fun `advertised second stream is accepted`() {
        assertThat(parseMaxVideoStreams("<root><MaxVideoStreams>2</MaxVideoStreams></root>"))
            .isEqualTo(2)
    }

    @Test
    fun `missing capability remains single stream`() {
        assertThat(parseMaxVideoStreams("<root><appversion>7.1.431</appversion></root>"))
            .isEqualTo(1)
    }

    @Test
    fun `malformed and nonpositive capabilities remain single stream`() {
        assertThat(parseMaxVideoStreams("<MaxVideoStreams>many</MaxVideoStreams>"))
            .isEqualTo(1)
        assertThat(parseMaxVideoStreams("<MaxVideoStreams>0</MaxVideoStreams>"))
            .isEqualTo(1)
        assertThat(parseMaxVideoStreams("<MaxVideoStreams>-4</MaxVideoStreams>"))
            .isEqualTo(1)
    }

    @Test
    fun `capability parser accepts formatted XML text`() {
        assertThat(parseMaxVideoStreams("<MaxVideoStreams enabled=\"true\">\n  3 \n</MaxVideoStreams>"))
            .isEqualTo(3)
    }
}
