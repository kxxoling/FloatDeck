@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.floatdeck.R
import app.floatdeck.data.BackgroundAdjustment
import app.floatdeck.data.TemplateOverride
import app.floatdeck.data.TemplateOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 背景缩放/位置调整设置项（作用于当前选中模板）。
 *
 * 自包含组件：自行从 [TemplateOverrides] 读取当前值并在滑动结束时写回
 * （保存会触发运行中壁纸的热重载）。独立成文件，便于设置页重构时整体搬移。
 */
@Composable
fun BackgroundAdjustSettings(
    templateId: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var adjustment by remember { mutableStateOf(BackgroundAdjustment()) }

    LaunchedEffect(templateId) {
        adjustment =
            withContext(Dispatchers.IO) {
                TemplateOverrides.load(context, templateId).background
            }
    }

    if (templateId.isBlank()) {
        Text(
            stringResource(R.string.background_adjust_no_template),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        return
    }

    fun persist(value: BackgroundAdjustment) {
        adjustment = value
        scope.launch(Dispatchers.IO) {
            TemplateOverrides.save(context, templateId, TemplateOverride(background = value))
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.background_adjust_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.background_adjust_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LabeledSlider(
            label = stringResource(R.string.background_zoom),
            value = adjustment.zoom,
            valueRange = 1f..3f,
            onValueChange = { adjustment = adjustment.copy(zoom = it) },
            onValueChangeFinished = { persist(adjustment) },
        )
        LabeledSlider(
            label = stringResource(R.string.background_offset_x),
            value = adjustment.offsetX,
            valueRange = -1f..1f,
            onValueChange = { adjustment = adjustment.copy(offsetX = it) },
            onValueChangeFinished = { persist(adjustment) },
        )
        LabeledSlider(
            label = stringResource(R.string.background_offset_y),
            value = adjustment.offsetY,
            valueRange = -1f..1f,
            onValueChange = { adjustment = adjustment.copy(offsetY = it) },
            onValueChangeFinished = { persist(adjustment) },
        )
        TextButton(onClick = { persist(BackgroundAdjustment()) }) {
            Text(stringResource(R.string.background_reset))
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(value * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp),
        )
    }
}
