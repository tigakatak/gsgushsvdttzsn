package com.redtermapp.service

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient

internal class TerminalSessionClientBridge(
    private val onFinished: (TerminalSession) -> Unit,
) : TerminalSessionClient {

    @Volatile
    private var delegate: TerminalSessionClient? = null

    fun attach(client: TerminalSessionClient) {
        delegate = client
    }

    fun detach(client: TerminalSessionClient) {
        if (delegate === client) delegate = null
    }

    fun detachAll() {
        delegate = null
    }

    override fun onTextChanged(session: TerminalSession) {
        delegate?.onTextChanged(session)
    }

    override fun onTitleChanged(session: TerminalSession) {
        delegate?.onTitleChanged(session)
    }

    override fun onSessionFinished(session: TerminalSession) {
        onFinished(session)
        delegate?.onSessionFinished(session)
        delegate = null
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        delegate?.onCopyTextToClipboard(session, text)
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        delegate?.onPasteTextFromClipboard(session)
    }

    override fun onBell(session: TerminalSession) {
        delegate?.onBell(session)
    }

    override fun onColorsChanged(session: TerminalSession) {
        delegate?.onColorsChanged(session)
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        delegate?.onTerminalCursorStateChange(state)
    }

    override fun getTerminalCursorStyle(): Int? = delegate?.getTerminalCursorStyle()

    override fun logError(tag: String, message: String) {
        delegate?.logError(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        delegate?.logWarn(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        delegate?.logInfo(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        delegate?.logDebug(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        delegate?.logVerbose(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        delegate?.logStackTraceWithMessage(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        delegate?.logStackTrace(tag, e)
    }
}
