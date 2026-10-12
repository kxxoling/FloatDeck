package app.floatdeck.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
import app.floatdeck.data.PortraitPosition
import app.floatdeck.data.TemplateConfig
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

// ============================================================================
// 数据模型
// ============================================================================

/**
 * 单张立绘卡片的运行时状态。
 *
 * @param lockedPosition explicit locked placement from the template (null = built-in layout)
 * @param unlockedPosition explicit unlocked placement from the template (null = built-in layout)
 * @param baseOffsetX/Y persisted user offsets from the template (pixels)
 */
data class PortraitState(
    val id: String,
    val file: String,
    val label: String,
    var textureId: Int = 0,
    var textureWidth: Int = 0,
    var textureHeight: Int = 0,
    var drawOrder: Int = 0,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
    val lockedPosition: PortraitPosition? = null,
    val unlockedPosition: PortraitPosition? = null,
    var baseOffsetX: Float = 0f,
    var baseOffsetY: Float = 0f,
    var extraScale: Float = 1f,
    var extraRotation: Float = 0f,
)

// ============================================================================
// 锁屏布局配置
// ============================================================================

/**
 * 锁屏时的行排列定义。
 * @property rows 每行的立绘数量列表，如 listOf(5, 5, 2)
 */
data class LockLayout(
    val rows: List<Int>,
)

/** 可用的横屏锁屏布局 */
private val LANDSCAPE_LAYOUTS =
    listOf(
        LockLayout(listOf(4, 4, 4)),
        LockLayout(listOf(5, 2, 5)),
    )

/** 可用的竖屏锁屏布局 */
private val PORTRAIT_LAYOUTS =
    listOf(
        LockLayout(listOf(4, 4, 4)),
        LockLayout(listOf(3, 3, 3, 3)),
    )

// ============================================================================
// 主渲染器
// ============================================================================

/**
 * FloatDeck 动态壁纸的 OpenGL ES 3.0 渲染器。
 */
class FloatDeckRenderer(
    private val context: Context,
) {
    // ------------------------------------------------------------------
    // OpenGL 着色器程序
    // ------------------------------------------------------------------
    private var portraitProgram = 0
    private var backgroundProgram = 0
    private var quadVertexBuffer: FloatBuffer? = null

    // ------------------------------------------------------------------
    // 背景壁纸状态
    // ------------------------------------------------------------------
    private var wallpaperTextureId = 0
    private var wallpaperPixelWidth = 1
    private var wallpaperPixelHeight = 1

    // ------------------------------------------------------------------
    // 立绘卡片状态
    // ------------------------------------------------------------------
    private val portraitStates = mutableListOf<PortraitState>()

    // ------------------------------------------------------------------
    // 传感器数值
    // ------------------------------------------------------------------
    var smoothedRollX = 0f
    var smoothedPitchY = 0f

    // ------------------------------------------------------------------
    // 锁屏/解锁过渡
    // ------------------------------------------------------------------
    @Volatile var transitionProgress = 0f
    @Volatile var targetTransition = 0f
    private var isFirstFrame = true

    // ------------------------------------------------------------------
    // 晃动动画
    // ------------------------------------------------------------------
    private var swayTimeSeconds = 0f

    // ------------------------------------------------------------------
    // Frame delta (seconds): animations advance by real elapsed time so
    // speed is identical on 60/90/120Hz screens
    // ------------------------------------------------------------------
    private var lastFrameNanos = 0L
    private var frameDeltaSeconds = 0.016f

    // ------------------------------------------------------------------
    // 锁屏布局
    // ------------------------------------------------------------------

    /** 当前选中的锁屏布局（启动时随机选择） */
    private var currentLockLayout: LockLayout = PORTRAIT_LAYOUTS[0]

    /** 是否已选择布局 */
    private var layoutSelected = false

    /** 横竖屏切换时需要重新选择布局 */
    private var needsTemplateReload = false

    // ------------------------------------------------------------------
    // 屏幕尺寸
    // ------------------------------------------------------------------
    private var screenWidthPixels = 1f
    private var screenHeightPixels = 1f
    private val orthographicMatrix = FloatArray(16)

    // ------------------------------------------------------------------
    // 着色器 uniform 位置 — 立绘
    // ------------------------------------------------------------------
    private var uniformPortraitMvp = 0
    private var uniformPortraitOffset = 0
    private var uniformPortraitRotation = 0
    private var uniformPortraitScale = 0
    private var uniformPortraitParallax = 0
    private var uniformPortraitTexture = 0
    private var uniformPortraitAlpha = 0
    private var uniformPortraitShadowColor = 0
    private var uniformPortraitShadowOffset = 0
    private var uniformPortraitCornerRadius = 0

    // 立绘特效 uniform
    private var uniformPortraitEffect = 0
    private var uniformPortraitTime = 0
    private var uniformPortraitViewAngle = 0

    // ------------------------------------------------------------------
    // 着色器 uniform 位置 — 背景
    // ------------------------------------------------------------------
    private var uniformBackgroundMvp = 0
    private var uniformBackgroundParallax = 0
    private var uniformBackgroundTexture = 0
    private var uniformBackgroundAlpha = 0

    // ------------------------------------------------------------------
    // 布局常量
    // ------------------------------------------------------------------
    private val portraitHeightScreenFraction = 0.24f
    private val portraitAspectRatio = 0.5f

    /** 当前立绘特效。由壁纸服务从 SharedPreferences 读取后设置。 */
    var portraitEffect: Int = 0 // 0=无, 1=碎碎冰, 2=炫彩

    /** Global tilt-parallax sensitivity multiplier (1 = default). Set by the wallpaper service from SharedPreferences. */
    @Volatile
    var parallaxSensitivity = 1f

    /** User background zoom multiplier (1 = exact cover). Loaded by the wallpaper service from the template's own template.json. */
    @Volatile
    var backgroundZoom = 1f

    // ------------------------------------------------------------------
    // 触控
    // ------------------------------------------------------------------
    var draggedPortraitIndex = -1
    var previousTouchX = 0f
    var previousTouchY = 0f

    /** 解锁动画延迟（秒） */
    private val unlockDelaySeconds = 0.1f
    @Volatile private var unlockDelayTimer = 0f
    @Volatile private var isWaitingForUnlock = false

    private val placeholderColors =
        intArrayOf(
            Color.argb(255, 70, 50, 90),
            Color.argb(255, 50, 60, 90),
            Color.argb(255, 60, 45, 85),
            Color.argb(255, 55, 55, 100),
            Color.argb(255, 75, 40, 80),
            Color.argb(255, 45, 65, 95),
            Color.argb(255, 65, 50, 75),
            Color.argb(255, 50, 70, 85),
            Color.argb(255, 80, 45, 70),
            Color.argb(255, 55, 55, 90),
            Color.argb(255, 60, 60, 80),
            Color.argb(255, 45, 50, 100),
        )

    // ==================================================================
    // 生命周期
    // ==================================================================

    fun onSurfaceCreated(
        gl: javax.microedition.khronos.opengles.GL10?,
        config: javax.microedition.khronos.egl.EGLConfig?,
    ) {
        GLES30.glClearColor(0.1f, 0.1f, 0.15f, 1f)
        GLES30.glEnable(GLES30.GL_BLEND)
        // GLUtils.texImage2D uploads premultiplied-alpha bitmaps; blend must be (ONE, ONE_MINUS_SRC_ALPHA)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        portraitProgram = ShaderProgram.compile(Shaders.portraitVertex, Shaders.portraitFragment)
        backgroundProgram = ShaderProgram.compile(Shaders.backgroundVertex, Shaders.backgroundFragment)

        uniformPortraitMvp = GLES30.glGetUniformLocation(portraitProgram, "uMVP")
        uniformPortraitOffset = GLES30.glGetUniformLocation(portraitProgram, "uOffset")
        uniformPortraitRotation = GLES30.glGetUniformLocation(portraitProgram, "uRotation")
        uniformPortraitScale = GLES30.glGetUniformLocation(portraitProgram, "uScale")
        uniformPortraitParallax = GLES30.glGetUniformLocation(portraitProgram, "uParallax")
        uniformPortraitTexture = GLES30.glGetUniformLocation(portraitProgram, "uTexture")
        uniformPortraitAlpha = GLES30.glGetUniformLocation(portraitProgram, "uAlpha")
        uniformPortraitShadowColor = GLES30.glGetUniformLocation(portraitProgram, "uShadowColor")
        uniformPortraitShadowOffset = GLES30.glGetUniformLocation(portraitProgram, "uShadowOffset")
        uniformPortraitCornerRadius = GLES30.glGetUniformLocation(portraitProgram, "uRadius")

        uniformPortraitEffect = GLES30.glGetUniformLocation(portraitProgram, "uEffect")
        uniformPortraitTime = GLES30.glGetUniformLocation(portraitProgram, "uTime")
        uniformPortraitViewAngle = GLES30.glGetUniformLocation(portraitProgram, "uViewAngle")

        uniformBackgroundMvp = GLES30.glGetUniformLocation(backgroundProgram, "uMVP")
        uniformBackgroundParallax = GLES30.glGetUniformLocation(backgroundProgram, "uParallax")
        uniformBackgroundTexture = GLES30.glGetUniformLocation(backgroundProgram, "uTexture")
        uniformBackgroundAlpha = GLES30.glGetUniformLocation(backgroundProgram, "uAlpha")

        quadVertexBuffer = Quad.createBuffer()
    }

    fun onSurfaceChanged(
        gl: javax.microedition.khronos.opengles.GL10?,
        width: Int,
        height: Int,
    ) {
        screenWidthPixels = width.toFloat()
        screenHeightPixels = height.toFloat()
        GLES30.glViewport(0, 0, width, height)
        Matrix.orthoM(orthographicMatrix, 0, 0f, screenWidthPixels, screenHeightPixels, 0f, -1f, 1f)

        // 重置传感器校准和布局
        needsTemplateReload = true
    }

    private fun selectRandomLayout() {
        val isLandscape = screenWidthPixels > screenHeightPixels
        val layouts = if (isLandscape) LANDSCAPE_LAYOUTS else PORTRAIT_LAYOUTS
        currentLockLayout = layouts[kotlin.random.Random.nextInt(layouts.size)]
        layoutSelected = true
    }

    // ==================================================================
    // 模板加载
    // ==================================================================

    fun loadTemplate(template: TemplateConfig) {
        portraitStates.forEach { TextureLoader.deleteTexture(it.textureId) }
        portraitStates.clear()
        if (wallpaperTextureId != 0) TextureLoader.deleteTexture(wallpaperTextureId)

        val bgResult =
            if (template.isRemote && template.wallpaperAsset != null) {
                loadTextureFromPath(template.wallpaperAsset)
            } else {
                loadFullResTexture(template.wallpaperAsset ?: "")
            }
        if (bgResult != null) {
            wallpaperTextureId = bgResult.first
            wallpaperPixelWidth = bgResult.second
            wallpaperPixelHeight = bgResult.third
        } else {
            wallpaperTextureId = TextureLoader.createGradientBackground()
            wallpaperPixelWidth = 512
            wallpaperPixelHeight = 1024
        }

        template.portraits.forEachIndexed { index, config ->
            // Positions and persisted offsets come from the template's own template.json;
            // offsets are converted to pixels at the current screen size
            val baseOffsetX = config.offsetX * screenWidthPixels
            val baseOffsetY = config.offsetY * screenHeightPixels

            val result =
                if (config.isRemote) {
                    loadTextureFromPath(config.assetPath)
                } else {
                    loadFullResTexture(config.assetPath)
                }
            if (result != null) {
                portraitStates.add(
                    PortraitState(
                        id = config.id,
                        file = config.file,
                        label = config.label,
                        textureId = result.first,
                        textureWidth = result.second,
                        textureHeight = result.third,
                        drawOrder = index,
                        lockedPosition = config.lockedPosition,
                        unlockedPosition = config.unlockedPosition,
                        baseOffsetX = baseOffsetX,
                        baseOffsetY = baseOffsetY,
                    ),
                )
            } else {
                portraitStates.add(
                    PortraitState(
                        id = config.id,
                        file = config.file,
                        label = config.label,
                        textureId =
                            TextureLoader.createPlaceholderTexture(
                                config.label,
                                bgColor = placeholderColors[index % placeholderColors.size],
                            ),
                        textureWidth = 128,
                        textureHeight = 256,
                        drawOrder = index,
                        lockedPosition = config.lockedPosition,
                        unlockedPosition = config.unlockedPosition,
                        baseOffsetX = baseOffsetX,
                        baseOffsetY = baseOffsetY,
                    ),
                )
            }
        }
    }

    private fun loadTextureFromPath(path: String): Triple<Int, Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, opts)
            val width = opts.outWidth
            val height = opts.outHeight
            val bitmap = BitmapFactory.decodeFile(path) ?: return null
            val texId = TextureLoader.loadBitmap(bitmap)
            bitmap.recycle()
            Triple(texId, width, height)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadFullResTexture(assetPath: String): Triple<Int, Int, Int>? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val width = opts.outWidth
            val height = opts.outHeight
            android.util.Log.d("FloatDeck", "Loading texture: $assetPath ${width}x${height}")
            if (width <= 0 || height <= 0) {
                android.util.Log.e("FloatDeck", "Invalid dimensions: $assetPath")
                return null
            }
            val bitmap =
                context.assets.open(assetPath).use {
                    BitmapFactory.decodeStream(it)
                } ?: run {
                    android.util.Log.e("FloatDeck", "decodeStream returned null: $assetPath")
                    return null
                }
            val texId = TextureLoader.loadBitmap(bitmap)
            android.util.Log.d("FloatDeck", "Loaded texture: $assetPath -> texId=$texId")
            bitmap.recycle()
            Triple(texId, width, height)
        } catch (e: Exception) {
            android.util.Log.e("FloatDeck", "Failed to load texture: $assetPath", e)
            null
        }
    }

    // ==================================================================
    // 每帧渲染
    // ==================================================================

    fun onDrawFrame(gl: javax.microedition.khronos.opengles.GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        updateFrameDelta()
        updateTransition()

        swayTimeSeconds += frameDeltaSeconds
        updateInertia()

        // 横竖屏切换时重新加载模板
        if (needsTemplateReload) {
            needsTemplateReload = false
            // 重新选择布局
            selectRandomLayout()
        }

        drawBackgroundLayers()
        drawPortraits()
    }

    /** Computes the frame delta (s) from the previous frame; clamped to avoid jumps after long stalls. */
    internal fun updateFrameDelta() {
        val now = System.nanoTime()
        frameDeltaSeconds =
            if (lastFrameNanos == 0L) {
                0.016f
            } else {
                ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0f, 0.1f)
            }
        lastFrameNanos = now
    }

    /**
     * Advance the lock/unlock transition state (pure CPU, callable without rendering).
     */
    fun updateTransition() {
        if (isFirstFrame) {
            transitionProgress = targetTransition
            isFirstFrame = false
        }

        if (isWaitingForUnlock) {
            unlockDelayTimer += frameDeltaSeconds
            if (unlockDelayTimer >= unlockDelaySeconds) {
                isWaitingForUnlock = false
                targetTransition = 0f
            }
        }

        // Exponential approach with 0.2s time constant; matches the old
        // 8%-per-frame convergence at 60fps
        val diff = targetTransition - transitionProgress
        transitionProgress +=
            if (abs(diff) < 0.01f) diff else diff * (1f - exp(-frameDeltaSeconds / 0.2f))
    }

    private fun updateInertia() {
        val maxOffsetX = screenWidthPixels * 0.4f
        val maxOffsetY = screenHeightPixels * 0.4f

        portraitStates.forEach { state ->
            if (state.velocityX != 0f || state.velocityY != 0f) {
                state.offsetX += state.velocityX * frameDeltaSeconds * 60f
                state.offsetY += state.velocityY * frameDeltaSeconds * 60f
                // Framerate-normalized damping: exp(-5t) equals the old
                // per-frame ×0.92 at 60fps
                val damping = exp(-frameDeltaSeconds * 5.0f)
                state.velocityX *= damping
                state.velocityY *= damping
                if (abs(state.velocityX) < 0.5f) state.velocityX = 0f
                if (abs(state.velocityY) < 0.5f) state.velocityY = 0f
            }
            state.offsetX = state.offsetX.coerceIn(-maxOffsetX, maxOffsetX)
            state.offsetY = state.offsetY.coerceIn(-maxOffsetY, maxOffsetY)
        }
    }

    // ==================================================================
    // 背景渲染
    // ==================================================================

    private fun drawBackgroundLayers() {
        // Parallax direction: pitch (tilting toward/away) → horizontal shift,
        // roll (tilting left/right) → vertical shift
        val offsets =
            parallaxOffsets(
                smoothedPitchY,
                smoothedRollX,
                screenWidthPixels,
                screenHeightPixels,
                BACKGROUND_PARALLAX_X,
                BACKGROUND_PARALLAX_Y,
                parallaxSensitivity,
            )
        drawSingleBackgroundLayer(offsets[0], offsets[1], 1.0f)
    }

    private fun drawSingleBackgroundLayer(
        parallaxX: Float,
        parallaxY: Float,
        alpha: Float,
    ) {
        GLES30.glUseProgram(backgroundProgram)
        val buffer = quadVertexBuffer ?: return
        bindQuadAttributes(buffer)

        // Maximum parallax offset: the passed-in offsets already include the
        // sensor clamp and sensitivity; take their magnitude with a small
        // margin so the shifted background never exposes screen edges
        val maxParallaxOffset =
            maxOf(
                abs(parallaxX),
                abs(parallaxY),
            ) * 1.05f
        // 额外 overscan 比例，确保视差偏移后不出现黑边
        val overscan = 1f + (maxParallaxOffset / minOf(screenWidthPixels, screenHeightPixels)) * 2f

        val screenAspect = screenWidthPixels / screenHeightPixels
        val textureAspect = wallpaperPixelWidth.toFloat() / wallpaperPixelHeight.toFloat()
        val scaleFactor =
            if (screenAspect > textureAspect) {
                screenWidthPixels * overscan / wallpaperPixelWidth.toFloat()
            } else {
                screenHeightPixels * overscan / wallpaperPixelHeight.toFloat()
            }
        val drawWidth = wallpaperPixelWidth.toFloat() * scaleFactor
        val drawHeight = wallpaperPixelHeight.toFloat() * scaleFactor

        // User zoom on top of the parallax overscan (clamped to MAX_BACKGROUND_ZOOM)
        val zoomed = backgroundGeometry(drawWidth, drawHeight, backgroundZoom)

        val mvp = FloatArray(16)
        val model = FloatArray(16)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(
            model,
            0,
            screenWidthPixels / 2f + parallaxX,
            screenHeightPixels / 2f + parallaxY,
            0f,
        )
        Matrix.scaleM(model, 0, zoomed[0] / 2f, zoomed[1] / 2f, 1f)
        Matrix.multiplyMM(mvp, 0, orthographicMatrix, 0, model, 0)

        GLES30.glUniformMatrix4fv(uniformBackgroundMvp, 1, false, mvp, 0)
        GLES30.glUniform2f(uniformBackgroundParallax, 0f, 0f)
        if (uniformBackgroundAlpha != 0) {
            GLES30.glUniform1f(uniformBackgroundAlpha, alpha)
        }

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wallpaperTextureId)
        GLES30.glUniform1i(uniformBackgroundTexture, 0)

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        unbindQuadAttributes()
    }

    // ==================================================================
    // 立绘渲染
    // ==================================================================

    private fun drawPortraits() {
        GLES30.glUseProgram(portraitProgram)
        val buffer = quadVertexBuffer ?: return
        val sortedByZ = portraitStates.sortedBy { it.drawOrder }
        val totalCount = portraitStates.size
        val leftSideCount = (totalCount + 1) / 2

        sortedByZ.forEach { state ->
            val globalIndex = portraitStates.indexOf(state)
            val isOnLeftSide = globalIndex < leftSideCount
            val sideIndex = if (isOnLeftSide) globalIndex else globalIndex - leftSideCount
            val sideCount = if (isOnLeftSide) leftSideCount else totalCount - leftSideCount

            val transitionT = transitionProgress

            // ---- Placement: explicit (template.json) wins, otherwise the built-in layout ----

            val placement =
                computePlacement(
                    state,
                    globalIndex,
                    totalCount,
                    isOnLeftSide,
                    sideIndex,
                    sideCount,
                    transitionT,
                )

            var drawX = placement[0] * screenWidthPixels
            var drawY = placement[1] * screenHeightPixels
            val drawScale = placement[2]
            val drawRotation = placement[3]

            // ---- Gyroscope parallax (always active) ----
            // Subtle parallax offsets; portraits shift more than the background;
            // explicit positions may carry a per-card tiltOffset (default 1),
            // interpolated along with the lock/unlock transition
            val portraitParallax =
                parallaxOffsets(
                    smoothedPitchY,
                    smoothedRollX,
                    screenWidthPixels,
                    screenHeightPixels,
                    PORTRAIT_PARALLAX_X,
                    PORTRAIT_PARALLAX_Y,
                    parallaxSensitivity,
                )
            val tiltLocked = state.lockedPosition?.tiltOffset ?: 1f
            val tiltUnlocked = state.unlockedPosition?.tiltOffset ?: 1f
            val tiltFactor = lerp(tiltUnlocked, tiltLocked, transitionT)
            drawX += portraitParallax[0] * tiltFactor
            drawY += portraitParallax[1] * tiltFactor

            // ---- 晃动动画（始终生效）----
            val sway = calculateSway(globalIndex)
            drawX += sway[0]
            drawY += sway[1]

            // User offsets: persisted base + the current drag
            drawX += state.baseOffsetX + state.offsetX
            drawY += state.baseOffsetY + state.offsetY

            // ---- 绘制 ----

            val portraitHeightPixels = screenHeightPixels * portraitHeightScreenFraction * drawScale
            val portraitWidthPixels = portraitHeightPixels * portraitAspectRatio

            val modelMatrix = FloatArray(16)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, drawX, drawY, 0f)
            Matrix.rotateM(modelMatrix, 0, drawRotation, 0f, 0f, 1f)
            Matrix.scaleM(modelMatrix, 0, portraitWidthPixels / 2f, portraitHeightPixels / 2f, 1f)

            val mvpMatrix = FloatArray(16)
            Matrix.multiplyMM(mvpMatrix, 0, orthographicMatrix, 0, modelMatrix, 0)

            bindQuadAttributes(buffer)

            GLES30.glUniformMatrix4fv(uniformPortraitMvp, 1, false, mvpMatrix, 0)
            GLES30.glUniform2f(uniformPortraitOffset, 0f, 0f)
            GLES30.glUniform1f(uniformPortraitRotation, 0f)
            GLES30.glUniform2f(uniformPortraitScale, 1f, 1f)
            GLES30.glUniform2f(uniformPortraitParallax, 0f, 0f)
            GLES30.glUniform1f(uniformPortraitAlpha, 1f)
            GLES30.glUniform4f(uniformPortraitShadowColor, 0f, 0f, 0f, 0.15f)
            GLES30.glUniform2f(uniformPortraitShadowOffset, 0.01f, -0.01f)
            GLES30.glUniform1f(uniformPortraitCornerRadius, 0.15f)

            // 立绘特效
            GLES30.glUniform1i(uniformPortraitEffect, portraitEffect)
            GLES30.glUniform1f(uniformPortraitTime, swayTimeSeconds)
            GLES30.glUniform2f(
                uniformPortraitViewAngle,
                smoothedPitchY.coerceIn(-0.5f, 0.5f),
                smoothedRollX.coerceIn(-0.5f, 0.5f),
            )

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, state.textureId)
            GLES30.glUniform1i(uniformPortraitTexture, 0)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            unbindQuadAttributes()
        }
    }

    // ==================================================================
    // 布局计算
    // ==================================================================

    /**
     * 根据当前锁屏布局计算立绘位置。
     * 布局随机选择，取决于横竖屏。
     */
    private fun calculateLockedPosition(
        index: Int,
        totalCount: Int,
    ): Pair<Float, Float> {
        if (!layoutSelected && portraitStates.isNotEmpty()) selectRandomLayout()

        val rows = currentLockLayout.rows
        // 确定立绘在哪一行、哪一列
        var remaining = index
        var targetRow = 0
        var colInRow = 0
        for ((rowIdx, count) in rows.withIndex()) {
            if (remaining < count) {
                targetRow = rowIdx
                colInRow = remaining
                break
            }
            remaining -= count
        }

        val totalRows = rows.size
        val colsInRow = rows[targetRow]

        // 居中该行
        val rowWidth = (colsInRow - 1) * 0.1f
        val startX = 0.5f - rowWidth / 2f

        val xRatio = (startX + colInRow * 0.1f).coerceIn(0.05f, 0.95f)
        val yRatio =
            (0.2f + targetRow * (0.6f / (totalRows - 1).coerceAtLeast(1)))
                .coerceIn(0.1f, 0.9f)

        return Pair(xRatio, yRatio)
    }

    private fun calculateUnlockedY(
        sideIndex: Int,
        sideCount: Int,
    ): Float {
        val yStart = 0.12f
        val yEnd = 0.88f
        val yStep = (yEnd - yStart) / (sideCount - 1).coerceAtLeast(1)
        return (yStart + sideIndex * yStep).coerceIn(0.1f, 0.9f)
    }

    // ==================================================================
    // Placement resolution (explicit vs built-in layout)
    // ==================================================================

    /**
     * Computes [x ratio, y ratio, scale, rotation] for a portrait at the
     * current transition progress. An explicit position (from template.json)
     * wins; otherwise the built-in layout applies.
     */
    private fun computePlacement(
        state: PortraitState,
        globalIndex: Int,
        totalCount: Int,
        isOnLeftSide: Boolean,
        sideIndex: Int,
        sideCount: Int,
        transitionT: Float,
    ): FloatArray {
        val legacyLockedXY = calculateLockedPosition(globalIndex, totalCount)
        val legacyLocked =
            floatArrayOf(
                legacyLockedXY.first,
                legacyLockedXY.second,
                LEGACY_LOCKED_SCALE,
                legacyLockedRotation(globalIndex, totalCount),
            )
        val legacyUnlocked =
            floatArrayOf(
                if (isOnLeftSide) 0.05f else 0.95f,
                calculateUnlockedY(sideIndex, sideCount),
                LEGACY_UNLOCKED_SCALE,
                legacyUnlockedRotation(isOnLeftSide, sideIndex),
            )
        val locked = applyEditorAdjustments(placementValues(state.lockedPosition, legacyLocked), state)
        val unlocked = applyEditorAdjustments(placementValues(state.unlockedPosition, legacyUnlocked), state)
        return interpolatePlacement(locked, unlocked, transitionT)
    }

    /** Applies the editor's temporary scale/rotation on top of the resolved placement. */
    private fun applyEditorAdjustments(
        placement: FloatArray,
        state: PortraitState,
    ): FloatArray =
        floatArrayOf(
            placement[0],
            placement[1],
            placement[2] * state.extraScale,
            placement[3] + state.extraRotation,
        )

    private fun legacyLockedRotation(
        globalIndex: Int,
        totalCount: Int,
    ): Float = (globalIndex - totalCount / 2) * 2.5f

    private fun legacyUnlockedRotation(
        isOnLeftSide: Boolean,
        sideIndex: Int,
    ): Float = if (isOnLeftSide) -7.2f + sideIndex * 1.8f else 7.2f - sideIndex * 1.8f

    /**
     * 计算立绘的晃动偏移（带随机元素，始终生效）。
     * 只有上下浮动，没有水平偏移。
     */
    private fun calculateSway(portraitIndex: Int): FloatArray {
        val seed = portraitIndex * 2654435761L
        val amplitudeVariation = ((seed and 0xFF) % 40 - 20) / 100f
        val frequencyVariation = ((seed shr 8 and 0xFF) % 30 - 15) / 100f
        val phaseOffset = (seed shr 16 and 0xFF) / 255f * 3.14f

        val portraitHeight = screenHeightPixels * portraitHeightScreenFraction * 0.85f
        val maxAmplitude = portraitHeight * 0.08f
        val amplitude = maxAmplitude * (1f + amplitudeVariation)
        val frequency = 0.8f * (1f + frequencyVariation)

        val offsetY = sin(swayTimeSeconds * frequency + phaseOffset) * amplitude

        return floatArrayOf(0f, offsetY)
    }

    // ==================================================================
    // GL 辅助
    // ==================================================================

    private fun bindQuadAttributes(buffer: FloatBuffer) {
        buffer.position(0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, Quad.getStride(), buffer)
        buffer.position(Quad.getUvOffset() / 4)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, Quad.getStride(), buffer)
    }

    private fun unbindQuadAttributes() {
        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    fun release() {
        portraitStates.forEach { TextureLoader.deleteTexture(it.textureId) }
        portraitStates.clear()
        if (wallpaperTextureId != 0) TextureLoader.deleteTexture(wallpaperTextureId)
        if (portraitProgram != 0) GLES30.glDeleteProgram(portraitProgram)
        if (backgroundProgram != 0) GLES30.glDeleteProgram(backgroundProgram)
    }

    // ==================================================================
    // 触控
    // ==================================================================

    fun onTouchDown(
        touchX: Float,
        touchY: Float,
    ): Boolean {
        val sortedByZ = portraitStates.sortedByDescending { it.drawOrder }
        for (state in sortedByZ) {
            val bounds = getPortraitBounds(state)
            if (touchX >= bounds[0] &&
                touchX <= bounds[2] &&
                touchY >= bounds[1] &&
                touchY <= bounds[3]
            ) {
                draggedPortraitIndex = portraitStates.indexOf(state)
                selectedPortraitId = state.id
                val maxOrder = portraitStates.maxOf { it.drawOrder }
                state.drawOrder = maxOrder + 1
                previousTouchX = touchX
                previousTouchY = touchY
                return true
            }
        }
        return false
    }

    fun onTouchMove(
        touchX: Float,
        touchY: Float,
    ) {
        if (draggedPortraitIndex < 0 || draggedPortraitIndex >= portraitStates.size) return
        val state = portraitStates[draggedPortraitIndex]
        val deltaX = touchX - previousTouchX
        val deltaY = touchY - previousTouchY
        state.offsetX += deltaX
        state.offsetY += deltaY
        state.velocityX = deltaX
        state.velocityY = deltaY
        previousTouchX = touchX
        previousTouchY = touchY
    }

    fun onTouchUp() {
        draggedPortraitIndex = -1
    }

    /** Reset all portrait drag offsets and velocities to their default positions. */
    fun resetDragOffsets() {
        portraitStates.forEach { state ->
            state.offsetX = 0f
            state.offsetY = 0f
            state.velocityX = 0f
            state.velocityY = 0f
        }
        draggedPortraitIndex = -1
    }

    /** Whether a portrait is currently being dragged (used at drag end to decide whether to persist). */
    fun isPortraitDragged(): Boolean = draggedPortraitIndex >= 0

    /**
     * Cumulative user offsets per portrait (saved base + current drag),
     * normalized by screen width/height. Keyed by the image file name for
     * persisting into the template's template.json.
     */
    fun normalizedPortraitOffsets(): Map<String, Pair<Float, Float>> =
        portraitStates.associate { state ->
            state.file to
                Pair(
                    (state.baseOffsetX + state.offsetX) / screenWidthPixels,
                    (state.baseOffsetY + state.offsetY) / screenHeightPixels,
                )
        }

    /** Call after a successful persist: folds the drag offsets into base and zeroes them to avoid double counting. */
    fun commitDragOffsets() {
        portraitStates.forEach { state ->
            state.baseOffsetX += state.offsetX
            state.baseOffsetY += state.offsetY
            state.offsetX = 0f
            state.offsetY = 0f
            state.velocityX = 0f
            state.velocityY = 0f
        }
    }

    // ==================================================================
    // Layout editor support
    // ==================================================================

    /** Id of the last portrait hit by a touch-down (null when none/not loaded); used by the editor for selection. */
    @Volatile
    var selectedPortraitId: String? = null
        private set

    /** Editor: sets a portrait's temporary scale/rotation adjustment (applied on top of the explicit or built-in placement). */
    fun setPortraitAdjust(
        portraitId: String,
        extraScale: Float,
        extraRotation: Float,
    ) {
        val state = portraitStates.find { it.id == portraitId } ?: return
        state.extraScale = extraScale.coerceIn(0.3f, 3f)
        state.extraRotation = extraRotation.coerceIn(-45f, 45f)
    }

    /** Editor: switches the previewed locked/unlocked state (reuses the lock/unlock transition animation). */
    fun setPreviewLocked(locked: Boolean) {
        isWaitingForUnlock = false
        targetTransition = if (locked) 1f else 0f
    }

    /** Image file names of all loaded portraits; used by the editor to build reset maps. */
    fun portraitFiles(): List<String> = portraitStates.map { it.file }

    /**
     * Exports every portrait's placement for the editor to save.
     *
     * The previewed state ([previewLocked]) folds its base/drag offsets and
     * editor adjustments into final screen-ratio coordinates; the other state
     * keeps its existing explicit position (null = built-in layout), so
     * editing one side never silently changes the other. Keyed by image file
     * name.
     */
    fun snapshotPortraitPositions(previewLocked: Boolean): Map<String, Pair<PortraitPosition?, PortraitPosition?>> {
        val totalCount = portraitStates.size
        val leftSideCount = (totalCount + 1) / 2
        return portraitStates
            .mapIndexed { globalIndex, state ->
                val isOnLeftSide = globalIndex < leftSideCount
                val sideIndex = if (isOnLeftSide) globalIndex else globalIndex - leftSideCount
                val sideCount = if (isOnLeftSide) leftSideCount else totalCount - leftSideCount

                val previewT = if (previewLocked) 1f else 0f
                val previewed =
                    computePlacement(state, globalIndex, totalCount, isOnLeftSide, sideIndex, sideCount, previewT)
                val offsetXFrac = (state.baseOffsetX + state.offsetX) / screenWidthPixels
                val offsetYFrac = (state.baseOffsetY + state.offsetY) / screenHeightPixels
                val previewTilt =
                    (if (previewLocked) state.lockedPosition else state.unlockedPosition)?.tiltOffset ?: 1f
                val previewedPosition =
                    PortraitPosition(
                        xRatio = previewed[0] + offsetXFrac,
                        yRatio = previewed[1] + offsetYFrac,
                        rotation = previewed[3],
                        scale = previewed[2],
                        tiltOffset = previewTilt,
                    )
                val otherPosition = if (previewLocked) state.unlockedPosition else state.lockedPosition

                state.file to
                    if (previewLocked) {
                        Pair(previewedPosition, otherPosition)
                    } else {
                        Pair(otherPosition, previewedPosition)
                    }
            }
            .toMap()
    }

    fun onDoubleTap(
        touchX: Float,
        touchY: Float,
    ): Boolean {
        val sortedByZ = portraitStates.sortedByDescending { it.drawOrder }
        for (state in sortedByZ) {
            val bounds = getPortraitBounds(state)
            if (touchX >= bounds[0] &&
                touchX <= bounds[2] &&
                touchY >= bounds[1] &&
                touchY <= bounds[3]
            ) {
                state.offsetX = 0f
                state.offsetY = 0f
                state.velocityX = 0f
                state.velocityY = 0f
                return true
            }
        }
        return false
    }

    /**
     * 触发解锁动画（带 0.1 秒延迟）
     */
    fun triggerUnlock() {
        isWaitingForUnlock = true
        unlockDelayTimer = 0f
    }

    /**
     * 触发锁屏（立即）
     */
    fun triggerLock() {
        isWaitingForUnlock = false
        targetTransition = 1f
        selectRandomLayout() // 重新随机布局
    }

    private fun getPortraitBounds(state: PortraitState): FloatArray {
        val totalCount = portraitStates.size
        val leftSideCount = (totalCount + 1) / 2
        val globalIndex = portraitStates.indexOf(state)
        val isOnLeftSide = globalIndex < leftSideCount
        val sideIndex = if (isOnLeftSide) globalIndex else globalIndex - leftSideCount
        val sideCount = if (isOnLeftSide) leftSideCount else totalCount - leftSideCount

        val t = transitionProgress

        val placement =
            computePlacement(
                state,
                globalIndex,
                totalCount,
                isOnLeftSide,
                sideIndex,
                sideCount,
                t,
            )

        val centerX = placement[0] * screenWidthPixels + state.baseOffsetX + state.offsetX
        val centerY = placement[1] * screenHeightPixels + state.baseOffsetY + state.offsetY
        val scale = placement[2]

        val height = screenHeightPixels * portraitHeightScreenFraction * scale
        val width = height * portraitAspectRatio

        return floatArrayOf(
            centerX - width / 2,
            centerY - height / 2,
            centerX + width / 2,
            centerY + height / 2,
        )
    }

    private fun lerp(
        a: Float,
        b: Float,
        t: Float,
    ): Float = a + (b - a) * t.coerceIn(0f, 1f)

    companion object {
        // Parallax strength coefficients: sensor value × sensitivity × screen size × coefficient = offset pixels
        internal const val BACKGROUND_PARALLAX_X = 0.1f
        internal const val BACKGROUND_PARALLAX_Y = 0.075f
        internal const val PORTRAIT_PARALLAX_X = 0.25f
        internal const val PORTRAIT_PARALLAX_Y = 0.2f

        // Built-in layout parameters when no explicit position exists (identical to historical behavior)
        internal const val LEGACY_LOCKED_SCALE = 0.85f
        internal const val LEGACY_UNLOCKED_SCALE = 0.64f

        /**
         * Resolves a placement: the explicit position wins, otherwise the
         * built-in layout values apply. [legacy] is [x, y, scale, rotation].
         */
        internal fun placementValues(
            position: PortraitPosition?,
            legacy: FloatArray,
        ): FloatArray =
            if (position != null) {
                floatArrayOf(position.xRatio, position.yRatio, position.scale, position.rotation)
            } else {
                legacy
            }

        /** Interpolates between the locked/unlocked placements by transition progress. */
        internal fun interpolatePlacement(
            locked: FloatArray,
            unlocked: FloatArray,
            t: Float,
        ): FloatArray {
            val tc = t.coerceIn(0f, 1f)
            return floatArrayOf(
                unlocked[0] + (locked[0] - unlocked[0]) * tc,
                unlocked[1] + (locked[1] - unlocked[1]) * tc,
                unlocked[2] + (locked[2] - unlocked[2]) * tc,
                unlocked[3] + (locked[3] - unlocked[3]) * tc,
            )
        }

        // Upper bound for the user background zoom; the pan exists to fix
        // aspect-ratio framing, not to create a zoom effect
        internal const val MAX_BACKGROUND_ZOOM = 2f

        /**
         * Computes the parallax offset in pixels: the sensor value is first
         * clamped to ±0.5 (so extreme tilts cannot produce huge offsets), then
         * scaled by the user sensitivity and the screen-size ratios. Extracted
         * as a pure function for unit testing.
         */
        internal fun parallaxOffsets(
            pitch: Float,
            roll: Float,
            screenWidth: Float,
            screenHeight: Float,
            xMultiplier: Float,
            yMultiplier: Float,
            sensitivity: Float,
        ): FloatArray {
            val clampedPitch = pitch.coerceIn(-0.5f, 0.5f) * sensitivity
            val clampedRoll = roll.coerceIn(-0.5f, 0.5f) * sensitivity
            return floatArrayOf(
                clampedPitch * screenWidth * xMultiplier,
                clampedRoll * screenHeight * yMultiplier,
            )
        }

        /**
         * Computes the final background draw dimensions for a user zoom.
         * The zoom is clamped to [1, MAX_BACKGROUND_ZOOM]; the input dimensions
         * already include the parallax overscan, so the result can never expose
         * screen edges. Pure function for unit testing.
         *
         * @return [drawWidth, drawHeight] after zoom
         */
        internal fun backgroundGeometry(
            drawWidth: Float,
            drawHeight: Float,
            zoom: Float,
        ): FloatArray {
            val safeZoom = zoom.coerceIn(1f, MAX_BACKGROUND_ZOOM)
            return floatArrayOf(drawWidth * safeZoom, drawHeight * safeZoom)
        }
    }
}
