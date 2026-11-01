package com.example.carmusicmeter.teyes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.example.carmusicmeter.data.McuVolume

/**
 * Минимальный клиент вендорного сервиса FYT/Teyes `com.syu.ms`.
 *
 * Протокол — обычные binder-транзакции AIDL-интерфейсов
 * `com.syu.ipc.IRemoteToolkit` / `IRemoteModule` / `IModuleCallback`.
 * Нас интересует только модуль `sound` (код 4) и коды `U_VOL=2`, `U_MUTE=3`.
 * Никаких команд изменения громкости не отправляется.
 */
class TeyesToolkitClient(private val context: Context) {

    data class Status(
        val available: Boolean = false,
        val connected: Boolean = false,
        val module: Boolean = false,
        val volume: Int? = null,
        val muted: Boolean? = null,
        val error: String? = null,
        val updatedAtMs: Long = 0L,
    ) {
        val signature: String
            get() = listOf(available, connected, module, volume, muted, error).joinToString("|")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val callback = ModuleCallback()
    private var module: IBinder? = null
    private var started = false
    private var binding = false
    private var bound = false

    @Volatile
    private var status = Status()

    private val retry = Runnable { bind() }

    private val connection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binding = false
            bound = true
            if (service == null) {
                update(error = "empty toolkit binder")
                return
            }
            val soundModule = runCatching { getRemoteModule(service, SOUND_MODULE) }
                .getOrElse { error ->
                    update(error = "getRemoteModule: ${error.message}")
                    null
                }
            if (soundModule == null) {
                runCatching { context.unbindService(connection) }
                bound = false
                update(
                    connected = true,
                    module = false,
                    error = "sound module is null",
                )
                scheduleRetry()
                return
            }
            module = soundModule
            val volume = runCatching { getInt(soundModule, U_VOL) }.getOrNull()
            val mute = runCatching { getInt(soundModule, U_MUTE) }.getOrNull()
            update(
                available = true,
                connected = true,
                module = true,
                volume = volume,
                muted = mute?.let { it != 0 },
                error = null,
            )
            runCatching {
                register(soundModule, callback, U_VOL)
                register(soundModule, callback, U_MUTE)
            }.onFailure { error ->
                update(error = "register: ${error.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            module = null
            bound = false
            update(
                available = false,
                connected = false,
                module = false,
                volume = null,
                muted = null,
                error = "toolkit disconnected",
            )
            scheduleRetry()
        }

        override fun onBindingDied(name: ComponentName?) {
            module = null
            bound = false
            update(
                available = false,
                connected = false,
                module = false,
                volume = null,
                muted = null,
                error = "toolkit binding died",
            )
            scheduleRetry()
        }

        override fun onNullBinding(name: ComponentName?) {
            binding = false
            bound = false
            update(
                available = false,
                connected = false,
                module = false,
                error = "toolkit returned null binding",
            )
            scheduleRetry()
        }
    }

    fun start() {
        if (started) return
        started = true
        bind()
    }

    fun stop() {
        started = false
        handler.removeCallbacks(retry)
        val soundModule = module
        module = null
        if (soundModule != null) {
            runCatching { unregister(soundModule, callback, U_VOL) }
            runCatching { unregister(soundModule, callback, U_MUTE) }
        }
        if (bound) {
            runCatching { context.unbindService(connection) }
        }
        bound = false
        binding = false
        status = Status(error = "stopped", updatedAtMs = System.currentTimeMillis())
    }

    fun status(): Status = status

    /** Периодически перечитывает значения, если callback вендора молчит. */
    fun refresh() {
        val soundModule = module ?: return
        if (!soundModule.isBinderAlive) {
            module = null
            update(
                available = false,
                connected = false,
                module = false,
                volume = null,
                muted = null,
                error = "toolkit binder died",
            )
            scheduleRetry()
            return
        }
        val volume = runCatching { getInt(soundModule, U_VOL) }.getOrNull()
        val mute = runCatching { getInt(soundModule, U_MUTE) }.getOrNull()
        if (volume != null) {
            update(
                available = true,
                connected = true,
                module = true,
                volume = volume,
                muted = mute?.let { it != 0 } ?: status.muted,
                error = null,
            )
        }
    }

    /** Свежее значение для выбора громкости; null — используем AudioManager. */
    fun reading(nowMs: Long = System.currentTimeMillis()): McuVolume? {
        val current = status
        if (!current.module || current.volume == null) return null
        if (current.updatedAtMs > 0 && nowMs - current.updatedAtMs > STALE_MS) return null
        return McuVolume(
            volume = current.volume,
            muted = current.muted,
            updatedAtMs = current.updatedAtMs,
        )
    }

    private fun bind() {
        if (!started || binding || bound || module != null) return
        binding = true
        val intent = Intent(ACTION_TOOLKIT).setComponent(
            ComponentName(PACKAGE, SERVICE),
        )
        val resolved = runCatching { context.packageManager.resolveService(intent, 0) }
            .getOrNull() != null
        if (!resolved) {
            binding = false
            update(
                available = false,
                connected = false,
                module = false,
                error = "toolkit service not found",
            )
            scheduleRetry()
            return
        }
        val ok = runCatching {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            update(
                available = false,
                connected = false,
                module = false,
                error = "bindService: ${error.javaClass.simpleName}: ${error.message}",
            )
            false
        }
        if (!ok) {
            binding = false
            update(
                available = false,
                connected = false,
                module = false,
                error = status.error ?: "bindService returned false",
            )
            scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!started) return
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, RETRY_MS)
    }

    private fun update(
        available: Boolean = status.available,
        connected: Boolean = status.connected,
        module: Boolean = status.module,
        volume: Int? = status.volume,
        muted: Boolean? = status.muted,
        error: String? = status.error,
    ) {
        status = Status(
            available = available,
            connected = connected,
            module = module,
            volume = volume,
            muted = muted,
            error = error,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    private fun onModuleUpdate(updateCode: Int, ints: IntArray?) {
        val value = ints?.firstOrNull() ?: return
        when (updateCode) {
            U_VOL -> update(
                available = true,
                connected = true,
                module = true,
                volume = value,
                error = null,
            )
            U_MUTE -> update(
                available = true,
                connected = true,
                module = true,
                muted = value != 0,
                error = null,
            )
        }
    }

    private inner class ModuleCallback : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == 1) {
                data.enforceInterface(CALLBACK_DESCRIPTOR)
                val updateCode = data.readInt()
                val ints = data.createIntArray()
                data.createFloatArray()
                data.createStringArray()
                onModuleUpdate(updateCode, ints)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    private fun getRemoteModule(toolkit: IBinder, moduleCode: Int): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TOOLKIT_DESCRIPTOR)
            data.writeInt(moduleCode)
            if (!toolkit.transact(1, data, reply, 0)) return null
            reply.readException()
            return reply.readStrongBinder()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun getInt(module: IBinder, code: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            data.writeInt(code)
            data.writeIntArray(null)
            data.writeFloatArray(null)
            data.writeStringArray(null)
            if (!module.transact(2, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() == 0) return null
            return reply.createIntArray()?.firstOrNull()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun register(module: IBinder, callback: IBinder, updateCode: Int) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.writeInt(updateCode)
            data.writeInt(1)
            module.transact(3, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    private fun unregister(module: IBinder, callback: IBinder, updateCode: Int) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR)
            data.writeStrongBinder(callback)
            data.writeInt(updateCode)
            module.transact(4, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    private companion object {
        const val PACKAGE = "com.syu.ms"
        const val SERVICE = "app.ToolkitService"
        const val ACTION_TOOLKIT = "com.syu.ms.toolkit"
        const val TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit"
        const val MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule"
        const val CALLBACK_DESCRIPTOR = "com.syu.ipc.IModuleCallback"

        const val SOUND_MODULE = 4
        const val U_VOL = 2
        const val U_MUTE = 3

        const val RETRY_MS = 15_000L
        const val STALE_MS = 60_000L
    }
}
