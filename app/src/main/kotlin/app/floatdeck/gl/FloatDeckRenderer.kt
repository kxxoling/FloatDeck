package app.floatdeck.gl

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.opengl.GLES30
import android.opengl.Matrix
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
 */
data class PortraitState(
    val id: String,
    val label: String,
    var textureId: Int = 0,
    var textureWidth: Int = 0,
    var textureHeight: Int = 0,
    var drawOrder: Int = 0,
    var offsetX: Float = 0f,
    var offsetY: Float = 0f,
    var velocityX: Float = 0f,
    var velocityY: Float = 0f,
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
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

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
                        label = config.label,
                        textureId = result.first,
                        textureWidth = result.second,
                        textureHeight = result.third,
                        drawOrder = index,
                    ),
                )
            } else {
                portraitStates.add(
                    PortraitState(
                        id = config.id,
                        label = config.label,
                        textureId =
                            TextureLoader.createPlaceholderTexture(
                                config.label,
                                bgColor = placeholderColors[index % placeholderColors.size],
                            ),
                        textureWidth = 128,
                        textureHeight = 256,
                        drawOrder = index,
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
        // 视差方向：前后倾平板 → 水平偏移，左右倾平板 → 垂直偏移
        // 钳制传感器值，防止大角度倾斜导致过大偏移
        val maxShift = 0.5f // 传感器值最大有效范围
        val clampedPitch = smoothedPitchY.coerceIn(-maxShift, maxShift)
        val clampedRoll = smoothedRollX.coerceIn(-maxShift, maxShift)
        val parallaxX = clampedPitch * screenWidthPixels * 0.1f
        val parallaxY = clampedRoll * screenHeightPixels * 0.075f
        drawSingleBackgroundLayer(parallaxX, parallaxY, 1.0f)
    }

    private fun drawSingleBackgroundLayer(
        parallaxX: Float,
        parallaxY: Float,
        alpha: Float,
    ) {
        GLES30.glUseProgram(backgroundProgram)
        val buffer = quadVertexBuffer ?: return
        bindQuadAttributes(buffer)

        // 视差导致的最大偏移量
        val maxParallaxOffset =
            maxOf(
                abs(smoothedPitchY) * screenWidthPixels * 0.1f,
                abs(smoothedRollX) * screenHeightPixels * 0.075f,
            )
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
        Matrix.scaleM(model, 0, drawWidth / 2f, drawHeight / 2f, 1f)
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

            // ---- 位置计算 ----

            // 锁屏位置（使用当前布局）
            val (lockedCenterX, lockedCenterY) = calculateLockedPosition(globalIndex, totalCount)
            val lockedScale = 0.85f
            val lockedRotation = (globalIndex - totalCount / 2) * 2.5f

            // 解锁位置（两侧边缘）
            val unlockedEdgeX = if (isOnLeftSide) 0.05f else 0.95f
            val unlockedEdgeY = calculateUnlockedY(sideIndex, sideCount)
            val unlockedScale = 0.64f
            val unlockedRotation =
                if (isOnLeftSide) -7.2f + sideIndex * 1.8f else 7.2f - sideIndex * 1.8f

            // ---- 插值 ----

            var drawX = lerp(unlockedEdgeX, lockedCenterX, transitionT) * screenWidthPixels
            var drawY = lerp(unlockedEdgeY, lockedCenterY, transitionT) * screenHeightPixels
            val drawScale = lerp(unlockedScale, lockedScale, transitionT)
            val drawRotation = lerp(unlockedRotation, lockedRotation, transitionT)

            // ---- 陀螺仪视差（始终生效）----
            // 轻微的视差偏移，立绘也有但比壁纸弱
            val portraitParallaxX = smoothedPitchY.coerceIn(-0.5f, 0.5f) * screenWidthPixels * 0.25f
            val portraitParallaxY = smoothedRollX.coerceIn(-0.5f, 0.5f) * screenHeightPixels * 0.2f
            drawX += portraitParallaxX
            drawY += portraitParallaxY

            // ---- 晃动动画（始终生效）----
            val sway = calculateSway(globalIndex)
            drawX += sway[0]
            drawY += sway[1]

            // 用户拖拽偏移
            drawX += state.offsetX
            drawY += state.offsetY

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

        val (lockedX, lockedY) = calculateLockedPosition(globalIndex, totalCount)
        val lockedScale = 0.85f

        val unlockedX = if (isOnLeftSide) 0.05f else 0.95f
        val unlockedY = calculateUnlockedY(sideIndex, sideCount)
        val unlockedScale = 0.64f

        val centerX = lerp(unlockedX, lockedX, t) * screenWidthPixels + state.offsetX
        val centerY = lerp(unlockedY, lockedY, t) * screenHeightPixels + state.offsetY
        val scale = lerp(unlockedScale, lockedScale, t)

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
}
