package app.floatdeck.settings


import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import app.floatdeck.R
import app.floatdeck.data.PortraitEffect

/** A template entry shown in the settings gallery (built-in or imported). */
data class TemplateUi(
    val id: String,
    val name: String,
    val thumb: Any?,
    val isRemote: Boolean,
)

private val ThumbPlaceholder =
    Brush.linearGradient(listOf(Color(0xFFC9A7FF), Color(0xFF8E7BFF)))

/** Top app header: launcher icon + app name; references the manifest icon
 * resource directly. Layout keeps room for a tagline once one is decided. */
@Composable
fun AppHeader(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = stringResource(R.string.app_name),
            modifier =
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp)),
        )
        Column(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            // Tagline placeholder: add a Text here once a slogan is decided.
        }
    }
}

/** Section label above a grouped card. */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 10.dp),
    )
}

/** Rounded grouped-card container for related settings. */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        content = content,
    )
}

/** Standard settings row: leading tonal icon + title/subtitle + trailing
 * content; the whole row is clickable. */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    iconRes: Int? = null,
    iconTint: Color? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val rowModifier = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            rowModifier
                .fillMaxWidth()
                .heightIn(min = 64.dp),
    ) {
        if (iconRes != null) {
            Box(
                modifier =
                    Modifier
                        .padding(start = 16.dp)
                        .size(38.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = iconTint ?: MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
        Spacer(Modifier.width(16.dp))
    }
}

/** Segmented selector for portrait effects. */
@Composable
fun EffectSelector(
    selected: PortraitEffect,
    onSelect: (PortraitEffect) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp),
    ) {
        PortraitEffect.entries.forEachIndexed { index, effect ->
            SegmentedButton(
                selected = selected == effect,
                onClick = { onSelect(effect) },
                shape =
                    SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = PortraitEffect.entries.size,
                    ),
            ) {
                Text(stringResource(effect.labelResId))
            }
        }
    }
}

/** Template gallery card: wallpaper thumbnail, name overlay and selected
 * badge; long-press an imported template for the delete action (dropdown
 * menu on wide layouts, bottom sheet on narrow ones). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateCard(
    item: TemplateUi,
    selected: Boolean,
    onClick: () -> Unit,
    imageHeight: Dp,
    modifier: Modifier = Modifier,
    useBottomSheet: Boolean = false,
    onDelete: (() -> Unit)? = null,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(imageHeight)
                    .clip(RoundedCornerShape(24.dp))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color =
                            if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                        shape = RoundedCornerShape(24.dp),
                    )
                    .background(ThumbPlaceholder)
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = if (onDelete != null) {
                            {
                                if (useBottomSheet) {
                                    sheetVisible = true
                                } else {
                                    menuExpanded = true
                                }
                            }
                        } else {
                            null
                        },
                    ),
        ) {
            if (item.thumb != null) {
                TemplateThumbnail(
                    model = item.thumb,
                    contentDescription = item.name,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selected) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xD3140A05)),
                            ),
                        ),
            )
            Text(
                text = item.name,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 10.dp),
            )
            if (useBottomSheet) {
                if (sheetVisible) {
                    ModalBottomSheet(onDismissRequest = { sheetVisible = false }) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        onClick = {
                                            sheetVisible = false
                                            onDelete?.invoke()
                                        },
                                    )
                                    .padding(horizontal = 24.dp, vertical = 18.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = stringResource(R.string.delete_template),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            } else {
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    // The popup anchors below the card by default; lift it fully
                    // above the card so the long-pressing finger never covers it.
                    offset = DpOffset(0.dp, -(imageHeight + 70.dp)),
                    shape = RoundedCornerShape(20.dp),
                    // Translucent, slightly darkened container. No backdrop
                    // blur: blurring the whole app content also hides the
                    // template card the menu acts on.
                    containerColor =
                        lerp(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            Color.Black,
                            0.04f,
                        ).copy(alpha = 0.9f),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.delete),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete?.invoke()
                        },
                    )
                }
            }
        }
    }
}

/** Pinned bottom primary action: set as live wallpaper. */
@Composable
fun PrimaryCtaBar(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = RoundedCornerShape(28.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp),
        ) {
            Text(
                text = stringResource(R.string.set_as_live_wallpaper),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}
