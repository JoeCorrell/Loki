package com.thor.data.capture

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import com.thor.core.common.log.ThorLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Draws the physical displays directly into a video encoder surface.
 *
 * The primary panel is a live external-OES texture supplied by MediaProjection.
 * Android does not expose a projection stream for a secondary physical display,
 * so that panel is a normal 2D texture refreshed by the accessibility capture
 * service. The compositor belongs to the recording service, not to an Activity or
 * Compose window, which is what lets it keep drawing after another app covers Loki.
 */
internal class RecordingProjectionCompositor(
    private val projection: MediaProjection,
    private val encoderSurface: Surface,
    private val layout: ScreenCaptureLayout,
    private val onFailure: (Throwable) -> Unit,
) {

    private val thread = HandlerThread(THREAD_NAME)
    private lateinit var handler: Handler
    private val topFrameAvailable = AtomicBoolean(false)
    private val bottomLock = Any()
    private val viewports = captureViewports(layout)

    @Volatile private var running = false
    @Volatile private var prepared = false
    @Volatile private var clearBottomRequested = false

    private var pendingBottom: Bitmap? = null
    private var topReady = false
    private var bottomReady = false

    private var projectionDisplay: VirtualDisplay? = null
    private var projectionSurface: Surface? = null
    private var projectionTexture: SurfaceTexture? = null

    private var eglDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface = EGL14.EGL_NO_SURFACE

    private var externalProgram = 0
    private var bitmapProgram = 0
    private var topTexture = 0
    private var bottomTexture = 0

    private val topMatrix = FloatArray(MATRIX_SIZE)
    private val bitmapMatrix = FloatArray(MATRIX_SIZE).apply {
        setIdentity(this)
        // Android bitmaps start at the top-left; GL texture coordinates do not.
        this[5] = -1f
        this[13] = 1f
    }
    private val positions = floatBuffer(
        -1f, -1f,
        1f, -1f,
        -1f, 1f,
        1f, 1f,
    )
    private val textureCoordinates = floatBuffer(
        0f, 0f,
        1f, 0f,
        0f, 1f,
        1f, 1f,
    )

    private val drawLoop = object : Runnable {
        override fun run() {
            if (!running) return
            runCatching { drawFrame() }
                .onFailure { error ->
                    ThorLog.e(TAG, "Recording compositor stopped drawing", error)
                    running = false
                    onFailure(error)
                }
            if (running) handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    /** Creates EGL and consumes the MediaProjection token exactly once. */
    fun prepare() {
        check(!prepared) { "The recording compositor is already prepared" }
        thread.start()
        handler = Handler(thread.looper)

        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        handler.post {
            runCatching { setUpOnGlThread() }
                .onSuccess { prepared = true }
                .onFailure { failure = it }
            ready.countDown()
        }

        if (!ready.await(SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            release()
            error("Timed out preparing the recording compositor")
        }
        failure?.let { error ->
            release()
            throw error
        }
    }

    /** Starts timestamps only after MediaRecorder itself has started. */
    fun start() {
        check(prepared) { "The recording compositor has not been prepared" }
        if (running) return
        running = true
        handler.post(drawLoop)
    }

    /** Transfers ownership of [bitmap] to the GL thread. */
    fun submitBottom(bitmap: Bitmap?) {
        if (!prepared) {
            bitmap?.recycle()
            return
        }
        synchronized(bottomLock) {
            pendingBottom?.recycle()
            pendingBottom = bitmap
            clearBottomRequested = bitmap == null
        }
    }

    /** Waits until no GL call can still be writing into the encoder surface. */
    fun pause() {
        running = false
        if (!::handler.isInitialized || Looper.myLooper() == thread.looper) return

        val quiet = CountDownLatch(1)
        handler.removeCallbacks(drawLoop)
        handler.post { quiet.countDown() }
        quiet.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    /** Releases the projection display and every GL object on their owning thread. */
    fun release() {
        pause()
        prepared = false
        synchronized(bottomLock) {
            pendingBottom?.recycle()
            pendingBottom = null
        }

        if (!::handler.isInitialized) return
        val released = CountDownLatch(1)
        val cleanup = {
            runCatching { releaseOnGlThread() }
                .onFailure { ThorLog.w(TAG, "Could not completely release compositor", it) }
            released.countDown()
        }

        if (Looper.myLooper() == thread.looper) {
            cleanup()
        } else {
            handler.removeCallbacksAndMessages(null)
            handler.post(cleanup)
            released.await(RELEASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        thread.quitSafely()
    }

    private fun setUpOnGlThread() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        check(EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) { "Could not initialise EGL" }

        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0)) {
            "Could not choose an EGL config"
        }
        val config = configs.firstOrNull() ?: error("No recordable EGL config")

        eglContext = EGL14.eglCreateContext(
            eglDisplay,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "Could not create an EGL context" }

        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay,
            config,
            encoderSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "Could not attach the video encoder" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            "Could not make the recording context current"
        }

        externalProgram = createProgram(VERTEX_SHADER, EXTERNAL_FRAGMENT_SHADER)
        bitmapProgram = createProgram(VERTEX_SHADER, BITMAP_FRAGMENT_SHADER)
        topTexture = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        bottomTexture = createTexture(GLES20.GL_TEXTURE_2D)
        setIdentity(topMatrix)

        projectionTexture = SurfaceTexture(topTexture).apply {
            setDefaultBufferSize(layout.top.width, layout.top.height)
            setOnFrameAvailableListener({ topFrameAvailable.set(true) }, handler)
        }
        projectionSurface = Surface(projectionTexture)
        projectionDisplay = projection.createVirtualDisplay(
            DISPLAY_NAME,
            layout.top.width,
            layout.top.height,
            layout.top.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            projectionSurface,
            null,
            handler,
        ) ?: error("The system would not create the projection display")

        GLES20.glDisable(GLES20.GL_BLEND)
        checkGl("setting up the compositor")
    }

    private fun drawFrame() {
        if (topFrameAvailable.getAndSet(false)) {
            projectionTexture?.updateTexImage()
            projectionTexture?.getTransformMatrix(topMatrix)
            topReady = true
        }

        val nextBottom = synchronized(bottomLock) {
            pendingBottom.also { pendingBottom = null }
        }
        if (nextBottom != null) {
            try {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, bottomTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, nextBottom, 0)
                bottomReady = true
                clearBottomRequested = false
            } finally {
                nextBottom.recycle()
            }
        } else if (clearBottomRequested) {
            bottomReady = false
            clearBottomRequested = false
        }

        GLES20.glViewport(0, 0, layout.outputWidth, layout.outputHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        if (topReady) drawTexture(
            program = externalProgram,
            target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            texture = topTexture,
            matrix = topMatrix,
            viewport = viewports.top,
        )
        if (bottomReady && viewports.bottom != null) drawTexture(
            program = bitmapProgram,
            target = GLES20.GL_TEXTURE_2D,
            texture = bottomTexture,
            matrix = bitmapMatrix,
            viewport = viewports.bottom,
        )

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
        check(EGL14.eglSwapBuffers(eglDisplay, eglSurface)) { "Encoder rejected a frame" }
        checkGl("drawing a recording frame")
    }

    private fun drawTexture(
        program: Int,
        target: Int,
        texture: Int,
        matrix: FloatArray,
        viewport: CaptureViewport,
    ) {
        GLES20.glViewport(viewport.left, viewport.bottom, viewport.width, viewport.height)
        GLES20.glUseProgram(program)

        val position = GLES20.glGetAttribLocation(program, ATTRIBUTE_POSITION)
        val textureCoordinate = GLES20.glGetAttribLocation(program, ATTRIBUTE_TEXTURE_COORDINATE)
        val textureMatrix = GLES20.glGetUniformLocation(program, UNIFORM_TEXTURE_MATRIX)
        val sampler = GLES20.glGetUniformLocation(program, UNIFORM_TEXTURE)

        positions.position(0)
        textureCoordinates.position(0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(textureCoordinate)
        GLES20.glVertexAttribPointer(
            textureCoordinate,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            textureCoordinates,
        )
        GLES20.glUniformMatrix4fv(textureMatrix, 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, texture)
        GLES20.glUniform1i(sampler, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoordinate)
    }

    private fun releaseOnGlThread() {
        projectionDisplay?.release()
        projectionDisplay = null
        projectionSurface?.release()
        projectionSurface = null
        projectionTexture?.release()
        projectionTexture = null

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            if (topTexture != 0 || bottomTexture != 0) {
                GLES20.glDeleteTextures(2, intArrayOf(topTexture, bottomTexture), 0)
            }
            if (externalProgram != 0) GLES20.glDeleteProgram(externalProgram)
            if (bitmapProgram != 0) GLES20.glDeleteProgram(bitmapProgram)
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(eglDisplay)
        }

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
        topTexture = 0
        bottomTexture = 0
        externalProgram = 0
        bitmapProgram = 0
        topReady = false
        bottomReady = false
        prepared = false
    }

    private fun createTexture(target: Int): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(target, textures[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textures[0]
    }

    private fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource)
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        return GLES20.glCreateProgram().also { program ->
            GLES20.glAttachShader(program, vertex)
            GLES20.glAttachShader(program, fragment)
            GLES20.glLinkProgram(program)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
            GLES20.glDeleteShader(vertex)
            GLES20.glDeleteShader(fragment)
            check(linked[0] == GLES20.GL_TRUE) {
                "Could not link recording shader: ${GLES20.glGetProgramInfoLog(program)}"
            }
        }
    }

    private fun compileShader(type: Int, source: String): Int =
        GLES20.glCreateShader(type).also { shader ->
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val compiled = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
            check(compiled[0] == GLES20.GL_TRUE) {
                "Could not compile recording shader: ${GLES20.glGetShaderInfoLog(shader)}"
            }
        }

    private fun checkGl(operation: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "GL error 0x${error.toString(16)} while $operation" }
    }

    private companion object {
        const val TAG = "RecordingCompositor"
        const val THREAD_NAME = "Loki recording compositor"
        const val DISPLAY_NAME = "Loki primary capture"
        const val FRAME_INTERVAL_MS = 1_000L / 30L
        const val SETUP_TIMEOUT_SECONDS = 5L
        const val RELEASE_TIMEOUT_SECONDS = 5L
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val MATRIX_SIZE = 16
        const val VERTEX_COUNT = 4
        const val ATTRIBUTE_POSITION = "aPosition"
        const val ATTRIBUTE_TEXTURE_COORDINATE = "aTextureCoordinate"
        const val UNIFORM_TEXTURE_MATRIX = "uTextureMatrix"
        const val UNIFORM_TEXTURE = "uTexture"

        val VERTEX_SHADER = """
            uniform mat4 uTextureMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoordinate;
            varying vec2 vTextureCoordinate;
            void main() {
                gl_Position = aPosition;
                vTextureCoordinate = (uTextureMatrix * aTextureCoordinate).xy;
            }
        """.trimIndent()

        val EXTERNAL_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTextureCoordinate;
            uniform samplerExternalOES uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate);
            }
        """.trimIndent()

        val BITMAP_FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 vTextureCoordinate;
            uniform sampler2D uTexture;
            void main() {
                gl_FragColor = texture2D(uTexture, vTextureCoordinate);
            }
        """.trimIndent()

        fun floatBuffer(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply { put(values); position(0) }

        fun setIdentity(matrix: FloatArray) {
            matrix.fill(0f)
            matrix[0] = 1f
            matrix[5] = 1f
            matrix[10] = 1f
            matrix[15] = 1f
        }
    }
}
