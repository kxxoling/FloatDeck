package app.floatdeck.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

/**
 * 布局编辑器 Activity：在 GL 预览中拖动/调整当前模板的立绘卡片，
 * 保存到模板 override。独立于设置页，便于重构时保持入口简单。
 */
class LayoutEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val templateId = intent.getStringExtra(EXTRA_TEMPLATE_ID) ?: ""
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LayoutEditorScreen(
                        context = applicationContext,
                        templateId = templateId,
                        onDone = { finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
    }
}
