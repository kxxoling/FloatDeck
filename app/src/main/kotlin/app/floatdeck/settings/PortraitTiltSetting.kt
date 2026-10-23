@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
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
 * Portrait tilt settings: a -30..30 degree offset added to every portrait's
 * rotation, plus a symmetry switch — when on, the right column mirrors the
 * offset; when off, both columns lean the same way.
 *
 * Self-contained component: reads the current values from
 * [SettingsRepository.portraitTilt] / [SettingsRepository.portraitTiltSymmetric]
 * itself and writes them back — no external state required. Kept in its own
 * file so a settings-page redesign can move it freely.
 */
@Composable
fun PortraitTiltSetting(
    repo: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var degrees by remember { mutableFloatStateOf(0f) }
    var symmetric by remember { mutableStateOf(true) }

    LaunchedEffect(repo) {
        repo.portraitTilt.collect { degrees = it }
    }
    LaunchedEffect(repo) {
        repo.portraitTiltSymmetric.collect { symmetric = it }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.portrait_tilt_title),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(
            stringResource(R.string.portrait_tilt_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Slider(
                value = degrees,
                onValueChange = { degrees = it },
                onValueChangeFinished = {
                    scope.launch { repo.setPortraitTilt(degrees) }
                },
                valueRange = -30f..30f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${degrees.roundToInt()}°",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(R.string.portrait_tilt_symmetric_title),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = symmetric,
                onCheckedChange = { value ->
                    symmetric = value
                    scope.launch { repo.setPortraitTiltSymmetric(value) }
                },
            )
        }
    }
}
