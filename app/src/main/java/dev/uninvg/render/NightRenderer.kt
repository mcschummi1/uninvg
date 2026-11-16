package dev.uninvg.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20 as GL
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.uninvg.core.Enhancement
import dev.uninvg.core.NvSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.min

class NightRenderer(private val view: GLSurfaceView, private val listener: Listener) : GLSurfaceView.Renderer {
    interface Listener {
        fun onSurface(texture: SurfaceTexture)
        fun onSample(luma: FloatArray, width: Int, height: Int, timestampNs: Long)
        fun onOutput(timestampNs: Long)
        fun onSnapshot(bitmap: Bitmap)
        fun onRenderError(message: String)
    }

    private data class Target(val texture: Int, val framebuffer: Int, val width: Int, val height: Int)
    private val main = Handler(Looper.getMainLooper())
    private val framePending = AtomicBoolean()
    private val snapshotPending = AtomicBoolean()
    private val vertices: FloatBuffer = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)); position(0)
    }
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val surfaceMatrix = FloatArray(16)
    private val uvMatrix = FloatArray(16)
    private val composedMatrix = FloatArray(16)
    private val samplePixels = ByteBuffer.allocateDirect(64 * 64 * 4).order(ByteOrder.nativeOrder())
    private var input: SurfaceTexture? = null
    private var externalTexture = 0
    private var bitmapTexture = 0
    private var externalProgram = 0
    private var copyProgram = 0
    private var stackProgram = 0
    private var enhanceProgram = 0
    private var source: Target? = null
    private var history = arrayOfNulls<Target>(2)
    private var output: Target? = null
    private var sampler: Target? = null
    private var historyIndex = 0
    private var accumulated = 0
    private var screenWidth = 1
    private var screenHeight = 1
    private var imageWidth = 1280
    private var imageHeight = 720
    private var rotation = 0
    private var cameraOrientation: CameraOrientation? = null
    private var isBitmap = false
    private var bitmapDirty = false
    private var renderDirty = true
    private var failureReported = false
    private var lastSample = 0L
    private val cadence = FrameCadence()
    private var lastTimestamp = 0L
    private var settings = NvSettings()
    private var enhancement = Enhancement()
    private var maxDepth = 8
    private var motionThreshold = 0.075f

    fun configureCamera(width: Int, height: Int, orientation: CameraOrientation) {
        view.queueEvent {
            imageWidth = width
            imageHeight = height
            cameraOrientation = orientation
            rotation = orientation.relativeDegrees
            isBitmap = false
            resetTargets()
        }
        view.requestRender()
    }

    fun configureSource(width: Int, height: Int, rotationDegrees: Int) {
        view.queueEvent {
            imageWidth = width
            imageHeight = height
            rotation = ((rotationDegrees % 360) + 360) % 360
            cameraOrientation = null
            isBitmap = false
            resetTargets()
        }
        view.requestRender()
    }

    fun setProfile(stackLimit: Int, threshold: Float) {
        view.queueEvent { maxDepth = stackLimit; motionThreshold = threshold; accumulated = 0 }
    }

    fun update(next: NvSettings, light: Enhancement) {
        view.queueEvent {
            if (next.stackDepth != settings.stackDepth || next.torch != settings.torch || next.fps != settings.fps) accumulated = 0
            settings = next
            enhancement = light
            renderDirty = true
        }
        view.requestRender()
    }

    fun showBitmap(bitmap: Bitmap) {
        view.queueEvent {
            try {
                if (bitmapTexture == 0) bitmapTexture = texture(GL.GL_TEXTURE_2D)
                GL.glBindTexture(GL.GL_TEXTURE_2D, bitmapTexture)
                android.opengl.GLUtils.texImage2D(GL.GL_TEXTURE_2D, 0, bitmap, 0)
                imageWidth = bitmap.width
                imageHeight = bitmap.height
                rotation = 0
                cameraOrientation = null
                isBitmap = true
                bitmapDirty = true
                resetTargets()
                bitmap.recycle()
            } catch (error: RuntimeException) { fail(error) }
        }
        view.requestRender()
    }

    fun resetHistory() {
        view.queueEvent { accumulated = 0; lastSample = 0; cadence.reset(); renderDirty = true }
    }

    fun snapshot() { snapshotPending.set(true); view.requestRender() }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        try {
            input?.release()
            source = null
            output = null
            history = arrayOfNulls(2)
            sampler = null
            bitmapTexture = 0
            isBitmap = false
            accumulated = 0
            failureReported = false
            externalProgram = program(VERTEX, EXTERNAL)
            copyProgram = program(VERTEX, COPY)
            stackProgram = program(VERTEX, STACK)
            enhanceProgram = program(VERTEX, ENHANCE)
            externalTexture = texture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
            input = SurfaceTexture(externalTexture).apply {
                setOnFrameAvailableListener { framePending.set(true); view.requestRender() }
            }
            sampler = target(64, 64)
            GL.glClearColor(0.025f, 0.04f, 0.035f, 1f)
            input?.let { texture -> main.post { listener.onSurface(texture) } }
        } catch (error: RuntimeException) { fail(error) }
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        screenWidth = max(1, width)
        screenHeight = max(1, height)
        renderDirty = true
    }

    override fun onDrawFrame(unused: GL10?) {
        if (failureReported) return
        try {
            val newFrame = !isBitmap && framePending.getAndSet(false)
            if (newFrame) {
                input?.updateTexImage()
                input?.getTransformMatrix(surfaceMatrix)
                lastTimestamp = input?.timestamp ?: 0L
            }
            if (newFrame || bitmapDirty) {
                ensureTargets()
                drawSource(source!!)
                if (lastTimestamp < lastSample) lastSample = 0
                if (isBitmap || lastTimestamp - lastSample >= 200_000_000L || lastSample == 0L) {
                    sampleSource()
                    lastSample = lastTimestamp
                }
                accumulate()
                bitmapDirty = false
                val due = isBitmap || cadence.accept(lastTimestamp.coerceAtLeast(0), settings.fps)
                if (due) {
                    enhance()
                    if (!isBitmap && lastTimestamp > 0) {
                        val stamp = lastTimestamp
                        main.post { listener.onOutput(stamp) }
                    }
                    renderDirty = false
                }
            } else if (renderDirty && source != null && accumulated > 0) {
                enhance()
                renderDirty = false
            }
            drawScreen()
            if (snapshotPending.getAndSet(false)) {
                if (output != null && accumulated > 0) saveSnapshot()
                else main.post { listener.onRenderError("No image to save yet") }
            }
        } catch (error: RuntimeException) { fail(error) }
    }

    private fun ensureTargets() {
        if (source != null) return
        val width = if (rotation % 180 == 0) imageWidth else imageHeight
        val height = if (rotation % 180 == 0) imageHeight else imageWidth
        source = target(width, height)
        history[0] = target(width, height)
        history[1] = target(width, height)
        output = target(width, height)
    }

    private fun resetTargets() {
        listOfNotNull(source, output, history[0], history[1]).forEach { delete(it) }
        source = null
        output = null
        history = arrayOfNulls(2)
        accumulated = 0
        lastSample = 0
        cadence.reset()
        renderDirty = true
    }

    private fun drawSource(destination: Target) {
        bind(destination)
        val shader = if (isBitmap) copyProgram else externalProgram
        GL.glUseProgram(shader)
        val orientation = cameraOrientation
        if (!isBitmap && orientation != null) {
            orientation.textureMatrix(surfaceMatrix).copyInto(composedMatrix)
        } else {
            Matrix.setIdentityM(uvMatrix, 0)
            Matrix.translateM(uvMatrix, 0, 0.5f, 0.5f, 0f)
            Matrix.rotateM(uvMatrix, 0, -rotation.toFloat(), 0f, 0f, 1f)
            Matrix.scaleM(uvMatrix, 0, 1f, if (isBitmap) -1f else 1f, 1f)
            Matrix.translateM(uvMatrix, 0, -0.5f, -0.5f, 0f)
            if (!isBitmap) Matrix.multiplyMM(composedMatrix, 0, surfaceMatrix, 0, uvMatrix, 0)
            else uvMatrix.copyInto(composedMatrix)
        }
        GL.glUniformMatrix4fv(GL.glGetUniformLocation(shader, "uMatrix"), 1, false, composedMatrix, 0)
        bindTexture(shader, "uSource", 0, if (isBitmap) bitmapTexture else externalTexture,
            if (isBitmap) GL.GL_TEXTURE_2D else GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        quad(shader)
    }

    private fun sampleSource() {
        val destination = sampler ?: return
        bind(destination)
        useCopy(source!!.texture)
        samplePixels.position(0)
        GL.glReadPixels(0, 0, 64, 64, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, samplePixels)
        val luma = FloatArray(64 * 64) { index ->
            val offset = index * 4
            ((samplePixels.get(offset).toInt() and 255) * 0.2126f +
                (samplePixels.get(offset + 1).toInt() and 255) * 0.7152f +
                (samplePixels.get(offset + 2).toInt() and 255) * 0.0722f) / 255f
        }
        listener.onSample(luma, 64, 64, if (isBitmap) System.nanoTime() else lastTimestamp)
    }

    private fun accumulate() {
        val next = 1 - historyIndex
        val dest = history[next]!!
        bind(dest)
        GL.glUseProgram(stackProgram)
        matrix(stackProgram)
        bindTexture(stackProgram, "uSource", 0, source!!.texture)
        bindTexture(stackProgram, "uHistory", 1, history[historyIndex]!!.texture)
        val depth = if (isBitmap) 1 else min(settings.stackDepth, maxDepth)
        GL.glUniform1f(GL.glGetUniformLocation(stackProgram, "uWeight"), if (accumulated == 0) 0f else min(accumulated, depth - 1).toFloat() / min(accumulated + 1, depth))
        GL.glUniform1f(GL.glGetUniformLocation(stackProgram, "uThreshold"), motionThreshold + enhancement.noise * 1.5f)
        quad(stackProgram)
        historyIndex = next
        accumulated = min(accumulated + 1, depth)
    }

    private fun enhance() {
        bind(output!!)
        GL.glUseProgram(enhanceProgram)
        matrix(enhanceProgram)
        bindTexture(enhanceProgram, "uSource", 0, history[historyIndex]!!.texture)
        GL.glUniform2f(GL.glGetUniformLocation(enhanceProgram, "uTexel"), 1f / output!!.width, 1f / output!!.height)
        GL.glUniform1f(GL.glGetUniformLocation(enhanceProgram, "uGain"), max(enhancement.gain, settings.digitalGain))
        GL.glUniform1f(GL.glGetUniformLocation(enhanceProgram, "uGamma"), settings.gamma)
        GL.glUniform1f(GL.glGetUniformLocation(enhanceProgram, "uDenoise"), settings.denoise)
        GL.glUniform1f(GL.glGetUniformLocation(enhanceProgram, "uNoise"), enhancement.noise.coerceIn(0.003f, 0.25f))
        GL.glUniform1i(GL.glGetUniformLocation(enhanceProgram, "uPalette"), settings.palette.ordinal)
        val gains = if (settings.regional) enhancement.regionalGains else List(16) { 1f }
        for (row in 0..3) GL.glUniform4fv(GL.glGetUniformLocation(enhanceProgram, "uRow$row"), 1, gains.subList(row * 4, row * 4 + 4).toFloatArray(), 0)
        quad(enhanceProgram)
    }

    private fun drawScreen() {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, 0)
        GL.glViewport(0, 0, screenWidth, screenHeight)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT)
        val target = output ?: return
        if (settings.vr) {
            val divider = max(2, screenWidth / 300)
            val eyeWidth = (screenWidth - divider) / 2
            val scale = max(eyeWidth.toFloat() / target.width, screenHeight.toFloat() / target.height)
            val width = (target.width * scale).toInt()
            val height = (target.height * scale).toInt()
            GL.glEnable(GL.GL_SCISSOR_TEST)
            GL.glScissor(0, 0, eyeWidth, screenHeight)
            GL.glViewport((eyeWidth - width) / 2, (screenHeight - height) / 2, width, height)
            useCopy(target.texture)
            val right = eyeWidth + divider
            GL.glScissor(right, 0, eyeWidth, screenHeight)
            GL.glViewport(right + (eyeWidth - width) / 2, (screenHeight - height) / 2, width, height)
            useCopy(target.texture)
            GL.glDisable(GL.GL_SCISSOR_TEST)
        } else {
            val scale = max(screenWidth.toFloat() / target.width, screenHeight.toFloat() / target.height)
            val width = (target.width * scale).toInt()
            val height = (target.height * scale).toInt()
            GL.glViewport((screenWidth - width) / 2, (screenHeight - height) / 2, width, height)
            useCopy(target.texture)
        }
    }

    private fun saveSnapshot() {
        val target = output ?: return
        bind(target)
        val pixels = ByteBuffer.allocateDirect(target.width * target.height * 4).order(ByteOrder.nativeOrder())
        GL.glReadPixels(0, 0, target.width, target.height, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, pixels)
        val argb = IntArray(target.width * target.height)
        for (y in 0 until target.height) for (x in 0 until target.width) {
            val offset = ((target.height - 1 - y) * target.width + x) * 4
            argb[y * target.width + x] = (255 shl 24) or ((pixels.get(offset).toInt() and 255) shl 16) or
                ((pixels.get(offset + 1).toInt() and 255) shl 8) or (pixels.get(offset + 2).toInt() and 255)
        }
        val bitmap = Bitmap.createBitmap(argb, target.width, target.height, Bitmap.Config.ARGB_8888)
        main.post { listener.onSnapshot(bitmap) }
    }

    private fun useCopy(texture: Int) {
        GL.glUseProgram(copyProgram)
        matrix(copyProgram)
        bindTexture(copyProgram, "uSource", 0, texture)
        quad(copyProgram)
    }

    private fun matrix(program: Int) = GL.glUniformMatrix4fv(GL.glGetUniformLocation(program, "uMatrix"), 1, false, identity, 0)
    private fun bind(target: Target) {
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, target.framebuffer)
        GL.glViewport(0, 0, target.width, target.height)
    }
    private fun bindTexture(program: Int, name: String, unit: Int, texture: Int, target: Int = GL.GL_TEXTURE_2D) {
        GL.glActiveTexture(GL.GL_TEXTURE0 + unit)
        GL.glBindTexture(target, texture)
        GL.glUniform1i(GL.glGetUniformLocation(program, name), unit)
    }
    private fun quad(program: Int) {
        val position = GL.glGetAttribLocation(program, "aPosition")
        val uv = GL.glGetAttribLocation(program, "aUv")
        vertices.position(0)
        GL.glEnableVertexAttribArray(position)
        GL.glVertexAttribPointer(position, 2, GL.GL_FLOAT, false, 16, vertices)
        vertices.position(2)
        GL.glEnableVertexAttribArray(uv)
        GL.glVertexAttribPointer(uv, 2, GL.GL_FLOAT, false, 16, vertices)
        GL.glDrawArrays(GL.GL_TRIANGLE_STRIP, 0, 4)
        GL.glDisableVertexAttribArray(position)
        GL.glDisableVertexAttribArray(uv)
    }
    private fun texture(target: Int): Int {
        val ids = IntArray(1)
        GL.glGenTextures(1, ids, 0)
        GL.glBindTexture(target, ids[0])
        GL.glTexParameteri(target, GL.GL_TEXTURE_MIN_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(target, GL.GL_TEXTURE_MAG_FILTER, GL.GL_LINEAR)
        GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_S, GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(target, GL.GL_TEXTURE_WRAP_T, GL.GL_CLAMP_TO_EDGE)
        return ids[0]
    }
    private fun target(width: Int, height: Int): Target {
        require(width > 0 && height > 0)
        val texture = texture(GL.GL_TEXTURE_2D)
        GL.glTexImage2D(GL.GL_TEXTURE_2D, 0, GL.GL_RGBA, width, height, 0, GL.GL_RGBA, GL.GL_UNSIGNED_BYTE, null)
        val ids = IntArray(1)
        GL.glGenFramebuffers(1, ids, 0)
        GL.glBindFramebuffer(GL.GL_FRAMEBUFFER, ids[0])
        GL.glFramebufferTexture2D(GL.GL_FRAMEBUFFER, GL.GL_COLOR_ATTACHMENT0, GL.GL_TEXTURE_2D, texture, 0)
        check(GL.glCheckFramebufferStatus(GL.GL_FRAMEBUFFER) == GL.GL_FRAMEBUFFER_COMPLETE) { "GPU framebuffer unavailable (${width}x$height)" }
        return Target(texture, ids[0], width, height)
    }
    private fun delete(target: Target) {
        GL.glDeleteFramebuffers(1, intArrayOf(target.framebuffer), 0)
        GL.glDeleteTextures(1, intArrayOf(target.texture), 0)
    }
    private fun program(vertex: String, fragment: String): Int {
        fun compile(type: Int, source: String): Int {
            val shader = GL.glCreateShader(type)
            GL.glShaderSource(shader, source)
            GL.glCompileShader(shader)
            val status = IntArray(1)
            GL.glGetShaderiv(shader, GL.GL_COMPILE_STATUS, status, 0)
            check(status[0] != 0) { "Shader compilation: ${GL.glGetShaderInfoLog(shader)}" }
            return shader
        }
        val vert = compile(GL.GL_VERTEX_SHADER, vertex)
        val frag = compile(GL.GL_FRAGMENT_SHADER, fragment)
        val program = GL.glCreateProgram()
        GL.glAttachShader(program, vert)
        GL.glAttachShader(program, frag)
        GL.glLinkProgram(program)
        val status = IntArray(1)
        GL.glGetProgramiv(program, GL.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Shader linking: ${GL.glGetProgramInfoLog(program)}" }
        GL.glDeleteShader(vert)
        GL.glDeleteShader(frag)
        return program
    }
    private fun fail(error: RuntimeException) {
        failureReported = true
        Log.e("UniNVG.Render", "operation=render", error)
        main.post { listener.onRenderError("Image processing unavailable: ${error.message}") }
    }

    companion object {
        private const val VERTEX = """
            attribute vec2 aPosition;
            attribute vec2 aUv;
            uniform mat4 uMatrix;
            varying vec2 vUv;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vUv = (uMatrix * vec4(aUv, 0.0, 1.0)).xy; }
        """
        private const val EXTERNAL = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uSource;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uSource, vUv); }
        """
        private const val COPY = """
            precision mediump float;
            uniform sampler2D uSource;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uSource, vUv); }
        """
        private const val STACK = """
            precision mediump float;
            uniform sampler2D uSource;
            uniform sampler2D uHistory;
            uniform float uWeight;
            uniform float uThreshold;
            varying vec2 vUv;
            void main() {
                vec3 current = texture2D(uSource, vUv).rgb;
                vec3 previous = texture2D(uHistory, vUv).rgb;
                float difference = length(current - previous) * 0.57735;
                float stable = 1.0 - smoothstep(uThreshold * 0.5, uThreshold, difference);
                gl_FragColor = vec4(mix(current, previous, uWeight * stable), 1.0);
            }
        """
        private const val ENHANCE = """
            precision mediump float;
            uniform sampler2D uSource;
            uniform vec2 uTexel;
            uniform float uGain;
            uniform float uGamma;
            uniform float uDenoise;
            uniform float uNoise;
            uniform int uPalette;
            uniform vec4 uRow0;
            uniform vec4 uRow1;
            uniform vec4 uRow2;
            uniform vec4 uRow3;
            varying vec2 vUv;
            float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
            float rowValue(vec4 row, float x) {
                float a = mix(row.x, row.y, clamp(x, 0.0, 1.0));
                float b = mix(row.y, row.z, clamp(x - 1.0, 0.0, 1.0));
                float c = mix(row.z, row.w, clamp(x - 2.0, 0.0, 1.0));
                return x < 1.0 ? a : (x < 2.0 ? b : c);
            }
            float localGain() {
                vec2 p = clamp(vUv * 4.0 - 0.5, 0.0, 3.0);
                vec4 column = vec4(rowValue(uRow0,p.x), rowValue(uRow1,p.x), rowValue(uRow2,p.x), rowValue(uRow3,p.x));
                return rowValue(column, p.y);
            }
            void main() {
                vec3 center = texture2D(uSource, vUv).rgb;
                float y = lum(center);
                vec3 sum = center;
                float weights = 1.0;
                for (int j = -1; j <= 1; j++) {
                    for (int i = -1; i <= 1; i++) {
                        vec3 c = texture2D(uSource, vUv + vec2(float(i), float(j)) * uTexel).rgb;
                        float d = abs(lum(c) - y);
                        float w = exp(-d / max(uNoise * 2.0, 0.015));
                        sum += c * w;
                        weights += w;
                    }
                }
                vec3 filtered = mix(center, sum / weights, uDenoise);
                float gain = max(0.1, uGain * localGain());
                vec3 boosted = filtered * gain;
                boosted = boosted / (vec3(1.0) + filtered * max(gain - 1.0, 0.0));
                vec3 color = pow(clamp(boosted, 0.0, 1.0), vec3(1.0 / max(uGamma, 0.25)));
                float l = lum(color);
                if (uPalette == 1) color = l * vec3(0.35, 1.0, 0.48);
                else if (uPalette == 2) color = l * vec3(0.94, 0.98, 1.0);
                else if (uPalette == 3) color = l * vec3(1.0, 0.15, 0.08);
                else if (uPalette == 4) color = l * vec3(0.62, 0.83, 1.0);
                gl_FragColor = vec4(color, 1.0);
            }
        """
    }
}
