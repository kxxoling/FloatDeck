package app.floatdeck.gl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.Matrix
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies semi-transparent PNG compositing on a real GPU: runs the production
 * shader + texture pipeline (BitmapFactory decode → GLUtils premultiplied
 * upload → render) into an offscreen EGL pbuffer, reads pixels back with
 * glReadPixels and asserts transparent/semi-transparent areas blend
 * correctly over a magenta background.
 *
 * Regression background: the old portrait shader ignored texture alpha, so
 * transparent regions rendered as solid black cards; the blend function also
 * mismatched the premultiplied textures, darkening semi-transparent edges.
 * This test fails against both defects, guarding the fix.
 */
class SemiTransparentPngRenderingTest {
    private val width = 64
    private val height = 128

    // Clear color: magenta (what transparent areas must reveal)
    private val bgR = 255
    private val bgG = 0
    private val bgB = 255

    // Per-row texture alpha: opaque → half → faint → fully transparent
    private val rowAlphas = intArrayOf(255, 128, 32, 0)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT

    private var quadBuffer: java.nio.FloatBuffer? = null
    private val createdTextures = mutableListOf<Int>()
    private val createdPrograms = mutableListOf<Int>()

    @Before
    fun setUp() {
        initEgl()
        quadBuffer = Quad.createBuffer()
    }

    @After
    fun tearDown() {
        createdTextures.forEach { TextureLoader.deleteTexture(it) }
        createdPrograms.forEach { GLES30.glDeleteProgram(it) }
        destroyEgl()
    }

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue("eglGetDisplay failed", eglDisplay != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        assertTrue(
            "eglInitialize failed",
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1),
        )

        val attribs =
            intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        assertTrue(
            "eglChooseConfig failed",
            EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0) &&
                numConfigs[0] > 0,
        )

        val pbufferAttribs =
            intArrayOf(
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE,
            )
        eglSurface =
            EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0)
        assertTrue(
            "eglCreatePbufferSurface failed",
            eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE,
        )

        val contextAttribs =
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext =
            EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        assertTrue(
            "eglCreateContext failed",
            eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT,
        )

        assertTrue(
            "eglMakeCurrent failed",
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext),
        )
    }

    private fun destroyEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            EGL14.eglTerminate(eglDisplay)
        }
    }

    /** Generates a 2×N solid-color alpha-step PNG, decodes it via BitmapFactory and uploads it as a texture (same path as remote templates). */
    private fun loadAlphaStepTexture(
        dir: File,
        fileName: String,
        r: Int,
        g: Int,
        b: Int,
    ): Int {
        val bitmap = Bitmap.createBitmap(2, rowAlphas.size, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(2 * rowAlphas.size)
        for (y in rowAlphas.indices) {
            for (x in 0 until 2) {
                pixels[y * 2 + x] = Color.argb(rowAlphas[y], r, g, b)
            }
        }
        bitmap.setPixels(pixels, 0, 2, 0, 0, 2, rowAlphas.size)
        val pngFile = File(dir, fileName)
        FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        // Decoding yields a premultiplied bitmap by default, matching the production remote-template load path
        val decoded = BitmapFactory.decodeFile(pngFile.absolutePath)
        assertTrue("decodeFile failed for $fileName", decoded != null)
        val texId = TextureLoader.loadBitmap(decoded!!)
        createdTextures.add(texId)
        decoded.recycle()
        return texId
    }

    private fun bindQuadAttributes(buffer: java.nio.FloatBuffer) {
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, Quad.getStride(), buffer)
        buffer.position(Quad.getUvOffset() / 4)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, Quad.getStride(), buffer)
    }

    private fun fullScreenMvp(): FloatArray {
        val ortho = FloatArray(16)
        Matrix.orthoM(ortho, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, width / 2f, height / 2f, 0f)
        Matrix.scaleM(model, 0, width / 2f, height / 2f, 1f)
        val mvp = FloatArray(16)
        Matrix.multiplyMM(mvp, 0, ortho, 0, model, 0)
        return mvp
    }

    /** Clears to magenta, runs [draw], reads back the whole pbuffer. */
    private fun renderAndReadPixels(draw: () -> Unit): ByteBuffer {
        GLES30.glViewport(0, 0, width, height)
        GLES30.glClearColor(bgR / 255f, bgG / 255f, bgB / 255f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        draw()
        GLES30.glFinish()
        val pixels =
            ByteBuffer
                .allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        pixels.rewind()
        return pixels
    }

    /**
     * Returns the screen pixel at the center of texture row [row] (x centered,
     * away from rounded corners). Texture row centers map to
     * (row+0.5)/rows*height from the top; the glReadPixels origin is
     * bottom-left, so flip.
     */
    private fun pixelOfRow(
        pixels: ByteBuffer,
        row: Int,
    ): IntArray {
        val x = width / 2
        val yFromTop = ((row + 0.5f) / rowAlphas.size * height).toInt()
        val yFromBottom = height - 1 - yFromTop
        val offset = (yFromBottom * width + x) * 4
        val r = pixels.get(offset).toInt() and 0xFF
        val g = pixels.get(offset + 1).toInt() and 0xFF
        val b = pixels.get(offset + 2).toInt() and 0xFF
        return intArrayOf(r, g, b)
    }

    private fun assertColor(
        message: String,
        actual: IntArray,
        texR: Int,
        texG: Int,
        texB: Int,
        alpha: Int,
    ) {
        val a = alpha / 255f
        val expR = texR * a + bgR * (1 - a)
        val expG = texG * a + bgG * (1 - a)
        val expB = texB * a + bgB * (1 - a)
        assertTrue(
            "$message: expected(~$expR, ~$expG, ~$expB), got (${actual[0]}, ${actual[1]}, ${actual[2]})",
            absDiff(actual[0], expR) <= 10 &&
                absDiff(actual[1], expG) <= 10 &&
                absDiff(actual[2], expB) <= 10,
        )
    }

    private fun absDiff(
        a: Int,
        b: Float,
    ): Int = Math.abs(a - Math.round(b))

    @Test
    fun portraitShaderCompositesSemiTransparentPngCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texId = loadAlphaStepTexture(context.cacheDir, "alpha_portrait.png", 255, 0, 0)

        // Same GL state as FloatDeckRenderer.onSurfaceCreated (incl. the fixed premultiplied blend)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val program = ShaderProgram.compile(Shaders.portraitVertex, Shaders.portraitFragment)
        createdPrograms.add(program)
        GLES30.glUseProgram(program)

        val mvp = fullScreenMvp()
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uOffset"), 0f, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uRotation"), 0f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uScale"), 1f, 1f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uParallax"), 0f, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAlpha"), 1f)
        // Shadow disabled to isolate the texture's own blending
        GLES30.glUniform4f(GLES30.glGetUniformLocation(program, "uShadowColor"), 0f, 0f, 0f, 0f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uShadowOffset"), 0f, 0f)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uRadius"), 0.05f)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uEffect"), 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uTime"), 0f)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(program, "uViewAngle"), 0f, 0f)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

        val pixels =
            renderAndReadPixels {
                quadBuffer?.let { buffer ->
                    bindQuadAttributes(buffer)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glDisableVertexAttribArray(0)
                    GLES30.glDisableVertexAttribArray(1)
                }
            }
        rowAlphas.forEachIndexed { row, alpha ->
            assertColor(
                "portrait row $row (alpha=$alpha)",
                pixelOfRow(pixels, row),
                255,
                0,
                0,
                alpha,
            )
        }
    }

    @Test
    fun backgroundShaderCompositesSemiTransparentPngCorrectly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val texId = loadAlphaStepTexture(context.cacheDir, "alpha_background.png", 0, 128, 128)

        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        val program = ShaderProgram.compile(Shaders.backgroundVertex, Shaders.backgroundFragment)
        createdPrograms.add(program)
        GLES30.glUseProgram(program)

        val mvp = fullScreenMvp()
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uMVP"), 1, false, mvp, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uAlpha"), 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTexture"), 0)

        val pixels =
            renderAndReadPixels {
                quadBuffer?.let { buffer ->
                    bindQuadAttributes(buffer)
                    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
                    GLES30.glDisableVertexAttribArray(0)
                    GLES30.glDisableVertexAttribArray(1)
                }
            }
        rowAlphas.forEachIndexed { row, alpha ->
            assertColor(
                "background row $row (alpha=$alpha)",
                pixelOfRow(pixels, row),
                0,
                128,
                128,
                alpha,
            )
        }
    }
}
