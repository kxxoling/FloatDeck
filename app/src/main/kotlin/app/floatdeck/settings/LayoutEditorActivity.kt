package app.floatdeck.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier

/**
 * Layout editor activity: drag/adjust the current template's portrait cards
 * in a GL preview and save to the template's own template.json. Kept separate
 * from the settings page so redesigns keep a minimal entry point.
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
