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
 * Portrait idle sway amplitude setting.
 *
 * Self-contained component: reads the current value from
 * [SettingsRepository.swayAmplitude] itself and writes it back when the
 * slider drag ends — no external state required. Kept in its own file so a
 * settings-page redesign can move it freely.
 */
@Composable
fun SwayAmplitudeSetting(
    repo: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var amplitude by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(repo) {
        repo.swayAmplitude.collect { amplitude = it }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.sway_amplitude_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.sway_amplitude_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = amplitude,
                onValueChange = { amplitude = it },
                onValueChangeFinished = {
                    scope.launch { repo.setSwayAmplitude(amplitude) }
                },
                valueRange = 0f..3f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${(amplitude * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
