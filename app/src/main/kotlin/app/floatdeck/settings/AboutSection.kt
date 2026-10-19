@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.floatdeck.BuildConfig
import app.floatdeck.R

private const val PROJECT_URL = "https://github.com/kxxoling/FloatDeck"
private const val DEVELOPER_URL = "https://github.com/kxxoling"
private const val DEVELOPER_HANDLE = "@kxxoling"

/** Help & about group: crash logs, update check, open-source licenses, project links. */
@Composable
fun AboutSection(
    crashLogCount: Int,
    onCrashLogs: () -> Unit,
    checkingUpdate: Boolean,
    onCheckUpdate: () -> Unit,
    onLicenses: () -> Unit,
) {
    val context = LocalContext.current
    var showProjectInfo by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        SettingsRow(
            title = stringResource(R.string.crash_logs_title),
            iconRes = R.drawable.ic_bug,
            iconTint = MaterialTheme.colorScheme.error,
            onClick = onCrashLogs,
        ) {
            if (crashLogCount > 0) {
                Box(
                    modifier =
                        Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = crashLogCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                    )
                }
                Spacer(Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRow(
            title = stringResource(R.string.check_for_updates),
            subtitle = stringResource(R.string.current_version, BuildConfig.VERSION_NAME),
            iconRes = R.drawable.ic_update,
            onClick = onCheckUpdate,
        ) {
            if (checkingUpdate) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsRow(
            title = stringResource(R.string.open_source_licenses),
            iconRes = R.drawable.ic_doc,
            onClick = onLicenses,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRow(
            title = stringResource(R.string.project_info_title),
            iconRes = R.drawable.ic_github,
            onClick = { showProjectInfo = true },
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showProjectInfo) {
        AlertDialog(
            onDismissRequest = { showProjectInfo = false },
            title = {
                Text(
                    stringResource(R.string.project_info_title),
                    modifier = Modifier.padding(start = 16.dp),
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.project_support_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    SettingsRow(
                        title = stringResource(R.string.star_project_title),
                        subtitle = PROJECT_URL,
                        iconRes = R.drawable.ic_github,
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL))) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    // Inset to the text column: 16+38+14dp on the left, 16+20+8dp on the right,
                    // so the rule never crosses either icon.
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 68.dp, end = 44.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    SettingsRow(
                        title = stringResource(R.string.follow_developer_title),
                        subtitle = DEVELOPER_HANDLE,
                        iconRes = R.drawable.ic_github,
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DEVELOPER_URL))) },
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_open_in_new),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showProjectInfo = false },
                    colors =
                        ButtonDefaults.textButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        ),
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
}
