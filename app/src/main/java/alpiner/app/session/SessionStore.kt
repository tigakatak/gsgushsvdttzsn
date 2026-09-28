package alpiner.app.session

import android.content.Context
import alpiner.app.AlpinerApp
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide list of live sessions. Every mutation happens on the main thread
 * (service coroutines, activity callbacks and session callbacks all run
 * there), so no locking is needed.
 */
internal class SessionStore {

    /**
     * The session list and the selected index as one atomic value, so
     * collectors never see a stale index paired with a fresh list.
     */
    data class State(
        val sessions: List<TerminalSession> = emptyList(),
        val currentIndex: Int = -1,
    ) {
        val current: TerminalSession? get() = sessions.getOrNull(currentIndex)
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val sessions: List<TerminalSession> get() = _state.value.sessions
    val currentIndex: Int get() = _state.value.currentIndex
    val current: TerminalSession? get() = _state.value.current

    /** The visible terminal view; sessions redraw it when they print. */
    var terminalView: TerminalView? = null

    private val _exitSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val exitSignal: SharedFlow<Unit> = _exitSignal.asSharedFlow()

    private var sessionCounter = 0

    /** Adds [session] and makes it the current one. */
    fun add(session: TerminalSession) {
        // Default names number sessions 1, 2, 3, ... monotonically.
        if (session.mSessionName.isNullOrEmpty()) {
            session.mSessionName = "session ${++sessionCounter}"
        }
        val sessions = _state.value.sessions + session
        _state.value = State(sessions, sessions.lastIndex)
    }

    fun switchTo(index: Int) {
        val old = _state.value
        if (index in old.sessions.indices) _state.value = old.copy(currentIndex = index)
    }

    /** Closes the session at [index] (user action): kills it and drops it. */
    fun close(index: Int) {
        val session = sessions.getOrNull(index) ?: return
        remove(session)
        session.finishIfRunning()
    }

    /** Drops [session] after its process exited on its own. */
    fun remove(session: TerminalSession) {
        val old = _state.value
        val index = old.sessions.indexOf(session)
        if (index < 0) return
        val sessions = old.sessions - session
        val currentIndex = when {
            old.currentIndex >= sessions.size -> sessions.size - 1
            index < old.currentIndex -> old.currentIndex - 1
            else -> old.currentIndex
        }
        _state.value = State(sessions, currentIndex)
        // Numbering restarts at "session 1" once every session is gone.
        if (sessions.isEmpty()) sessionCounter = 0
    }

    fun finishAll() {
        val active = sessions
        _state.value = State()
        sessionCounter = 0
        active.forEach { it.finishIfRunning() }
    }

    fun signalExit() {
        _exitSignal.tryEmit(Unit)
    }
}

internal val Context.sessionStore: SessionStore
    get() = (applicationContext as AlpinerApp).sessionStore
