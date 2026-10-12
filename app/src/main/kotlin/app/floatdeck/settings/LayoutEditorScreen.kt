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
import androidx.compose.foundation.layout.statusBarsPadding
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
import app.floatdeck.data.RemoteTemplateLoader
import app.floatdeck.data.TemplateEditorStore
import app.floatdeck.data.Templates
import app.floatdeck.gl.FloatDeckRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Layout editor screen: GL preview + locked/unlocked state toggle + scale/
 * rotation sliders for the selected card.
 *
 * Saving writes only the previewed state's positions into the template's own
 * template.json; the other state keeps its stored value, so editing one side
 * never silently changes the other.
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

    // Release textures/shaders on the GL thread when the activity is destroyed
    DisposableEffect(glView) {
        onDispose {
            glView.queueEvent { renderer.release() }
        }
    }

    // Poll the renderer's selection (touches are forwarded directly by the
    // GLSurfaceView, bypassing Compose); reset the sliders on a new selection
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
            val snapshot = renderer.snapshotPortraitPositions(lockedSnapshot)
            scope.launch {
                val ok =
                    withContext(Dispatchers.IO) {
                        runCatching { TemplateEditorStore.saveLayout(context, templateId, snapshot) }.isSuccess
                    }
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
        val files = renderer.portraitFiles()
        scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                        // null positions remove the explicit placement; the save also
                        // zeroes the drag offsets, restoring the built-in layout
                        TemplateEditorStore.saveLayout(
                            context,
                            templateId,
                            files.associateWith { Pair(null, null) },
                        )
                    }.isSuccess
                }
            if (ok) reloadTemplate()
            Toast.makeText(context, R.string.layout_editor_reset_done, Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            stringResource(R.string.layout_editor_drag_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        AndroidView(
            factory = { glView },
            modifier =
                Modifier
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
                Button(onClick = { saveLayout() }) {
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
 * Creates the editor's GLSurfaceView, reusing [FloatDeckRenderer].
 * Touches are forwarded directly (drag = the renderer's own dragging;
 * double-tap = undo that card's current drag).
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
                    loaded = false // textures must be reloaded after an EGL context rebuild
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

/** Resolves the template definition, applies it to the renderer (must be called on the GL thread). */
private fun loadEditorTemplate(
    context: Context,
    renderer: FloatDeckRenderer,
    templateId: String,
) {
    val remoteDir = File(context.filesDir, "${RemoteTemplateLoader.TEMPLATE_DIR}/$templateId")
    var def = if (remoteDir.isDirectory) Templates.loadTemplateFromDir(remoteDir) else null
    if (def != null) {
        renderer.loadTemplate(Templates.toTemplateConfigFromDir(def, remoteDir))
    } else {
        def = Templates.loadTemplate(context, templateId)
        if (def != null) {
            renderer.loadTemplate(Templates.toTemplateConfig(context, def))
        }
    }
    if (def != null) {
        renderer.backgroundZoom = def.backgroundZoom
    }
}
