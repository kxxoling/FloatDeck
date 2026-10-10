@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import android.content.Context
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.floatdeck.data.Templates
import coil3.compose.AsyncImage
import java.io.File

/**
 * 模板列表缩略图：加载模板壁纸作为预览。
 *
 * Coil 自带内存缓存与按视图尺寸降采样；模型解析独立成函数便于设置页重构复用。
 */
@Composable
fun TemplateThumbnail(
    model: Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        Surface(
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {}
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(RoundedCornerShape(8.dp)),
        )
    }
}

/**
 * 解析模板壁纸的 Coil 加载模型：远程导入模板用文件 URI，内置模板用
 * android_asset URI；模板不存在或缺少壁纸时返回 null。
 */
fun templateWallpaperModel(
    context: Context,
    templateId: String,
): Any? {
    val resolved = Templates.resolveDef(context, templateId) ?: return null
    val (def, dir) = resolved
    return if (dir != null) {
        File(dir, def.wallpaper).toUri()
    } else {
        "file:///android_asset/templates/$templateId/${def.wallpaper}".toUri()
    }
}
