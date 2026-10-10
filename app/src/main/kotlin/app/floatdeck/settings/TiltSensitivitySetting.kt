@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.floatdeck.R
import app.floatdeck.data.SettingsRepository
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 倾斜视差灵敏度设置项。
 *
 * 自包含组件：自行从 [SettingsRepository.tiltSensitivity] 读取当前值并在
 * 滑动结束时写回，不依赖外部状态。独立成文件，便于设置页重构时整体搬移。
 */
@Composable
fun TiltSensitivitySetting(
    repo: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sensitivity by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(repo) {
        repo.tiltSensitivity.collect { sensitivity = it }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.tilt_sensitivity_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.tilt_sensitivity_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = {
                    scope.launch { repo.setTiltSensitivity(sensitivity) }
                },
                valueRange = 0.2f..3f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(sensitivity * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
