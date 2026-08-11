package com.redtermapp.session

import android.content.Context
import com.redtermapp.RedTermApp
import com.redtermapp.service.TerminalSessionClientBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.IdentityHashMap

internal class TerminalSessionStore {

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val sessionDistros = IdentityHashMap<TerminalSession, String>()
    private val sessionClients = IdentityHashMap<TerminalSession, TerminalSessionClientBridge>()

    internal fun addSession(
        session: TerminalSession,
        distroName: String,
        clientBridge: TerminalSessionClientBridge,
    ) {
        synchronized(this) {
            sessionDistros[session] = distroName
            sessionClients[session] = clientBridge
            _sessions.value = _sessions.value + session
        }
    }

    fun indexOfSessionForDistro(distroName: String): Int =
        _sessions.value.indexOfFirst {
            sessionDistros[it].equals(distroName, ignoreCase = true)
        }

    fun attachClient(session: TerminalSession, client: TerminalSessionClient) {
        sessionClients[session]?.attach(client)
    }

    fun detachClient(client: TerminalSessionClient) {
        sessionClients.values.forEach { it.detach(client) }
    }

    fun removeSession(index: Int) {
        synchronized(this) {
            val current = _sessions.value
            if (index !in current.indices) return
            val removed = current[index]
            _sessions.value = current.toMutableList().apply { removeAt(index) }
            sessionDistros.remove(removed)
            sessionClients.remove(removed)
            removed.finishIfRunning()
            if (_currentIndex.value >= _sessions.value.size) {
                _currentIndex.value = _sessions.value.size - 1
            } else if (index < _currentIndex.value) {
                _currentIndex.value = _currentIndex.value - 1
            }
        }
    }

    internal fun sessionFinished(session: TerminalSession) {
        synchronized(this) {
            val index = _sessions.value.indexOf(session)
            if (index < 0) return
            val current = _sessions.value
            _sessions.value = current.toMutableList().apply { removeAt(index) }
            sessionDistros.remove(session)
            sessionClients.remove(session)
            if (_currentIndex.value >= _sessions.value.size) {
                _currentIndex.value = _sessions.value.size - 1
            } else if (index < _currentIndex.value) {
                _currentIndex.value--
            }
        }
    }

    fun finishAllSessions() {
        synchronized(this) {
            val active = _sessions.value
            _sessions.value = emptyList()
            _currentIndex.value = -1
            sessionDistros.clear()
            sessionClients.clear()
            active.forEach { it.finishIfRunning() }
        }
    }

    fun switchToSession(index: Int) {
        synchronized(this) {
            if (index in _sessions.value.indices) {
                _currentIndex.value = index
            }
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

    fun removeSessionsForDistro(distroName: String) {
        synchronized(this) {
            val current = _sessions.value
            val removed = mutableListOf<TerminalSession>()
            var removedBeforeCurrent = 0
            val kept = current.filterIndexed { i, s ->
                if (sessionDistros[s].equals(distroName, ignoreCase = true)) {
                    removed.add(s)
                    if (i < _currentIndex.value) removedBeforeCurrent++
                    false
                } else {
                    true
                }
            }
            _sessions.value = kept
            removed.forEach { sessionDistros.remove(it) }
            removed.forEach { sessionClients.remove(it) }
            removed.forEach { it.finishIfRunning() }
            _currentIndex.value = if (kept.isEmpty()) {
                -1
            } else {
                (_currentIndex.value - removedBeforeCurrent).coerceIn(0, kept.size - 1)
            }
        }
    }
}

internal val Context.terminalSessionStore: TerminalSessionStore
    get() = (applicationContext as RedTermApp).terminalSessions
