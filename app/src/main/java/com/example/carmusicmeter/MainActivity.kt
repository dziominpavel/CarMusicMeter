package com.example.carmusicmeter

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.carmusicmeter.data.ExportInfo
import com.example.carmusicmeter.data.ExportPlan
import com.example.carmusicmeter.data.ExportRange
import com.example.carmusicmeter.data.ExportStorage
import com.example.carmusicmeter.data.PlayExport
import com.example.carmusicmeter.data.PlayFactsDiagnostics
import com.example.carmusicmeter.data.PlaySource
import com.example.carmusicmeter.data.ReportExporter
import com.example.carmusicmeter.data.ReportRepository
import com.example.carmusicmeter.data.ReportUiState
import com.example.carmusicmeter.data.metrics.MetricsEngine
import com.example.carmusicmeter.data.metrics.toReportPeriod
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.NotificationListenerStatus
import com.example.carmusicmeter.data.Prefs
import com.example.carmusicmeter.data.RetentionPlan
import com.example.carmusicmeter.data.LaunchSplashGate
import com.example.carmusicmeter.data.StoragePolicy
import com.example.carmusicmeter.autostart.AutostartScheduler
import com.example.carmusicmeter.data.StorageStats
import com.example.carmusicmeter.ui.formatBytes
import com.example.carmusicmeter.diagnostics.CrashLogger
import com.example.carmusicmeter.diagnostics.SnapshotWriter
import com.example.carmusicmeter.probe.MediaSessionProbe
import com.example.carmusicmeter.service.AccessibilityAccess
import com.example.carmusicmeter.service.NotificationAccess
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController
import com.example.carmusicmeter.ui.ExportDialog
import com.example.carmusicmeter.ui.ExportProgressDialog
import com.example.carmusicmeter.ui.ExportResult
import com.example.carmusicmeter.ui.ExportResultDialog
import com.example.carmusicmeter.ui.AllTracksScreen
import com.example.carmusicmeter.ui.DashboardScreen
import com.example.carmusicmeter.ui.DiagnosticsScreen
import com.example.carmusicmeter.ui.QualityScreen
import com.example.carmusicmeter.ui.ReportScreen
import com.example.carmusicmeter.ui.TrackDetailScreen
import com.example.carmusicmeter.ui.LaunchSplash
import com.example.carmusicmeter.ui.SettingsScreen
import com.example.carmusicmeter.ui.theme.CarMusicMeterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class AppScreen {
    DASHBOARD,
    REPORT,
    TRACK_DETAIL,
    QUALITY,
    ALL_TRACKS,
    DIAGNOSTICS,
    SETTINGS,
}

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private lateinit var reportRepository: ReportRepository
    private val exportResult = mutableStateOf<ExportResult?>(null)
    private val exportBusy = mutableStateOf(false)
    private val storageStats = mutableStateOf<StorageStats?>(null)
    private var pendingExport: PendingExport? = null

    private data class PendingExport(
        val fileName: String,
        val plan: ExportPlan,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = (application as CarMusicMeterApp).prefs
        reportRepository = ReportRepository(
            baseDir = filesDir,
            deviceId = prefs.deviceId,
            appVersion = AppVersion.name(this),
            liveSince = { prefs.liveSince },
            scope = lifecycleScope,
        )
        if (prefs.autoStart && !ListeningRepository.state.value.serviceRunning) {
            runCatching {
                WatchServiceController.start(this, StartSource.APP_OPEN)
            }
        }

        setContent {
            CarMusicMeterTheme {
                val state by ListeningRepository.state.collectAsState()
                val reportState by reportRepository.state.collectAsState()
                val app = application as CarMusicMeterApp
                val busy by exportBusy
                val stats = storageStats.value
                var autoStart by remember { mutableStateOf(prefs.autoStart) }
                var retentionDays by remember { mutableStateOf(prefs.retentionDays) }
                var exportRange by remember { mutableStateOf(prefs.exportRange) }
                var showExportDialog by remember { mutableStateOf(false) }
                var exportPlan by remember { mutableStateOf<ExportPlan?>(null) }
                var exportSnapshotTaken by remember { mutableStateOf(false) }
                var pendingRetention by remember { mutableStateOf<RetentionPlan?>(null) }
                var pendingRetentionDays by remember { mutableStateOf<Int?>(null) }
                var screenName by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD.name) }
                var splashVisible by remember { mutableStateOf(LaunchSplashGate.shouldShow()) }
                var playFacts by remember { mutableStateOf(PlayFactsDiagnostics()) }
                var reportPeriod by remember { mutableStateOf(prefs.reportPeriod) }
                var selectedTrackKey by remember { mutableStateOf<String?>(null) }

                BackHandler(enabled = screenName != AppScreen.DASHBOARD.name) {
                    screenName = when (AppScreen.valueOf(screenName)) {
                        AppScreen.TRACK_DETAIL,
                        AppScreen.QUALITY,
                        AppScreen.ALL_TRACKS,
                        -> AppScreen.REPORT.name

                        else -> AppScreen.DASHBOARD.name
                    }
                }

                LaunchedEffect(screenName) {
                    if (screenName == AppScreen.DIAGNOSTICS.name) {
                        playFacts = withContext(Dispatchers.IO) {
                            PlaySource(
                                baseDir = filesDir,
                                deviceId = prefs.deviceId,
                                appVersion = AppVersion.name(this@MainActivity),
                                liveSince = prefs.liveSince,
                            ).diagnostics()
                        }
                    }
                }

                LaunchedEffect(screenName, reportPeriod) {
                    val reportScreen = screenName == AppScreen.REPORT.name ||
                        screenName == AppScreen.TRACK_DETAIL.name ||
                        screenName == AppScreen.QUALITY.name ||
                        screenName == AppScreen.ALL_TRACKS.name
                    if (reportScreen &&
                        (screenName == AppScreen.REPORT.name || reportState !is ReportUiState.Ready)
                    ) {
                        reportRepository.load(reportPeriod)
                    }
                }

                val applyRetention: (Int) -> Unit = { days ->
                    retentionDays = days
                    prefs.retentionDays = days
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            app.eventLog.trimOld(prefs.storagePolicy())
                        }
                        refreshStorageStats()
                    }
                    toast("Срок хранения: ${retentionLabel(days)}")
                }

                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    if (uri != null) {
                        exportLogs(uri)
                    } else {
                        pendingExport = null
                        exportBusy.value = false
                    }
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    refreshStorageStats()
                }

                // Снимок снимается один раз при открытии диалога и до расчёта
                // плана: иначе свежий файл не попадёт в архив и счётчик соврёт.
                LaunchedEffect(showExportDialog) {
                    if (showExportDialog) {
                        exportSnapshotTaken = withContext(Dispatchers.IO) {
                            captureExportSnapshot(app)
                        }
                        exportPlan = withContext(Dispatchers.IO) {
                            app.eventLog.planExport(exportRange)
                        }
                    }
                }

                LaunchedEffect(exportRange) {
                    if (showExportDialog) {
                        exportPlan = withContext(Dispatchers.IO) {
                            app.eventLog.planExport(exportRange)
                        }
                    }
                }

                LaunchedEffect(screenName) {
                    if (screenName == AppScreen.SETTINGS.name) {
                        refreshStorageStats()
                    }
                }

                val onStartService: () -> Unit = {
                    runCatching {
                        WatchServiceController.start(this@MainActivity, StartSource.MANUAL)
                    }
                        .onSuccess { toast("Служба запускается") }
                        .onFailure { toast("Не удалось запустить: ${it.message}") }
                }
                val onStopService: () -> Unit = {
                    WatchServiceController.stop(this@MainActivity)
                    toast("Служба остановлена")
                }
                val onOpenNotificationAccess: () -> Unit = {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    }.onFailure {
                        toast("Открой настройки вручную: Приложения → Спецдоступ → Доступ к уведомлениям")
                    }
                }
                val onOpenBatterySettings: () -> Unit = { openBatterySettings() }
                val onOpenAccessibilitySettings: () -> Unit = {
                    if (!AccessibilityAccess.openSettings(this@MainActivity)) {
                        toast("Открой Настройки → Спецдоступ → Спецвозможности")
                    }
                }
                val onExport: () -> Unit = { showExportDialog = true }
                val onOpenReport: () -> Unit = { screenName = AppScreen.REPORT.name }
                val reportScreen: @Composable () -> Unit = {
                    ReportScreen(
                        state = reportState,
                        period = reportPeriod,
                        onPeriodChange = { period ->
                            reportPeriod = period
                            prefs.reportPeriod = period
                        },
                        onBack = { screenName = AppScreen.DASHBOARD.name },
                        onRetry = { reportRepository.retry() },
                        onTrackClick = { key ->
                            selectedTrackKey = key
                            screenName = AppScreen.TRACK_DETAIL.name
                        },
                        onQualityClick = { screenName = AppScreen.QUALITY.name },
                        onAllTracks = { screenName = AppScreen.ALL_TRACKS.name },
                        onExport = onExport,
                    )
                }
                val reportHint = (reportState as? ReportUiState.Ready)?.let {
                    "В отчёте сейчас: " + it.model.tracks.size + " треков, " +
                        it.model.summary.plays + " прослушиваний"
                } ?: "В ZIP также попадут факты и отчёт"

                Box(modifier = Modifier.fillMaxSize()) {
                    when (AppScreen.valueOf(screenName)) {
                        AppScreen.DASHBOARD -> DashboardScreen(
                            state = state,
                            onOpenDiagnostics = { screenName = AppScreen.DIAGNOSTICS.name },
                            onOpenSettings = { screenName = AppScreen.SETTINGS.name },
                            onOpenReport = onOpenReport,
                            onStartService = onStartService,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onExport = onExport,
                        )

                        AppScreen.REPORT -> reportScreen()

                        AppScreen.TRACK_DETAIL -> {
                            val ready = reportState as? ReportUiState.Ready
                            if (ready == null) {
                                reportScreen()
                            } else {
                                TrackDetailScreen(
                                    model = ready.model,
                                    trackKey = selectedTrackKey,
                                    onBack = { screenName = AppScreen.REPORT.name },
                                )
                            }
                        }

                        AppScreen.QUALITY -> {
                            val ready = reportState as? ReportUiState.Ready
                            if (ready == null) {
                                reportScreen()
                            } else {
                                QualityScreen(
                                    model = ready.model,
                                    onBack = { screenName = AppScreen.REPORT.name },
                                )
                            }
                        }

                        AppScreen.ALL_TRACKS -> {
                            val ready = reportState as? ReportUiState.Ready
                            if (ready == null) {
                                reportScreen()
                            } else {
                                AllTracksScreen(
                                    model = ready.model,
                                    onBack = { screenName = AppScreen.REPORT.name },
                                    onTrackClick = { key ->
                                        selectedTrackKey = key
                                        screenName = AppScreen.TRACK_DETAIL.name
                                    },
                                )
                            }
                        }

                        AppScreen.DIAGNOSTICS -> DiagnosticsScreen(
                            state = state,
                            playFacts = playFacts,
                            onBack = { screenName = AppScreen.DASHBOARD.name },
                            onStartService = onStartService,
                            onStopService = onStopService,
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        )

                        AppScreen.SETTINGS -> SettingsScreen(
                            state = state,
                            autoStart = autoStart,
                            storageStats = stats,
                            retentionDays = retentionDays,
                            onBack = { screenName = AppScreen.DASHBOARD.name },
                            onAutoStartChange = { enabled ->
                                autoStart = enabled
                                prefs.autoStart = enabled
                                AutostartScheduler.sync(this@MainActivity, enabled)
                                if (enabled && !state.serviceRunning) {
                                    runCatching {
                                        WatchServiceController.start(
                                            this@MainActivity,
                                            StartSource.APP_OPEN,
                                        )
                                    }
                                }
                                toast(if (enabled) "Автозапуск включён" else "Автозапуск выключен")
                            },
                            onRetentionChange = { days ->
                                if (days == 0) {
                                    applyRetention(days)
                                } else {
                                    lifecycleScope.launch {
                                        val plan = withContext(Dispatchers.IO) {
                                            app.eventLog.planTrim(
                                                StoragePolicy(
                                                    retentionDays = days,
                                                    maxBytes = prefs.maxBytes,
                                                ),
                                            )
                                        }
                                        if (plan.isEmpty) {
                                            applyRetention(days)
                                        } else {
                                            pendingRetentionDays = days
                                            pendingRetention = plan
                                        }
                                    }
                                }
                            },
                            onOpenNotificationAccess = onOpenNotificationAccess,
                            onOpenBatterySettings = onOpenBatterySettings,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        )
                    }

                    if (splashVisible) {
                        LaunchSplash(onFinished = { splashVisible = false })
                    }
                }

                val retentionPlan = pendingRetention
                val retentionDaysPending = pendingRetentionDays
                if (retentionPlan != null && retentionDaysPending != null) {
                    AlertDialog(
                        onDismissRequest = {
                            pendingRetention = null
                            pendingRetentionDays = null
                        },
                        title = { Text("Удалить старые логи?") },
                        text = {
                            Text(
                                "Будет удалено ${retentionPlan.all.size} файлов " +
                                    "(${formatBytes(retentionPlan.totalBytes)}) старше " +
                                    "$retentionDaysPending дней. Удаление необратимо.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pendingRetention = null
                                    pendingRetentionDays = null
                                    applyRetention(retentionDaysPending)
                                },
                            ) {
                                Text("Удалить")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = {
                                    pendingRetention = null
                                    pendingRetentionDays = null
                                },
                            ) {
                                Text("Отмена")
                            }
                        },
                    )
                }

                if (showExportDialog) {
                    ExportDialog(
                        range = exportRange,
                        plan = exportPlan,
                        freshSnapshot = exportSnapshotTaken,
                        reportHint = reportHint,
                        onRangeChange = { range ->
                            if (range != exportRange) {
                                exportRange = range
                                prefs.exportRange = range
                                exportPlan = null
                            }
                        },
                        onConfirm = {
                            val plan = exportPlan
                            if (plan == null || plan.isEmpty) {
                                toast("Нет данных за период")
                            } else {
                                showExportDialog = false
                                val name = exportFileName(plan.range)
                                pendingExport = PendingExport(name, plan)
                                runCatching { exportLauncher.launch(name) }
                                    .onFailure { error ->
                                        if (error is ActivityNotFoundException) {
                                            exportToDownloads(name)
                                        } else {
                                            pendingExport = null
                                            toast("Не удалось открыть сохранение: ${error.message}")
                                        }
                                    }
                            }
                        },
                        onDismiss = { showExportDialog = false },
                    )
                }
                if (busy) {
                    ExportProgressDialog()
                }
                exportResult.value?.let { result ->
                    ExportResultDialog(
                        result = result,
                        onShare = { shareExport(result.uri) },
                        onDismiss = { exportResult.value = null },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshEnvironment()
        refreshStorageStats()
    }

    private fun refreshEnvironment() {
        ListeningRepository.setNotificationAccess(NotificationAccess.isGranted(this))
        ListeningRepository.setNotificationListenerConnected(NotificationListenerStatus.connected)
        val power = getSystemService(PowerManager::class.java)
        ListeningRepository.setBatteryUnrestricted(
            power.isIgnoringBatteryOptimizations(packageName),
        )
    }

    @SuppressLint("BatteryLife")
    private fun openBatterySettings() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            toast("Уже без ограничений батареи")
            return
        }
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    "package:$packageName".toUri(),
                ),
            )
        }.onFailure {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }.onFailure {
                toast("Настройки батареи недоступны — открой их вручную")
            }
        }
    }

    /**
     * Снимок «что видно сейчас» перед экспортом. Сбой снимка не должен лишать
     * пользователя возможности выгрузить журнал: ошибка уходит в файл падений,
     * архив собирается без свежего снимка.
     */
    private fun captureExportSnapshot(app: CarMusicMeterApp): Boolean =
        runCatching {
            SnapshotWriter(app, MediaSessionProbe(app)).capture(
                reason = "export",
                live = ListeningRepository.state.value.live,
            )
        }.onFailure { error ->
            CrashLogger.record(error, source = "snapshot_export")
        }.isSuccess

    private fun exportLogs(uri: Uri) {
        val pending = pendingExport ?: return
        exportBusy.value = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { exportTo(uri, pending.plan) }
            }
            result.onSuccess { count ->
                pendingExport = null
                toast("Экспортировано файлов: $count")
                refreshStorageStats()
            }.onFailure { toast("Ошибка экспорта: ${it.message}") }
            exportBusy.value = false
        }
    }

    /**
     * Запасной путь для магнитол без `ACTION_CREATE_DOCUMENT`: ZIP пишется
     * в «Загрузки» через MediaStore, без разрешения на запись.
     */
    private fun exportToDownloads(fileName: String) {
        val pending = pendingExport ?: return
        exportBusy.value = true
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = ExportStorage.createDownloadsZip(contentResolver, fileName)
                    try {
                        val count = exportTo(uri, pending.plan)
                        ExportStorage.publish(contentResolver, uri)
                        count to uri
                    } catch (t: Throwable) {
                        ExportStorage.discard(contentResolver, uri)
                        throw t
                    }
                }
            }
            result.onSuccess { (count, uri) ->
                pendingExport = null
                exportBusy.value = false
                exportResult.value = ExportResult(
                    fileName = fileName,
                    location = ExportStorage.displayPath(fileName),
                    uri = uri,
                )
                toast("Экспортировано файлов: $count")
                refreshStorageStats()
            }.onFailure { toast("Ошибка экспорта: ${it.message}") }
            exportBusy.value = false
        }
    }

    private fun exportTo(uri: Uri, plan: ExportPlan): Int {
        val app = application as CarMusicMeterApp
        val source = PlaySource(
            baseDir = filesDir,
            deviceId = app.prefs.deviceId,
            appVersion = AppVersion.name(this),
            liveSince = app.prefs.liveSince,
        )
        val facts = source.load(plan.requestedFromMs, plan.requestedToMs)
        val bundle = PlayExport.build(facts, plan.range)
        val now = System.currentTimeMillis()
        val model = MetricsEngine.compute(
            plays = facts,
            period = plan.range.toReportPeriod(),
            captureGaps = source.captureGaps(plan.requestedFromMs, plan.requestedToMs),
            nowMs = now,
        )
        val report = ReportExporter.build(model)
        return app.eventLog.exportTo(
            resolver = contentResolver,
            uri = uri,
            info = exportInfo(),
            plan = plan,
            generated = bundle.generated + report.generated,
            plays = bundle.stats,
            report = report.stats,
        )
    }

    private fun exportInfo(): ExportInfo {
        val app = application as CarMusicMeterApp
        return ExportInfo(
            deviceId = app.prefs.deviceId,
            exportSeq = app.prefs.nextExportSeq(),
            appVersion = AppVersion.name(this),
            versionCode = BuildConfig.VERSION_CODE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }

    private fun exportFileName(range: ExportRange): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val device = prefs.deviceId.take(8)
        return "CarMusicMeter-$device-${range.tag}-$stamp.zip"
    }

    private fun refreshStorageStats() {
        lifecycleScope.launch {
            storageStats.value = withContext(Dispatchers.IO) {
                (application as CarMusicMeterApp).eventLog.storageStats()
            }
        }
    }

    private fun retentionLabel(days: Int): String = when (days) {
        0 -> "бессрочно"
        365 -> "1 год"
        else -> "$days дней"
    }

    private fun shareExport(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "Поделиться архивом"))
        }.onFailure {
            toast("Нет приложения, чтобы поделиться файлом")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
