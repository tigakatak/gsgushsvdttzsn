package alpiner.app.service

import android.content.Context
import android.util.Log
import alpiner.app.session.sessionStore
import alpiner.app.util.Clipboard
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

/**
 * The permanent client of one session, created with it by the service. It
 * outlives activities: output redraws whichever terminal view is currently
 * registered in the session store, and [onFinished] always reaches the
 * service even with no UI alive. All callbacks arrive on the main thread.
 */
internal class SessionClient(
    context: Context,
    private val onFinished: (TerminalSession) -> Unit,
) : TerminalSessionClient {

    private val context = context.applicationContext

    override fun onTextChanged(changedSession: TerminalSession) {
        val view = context.sessionStore.terminalView ?: return
        if (view.mTermSession === changedSession) view.onScreenUpdated()
    }

    override fun onSessionFinished(finishedSession: TerminalSession) {
        onFinished(finishedSession)
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        Clipboard.copy(context, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val text = Clipboard.primaryText(context) ?: return
        session?.emulator?.paste(text)
    }

    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String, message: String) { Log.e(tag, message) }
    override fun logWarn(tag: String, message: String) { Log.w(tag, message) }
    override fun logInfo(tag: String, message: String) { Log.i(tag, message) }
    override fun logDebug(tag: String, message: String) { Log.d(tag, message) }
    override fun logVerbose(tag: String, message: String) { Log.v(tag, message) }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e(tag, message, e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e(tag, "stacktrace", e) }
}
