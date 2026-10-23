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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.floatdeck.CrashLogCollector
import app.floatdeck.FloatDeckApp
import app.floatdeck.R
import app.floatdeck.data.PortraitEffect
import app.floatdeck.data.RemoteTemplateLoader
import app.floatdeck.data.RemoteTemplateLoader.HttpProtocolException
import app.floatdeck.data.SettingsRepository
import app.floatdeck.data.TemplateLoadException
import app.floatdeck.data.UpdateChecker
import app.floatdeck.service.FloatDeckWallpaperService
import app.floatdeck.ui.FloatDeckTheme
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Settings activity: pick a template and set FloatDeck as the live wallpaper. */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = SettingsRepository(this)

        setContent {
            FloatDeckTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SettingsScreen(
                        repo = repo,
                        onSetWallpaper = { setAsWallpaper() },
                    )
                }
            }
        }
    }

    /** Jumps to the system live-wallpaper picker with FloatDeck preselected. */
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

/** Loads built-in and imported templates on an IO thread for the gallery. */
private fun loadAllTemplates(
    context: Context,
    remoteLoader: RemoteTemplateLoader,
): List<TemplateUi> {
    val assets =
        runCatching {
            context.assets
                .list("templates")
                ?.mapNotNull { dir ->
                    val json =
                        runCatching {
                            context.assets
                                .open("templates/$dir/template.json")
                                .bufferedReader()
                                .use { it.readText() }
                        }.getOrNull() ?: return@mapNotNull null
                    val jsonObj =
                        runCatching { JSONObject(json) }.getOrNull() ?: return@mapNotNull null
                    TemplateUi(
                        id = dir,
                        name = jsonObj.optString("name", dir),
                        thumb = templateWallpaperModel(context, dir),
                        isRemote = false,
                    )
                }.orEmpty()
        }.getOrDefault(emptyList())
    val imported =
        remoteLoader.getImportedTemplates().map { tmpl ->
            TemplateUi(
                id = tmpl.id,
                name = tmpl.name,
                thumb = templateWallpaperModel(context, tmpl.id),
                isRemote = true,
            )
        }
    return assets + imported
}

/** Settings screen: template gallery, template-scoped wallpaper settings,
 * app-level settings and a pinned bottom CTA. */
@Composable
fun SettingsScreen(
    repo: SettingsRepository,
    onSetWallpaper: () -> Unit,
) {
    val context = LocalContext.current
    var templateId by remember { mutableStateOf("") }
    var selectedEffect by remember { mutableStateOf(PortraitEffect.NONE) }
    var dragEnabled by remember { mutableStateOf(true) }
    var swayEnabled by remember { mutableStateOf(true) }
    var templates by remember { mutableStateOf<List<TemplateUi>>(emptyList()) }
    var templatesVersion by remember { mutableIntStateOf(0) }
    var showWallpaperSettings by rememberSaveable { mutableStateOf(false) }
    var urlText by rememberSaveable { mutableStateOf("") }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var showHttpWarning by rememberSaveable { mutableStateOf(false) }
    var showUrlImport by rememberSaveable { mutableStateOf(false) }
    var showLocalImport by rememberSaveable { mutableStateOf(false) }
    var showCrashDialog by rememberSaveable { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<TemplateUi?>(null) }
    var crashLogCount by remember { mutableStateOf(CrashLogCollector.getLogFiles().size) }
    var updateChecking by remember { mutableStateOf(false) }
    var updateAvailable by remember { mutableStateOf<app.floatdeck.data.ReleaseInfo?>(null) }
    var updateError by remember { mutableStateOf<String?>(null) }
    var currentFrameRateMode by remember { mutableStateOf("auto") }
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    val remoteLoader = remember { RemoteTemplateLoader(context) }

    // Load persisted settings from DataStore on start
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
    remember {
        scope.launch {
            repo.swayEnabled.collect { swayEnabled = it }
        }
    }
    remember {
        scope.launch {
            repo.frameRateMode.collect { currentFrameRateMode = it }
        }
    }

    // Template list loads asynchronously on IO; bump templatesVersion after
    // import/delete to reload
    LaunchedEffect(templatesVersion) {
        templates = withContext(Dispatchers.IO) { loadAllTemplates(context, remoteLoader) }
    }
    // Leave the wallpaper settings page when the selected template is deleted
    LaunchedEffect(templateId) {
        if (templateId.isBlank()) showWallpaperSettings = false
    }
    BackHandler(enabled = showWallpaperSettings) { showWallpaperSettings = false }

    // Highest refresh rate supported by the built-in display.
    val maxRefreshHz =
        remember {
            ((context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(android.view.Display.DEFAULT_DISPLAY)
                ?.supportedModes
                ?.maxOfOrNull { it.refreshRate } ?: 60f).toInt()
        }

    // Fraction-of-max options, dropping any that would fall below 30fps.
    val frameRateOptions: List<Pair<String, String>> =
        remember(maxRefreshHz) {
            val options =
                listOf("half" to 2, "third" to 3, "quarter" to 4)
                    .map { (mode, denom) -> mode to maxRefreshHz / denom }
                    .filter { (_, hz) -> hz >= 30 }
                    .map { (mode, hz) -> mode to "$hz Hz" }
            listOf("auto" to context.getString(R.string.frame_rate_auto, maxRefreshHz)) + options
        }

    val selectedName = templates.firstOrNull { it.id == templateId }?.name

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
                templatesVersion++
                urlText = ""
                templateId = id
                repo.setTemplate(id)
                showUrlImport = false
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
        scope.launch {
            try {
                val tempFile = File.createTempFile("local_import", ".zip", context.cacheDir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                } ?: throw TemplateLoadException(context.getString(R.string.error_cannot_read_file))
                val id = remoteLoader.importFromZip(tempFile)
                tempFile.delete()
                templatesVersion++
                templateId = id
                repo.setTemplate(id)
                Toast.makeText(context, context.getString(R.string.import_success, id), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("FloatDeck", "Local ZIP import failed", e)
                snackbarHostState.showSnackbar(localizedError(e))
            } finally {
                isLoading = false
            }
        }
    }

    fun importLocalDirectory(uri: Uri) {
        isLoading = true
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
                templatesVersion++
                templateId = id
                repo.setTemplate(id)
                Toast.makeText(context, context.getString(R.string.import_success, id), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.util.Log.e("FloatDeck", "Local directory import failed", e)
                snackbarHostState.showSnackbar(localizedError(e))
            } finally {
                isLoading = false
            }
        }
    }

    fun deleteTemplate(item: TemplateUi) {
        pendingDelete = item
    }

    fun confirmDeleteTemplate() {
        val item = pendingDelete ?: return
        remoteLoader.deleteTemplate(item.id)
        templatesVersion++
        if (templateId == item.id) {
            scope.launch { repo.setTemplate("") }
        }
        pendingDelete = null
    }

    fun checkForUpdate() {
        updateChecking = true
        scope.launch {
            var notice: String? = null
            when (val result = UpdateChecker.checkForUpdate()) {
                is app.floatdeck.data.UpdateResult.Available -> {
                    updateAvailable = result.info
                }

                is app.floatdeck.data.UpdateResult.UpToDate -> {
                    notice = context.getString(R.string.already_latest)
                }

                is app.floatdeck.data.UpdateResult.Error -> {
                    val friendly =
                        when {
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
            // showSnackbar suspends until the snackbar is dismissed; end the
            // loading state before posting the notice
            updateChecking = false
            notice?.let { snackbarHostState.showSnackbar(it) }
        }
    }

    val zipPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { importLocalZip(it) }
        }

    val dirPickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree(),
        ) { uri: Uri? ->
            uri?.let { importLocalDirectory(it) }
        }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = {
            // Theme-aware snackbar (the M3 default uses inverse colors, which read
            // as light in dark mode)
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                )
            }
        },
        bottomBar = {
            PrimaryCtaBar(
                enabled = templateId.isNotBlank(),
                onClick = onSetWallpaper,
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            // Two-pane on tablets (>= 840dp): left = header + template domain,
            // right = template-scoped wallpaper settings (after selection).
            if (maxWidth >= 840.dp) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier =
                            Modifier
                                .weight(0.42f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        AppHeader()
                        SectionLabel(stringResource(R.string.template_title))
                        TemplateSection(
                            templates = templates,
                            selectedId = templateId,
                            grid = true,
                            onSelect = { scope.launch { repo.setTemplate(it) } },
                            onDelete = ::deleteTemplate,
                        )
                        SectionLabel(stringResource(R.string.import_section_title))
                        ImportSection(
                            onUrl = {
                                errorMessage = ""
                                showUrlImport = true
                            },
                            onLocal = { showLocalImport = true },
                        )
                        SectionLabel(stringResource(R.string.help_and_about))
                        AboutSection(
                            crashLogCount = crashLogCount,
                            onCrashLogs = {
                                crashLogCount = CrashLogCollector.getLogFiles().size
                                showCrashDialog = true
                            },
                            checkingUpdate = updateChecking,
                            onCheckUpdate = ::checkForUpdate,
                            onLicenses = { context.startActivity(Intent(context, LicenseActivity::class.java)) },
                        )
                        Spacer(Modifier.height(32.dp))
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        modifier =
                            Modifier
                                .weight(0.58f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                    ) {
                        // Slide the settings pane in when switching templates
                        AnimatedContent(
                            targetState = templateId,
                            transitionSpec = {
                                (slideInHorizontally(
                                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                ) { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it / 4 } + fadeOut())
                            },
                            label = "wallpaper-settings-pane",
                        ) { currentId ->
                            Column {
                                if (currentId.isBlank()) {
                                    Text(
                                        stringResource(R.string.select_template_hint),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 28.dp),
                                    )
                                } else {
                                    val current = templates.firstOrNull { it.id == currentId }
                                    current?.let {
                                        Text(
                                            text = it.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier =
                                                Modifier.padding(
                                                    start = 28.dp,
                                                    end = 28.dp,
                                                    bottom = 10.dp,
                                                ),
                                        )
                                    }
                                    BehaviorSection(
                                        selectedEffect = selectedEffect,
                                        onEffectChange = { scope.launch { repo.setPortraitEffect(it) } },
                                        dragEnabled = dragEnabled,
                                        onDragChange = { scope.launch { repo.setDragEnabled(it) } },
                                        swayEnabled = swayEnabled,
                                        onSwayChange = { scope.launch { repo.setSwayEnabled(it) } },
                                        swaySetting = { SwayAmplitudeSetting(repo = repo) },
                                tiltAdjust = { PortraitTiltSetting(repo = repo) },
                                        frameRateOptions = frameRateOptions,
                                        currentFrameRateMode = currentFrameRateMode,
                                        onFrameRateChange = { scope.launch { repo.setFrameRateMode(it) } },
                                        tiltSetting = { TiltSensitivitySetting(repo = repo) },
                                        backgroundAdjust = {
                                            BackgroundAdjustSettings(templateId = currentId)
                                        },
                                    )
                                    if (current != null && current.isRemote) {
                                        Spacer(Modifier.height(16.dp))
                                        TemplateDeleteSection(onDelete = { deleteTemplate(current) })
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                    }
                }
            } else {
                // Phones (< 840dp): main page <-> wallpaper settings page,
                // progressive hierarchy.
                val availableWidth = maxWidth
                Crossfade(targetState = showWallpaperSettings, label = "wallpaper-settings") { inSettings ->
                    if (inSettings) {
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                        ) {
                            DetailTopBar(title = selectedName, onBack = { showWallpaperSettings = false })
                            BehaviorSection(
                                selectedEffect = selectedEffect,
                                onEffectChange = { scope.launch { repo.setPortraitEffect(it) } },
                                dragEnabled = dragEnabled,
                                onDragChange = { scope.launch { repo.setDragEnabled(it) } },
                                swayEnabled = swayEnabled,
                                onSwayChange = { scope.launch { repo.setSwayEnabled(it) } },
                                swaySetting = { SwayAmplitudeSetting(repo = repo) },
                                tiltAdjust = { PortraitTiltSetting(repo = repo) },
                                frameRateOptions = frameRateOptions,
                                currentFrameRateMode = currentFrameRateMode,
                                onFrameRateChange = { scope.launch { repo.setFrameRateMode(it) } },
                                tiltSetting = { TiltSensitivitySetting(repo = repo) },
                                backgroundAdjust = { BackgroundAdjustSettings(templateId = templateId) },
                            )
                            templates.firstOrNull { it.id == templateId }?.let { current ->
                                if (current.isRemote) {
                                    Spacer(Modifier.height(16.dp))
                                    TemplateDeleteSection(onDelete = { deleteTemplate(current) })
                                }
                            }
                            Spacer(Modifier.height(32.dp))
                        }
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            LazyColumn(
                                modifier = Modifier.widthIn(max = 640.dp),
                            ) {
                                item { AppHeader() }
                                item { SectionLabel(stringResource(R.string.template_title)) }
                                item {
                                    TemplateSection(
                                        templates = templates,
                                        selectedId = templateId,
                                        grid = availableWidth >= 600.dp,
                                        onSelect = { scope.launch { repo.setTemplate(it) } },
                                        onDelete = ::deleteTemplate,
                                    )
                                }
                                item {
                                    GroupCard {
                                        SettingsRow(
                                            title = stringResource(R.string.wallpaper_settings_title),
                                            subtitle =
                                                selectedName
                                                    ?: stringResource(R.string.select_template_hint),
                                            iconRes = R.drawable.ic_tune,
                                            onClick =
                                                if (templateId.isNotBlank()) {
                                                    { showWallpaperSettings = true }
                                                } else {
                                                    null
                                                },
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                item { SectionLabel(stringResource(R.string.import_section_title)) }
                                item {
                                    ImportSection(
                                        onUrl = {
                                            errorMessage = ""
                                            showUrlImport = true
                                        },
                                        onLocal = { showLocalImport = true },
                                    )
                                }
                                item { SectionLabel(stringResource(R.string.help_and_about)) }
                                item {
                                    AboutSection(
                                        crashLogCount = crashLogCount,
                                        onCrashLogs = {
                                            crashLogCount = CrashLogCollector.getLogFiles().size
                                            showCrashDialog = true
                                        },
                                        checkingUpdate = updateChecking,
                                        onCheckUpdate = ::checkForUpdate,
                                        onLicenses = {
                                            context.startActivity(Intent(context, LicenseActivity::class.java))
                                        },
                                    )
                                }
                                item { Spacer(Modifier.height(32.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Dialogs ----

    DeleteTemplateDialog(
        template = pendingDelete,
        onConfirm = ::confirmDeleteTemplate,
        onDismiss = { pendingDelete = null },
    )

    if (showUrlImport) {
        AlertDialog(
            onDismissRequest = { if (!isLoading) showUrlImport = false },
            title = { Text(stringResource(R.string.import_remote_template)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.import_remote_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text(stringResource(R.string.zip_url_label)) },
                        singleLine = true,
                        enabled = !isLoading,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (errorMessage.isNotEmpty()) {
                        Text(
                            errorMessage,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier =
                                Modifier
                                    .padding(top = 8.dp)
                                    .size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { importTemplate() }, enabled = !isLoading) {
                    Text(stringResource(R.string.import_button))
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlImport = false }, enabled = !isLoading) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showLocalImport) {
        AlertDialog(
            onDismissRequest = { showLocalImport = false },
            title = { Text(stringResource(R.string.local_import)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.local_import_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                showLocalImport = false
                                zipPickerLauncher.launch(
                                    arrayOf(
                                        "application/zip",
                                        "application/x-zip-compressed",
                                    ),
                                )
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.zip_file))
                        }
                        Button(
                            onClick = {
                                showLocalImport = false
                                dirPickerLauncher.launch(null)
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(stringResource(R.string.directory))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLocalImport = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showCrashDialog) {
        val crashLogs = CrashLogCollector.getLogFiles()
        AlertDialog(
            onDismissRequest = { showCrashDialog = false },
            title = { Text(stringResource(R.string.crash_logs_title)) },
            text = {
                Column {
                    if (crashLogs.isEmpty()) {
                        Text(
                            stringResource(R.string.no_crash_logs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            crashLogs.first().name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            crashLogs.firstOrNull()?.let { log ->
                                val intent =
                                    CrashLogCollector.shareLog(
                                        context.applicationContext as FloatDeckApp,
                                        log,
                                    )
                                context.startActivity(Intent.createChooser(intent, null))
                            }
                        },
                        enabled = crashLogs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.share_crash_log))
                    }
                    TextButton(
                        onClick = {
                            crashLogs.firstOrNull()?.let { log ->
                                val ok = CrashLogCollector.saveToDownloads(context, log)
                                val msg =
                                    if (ok) {
                                        context.getString(R.string.saved_to_downloads, log.name)
                                    } else {
                                        context.getString(R.string.save_to_downloads_failed)
                                    }
                                scope.launch { snackbarHostState.showSnackbar(msg) }
                            }
                        },
                        enabled = crashLogs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.save_to_downloads))
                    }
                    TextButton(
                        onClick = {
                            CrashLogCollector.clearLogs()
                            crashLogCount = CrashLogCollector.getLogFiles().size
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.crash_logs_cleared),
                                )
                            }
                        },
                        enabled = crashLogs.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.clear_crash_log))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCrashDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
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
                TextButton(
                    onClick = {
                        showHttpWarning = false
                        importTemplate(allowHttp = true)
                    },
                ) { Text(stringResource(R.string.http_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showHttpWarning = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

/** Template gallery: card carousel on narrow screens, two-column grid on wide ones. */
@Composable
private fun TemplateSection(
    templates: List<TemplateUi>,
    selectedId: String,
    grid: Boolean,
    onSelect: (String) -> Unit,
    onDelete: (TemplateUi) -> Unit,
) {
    if (templates.isEmpty()) {
        Text(
            stringResource(R.string.no_templates_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        return
    }
    if (!grid) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(vertical = 4.dp),
        ) {
            items(templates, key = { it.id }) { item ->
                TemplateCard(
                    item = item,
                    selected = selectedId == item.id,
                    onClick = { onSelect(item.id) },
                    onDelete = if (item.isRemote) ({ onDelete(item) }) else null,
                    imageHeight = 150.dp,
                    useBottomSheet = !grid,
                    modifier = Modifier.width(172.dp),
                )
            }
        }
    } else {
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            templates.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { item ->
                        TemplateCard(
                            item = item,
                            selected = selectedId == item.id,
                            onClick = { onSelect(item.id) },
                            onDelete = if (item.isRemote) ({ onDelete(item) }) else null,
                            imageHeight = 170.dp,
                            useBottomSheet = !grid,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (row.size == 1) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Wallpaper settings (template-scoped), grouped into labeled cards:
 * portraits (effect/drag/sway), parallax & background, performance. */
/** Bold header at the top of a settings group card. */
@Composable
private fun SettingsGroupLabel(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

@Composable
private fun BehaviorSection(
    selectedEffect: PortraitEffect,
    onEffectChange: (PortraitEffect) -> Unit,
    dragEnabled: Boolean,
    onDragChange: (Boolean) -> Unit,
    swayEnabled: Boolean,
    onSwayChange: (Boolean) -> Unit,
    swaySetting: @Composable () -> Unit = {},
    tiltAdjust: @Composable () -> Unit = {},
    frameRateOptions: List<Pair<String, String>>,
    currentFrameRateMode: String,
    onFrameRateChange: (String) -> Unit,
    tiltSetting: @Composable () -> Unit = {},
    backgroundAdjust: @Composable () -> Unit = {},
) {
    GroupCard {
        SettingsGroupLabel(R.string.settings_group_portrait)
        Text(
            text = stringResource(R.string.effect_title),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        )
        EffectSelector(
            selected = selectedEffect,
            onSelect = onEffectChange,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        SettingsRow(
            title = stringResource(R.string.drag_enabled),
            iconRes = R.drawable.ic_tune,
            onClick = { onDragChange(!dragEnabled) },
        ) {
            Switch(
                checked = dragEnabled,
                onCheckedChange = onDragChange,
            )
        }
        SettingsRow(
            title = stringResource(R.string.sway_enabled_title),
            iconRes = R.drawable.ic_tune,
            onClick = { onSwayChange(!swayEnabled) },
        ) {
            Switch(
                checked = swayEnabled,
                onCheckedChange = onSwayChange,
            )
        }
        Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp)) { swaySetting() }
        Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp)) { tiltAdjust() }
    }
    Spacer(Modifier.height(16.dp))
    GroupCard {
        SettingsGroupLabel(R.string.settings_group_scene)
        Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp)) { tiltSetting() }
        Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 8.dp)) { backgroundAdjust() }
    }
    Spacer(Modifier.height(16.dp))
    GroupCard {
        Box {
            var menuExpanded by remember { mutableStateOf(false) }
            SettingsRow(
                title = stringResource(R.string.frame_rate_title),
                iconRes = R.drawable.ic_speed,
                onClick = { menuExpanded = true },
            ) {
                Text(
                    text =
                        frameRateOptions
                            .firstOrNull { it.first == currentFrameRateMode }
                            ?.second
                            .orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                frameRateOptions.forEach { (mode, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onFrameRateChange(mode)
                            menuExpanded = false
                        },
                    )
                }
            }
        }
    }
}

/** Import group: remote URL and local file entries. */
@Composable
private fun ImportSection(
    onUrl: () -> Unit,
    onLocal: () -> Unit,
) {
    GroupCard {
        SettingsRow(
            title = stringResource(R.string.import_remote_template),
            subtitle = stringResource(R.string.import_remote_desc),
            iconRes = R.drawable.ic_link,
            onClick = onUrl,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsRow(
            title = stringResource(R.string.local_import),
            subtitle = stringResource(R.string.local_import_desc),
            iconRes = R.drawable.ic_folder,
            onClick = onLocal,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Wallpaper settings page top bar: back button + current template name. */
@Composable
private fun DetailTopBar(
    title: String?,
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(end = 16.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.back),
            )
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
