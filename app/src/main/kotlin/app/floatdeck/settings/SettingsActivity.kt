@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import app.floatdeck.BuildConfig
import app.floatdeck.data.PortraitEffect
import app.floatdeck.data.RemoteTemplateLoader
import app.floatdeck.data.SettingsRepository
import app.floatdeck.data.TemplateDef
import app.floatdeck.data.RemoteTemplateLoader.HttpProtocolException
import app.floatdeck.data.TemplateLoadException
import app.floatdeck.data.UpdateChecker
import app.floatdeck.service.FloatDeckWallpaperService
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** 设置页 Activity：选择模板并将 FloatDeck 设为动态壁纸。 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository(this)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        repo = repo,
                        onSetWallpaper = { setAsWallpaper() },
                    )
                }
            }
        }
    }

    /** 跳转系统动态壁纸选择器，预选 FloatDeck。 */
    private fun setAsWallpaper() {
        val intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(
                        this@SettingsActivity,
                        FloatDeckWallpaperService::class.java,
                    ),
                )
            }
        startActivity(intent)
    }
}

/** 设置页 Compose 界面：壁纸设置按钮 + 模板单选列表 + 远程模板导入。 */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    onSetWallpaper: () -> Unit,
) {
    val context = LocalContext.current
    var templateId by remember { mutableStateOf("") }
    var selectedEffect by remember { mutableStateOf(PortraitEffect.NONE) }
    var dragEnabled by remember { mutableStateOf(true) }
    var remoteTemplates by remember { mutableStateOf<List<TemplateDef>>(emptyList()) }
    var urlText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showHttpWarning by remember { mutableStateOf(false) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf<app.floatdeck.data.ReleaseInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    val remoteLoader = remember { RemoteTemplateLoader(context) }

    // 启动时从 DataStore 读取已保存的模板 ID
    remember {
        scope.launch {
            repo.templateId.collect { templateId = it }
        }
    }
    remember {
        scope.launch {
            repo.portraitEffect.collect { selectedEffect = it }
        }
    }
    remember {
        scope.launch {
            repo.dragEnabled.collect { dragEnabled = it }
        }
    }

    var frameRateMode by remember { mutableStateOf("auto") }
    remember {
        scope.launch {
            repo.frameRateMode.collect { frameRateMode = it }
        }
    }

    // Highest refresh rate supported by the built-in display.
    val maxRefreshHz by remember {
        mutableStateOf(
            ((context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.supportedModes
                ?.maxOfOrNull { it.refreshRate } ?: 60f).toInt(),
        )
    }

    // Fraction-of-max options, dropping any that would fall below 30fps.
    val frameRateFractionOptions =
        listOf("half" to 2, "third" to 3, "quarter" to 4)
            .map { (mode, denom) -> Triple(mode, denom, maxRefreshHz / denom) }
            .filter { (_, _, hz) -> hz >= 30 }

    fun frameRateModeLabel(mode: String): String =
        when (mode) {
            "half" -> "${maxRefreshHz / 2} Hz"
            "third" -> "${maxRefreshHz / 3} Hz"
            "quarter" -> "${maxRefreshHz / 4} Hz"
            else -> context.getString(R.string.frame_rate_auto, maxRefreshHz)
        }

    // 加载已导入的远程模板
    fun refreshRemoteTemplates() {
        remoteTemplates = remoteLoader.getImportedTemplates()
    }

    remember { refreshRemoteTemplates() }

    // Resolve an import error to a localized message: prefer the loader's messageResId
    // (carried on TemplateLoadException for i18n) over the English literal.
    fun localizedError(e: Throwable): String =
        if (e is TemplateLoadException && e.messageResId != 0) {
            context.getString(e.messageResId, *e.args)
        } else {
            e.message ?: context.getString(R.string.error_import_failed)
        }

    fun importTemplate(allowHttp: Boolean = false) {
        if (urlText.isBlank()) {
            errorMessage = context.getString(R.string.error_enter_url)
            return
        }
        isLoading = true
        errorMessage = ""
        scope.launch {
            try {
                val id = remoteLoader.importFromUrl(urlText, allowHttp)
                refreshRemoteTemplates()
                urlText = ""
                templateId = id
                repo.setTemplate(id)
                Toast.makeText(context, context.getString(R.string.import_success, id), Toast.LENGTH_SHORT).show()
            } catch (e: HttpProtocolException) {
                // HTTP (unencrypted): show a confirmation dialog; retry with allowHttp on accept.
                showHttpWarning = true
            } catch (e: Exception) {
                errorMessage = localizedError(e)
            } finally {
                isLoading = false
            }
        }
    }

    fun importLocalZip(uri: Uri) {
        isLoading = true
        errorMessage = ""
        scope.launch {
            try {
                val tempFile = File.createTempFile("local_import", ".zip", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw TemplateLoadException(context.getString(R.string.error_cannot_read_file))
                val id = remoteLoader.importFromZip(tempFile)
                tempFile.delete()
                refreshRemoteTemplates()
                templateId = id
                repo.setTemplate(id)
                Toast.makeText(context, context.getString(R.string.import_success, id), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("FloatDeck", "Local ZIP import failed", e)
                errorMessage = localizedError(e)
            } finally {
                isLoading = false
            }
        }
    }

    fun importLocalDirectory(uri: Uri) {
        isLoading = true
        errorMessage = ""
        scope.launch {
            try {
                val tempDir = File(context.cacheDir, "local_import_dir_${UUID.randomUUID()}")
                tempDir.deleteRecursively()
                tempDir.mkdirs()
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
                val resolver = context.contentResolver
                val allowedExt = setOf("png", "jpg", "jpeg", "webp", "json")
                resolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                    val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val uriCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol)
                        val ext = name.substringAfterLast(".", "").lowercase()
                        if (ext !in allowedExt) continue
                        val childId = cursor.getString(uriCol)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, childId)
                        resolver.openInputStream(childUri)?.use { input ->
                            FileOutputStream(File(tempDir, name)).use { output -> input.copyTo(output) }
                        }
                    }
                }
                val id = remoteLoader.importFromDirectory(tempDir)
                tempDir.deleteRecursively()
                refreshRemoteTemplates()
                templateId = id
                repo.setTemplate(id)
                Toast.makeText(context, context.getString(R.string.import_success, id), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("FloatDeck", "Local directory import failed", e)
                errorMessage = localizedError(e)
            } finally {
                isLoading = false
            }
        }
    }

    val zipPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { importLocalZip(it) }
    }

    val dirPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        uri?.let { importLocalDirectory(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(title = { Text("FloatDeck") })
        },
    ) { padding ->
        // Scan built-in templates from assets
        val assetTemplates = remember {
            context.assets.list("templates")
                ?.filterNotNull()
                ?.mapNotNull { dir ->
                    val json = runCatching {
                        context.assets.open("templates/$dir/template.json")
                            .bufferedReader().use { it.readText() }
                    }.getOrNull() ?: return@mapNotNull null
                    val jsonObj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return@mapNotNull null
                    dir to jsonObj.optString("name", dir)
                } ?: emptyList()
        }

        LazyColumn(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(stringResource(R.string.template_title), style = MaterialTheme.typography.titleMedium)
            }

            if (assetTemplates.isEmpty() && remoteTemplates.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.no_templates_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(assetTemplates) { (id, name) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = templateId == id,
                        onClick = {
                            scope.launch { repo.setTemplate(id) }
                        },
                    )
                    Text(name, modifier = Modifier.padding(start = 8.dp))
                }
            }

            // 已导入的远程模板列表
            if (remoteTemplates.isNotEmpty()) {
                item {
                    Text(stringResource(R.string.imported_templates), style = MaterialTheme.typography.titleSmall)
                }

                items(remoteTemplates) { tmpl ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = templateId == tmpl.id,
                            onClick = {
                                scope.launch { repo.setTemplate(tmpl.id) }
                            },
                        )
                        Text(
                            tmpl.name,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = {
                            remoteLoader.deleteTemplate(tmpl.id)
                            refreshRemoteTemplates()
                            if (templateId == tmpl.id) {
                                scope.launch { repo.setTemplate("") }
                            }
                        }) {
                            Text(stringResource(R.string.delete))
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = onSetWallpaper,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = templateId.isNotBlank(),
                ) {
                    Text(stringResource(R.string.set_as_live_wallpaper))
                }
            }

            // 立绘特效选择
            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.portrait_effect), style = MaterialTheme.typography.titleMedium)
            }

            items(PortraitEffect.entries) { effect ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = selectedEffect == effect,
                        onClick = {
                            scope.launch { repo.setPortraitEffect(effect) }
                        },
                    )
                    Text(
                        stringResource(effect.labelResId),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }

            // Portrait drag toggle
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        stringResource(R.string.drag_enabled),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = dragEnabled,
                        onCheckedChange = { scope.launch { repo.setDragEnabled(it) } },
                    )
                }
            }

            // Render frame rate preference (Material3 exposed dropdown)
            item {
                var frameRateMenuExpanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = frameRateMenuExpanded,
                    onExpandedChange = { frameRateMenuExpanded = it },
                ) {
                    OutlinedTextField(
                        value = frameRateModeLabel(frameRateMode),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.frame_rate_title)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = frameRateMenuExpanded)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    )
                    ExposedDropdownMenu(
                        expanded = frameRateMenuExpanded,
                        onDismissRequest = { frameRateMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(context.getString(R.string.frame_rate_auto, maxRefreshHz)) },
                            onClick = {
                                scope.launch { repo.setFrameRateMode("auto") }
                                frameRateMenuExpanded = false
                            },
                        )
                        frameRateFractionOptions.forEach { (mode, _, hz) ->
                            DropdownMenuItem(
                                text = { Text("$hz Hz") },
                                onClick = {
                                    scope.launch { repo.setFrameRateMode(mode) }
                                    frameRateMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                Text(stringResource(R.string.import_section_title), style = MaterialTheme.typography.titleMedium)
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.import_remote_template),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.import_remote_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            label = { Text(stringResource(R.string.zip_url_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isLoading,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = { importTemplate() },
                                enabled = !isLoading,
                            ) {
                                Text(stringResource(R.string.import_button))
                            }
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.padding(start = 16.dp),
                                )
                            }
                        }
                        if (errorMessage.isNotEmpty()) {
                            Text(
                                errorMessage,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            stringResource(R.string.local_import),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(R.string.local_import_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    zipPickerLauncher.launch(
                                        arrayOf(
                                            "application/zip",
                                            "application/x-zip-compressed",
                                        ),
                                    )
                                },
                                enabled = !isLoading,
                            ) {
                                Text(stringResource(R.string.zip_file))
                            }
                            Button(
                                onClick = {
                                    dirPickerLauncher.launch(null)
                                },
                                enabled = !isLoading,
                            ) {
                                Text(stringResource(R.string.directory))
                            }
                        }
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                Text(
                    stringResource(R.string.crash_logs_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                var crashLogs by remember {
                    mutableStateOf(app.floatdeck.CrashLogCollector.getLogFiles())
                }
                if (crashLogs.isEmpty()) {
                    Text(
                        stringResource(R.string.no_crash_logs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val hasLog = crashLogs.isNotEmpty()
                    val firstLog = crashLogs.firstOrNull()
                    Button(
                        onClick = {
                            firstLog?.let { log ->
                                val intent = app.floatdeck.CrashLogCollector.shareLog(
                                    context.applicationContext as app.floatdeck.FloatDeckApp,
                                    log,
                                )
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        enabled = hasLog,
                    ) {
                        Text(stringResource(R.string.share_crash_log))
                    }
                    Button(
                        onClick = {
                            firstLog?.let { log ->
                                val ok = app.floatdeck.CrashLogCollector.saveToDownloads(context, log)
                                val msg = if (ok) {
                                    context.getString(R.string.saved_to_downloads, log.name)
                                } else {
                                    context.getString(R.string.save_to_downloads_failed)
                                }
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        enabled = hasLog,
                    ) {
                        Text(stringResource(R.string.save_to_downloads))
                    }
                    TextButton(
                        onClick = {
                            app.floatdeck.CrashLogCollector.clearLogs()
                            crashLogs = app.floatdeck.CrashLogCollector.getLogFiles()
                            scope.launch {
                                snackbarHostState.showSnackbar(context.getString(R.string.crash_logs_cleared))
                            }
                        },
                        enabled = hasLog,
                    ) {
                        Text(stringResource(R.string.clear_crash_log))
                    }
                }
            }

            item { HorizontalDivider() }
            item {
                Text(
                    stringResource(R.string.current_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        updateChecking = true
                        scope.launch {
                            when (val result = UpdateChecker.checkForUpdate()) {
                                is app.floatdeck.data.UpdateResult.Available -> {
                                    updateAvailable = result.info
                                }

                                is app.floatdeck.data.UpdateResult.UpToDate -> {
                                        scope.launch {
                                            snackbarHostState.showSnackbar(
                                                context.getString(R.string.already_latest),
                                            )
                                        }
                                    }

                                is app.floatdeck.data.UpdateResult.Error -> {
                                    val friendly = when {
                                        result.message.contains("timeout", ignoreCase = true) ->
                                            context.getString(R.string.update_timeout)

                                        result.message.contains("network", ignoreCase = true) ||
                                            result.message.contains("connection", ignoreCase = true) ->
                                            context.getString(R.string.update_network_error)

                                        else -> context.getString(R.string.update_check_failed)
                                    }
                                    updateError = "$friendly\n${result.message}"
                                }
                            }
                            updateChecking = false
                        }
                    },
                    enabled = !updateChecking,
                ) {
                    Text(stringResource(R.string.check_for_updates))
                }
                if (updateChecking) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }

                if (updateAvailable != null) {
                    AlertDialog(
                        onDismissRequest = { updateAvailable = null },
                        title = { Text(stringResource(R.string.update_available)) },
                        text = {
                            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                                Text(updateAvailable!!.tagName)
                                if (updateAvailable!!.body.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(updateAvailable!!.body)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(updateAvailable!!.htmlUrl)),
                                    )
                                    updateAvailable = null
                                },
                            ) {
                                Text(stringResource(R.string.view_release))
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { updateAvailable = null }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }

                if (updateError != null) {
                    AlertDialog(
                        onDismissRequest = { updateError = null },
                        title = { Text(stringResource(R.string.update_check_failed)) },
                        text = { Text(updateError!!) },
                        confirmButton = {
                            TextButton(onClick = { updateError = null }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        },
                    )
                }

                if (showHttpWarning) {
                    AlertDialog(
                        onDismissRequest = { showHttpWarning = false },
                        title = { Text(stringResource(R.string.http_warning_title)) },
                        text = {
                            Text(stringResource(R.string.http_warning_message))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showHttpWarning = false
                                importTemplate(allowHttp = true)
                            }) { Text(stringResource(R.string.http_warning_continue)) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showHttpWarning = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        context.startActivity(Intent(context, LicenseActivity::class.java))
                    },
                ) {
                    Text(stringResource(R.string.open_source_licenses))
                }
            }
        }
    }
}
