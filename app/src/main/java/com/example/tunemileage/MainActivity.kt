package com.example.tunemileage

import android.Manifest
import android.annotation.SuppressLint
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
import com.example.tunemileage.data.ListeningRepository
import com.example.tunemileage.data.Prefs
import com.example.tunemileage.diagnostics.SnapshotWriter
import com.example.tunemileage.probe.MediaSessionProbe
import com.example.tunemileage.service.NotificationAccess
import com.example.tunemileage.service.WatchServiceController
import com.example.tunemileage.ui.ProbeScreen
import com.example.tunemileage.ui.theme.TuneMileageTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = (application as TuneMileageApp).prefs

        setContent {
            TuneMileageTheme {
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
                        runCatching { WatchServiceController.start(this@MainActivity) }
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
                        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                        exportLauncher.launch("TuneMileage-logs-$stamp.zip")
                    },
                )
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
            val app = application as TuneMileageApp
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.eventLog.exportTo(
                        resolver = contentResolver,
                        uri = uri,
                        appVersion = AppVersion.name(this@MainActivity),
                        versionCode = BuildConfig.VERSION_CODE,
                    )
                }
            }
            result.onSuccess { count -> toast("Экспортировано файлов: $count") }
                .onFailure { toast("Ошибка экспорта: ${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
