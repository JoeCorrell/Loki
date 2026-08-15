package com.thor.feature.stream.panel

import com.google.common.truth.Truth.assertThat
import com.thor.core.streaming.StreamPad
import org.junit.Test

/** Tests the overlay state shared by the stream activity and lower display. */
class StreamPanelControllerTest {

    @Test
    fun `keyboard toggle opens and closes the keyboard`() {
        val controller = StreamPanelController(StreamPad())

        controller.toggleKeyboard()
        assertThat(controller.keyboardFocused).isTrue()

        controller.toggleKeyboard()
        assertThat(controller.keyboardFocused).isFalse()
    }

    @Test
    fun `opening quick settings closes the keyboard`() {
        val controller = StreamPanelController(StreamPad())
        controller.takeKeyboard()

        controller.toggleSettings()

        assertThat(controller.mode).isEqualTo(PanelMode.SETTINGS)
        assertThat(controller.keyboardFocused).isFalse()
    }

    @Test
    fun `opening keyboard returns from quick settings to the video panel`() {
        val controller = StreamPanelController(StreamPad())
        controller.toggleSettings()

        controller.takeKeyboard()

        assertThat(controller.mode).isEqualTo(PanelMode.PAD)
        assertThat(controller.keyboardFocused).isTrue()
    }
}
