package com.clipstudio.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.View
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min

/** One decoder feeds both crop panels. Framing changes redraw the current texture. */
internal class LiveFramingView(
    context: Context, private val uri: Uri, private val onSize: (Int, Int) -> Unit,
    private val onFailure: () -> Unit
) : GLSurfaceView(context) {
    private val main = Handler(Looper.getMainLooper())
    private val scene = FramingRenderer()
    private var player: MediaPlayer? = null
    private var output: Surface? = null
    private var ready = false
    private var seeking = false
    private var released = false
    private var from = 0L
    private var to = Long.MAX_VALUE
    private var muted = false

    private val loop = object : Runnable {
        override fun run() {
            val media = player ?: return
            if (!ready || released || windowVisibility != View.VISIBLE) return
            val position = media.currentPosition.toLong()
            scene.position = (position - from).coerceAtLeast(0)
            if (!seeking && (position >= to || position < from)) seekStart(media)
            main.postDelayed(this, 65)
        }
    }

    init {
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true
        setRenderer(scene)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun updateFrame(layout: Layout, camera: Crop, gameplay: Crop, path: List<FacePoint>, start: Long, end: Long, mute: Boolean) {
        scene.framing = Framing(layout, camera, gameplay, path)
        val changed = from != start || to != end
        from = start
        to = end
        muted = mute
        player?.takeIf { ready }?.let { media ->
            val volume = if (muted) 0f else 1f
            media.setVolume(volume, volume)
            if (changed && !seeking) seekStart(media)
        }
        requestRender()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        main.removeCallbacks(loop)
        val media = player?.takeIf { ready } ?: return
        if (visibility == View.VISIBLE && !released) {
            if (!seeking) media.start()
            main.post(loop)
        } else if (media.isPlaying) {
            media.pause()
        }
    }

    private fun seekStart(media: MediaPlayer) {
        seeking = true
        media.seekTo(from, MediaPlayer.SEEK_CLOSEST)
    }

    private fun bind(texture: SurfaceTexture) {
        if (released) return
        stopPlayer()
        val surface = Surface(texture)
        output = surface
        val media = MediaPlayer()
        player = media
        try {
            media.setDataSource(context, uri)
            media.setSurface(surface)
            media.setOnVideoSizeChangedListener { active, width, height ->
                if (active == player && width > 0 && height > 0) {
                    scene.source = SourceSize(width, height)
                    onSize(width, height)
                    requestRender()
                }
            }
            media.setOnPreparedListener { active ->
                if (active != player || released) return@setOnPreparedListener
                ready = true
                if (active.videoWidth > 0 && active.videoHeight > 0) {
                    scene.source = SourceSize(active.videoWidth, active.videoHeight)
                    onSize(active.videoWidth, active.videoHeight)
                }
                val volume = if (muted) 0f else 1f
                active.setVolume(volume, volume)
                seekStart(active)
                main.removeCallbacks(loop)
                main.post(loop)
            }
            media.setOnSeekCompleteListener { active ->
                if (active == player && !released) {
                    seeking = false
                    if (windowVisibility == View.VISIBLE) active.start()
                }
            }
            media.setOnCompletionListener { active ->
                if (active == player && ready && !released) seekStart(active)
            }
            media.setOnErrorListener { active, _, _ ->
                if (active == player && !released) {
                    stopPlayer()
                    onFailure()
                }
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            stopPlayer()
            onFailure()
        }
    }

    private fun stopPlayer() {
        main.removeCallbacks(loop)
        ready = false
        seeking = false
        player?.release()
        player = null
        output?.release()
        output = null
    }

    fun close() {
        if (released) return
        released = true
        stopPlayer()
        queueEvent { scene.close() }
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private data class Framing(val layout: Layout, val camera: Crop, val game: Crop, val path: List<FacePoint>)
    private data class SourceSize(val width: Int, val height: Int)

    private inner class FramingRenderer : Renderer {
        @Volatile var framing = Framing(Layout.Split, Crop(.5f, .5f), Crop(.5f, .5f), emptyList())
        @Volatile var source = SourceSize(0, 0)
        @Volatile var position = 0L
        private var texture: SurfaceTexture? = null
        private val pending = AtomicBoolean()
        private var hasFrame = false
        private var textureName = 0
        private var program = 0
        private var positionLoc = 0
        private var coordLoc = 0
        private var rectLoc = 0
        private var matrixLoc = 0
        private var viewWidth = 0
        private var viewHeight = 0
        private val matrix = FloatArray(16)
        private val vertices = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f))
            position(0)
        }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            texture?.setOnFrameAvailableListener(null)
            texture?.release()
            hasFrame = false
            pending.set(false)
            val names = IntArray(1)
            glGenTextures(1, names, 0)
            textureName = names[0]
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureName)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            program = glCreateProgram()
            val vertex = shader(GL_VERTEX_SHADER, VERTEX)
            val fragment = shader(GL_FRAGMENT_SHADER, FRAGMENT)
            glAttachShader(program, vertex)
            glAttachShader(program, fragment)
            glLinkProgram(program)
            glDeleteShader(vertex)
            glDeleteShader(fragment)
            val linked = IntArray(1)
            glGetProgramiv(program, GL_LINK_STATUS, linked, 0)
            if (linked[0] == 0) {
                main.post { if (!released) onFailure() }
                return
            }
            positionLoc = glGetAttribLocation(program, "pos")
            coordLoc = glGetAttribLocation(program, "uv")
            rectLoc = glGetUniformLocation(program, "region")
            matrixLoc = glGetUniformLocation(program, "transform")
            val fresh = SurfaceTexture(textureName)
            texture = fresh
            fresh.setOnFrameAvailableListener({
                pending.set(true)
                requestRender()
            }, main)
            main.post { bind(fresh) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            viewWidth = width
            viewHeight = height
        }

        override fun onDrawFrame(gl: GL10?) {
            glClearColor(.05f, .06f, .08f, 1f)
            glClear(GL_COLOR_BUFFER_BIT)
            val surfaceTexture = texture ?: return
            if (pending.getAndSet(false)) {
                surfaceTexture.updateTexImage()
                surfaceTexture.getTransformMatrix(matrix)
                hasFrame = true
            }
            val (width, height) = source
            if (!hasFrame || width <= 0 || height <= 0 || viewWidth <= 0 || viewHeight <= 0) return
            glUseProgram(program)
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureName)
            glUniformMatrix4fv(matrixLoc, 1, false, matrix, 0)
            vertices.position(0)
            glVertexAttribPointer(positionLoc, 2, GL_FLOAT, false, 16, vertices)
            glEnableVertexAttribArray(positionLoc)
            vertices.position(2)
            glVertexAttribPointer(coordLoc, 2, GL_FLOAT, false, 16, vertices)
            glEnableVertexAttribArray(coordLoc)
            val (layout, camera, game, path) = framing
            fun panel(bottom: Int, h: Int, crop: Crop) {
                if (h <= 0) return
                val window = cropWindow(width, height, viewWidth.toFloat() / h, crop)
                glViewport(0, bottom, viewWidth, h)
                draw(window.x.toFloat() / width, (height - window.y - window.height).toFloat() / height,
                    window.width.toFloat() / width, window.height.toFloat() / height)
            }
            when (layout) {
                Layout.Split -> {
                    val focus = focusAt(path, position)
                    val top = if (focus == null) camera else camera.copy(x = focus.x, y = focus.y)
                    panel(viewHeight / 2, viewHeight - viewHeight / 2, top)
                    panel(0, viewHeight / 2, game)
                }
                Layout.Fill, Layout.Square -> panel(0, viewHeight, game)
                Layout.Fit, Layout.Landscape -> {
                    val scale = min(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
                    val w = (width * scale).toInt()
                    val h = (height * scale).toInt()
                    glViewport((viewWidth - w) / 2, (viewHeight - h) / 2, w, h)
                    draw(0f, 0f, 1f, 1f)
                }
            }
        }

        private fun draw(x: Float, y: Float, width: Float, height: Float) {
            glUniform4f(rectLoc, x, y, width, height)
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }

        fun close() {
            texture?.setOnFrameAvailableListener(null)
            texture?.release()
            texture = null
            glDeleteTextures(1, intArrayOf(textureName), 0)
            if (program != 0) glDeleteProgram(program)
        }

        private fun shader(type: Int, code: String): Int = glCreateShader(type).also {
            glShaderSource(it, code)
            glCompileShader(it)
        }
    }

    private companion object {
        const val VERTEX = """attribute vec2 pos; attribute vec2 uv; varying vec2 tex;
            uniform mat4 transform; uniform vec4 region;
            void main() { gl_Position=vec4(pos,0.0,1.0);
                tex=(transform*vec4(region.xy+uv*region.zw,0.0,1.0)).xy; }"""
        const val FRAGMENT = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 tex; uniform samplerExternalOES image;
            void main() { gl_FragColor=texture2D(image,tex); }"""
    }
}
