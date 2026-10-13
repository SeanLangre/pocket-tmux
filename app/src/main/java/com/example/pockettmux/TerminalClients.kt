package com.example.pockettmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

private const val TAG = "Terminal"

/** Routes emulator events (screen updates, clipboard, bell) to whichever view is attached. */
class SessionClient(
    private val context: Context,
    private val view: () -> TerminalView?,
    private val onOutput: () -> Unit = {},
) : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) {
        view()?.onScreenUpdated()
        onOutput()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text ?: return))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) {
        view()?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message, e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }
}

/** Handles input-side callbacks from [TerminalView]: keyboard, sticky modifiers, pinch-zoom. */
class ViewClient(
    private val view: TerminalView,
    private val modifiers: ModifierKeys,
    private val onFontSizeChanged: (Int) -> Unit,
) : TerminalViewClient {
    var fontSizePx = 0

    override fun onScale(scale: Float): Float {
        if (scale < 0.9f || scale > 1.1f) {
            val newSize = (fontSizePx + if (scale > 1f) 2 else -2).coerceIn(MIN_FONT_PX, MAX_FONT_PX)
            if (newSize != fontSizePx) {
                fontSizePx = newSize
                view.setTextSize(newSize)
                onFontSizeChanged(newSize)
            }
            return 1.0f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        view.requestFocus()
        view.context.getSystemService(InputMethodManager::class.java)
            .showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape() = false
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) = Unit
    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
    override fun onLongPress(event: MotionEvent?) = false
    override fun readControlKey() = modifiers.consumeCtrl()
    override fun readAltKey() = modifiers.consumeAlt()
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
    override fun onEmulatorSet() = Unit

    override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
    override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
    override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
    override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
    override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(tag ?: TAG, message, e)
    }
    override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag ?: TAG, "", e) }

    companion object {
        const val MIN_FONT_PX = 16
        const val MAX_FONT_PX = 72
    }
}
