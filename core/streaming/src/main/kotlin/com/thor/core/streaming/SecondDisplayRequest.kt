package com.thor.core.streaming

/**
 * What the client asks the host to send to its second panel.
 *
 * A request rather than a description, in exactly the sense the first display's
 * settings are: the host decides what it can encode, and one that cannot manage
 * the asked-for mode sends something else rather than refusing.
 *
 * Separate from [com.thor.core.model.SessionQuality] because almost none of that
 * applies twice. The codec, the audio configuration, the network type and the
 * encryption keys belong to the *session* and are shared; only the picture
 * differs between the two screens. Reusing the whole type would invite setting
 * an audio channel count on a display.
 */
data class SecondDisplayRequest(
    val width: Int,
    val height: Int,
    val fps: Int,
    /**
     * Kilobits per second for this stream alone.
     *
     * Budgeted separately from the first display rather than splitting one
     * allowance by area. The second panel usually holds a desktop that is static
     * for minutes at a time, and a proportional split would starve the game to
     * reserve bandwidth for a screen that is not changing.
     */
    val bitrateKbps: Int,
) {
    companion object {
        /**
         * A sensible request for the Thor's lower panel.
         *
         * Far below the first display's default, and deliberately. What lands
         * here is a desktop — a browser, a launcher, a chat window — which is
         * mostly static and mostly text, so it wants resolution for legibility
         * rather than bitrate for motion.
         */
        fun forPanel(width: Int, height: Int, fps: Int = DEFAULT_FPS): SecondDisplayRequest =
            SecondDisplayRequest(
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = DEFAULT_BITRATE_KBPS,
            )

        /**
         * 30 rather than 60.
         *
         * A second screen showing a desktop does not need the frame rate the game
         * needs, and every frame spent here is bandwidth and encoder time taken
         * from the screen the user is actually looking at.
         */
        const val DEFAULT_FPS = 30

        const val DEFAULT_BITRATE_KBPS = 5_000
    }
}
