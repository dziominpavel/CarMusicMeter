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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.example.carmusicmeter.data.ExportInfo
import com.example.carmusicmeter.data.ExportStorage
import com.example.carmusicmeter.data.ListeningRepository
import com.example.carmusicmeter.data.Prefs
import com.example.carmusicmeter.diagnostics.SnapshotWriter
import com.example.carmusicmeter.probe.MediaSessionProbe
import com.example.carmusicmeter.service.NotificationAccess
import com.example.carmusicmeter.service.StartSource
import com.example.carmusicmeter.service.WatchServiceController
import com.example.carmusicmeter.ui.ExportResult
import com.example.carmusicmeter.ui.ExportResultDialog
import com.example.carmusicmeter.ui.ProbeScreen
import com.example.carmusicmeter.ui.theme.CarMusicMeterTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs
    private val exportResult = mutableStateOf<ExportResult?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = (application as CarMusicMeterApp).prefs

        setContent {
            CarMusicMeterTheme {
                val state by ListeningRepository.state.collectAsState()
                var autoStart by remember { mutableStateOf(prefs.autoStart) }

                val exportLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.CreateDocument("application/zip"),
                ) { uri ->
                    if (uri != null) exportLogs(uri)
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
                }

                ProbeScreen(
                    state = state,
                    autoStart = autoStart,
                    onAutoStartChange = { enabled ->
                        autoStart = enabled
                        prefs.autoStart = enabled
                        toast(if (enabled) "Автозапуск включён" else "Автозапуск выключен")
                    },
                    onStartService = {
                        runCatching {
                            WatchServiceController.start(this@MainActivity, StartSource.MANUAL)
                        }
                            .onSuccess { toast("Служба запускается") }
                            .onFailure { toast("Не удалось запустить: ${it.message}") }
                    },
                    onStopService = {
                        WatchServiceController.stop(this@MainActivity)
                        toast("Служба остановлена")
                    },
                    onOpenNotificationAccess = {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }.onFailure {
                            toast("Открой настройки вручную: Приложения → Спецдоступ → Доступ к уведомлениям")
                        }
                    },
                    onOpenBatterySettings = { openBatterySettings() },
                    onSnapshot = { takeSnapshot() },
                    onExport = {
                        val name = exportFileName()
                        runCatching { exportLauncher.launch(name) }
                            .onFailure { error ->
                                if (error is ActivityNotFoundException) {
                                    exportToDownloads(name)
                                } else {
                                    toast("Не удалось открыть сохранение: ${error.message}")
                                }
                            }
                    },
                )
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
    }

    private fun refreshEnvironment() {
        ListeningRepository.setNotificationAccess(NotificationAccess.isGranted(this))
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

    private fun takeSnapshot() {
        lifecycleScope.launch {
            val context = this@MainActivity
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SnapshotWriter(context, MediaSessionProbe(context)).capture(
                        reason = "manual",
                        live = ListeningRepository.state.value.live,
                    )
                }
            }
            result.onSuccess { toast("Снимок: ${it.name}") }
                .onFailure { toast("Ошибка снимка: ${it.message}") }
        }
    }

    private fun exportLogs(uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { exportTo(uri) }
            }
            result.onSuccess { count -> toast("Экспортировано файлов: $count") }
                .onFailure { toast("Ошибка экспорта: ${it.message}") }
        }
    }

    /**
     * Запасной путь для магнитол без `ACTION_CREATE_DOCUMENT`: ZIP пишется
     * в «Загрузки» через MediaStore, без разрешения на запись.
     */
    private fun exportToDownloads(fileName: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val uri = ExportStorage.createDownloadsZip(contentResolver, fileName)
                    try {
                        val count = exportTo(uri)
                        ExportStorage.publish(contentResolver, uri)
                        count to uri
                    } catch (t: Throwable) {
                        ExportStorage.discard(contentResolver, uri)
                        throw t
                    }
                }
            }
            result.onSuccess { (count, uri) ->
                exportResult.value = ExportResult(
                    fileName = fileName,
                    location = ExportStorage.displayPath(fileName),
                    uri = uri,
                )
                toast("Экспортировано файлов: $count")
            }.onFailure { toast("Ошибка экспорта: ${it.message}") }
        }
    }

    private fun exportTo(uri: Uri): Int {
        val app = application as CarMusicMeterApp
        return app.eventLog.exportTo(
            resolver = contentResolver,
            uri = uri,
            info = exportInfo(),
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

    private fun exportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val device = prefs.deviceId.take(8)
        return "CarMusicMeter-$device-logs-$stamp.zip"
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
