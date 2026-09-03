package com.redt.session

import android.content.Context
import com.redt.RedTApp
import com.redt.service.TerminalSessionClientBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap

internal class TerminalSessionStore {

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _exitSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitSignal: SharedFlow<Unit> = _exitSignal.asSharedFlow()

    private val sessionClients = IdentityHashMap<TerminalSession, TerminalSessionClientBridge>()

    internal fun addSession(
        session: TerminalSession,
        clientBridge: TerminalSessionClientBridge,
    ) {
        synchronized(this) {
            sessionClients[session] = clientBridge
            _sessions.value = _sessions.value + session
        }
    }

    fun attachClient(session: TerminalSession, client: TerminalSessionClient) = synchronized(this) {
        sessionClients[session]?.attach(client)
    }

    fun detachClient(client: TerminalSessionClient) = synchronized(this) {
        sessionClients.values.forEach { it.detach(client) }
    }

    fun removeSession(index: Int) {
        synchronized(this) {
            val current = _sessions.value
            if (index !in current.indices) return
            val removed = current[index]
            removeFromStateLocked(removed)
            removed.finishIfRunning()
        }
    }

    internal fun sessionFinished(session: TerminalSession) {
        synchronized(this) {
            if (session !in _sessions.value) return
            removeFromStateLocked(session)
        }
    }

    /**
     * Removes [session] from the store and fixes up the current index.
 * Caller must hold the monitor. Does not finish the session itself:
 * [removeSession] does, [sessionFinished] must not.
     */
    private fun removeFromStateLocked(session: TerminalSession) {
        val index = _sessions.value.indexOf(session)
        if (index < 0) return
        _sessions.value = _sessions.value.toMutableList().apply { removeAt(index) }
        sessionClients.remove(session)?.detachAll()
        if (_currentIndex.value >= _sessions.value.size) {
            _currentIndex.value = _sessions.value.size - 1
        } else if (index < _currentIndex.value) {
            _currentIndex.value--
        }
    }

    fun finishAllSessions() {
        synchronized(this) {
            val active = _sessions.value
            _sessions.value = emptyList()
            _currentIndex.value = -1
            sessionClients.values.forEach { it.detachAll() }
            sessionClients.clear()
            active.forEach { it.finishIfRunning() }
        }
    }

    fun switchToSession(session: TerminalSession): Boolean {
        synchronized(this) {
            val idx = _sessions.value.indexOf(session)
            if (idx >= 0) {
                _currentIndex.value = idx
                return true
            }
        }
        return false
    }

    fun signalExit() {
        _exitSignal.tryEmit(Unit)
    }
}

internal val Context.terminalSessionStore: TerminalSessionStore
    get() = (applicationContext as RedTApp).terminalSessions
