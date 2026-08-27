package app.floatdeck.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import app.floatdeck.data.RemoteTemplateLoader
import app.floatdeck.data.TemplateDef
import app.floatdeck.data.Templates
import app.floatdeck.gl.FloatDeckRenderer
import app.floatdeck.sensor.SensorHandler
import java.io.File
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay

private const val TAG = "FloatDeckWallpaper"
private const val PREFS_NAME = "settings_prefs"
private const val KEY_TEMPLATE_ID = "template_id"
private const val KEY_PORTRAIT_EFFECT = "portrait_effect"
private const val KEY_DRAG_ENABLED = "drag_enabled"
private const val KEY_FRAME_RATE_MODE = "frame_rate_mode"

/**
 * FloatDeck 动态壁纸服务入口。
 *
 * 管理 EGL 上下文、渲染线程、传感器监听和锁屏状态广播。
 */
class FloatDeckWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = FloatDeckEngine()

    inner class FloatDeckEngine : Engine() {
        private val renderer = FloatDeckRenderer(applicationContext)
        private val sensorHandler = SensorHandler(applicationContext)

        private var glThread: GLWallpaperThread? = null

        /**
         * 监听设置变更（模板/特效），通知运行中的 GL 线程热重载。
         * 使用强引用字段，避免 SharedPreferences 内部弱引用导致被 GC。
         */
        private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                KEY_TEMPLATE_ID, KEY_PORTRAIT_EFFECT -> glThread?.requestReload()
                KEY_DRAG_ENABLED -> {
                    val enabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(KEY_DRAG_ENABLED, true)
                    if (!enabled) renderer.resetDragOffsets()
                }
                KEY_FRAME_RATE_MODE -> {
                    val mode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_FRAME_RATE_MODE, "auto") ?: "auto"
                    glThread?.updateFrameRatePreference(mode)
                }
            }
        }

        /** 监听锁屏/解锁广播，驱动渲染器的锁定-解锁过渡动画。 */
        private val lockReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    when (intent?.action) {
                        Intent.ACTION_SCREEN_OFF -> {
                            renderer.triggerLock()
                        }
                        Intent.ACTION_SCREEN_ON -> {
                            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
                            if (!km.isDeviceLocked) {
                                renderer.triggerUnlock()
                            }
                        }
                        Intent.ACTION_USER_PRESENT -> {
                            renderer.triggerUnlock()
                        }
                    }
                }
            }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)

            // 根据当前锁屏状态初始化过渡进度（避免从默认状态跳变）
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            renderer.targetTransition = if (km.isDeviceLocked) 1f else 0f
            renderer.transitionProgress = renderer.targetTransition

            val filter =
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                }
            // Android 14+ requires an explicit exported flag, otherwise SecurityException.
            // Use EXPORTED: ACTION_USER_PRESENT is sent by a non-system_server process
            // on some OEM ROMs (e.g. ASUS ZenUI) and would be blocked by NOT_EXPORTED.
            ContextCompat.registerReceiver(
                this@FloatDeckWallpaperService,
                lockReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
            // 热重载：监听设置变化
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .registerOnSharedPreferenceChangeListener(prefsListener)
            sensorHandler.register()
        }

        override fun onDestroy() {
            unregisterReceiver(lockReceiver)
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(prefsListener)
            sensorHandler.unregister()
            // GL 资源由 GL 线程在自己的 finally 中释放（需要 current context），
            // stopGLThread 会 join 等待其完成。
            stopGLThread()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                sensorHandler.register()
                startGLThread()
                glThread?.setRendering(true)
            } else {
                sensorHandler.unregister()
                // Keep the GL thread alive: preserves EGL context and textures,
                // only pauses rendering. updateTransition() still runs so the
                // lock/unlock lerp completes while the screen is off, avoiding
                // the template-reload stutter on screen-on.
                glThread?.setRendering(false)
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            // Surface recreated (rotation, wallpaper host change, etc.):
            // notify the GL thread to rebuild the EGL window surface.
            // The EGL context is kept, so textures/shaders survive.
            glThread?.notifySurfaceRecreated()
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            // Do not call renderer.onSurfaceChanged directly here:
            // glViewport is a GL call while the EGL context is current on
            // the GL thread, so it would be silently dropped on the UI
            // thread. The viewport would then keep the old orientation
            // after rotation and the scene would only cover a corner of
            // the screen. Forward to the GL thread instead.
            glThread?.notifySurfaceChanged(width, height)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            // Surface is about to become invalid; the GL thread must
            // release the EGL window surface promptly (context is kept)
            glThread?.notifySurfaceDestroyed()
        }

        override fun onTouchEvent(event: MotionEvent) {
            val dragEnabled = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DRAG_ENABLED, true)
            if (dragEnabled) {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        renderer.onTouchDown(event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        renderer.onTouchMove(event.x, event.y)
                    }
                    MotionEvent.ACTION_UP -> {
                        renderer.onTouchUp()
                    }
                }
            }
            super.onTouchEvent(event)
        }

        private fun startGLThread() {
            if (glThread?.isRunning == true) return
            stopGLThread()
            glThread =
                GLWallpaperThread(
                    surfaceHolder,
                    renderer,
                    sensorHandler,
                    applicationContext,
                ).apply { start() }
        }

        private fun stopGLThread() {
            glThread?.let { thread ->
                thread.requestStop()
                try {
                    // 等待渲染循环退出，确保 EGL/renderer 资源在该线程内清理完毕
                    thread.join(STOP_JOIN_TIMEOUT_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                if (thread.isAlive) {
                    Log.w(TAG, "GL thread did not stop in time, interrupting")
                    thread.interrupt()
                }
            }
            glThread = null
        }
    }
}

private const val STOP_JOIN_TIMEOUT_MS = 1000L

/**
 * GL 渲染线程：在壁纸 Surface 上自建 EGL 环境，以 ~60fps 循环调用渲染器。
 *
 * 壁纸服务没有 GLSurfaceView，需要手动管理 EGL 初始化、销毁和缓冲区交换。
 */
class GLWallpaperThread(
    private val surfaceHolder: SurfaceHolder,
    private val renderer: FloatDeckRenderer,
    private val sensorHandler: SensorHandler,
    private val context: android.content.Context,
) : Thread("GLWallpaperThread") {
    @Volatile
    var isRunning = true
        private set

    /** Whether to perform GPU rendering + swap. Set to false when the screen is off; only updateTransition() runs. */
    @Volatile
    private var rendering = true

    /** 由外部（设置变更）置位，渲染循环检测到后重新加载模板/特效。 */
    @Volatile
    private var reloadRequested = true

    // ------------------------------------------------------------------
    // Surface lifecycle (notified by the Engine thread, handled here):
    // On rotation the Surface is typically resized in place - the EGL
    // window surface follows the native window size, so only the
    // viewport/projection need updating on this thread (where the context
    // is current). The EGL surface is rebuilt only when the Surface is
    // actually destroyed and recreated (a brand-new native window);
    // rebuilding it on a plain resize would briefly expose an empty
    // buffer (white flash).
    // ------------------------------------------------------------------

    /** A new size is pending; apply viewport/projection when convenient. */
    @Volatile
    private var sizeDirty = false

    /** The native Surface was destroyed; the EGL window surface must be released. */
    @Volatile
    private var surfaceLost = false

    /** A fresh native Surface exists; the EGL window surface must be rebuilt. */
    @Volatile
    private var eglRebuildNeeded = false

    @Volatile
    private var pendingWidth = 0

    @Volatile
    private var pendingHeight = 0

    fun notifySurfaceRecreated() {
        eglRebuildNeeded = true
    }

    fun notifySurfaceChanged(
        width: Int,
        height: Int,
    ) {
        pendingWidth = width
        pendingHeight = height
        sizeDirty = true
    }

    fun notifySurfaceDestroyed() {
        surfaceLost = true
    }

    /** Whether the template has been loaded. Stays true across visibility changes since the GL thread is no longer recreated. */
    private var templateLoaded = false

    /** 标记 EGL/渲染器资源已就绪，finally 中据此决定是否调用 renderer.release()。 */
    private var glReady = false

    // ------------------------------------------------------------------
    // Display pacing state (polled on this thread ~every 0.5s)
    // ------------------------------------------------------------------

    /** Default display: source of the refresh rate; also votes for high refresh. */
    private val display: android.view.Display? by lazy {
        (context.getSystemService(android.hardware.display.DisplayManager::class.java))
            .getDisplay(android.view.Display.DEFAULT_DISPLAY)
    }

    /** Frame interval matching the display refresh rate; 60fps until first poll. */
    @Volatile
    private var fullFrameIntervalNanos = FRAME_INTERVAL_NANOS

    private var frameCounter = 0

    /** Last refresh rate voted for via Surface.setFrameRate; -1 = none yet. */
    private var lastRequestedRefreshFps = -1f

    /** Frame rate mode from settings: auto | half | third | quarter (of max refresh). */
    @Volatile
    private var preferredFrameRateMode = "auto"

    fun requestStop() {
        isRunning = false
    }

    fun setRendering(enabled: Boolean) {
        rendering = enabled
        // Wake the loop promptly when becoming visible again: it may be in
        // a long screen-off sleep.
        if (enabled) interrupt()
    }

    fun requestReload() {
        reloadRequested = true
    }

    /** Applies a frame-rate mode from settings (auto | half | third | quarter) on the next poll. */
    fun updateFrameRatePreference(mode: String) {
        preferredFrameRateMode = mode
        // Nudge the counter so the next frame triggers an immediate poll
        frameCounter = DISPLAY_POLL_INTERVAL_FRAMES - 1
    }

    override fun run() {
        if (!initEGL()) {
            Log.e(TAG, "EGL initialization failed")
            return
        }
        glReady = true

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var frameStart: Long
        try {
            renderer.onSurfaceCreated(null, null)
            // Initial size: the engine's onSurfaceChanged may have fired
            // before this thread started (notifications dropped), so fall
            // back to the holder's current frame to keep the viewport and
            // projection correct from the very first frame
            val frame = surfaceHolder.surfaceFrame
            renderer.onSurfaceChanged(null, frame.width(), frame.height())
            while (isRunning) {
                frameStart = System.nanoTime()

                handleSurfaceChanges()

                if (rendering && eglSurface != null) {
                    // Refresh display pacing state (refresh rate,
                    // high-refresh vote) every ~0.5s; per-frame queries
                    // are not free.
                    frameCounter++
                    if (frameCounter % DISPLAY_POLL_INTERVAL_FRAMES == 0) {
                        pollDisplayState()
                    }

                    // 平滑插值传感器值（低通滤波，系数 0.08）
                    renderer.smoothedRollX +=
                        (sensorHandler.rollX - renderer.smoothedRollX) * 0.08f
                    renderer.smoothedPitchY +=
                        (sensorHandler.pitchY - renderer.smoothedPitchY) * 0.08f

                    // 首帧或设置变更后重新加载模板/特效
                    if (!templateLoaded || reloadRequested) {
                        loadConfigFromPrefs(prefs)
                        templateLoaded = true
                        reloadRequested = false
                    }

                    renderer.onDrawFrame(null)

                    egl10?.eglSwapBuffers(eglDisplay, eglSurface)
                } else if (!rendering) {
                    // Screen off: advance transition state only, no GPU
                    // rendering. Nobody sees intermediate frames while the
                    // screen is off, so advance with coarse sleeps instead
                    // of the render pace, and settle into a ~1Hz poll once
                    // the transition is done - otherwise the loop keeps
                    // waking 60x/s for nothing all night. A wake nudge from
                    // setRendering interrupts the sleep so screen-on still
                    // renders immediately.
                    renderer.updateFrameDelta()
                    renderer.updateTransition()
                    val settled =
                        kotlin.math.abs(renderer.targetTransition - renderer.transitionProgress) < 0.005f
                    sleepQuietly(
                        if (settled) SCREEN_OFF_SETTLED_SLEEP_MS else SCREEN_OFF_ACTIVE_SLEEP_MS,
                    )
                }
                // While eglSurface is not rebuilt yet (surfaceLost/dirty
                // pending), just wait
                paceFrame(frameStart)
            }
        } catch (e: InterruptedException) {
            // 关闭时被中断，正常退出
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // 记录渲染循环异常，避免静默吞掉（Surface 销毁时也走此分支）
            if (isRunning) {
                Log.e(TAG, "Render loop terminated unexpectedly", e)
            } else {
                Log.d(TAG, "Render loop exited: ${e.message}")
            }
        } finally {
            if (glReady) {
                // 必须在 EGL context 仍为 current 时释放 GL 资源
                runCatching { renderer.release() }
                    .onFailure { Log.w(TAG, "renderer.release() failed", it) }
            }
            destroyEGL()
        }
    }

    /** Refreshes pacing state from the display. */
    private fun pollDisplayState() {
        val d = display ?: return
        val refresh = d.refreshRate.toInt().coerceIn(30, 240)
        val maxHz = d.supportedModes.maxOfOrNull { it.refreshRate.toInt() } ?: refresh
        val preferred = when (preferredFrameRateMode) {
            "half" -> maxHz / 2
            "third" -> maxHz / 3
            "quarter" -> maxHz / 4
            else -> -1
        }
        // Pace at the preferred rate but never faster than the panel currently
        // refreshes; the vote below asks the panel to move toward it. Fraction
        // rates divide the max refresh evenly, so even a panel still running
        // at max presents each pace tick at a whole number of vblanks.
        val paceFps = if (preferred > 0) minOf(preferred, refresh) else refresh
        fullFrameIntervalNanos = 1_000_000_000L / paceFps
        requestHighRefreshRate(d, preferred.takeIf { it > 0 })
    }

    /**
     * Votes for the panel's highest supported refresh rate via
     * Surface.setFrameRate. Without a vote the compositor keeps the panel at
     * its default mode (60Hz on the test device) even when higher modes are
     * available; the vote lets the panel ramp up while the wallpaper renders.
     */
    private fun requestHighRefreshRate(
        d: android.view.Display,
        preferredFps: Int?,
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        val desired =
            preferredFps?.toFloat()
                ?: d.supportedModes.maxOfOrNull { it.refreshRate }
                ?: d.refreshRate
        if (desired == lastRequestedRefreshFps) return
        val surface = surfaceHolder.surface ?: return
        runCatching {
            surface.setFrameRate(
                desired,
                android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
            )
            lastRequestedRefreshFps = desired
        }.onFailure { Log.w(TAG, "setFrameRate($desired) failed", it) }
    }

    /** 根据本帧已耗时计算剩余时间睡眠，接近目标帧率且不浪费 CPU。 */
    private fun paceFrame(frameStartNanos: Long) {
        val elapsed = System.nanoTime() - frameStartNanos
        val interval = fullFrameIntervalNanos
        // Scale the present margin with the interval so high refresh rates
        // still leave room for draw + submission.
        val margin =
            (interval / 3).coerceIn(MIN_PRESENT_MARGIN_NANOS, MAX_PRESENT_MARGIN_NANOS)
        // The coarse screen-off sleeps make elapsed exceed the interval, so
        // this naturally becomes a no-op right after a screen-off sleep.
        sleepQuietly(sleepMillisForFrame(elapsed, interval, margin))
    }

    /**
     * Sleeps, swallowing interrupts: the only interrupt source is our own
     * wake-up nudge from setRendering, and a stale interrupt flag would make
     * every subsequent sleep return immediately (busy loop).
     */
    private fun sleepQuietly(millis: Long) {
        if (millis <= 0) return
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
        }
    }

    /** Handles surface lifecycle changes: release/rebuild on loss, viewport-only update on resize. */
    private fun handleSurfaceChanges() {
        if (surfaceLost) {
            releaseEglSurface()
            surfaceLost = false
        }
        if ((eglSurface == null || eglRebuildNeeded) && surfaceHolder.surface?.isValid == true) {
            if (recreateEglSurface()) {
                eglRebuildNeeded = false
                sizeDirty = false
            }
            // Keep retrying next frame while the surface is not ready yet
            return
        }
        if (sizeDirty && surfaceHolder.surface?.isValid == true) {
            // In-place resize (typical rotation): the EGL window surface
            // follows the native window, so only the viewport/projection
            // need updating - no EGL rebuild, no white flash.
            applyPendingSize()
            sizeDirty = false
        }
    }

    /** Applies the pending surface size to the renderer (viewport + projection). */
    private fun applyPendingSize() {
        val width = pendingWidth
        val height = pendingHeight
        if (width > 0 && height > 0) {
            renderer.onSurfaceChanged(null, width, height)
        } else {
            val frame = surfaceHolder.surfaceFrame
            renderer.onSurfaceChanged(null, frame.width(), frame.height())
        }
    }

    /** Unbinds and destroys the EGL window surface; context and display are kept so GL resources survive. */
    private fun releaseEglSurface() {
        val egl = egl10 ?: return
        val display = eglDisplay ?: return
        runCatching {
            egl.eglMakeCurrent(
                display,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_SURFACE,
                EGL10.EGL_NO_CONTEXT,
            )
            eglSurface?.let { egl.eglDestroySurface(display, it) }
        }.onFailure { Log.w(TAG, "Failed releasing EGL surface", it) }
        eglSurface = null
    }

    /**
     * Rebuilds the EGL window surface from the holder's current Surface and
     * updates the viewport/projection. Returns false on failure (surface
     * not ready yet, etc.); the caller retries on a later frame.
     */
    private fun recreateEglSurface(): Boolean {
        val egl = egl10 ?: return false
        val display = eglDisplay ?: return false
        val config = eglConfig ?: return false
        val context = eglContext ?: return false
        val surface = surfaceHolder.surface ?: return false
        if (!surface.isValid) return false

        releaseEglSurface()
        val newSurface = egl.eglCreateWindowSurface(display, config, surface, null)
        if (newSurface == null || newSurface == EGL10.EGL_NO_SURFACE) {
            Log.e(TAG, "eglCreateWindowSurface failed: 0x${egl.eglGetError().toString(16)}")
            return false
        }
        eglSurface = newSurface
        if (!egl.eglMakeCurrent(display, newSurface, newSurface, context)) {
            Log.e(TAG, "eglMakeCurrent failed after recreation: 0x${egl.eglGetError().toString(16)}")
            return false
        }

        applyPendingSize()
        return true
    }

    /** 从设置读取模板 id 与特效并加载到渲染器。 */
    private fun loadConfigFromPrefs(prefs: SharedPreferences) {
        val savedId = prefs.getString(KEY_TEMPLATE_ID, "") ?: ""
        val effectStr = prefs.getString(KEY_PORTRAIT_EFFECT, "none") ?: "none"
        renderer.portraitEffect =
            when (effectStr) {
                "ice" -> 1
                "holo" -> 2
                else -> 0
            }
        preferredFrameRateMode = prefs.getString(KEY_FRAME_RATE_MODE, "auto") ?: "auto"

        // 尝试从远程模板加载，再从 assets 加载
        val remoteLoader = RemoteTemplateLoader(context)
        val remoteTemplateDir = File(context.filesDir, "remote_templates")
        val remoteDir = File(remoteTemplateDir, savedId)
        var def: TemplateDef? = null
        var isRemote = false
        if (remoteDir.isDirectory) {
            def = Templates.loadTemplateFromDir(remoteDir)
            isRemote = def != null
        }
        if (def == null) {
            def = Templates.loadTemplate(context, savedId)
        }
        if (def != null) {
            val rect = surfaceHolder.surfaceFrame
            val config =
                if (isRemote) {
                    Templates.toTemplateConfigFromDir(
                        def,
                        remoteDir,
                        rect.width().toFloat(),
                        rect.height().toFloat(),
                    )
                } else {
                    Templates.toTemplateConfig(
                        context,
                        def,
                        rect.width().toFloat(),
                        rect.height().toFloat(),
                    )
                }
            renderer.loadTemplate(config)
        }
    }

    /**
     * 初始化 EGL 环境：
     * 获取显示 → 初始化 → 选择配置（RGBA8888，OpenGL ES 3.0）→ 创建上下文和窗口表面。
     */
    private fun initEGL(): Boolean {
        val egl = EGLContext.getEGL() as EGL10
        egl10 = egl

        val display = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
        eglDisplay = display
        if (display == EGL10.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!egl.eglInitialize(display, version)) return false

        // 要求 OpenGL ES 3.0 可渲染，RGBA 各 8 位，无深度/模板
        val configAttribs =
            intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE,
                0x40, // EGL_OPENGL_ES2_BIT (也兼容 ES 3.0)
                EGL10.EGL_RED_SIZE,
                8,
                EGL10.EGL_GREEN_SIZE,
                8,
                EGL10.EGL_BLUE_SIZE,
                8,
                EGL10.EGL_ALPHA_SIZE,
                8,
                EGL10.EGL_DEPTH_SIZE,
                0,
                EGL10.EGL_STENCIL_SIZE,
                0,
                EGL10.EGL_NONE,
            )

        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!egl.eglChooseConfig(display, configAttribs, configs, 1, numConfigs)) {
            return false
        }

        val config = configs[0] ?: return false
        eglConfig = config

        // 请求 OpenGL ES 3.0 上下文
        val contextAttribs =
            intArrayOf(
                0x3098, // EGL_CONTEXT_CLIENT_VERSION
                3,
                EGL10.EGL_NONE,
            )

        val context =
            egl.eglCreateContext(
                display,
                config,
                EGL10.EGL_NO_CONTEXT,
                contextAttribs,
            )
        eglContext = context
        if (context == EGL10.EGL_NO_CONTEXT) return false

        // 创建窗口表面并绑定为当前渲染目标
        val surface = surfaceHolder.surface
        val eglSurface = egl.eglCreateWindowSurface(display, config, surface, null)
        this.eglSurface = eglSurface
        if (eglSurface == EGL10.EGL_NO_SURFACE) return false

        if (!egl.eglMakeCurrent(display, eglSurface, eglSurface, context)) return false

        return true
    }

    /** 释放 EGL 资源：解绑上下文 → 销毁表面 → 销毁上下文 → 释放线程状态 → 终止显示。 */
    private fun destroyEGL() {
        val egl = egl10 ?: return
        val display = eglDisplay ?: return

        egl.eglMakeCurrent(
            display,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_SURFACE,
            EGL10.EGL_NO_CONTEXT,
        )
        eglSurface?.let { egl.eglDestroySurface(display, it) }
        eglContext?.let { egl.eglDestroyContext(display, it) }
        egl.eglTerminate(display)
        eglSurface = null
        eglContext = null
    }

    companion object {
        private const val TAG = "GLWallpaperThread"
        private const val TARGET_FPS = 60L
        private const val FRAME_INTERVAL_NANOS = 1_000_000_000L / TARGET_FPS

        private const val NANOS_PER_MS = 1_000_000L

        /** Poll interval (frames) for refreshing pacing state (~0.5s at 60fps). */
        private const val DISPLAY_POLL_INTERVAL_FRAMES = 30

        /** Screen-off sleep cadence: coarse while the transition advances, ~1Hz once settled. */
        private const val SCREEN_OFF_ACTIVE_SLEEP_MS = 100L
        private const val SCREEN_OFF_SETTLED_SLEEP_MS = 1000L

        /**
         * Bounds for the present margin: subtracted from the sleep target so
         * the *next* frame's draw + buffer submission lands before the
         * intended vblank. Waking exactly at the frame boundary means the
         * queue happens after it, the presentation slips a whole vblank
         * period, and the pace judders (e.g. 33/50ms alternation for a 30fps
         * target on a 60Hz panel).
         */
        private const val MIN_PRESENT_MARGIN_NANOS = 1L * NANOS_PER_MS
        private const val MAX_PRESENT_MARGIN_NANOS = 3L * NANOS_PER_MS

        /**
         * 计算本帧应睡眠的毫秒数：用目标帧间隔减去已耗时与呈现余量的纳秒，下限为 0。
         * 提取为纯函数便于单测。
         */
        internal fun sleepMillisForFrame(
            elapsedNanos: Long,
            frameIntervalNanos: Long = FRAME_INTERVAL_NANOS,
            presentMarginNanos: Long = MAX_PRESENT_MARGIN_NANOS,
        ): Long =
            ((frameIntervalNanos - presentMarginNanos - elapsedNanos) / NANOS_PER_MS)
                .coerceAtLeast(0L)
    }

    private var egl10: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: javax.microedition.khronos.egl.EGLSurface? = null
}
