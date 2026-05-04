package app.floatdeck.service

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
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
            registerReceiver(lockReceiver, filter)
            sensorHandler.register()
        }

        override fun onDestroy() {
            unregisterReceiver(lockReceiver)
            sensorHandler.unregister()
            stopGLThread()
            renderer.release()
            super.onDestroy()
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                sensorHandler.register()
                startGLThread()
            } else {
                sensorHandler.unregister()
                stopGLThread()
            }
        }

        override fun onSurfaceChanged(
            holder: SurfaceHolder,
            format: Int,
            width: Int,
            height: Int,
        ) {
            super.onSurfaceChanged(holder, format, width, height)
            renderer.onSurfaceChanged(null, width, height)
        }

        override fun onTouchEvent(event: MotionEvent) {
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
            glThread?.requestStop()
            glThread = null
        }
    }
}

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

    private var egl10: EGL10? = null
    private var eglDisplay: EGLDisplay? = null
    private var eglContext: EGLContext? = null
    private var eglSurface: javax.microedition.khronos.egl.EGLSurface? = null

    /** 模板是否已加载（只加载一次）。 */
    private var templateLoaded = false

    fun requestStop() {
        isRunning = false
    }

    override fun run() {
        if (!initEGL()) return

        try {
            renderer.onSurfaceCreated(null, null)
            while (isRunning) {
                // 平滑插值传感器值（低通滤波，系数 0.08）
                renderer.smoothedRollX +=
                    (sensorHandler.rollX - renderer.smoothedRollX) * 0.08f
                renderer.smoothedPitchY +=
                    (sensorHandler.pitchY - renderer.smoothedPitchY) * 0.08f

                // 首帧加载模板配置
                if (!templateLoaded) {
                    val prefs =
                        context.getSharedPreferences(
                            "settings_prefs",
                            Context.MODE_PRIVATE,
                        )
                    val savedId =
                        prefs.getString("template_id", "") ?: ""

                    // 读取立绘特效设置
                    val effectStr = prefs.getString("portrait_effect", "none") ?: "none"
                    renderer.portraitEffect =
                        when (effectStr) {
                            "ice" -> 1
                            "holo" -> 2
                            else -> 0
                        }

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
                    templateLoaded = true
                }

                renderer.onDrawFrame(null)

                egl10?.eglSwapBuffers(eglDisplay, eglSurface)
                Thread.sleep(16) // ≈60fps
            }
        } catch (_: Exception) {
            // Surface 销毁时正常退出
        } finally {
            destroyEGL()
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

    /** 释放 EGL 资源：解绑上下文 → 销毁表面 → 销毁上下文 → 终止显示。 */
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
    }
}
