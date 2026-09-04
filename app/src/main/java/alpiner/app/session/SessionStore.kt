package alpiner.app.session

import android.content.Context
import alpiner.app.AlpinerApp
import alpiner.app.service.SessionBridge
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.IdentityHashMap

internal class SessionStore {

    /**
     * The session list and the selected index as one atomic value: collectors
     * can never observe a stale index paired with a fresh list (which the
     * previous two-StateFlow design allowed while they emitted out of step).
     */
    data class State(
        val sessions: List<TerminalSession> = emptyList(),
        val currentIndex: Int = -1,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Synchronous snapshots for one-off reads; mutations happen on the main
     * thread, so these never tear mid-callback.
     */
    val sessions: List<TerminalSession> get() = _state.value.sessions
    val currentIndex: Int get() = _state.value.currentIndex

    private val _exitSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitSignal: SharedFlow<Unit> = _exitSignal.asSharedFlow()

    private val sessionClients = IdentityHashMap<TerminalSession, SessionBridge>()
    private var sessionCounter = 0

    internal fun addSession(
        session: TerminalSession,
        clientBridge: SessionBridge,
    ) {
        synchronized(this) {
            sessionClients[session] = clientBridge
            // Default names number sessions 1, 2, 3, ... monotonically; a
            // name is only left alone when the user renamed it.
            if (session.mSessionName.isNullOrEmpty()) {
                session.mSessionName = "session ${++sessionCounter}"
            }
            _state.value = _state.value.copy(sessions = _state.value.sessions + session)
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
            val current = _state.value.sessions
            if (index !in current.indices) return
            val removed = current[index]
            removeFromStateLocked(removed)
            removed.finishIfRunning()
        }
    }

    internal fun sessionFinished(session: TerminalSession) {
        synchronized(this) {
            removeFromStateLocked(session)
        }
    }

    /**
     * Removes [session] from the state and fixes up the current index.
     * Caller must hold the monitor. Does not finish the session itself:
     * [removeSession] does, [sessionFinished] must not.
     */
    private fun removeFromStateLocked(session: TerminalSession) {
        val old = _state.value
        val index = old.sessions.indexOf(session)
        if (index < 0) return
        sessionClients.remove(session)?.detachAll()
        val sessions = old.sessions.toMutableList().apply { removeAt(index) }
        val currentIndex = when {
            old.currentIndex >= sessions.size -> sessions.size - 1
            index < old.currentIndex -> old.currentIndex - 1
            else -> old.currentIndex
        }
        _state.value = State(sessions, currentIndex)
    }

    fun finishAllSessions() {
        synchronized(this) {
            val active = _state.value.sessions
            _state.value = State()
            sessionClients.values.forEach { it.detachAll() }
            sessionClients.clear()
            active.forEach { it.finishIfRunning() }
        }
    }

    fun switchToSession(session: TerminalSession): Boolean {
        synchronized(this) {
            val idx = _state.value.sessions.indexOf(session)
            if (idx >= 0) {
                _state.value = _state.value.copy(currentIndex = idx)
                return true
            }
            return false
        }
    }

    fun signalExit() {
        _exitSignal.tryEmit(Unit)
    }
}

internal val Context.sessionStore: SessionStore
    get() = (applicationContext as AlpinerApp).terminalSessions
