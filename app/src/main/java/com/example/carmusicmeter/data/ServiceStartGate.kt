package com.example.carmusicmeter.data

/** Пропускает только первое событие старта на один экземпляр службы. */
class ServiceStartGate {

    private var logged = false

    fun shouldLog(): Boolean {
        if (logged) return false
        logged = true
        return true
    }

    fun reset() {
        logged = false
    }
}

/** Определяет, стартовала ли служба в новом процессе. */
object ServiceProcessInfo {

    @Volatile
    private var lastPid: Int? = null

    fun newProcess(pid: Int): Boolean {
        val fresh = lastPid != pid
        lastPid = pid
        return fresh
    }

    fun reset() {
        lastPid = null
    }
}
