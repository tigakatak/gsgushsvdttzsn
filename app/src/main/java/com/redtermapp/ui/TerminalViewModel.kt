package com.redtermapp.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.redtermapp.service.TerminalService
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class TerminalViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        @Volatile
        private var instance: TerminalViewModel? = null

        fun get(application: Application): TerminalViewModel =
            instance ?: synchronized(this) {
                instance ?: TerminalViewModel(application).also { instance = it }
            }

        fun clearIfEmpty() {
            synchronized(this) {
                val inst = instance ?: return
                if (inst.sessions.value.isEmpty()) {
                    instance = null
                }
            }
        }
    }

    private val _sessions = MutableStateFlow<List<TerminalSession>>(emptyList())
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    fun addSession(session: TerminalSession) {
        _sessions.update { it + session }
        _currentIndex.value = _sessions.value.size - 1
    }

    fun removeSession(index: Int) {
        _sessions.update { list ->
            if (index !in list.indices) return@update list
            val mutable = list.toMutableList()
            mutable[index].finishIfRunning()
            mutable.removeAt(index)
            if (_currentIndex.value >= mutable.size) {
                _currentIndex.value = mutable.size - 1
            }
            if (mutable.isEmpty()) {
                getApplication<Application>().stopService(
                    Intent(getApplication(), TerminalService::class.java)
                )
            }
            mutable
        }
    }

    fun switchToSession(index: Int) {
        if (index in _sessions.value.indices) {
            _currentIndex.value = index
        }
    }

    fun removeSessionsForDistro(distroName: String) {
        _sessions.update { list ->
            val kept = mutableListOf<TerminalSession>()
            for (s in list) {
                if (s.mSessionName.equals(distroName, ignoreCase = true)) {
                    s.finishIfRunning()
                } else {
                    kept.add(s)
                }
            }
            if (_currentIndex.value >= kept.size) {
                _currentIndex.value = kept.size - 1
            }
            if (kept.isEmpty()) {
                getApplication<Application>().stopService(
                    Intent(getApplication(), TerminalService::class.java)
                )
            }
            kept
        }
    }
}
