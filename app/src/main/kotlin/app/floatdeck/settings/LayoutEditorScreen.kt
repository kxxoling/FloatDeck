@file:Suppress("ktlint:standard:function-naming", "ClickableViewAccessibility")

package app.floatdeck.settings

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.floatdeck.R
import app.floatdeck.data.TemplateOverrides
import app.floatdeck.data.Templates
import app.floatdeck.gl.FloatDeckRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 布局编辑器界面：GL 预览 + 锁屏/解锁状态切换 + 选中卡片的缩放/旋转滑杆。
 *
 * 保存时仅把当前预览状态（锁屏或解锁）的位置写入模板 override 的对应字段，
 * 另一状态保持原值，避免编辑一侧时暗中改动另一侧。
 */
@Composable
fun LayoutEditorScreen(
    context: Context,
    templateId: String,
    onDone: () -> Unit,
) {
    var previewLocked by remember { mutableStateOf(true) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var extraScale by remember { mutableStateOf(1f) }
    var extraRotation by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    val renderer = remember { FloatDeckRenderer(context) }
    val glView =
        remember(templateId) { createEditorGlView(context, renderer, templateId) }

    // Activity 销毁时在 GL 线程释放纹理/着色器
    DisposableEffect(glView) {
        onDispose {
            glView.queueEvent { renderer.release() }
        }
    }

    // 轮询渲染器选中状态（触摸由 GLSurfaceView 直接转发，不走 Compose）；
    // 新选中时重置滑杆显示
    LaunchedEffect(glView) {
        while (true) {
            val id = renderer.selectedPortraitId
            if (id != selectedId) {
                selectedId = id
                extraScale = 1f
                extraRotation = 0f
            }
            delay(100)
        }
    }

    fun reloadTemplate() {
        glView.queueEvent { loadEditorTemplate(context, renderer, templateId) }
    }

    fun saveLayout() {
        val lockedSnapshot = previewLocked
        glView.queueEvent {
            val snapshot = renderer.snapshotPortraitOverrides(lockedSnapshot)
            scope.launch {
                val ok =
                    runCatching {
                        withContext(Dispatchers.IO) {
                            val current = TemplateOverrides.load(context, templateId)
                            TemplateOverrides.save(
                                context,
                                templateId,
                                current.copy(portraits = current.portraits + snapshot),
                            )
                        }
                    }.isSuccess
                Toast.makeText(
                    context,
                    context.getString(
                        if (ok) R.string.layout_editor_saved else R.string.update_check_failed,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
                if (ok) reloadTemplate()
            }
        }
    }

    fun resetPositions() {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = TemplateOverrides.load(context, templateId)
                    TemplateOverrides.save(context, templateId, current.copy(portraits = emptyMap()))
                }
            }
            reloadTemplate()
            Toast.makeText(context, R.string.layout_editor_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.layout_editor_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        AndroidView(
            factory = { glView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = previewLocked,
                    onClick = {
                        previewLocked = true
                        renderer.setPreviewLocked(true)
                    },
                    label = { Text(stringResource(R.string.layout_editor_state_locked)) },
                )
                FilterChip(
                    selected = !previewLocked,
                    onClick = {
                        previewLocked = false
                        renderer.setPreviewLocked(false)
                    },
                    label = { Text(stringResource(R.string.layout_editor_state_unlocked)) },
                )
            }

            val selection = selectedId
            if (selection == null) {
                Text(
                    stringResource(R.string.layout_editor_no_selection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LabeledEditorSlider(
                    label = stringResource(R.string.layout_editor_scale),
                    value = extraScale,
                    valueRange = 0.5f..2f,
                    onChange = {
                        extraScale = it
                        renderer.setPortraitAdjust(selection, it, extraRotation)
                    },
                )
                LabeledEditorSlider(
                    label = stringResource(R.string.layout_editor_rotation),
                    value = extraRotation,
                    valueRange = -45f..45f,
                    onChange = {
                        extraRotation = it
                        renderer.setPortraitAdjust(selection, extraScale, it)
                    },
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Button(onClick = { saveLayout() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.layout_editor_save))
                }
                TextButton(onClick = { resetPositions() }) {
                    Text(stringResource(R.string.layout_editor_reset))
                }
                TextButton(onClick = onDone) {
                    Text(stringResource(R.string.layout_editor_close))
                }
            }
        }
    }
}

@Composable
private fun LabeledEditorSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
        )
    }
}

/**
 * 创建编辑器使用的 GLSurfaceView：复用 [FloatDeckRenderer]。
 * 触摸直接转发（拖动 = renderer 自带拖动；双击 = 撤销该卡片本次拖动）。
 */
@SuppressLint("ClickableViewAccessibility")
private fun createEditorGlView(
    context: Context,
    renderer: FloatDeckRenderer,
    templateId: String,
): GLSurfaceView =
    GLSurfaceView(context).apply {
        setEGLContextClientVersion(3)
        preserveEGLContextOnPause = true

        setRenderer(
            object : GLSurfaceView.Renderer {
                private var loaded = false

                override fun onSurfaceCreated(
                    gl: GL10?,
                    config: EGLConfig?,
                ) {
                    renderer.onSurfaceCreated(gl, config)
                    loaded = false // EGL 上下文重建后需要重新加载纹理
                }

                override fun onSurfaceChanged(
                    gl: GL10?,
                    width: Int,
                    height: Int,
                ) {
                    renderer.onSurfaceChanged(gl, width, height)
                    if (!loaded) {
                        loadEditorTemplate(context, renderer, templateId)
                        loaded = true
                    }
                }

                override fun onDrawFrame(gl: GL10?) {
                    renderer.onDrawFrame(gl)
                }
            },
        )
        renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        val doubleTapDetector =
            GestureDetector(
                context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        renderer.onDoubleTap(e.x, e.y)
                        return true
                    }
                },
            )
        setOnTouchListener { _, event ->
            doubleTapDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> renderer.onTouchDown(event.x, event.y)
                MotionEvent.ACTION_MOVE -> renderer.onTouchMove(event.x, event.y)
                MotionEvent.ACTION_UP -> renderer.onTouchUp()
            }
            true
        }
    }

/** 解析模板定义并叠加 override 后加载到渲染器（必须在 GL 线程调用）。 */
private fun loadEditorTemplate(
    context: Context,
    renderer: FloatDeckRenderer,
    templateId: String,
) {
    val resolved = Templates.resolveDef(context, templateId) ?: return
    val (def, dir) = resolved
    val config =
        if (dir != null) {
            Templates.toTemplateConfigFromDir(def, dir)
        } else {
            Templates.toTemplateConfig(context, def)
        }
    val override = TemplateOverrides.load(context, templateId)
    renderer.loadTemplate(config, override)
    renderer.backgroundAdjust = override.background
}
