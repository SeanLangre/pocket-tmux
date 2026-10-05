package com.example.remoteterminal

import android.graphics.Typeface
import android.util.TypedValue
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView

@Composable
fun TerminalScreen(vm: TerminalViewModel, onOpenSettings: () -> Unit) {
    val state by vm.connection.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        StatusBanner(
            state = state,
            onRetry = vm::retry,
            onForgetHostKey = vm::forgetHostKeyAndRetry,
            onOpenSettings = onOpenSettings,
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { context ->
                TerminalView(context, null).apply {
                    val client = ViewClient(this, vm.modifiers) { vm.fontSizePx = it }
                    setTerminalViewClient(client)
                    val size = vm.fontSizePx.takeIf { it > 0 } ?: TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics
                    ).toInt()
                    client.fontSizePx = size
                    setTextSize(size)
                    setTypeface(Typeface.MONOSPACE)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    keepScreenOn = true
                    attachSession(vm.terminal)
                    vm.terminalView = this
                    requestFocus()
                }
            },
            onRelease = { view -> if (vm.terminalView === view) vm.terminalView = null },
        )
        ExtraKeys(vm, onOpenSettings)
    }
}

@Composable
private fun StatusBanner(
    state: ConnectionState,
    onRetry: () -> Unit,
    onForgetHostKey: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (state == ConnectionState.Connected) return
    val (color, text) = when (state) {
        is ConnectionState.Connecting -> Color(0xFF1E3A5F) to
            if (state.attempt == 1) "Connecting…" else "Connecting (attempt ${state.attempt})…"
        is ConnectionState.Reconnecting -> Color(0xFF5F4B1E) to
            "${state.reason}\nRetrying in ${state.retryInSeconds}s"
        is ConnectionState.Failed -> Color(0xFF5F1E1E) to state.reason
        ConnectionState.Ended -> Color(0xFF333333) to "tmux session ended or detached"
        ConnectionState.Idle -> Color(0xFF333333) to "Not connected"
        ConnectionState.Connected -> return
    }
    Surface(color = color, contentColor = Color.White, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state is ConnectionState.Connecting) {
                CircularProgressIndicator(
                    Modifier.padding(end = 10.dp).height(16.dp).widthIn(max = 16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            }
            Text(text, Modifier.weight(1f), fontSize = 13.sp)
            when {
                state is ConnectionState.Failed && state.hostKeyChanged ->
                    TextButton(onClick = onForgetHostKey) { Text("Trust new key", color = Color.White) }
                state is ConnectionState.Failed ->
                    TextButton(onClick = onOpenSettings) { Text("Settings", color = Color.White) }
                state !is ConnectionState.Connecting ->
                    TextButton(onClick = onRetry) {
                        Text(if (state is ConnectionState.Reconnecting) "Retry now" else "Reconnect", color = Color.White)
                    }
            }
        }
    }
}

/** Keys missing from soft keyboards that terminals and tmux need. */
@Composable
private fun ExtraKeys(vm: TerminalViewModel, onOpenSettings: () -> Unit) {
    val modifiers = vm.modifiers

    fun view(): TerminalView? = vm.terminalView

    fun sendKeyCode(keyCode: Int) {
        var mod = 0
        if (modifiers.consumeCtrl()) mod = mod or KeyHandler.KEYMOD_CTRL
        if (modifiers.consumeAlt()) mod = mod or KeyHandler.KEYMOD_ALT
        view()?.handleKeyCode(keyCode, mod)
    }

    fun sendChar(c: Char) {
        view()?.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, c.code, false, false)
    }

    // Quick answers for AI CLI prompts (Claude Code etc.): Enter accepts the highlighted option,
    // "2" picks the second ("don't ask again"), Esc declines, Shift+Tab cycles the permission mode.
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val wide = Modifier.weight(1f)
        ExtraKey("✓ Yes", wide, color = Color(0xFF1E5F2E)) { vm.terminal.write(byteArrayOf('\r'.code.toByte()), 0, 1) }
        ExtraKey("2 Always", wide, color = Color(0xFF1E3A5F)) { vm.terminal.write(byteArrayOf('2'.code.toByte()), 0, 1) }
        ExtraKey("✗ No", wide, color = Color(0xFF5F1E1E)) { view()?.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0) }
        ExtraKey("⇧TAB", wide) { view()?.handleKeyCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1A1A))
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ExtraKey("ESC") { sendKeyCode(KeyEvent.KEYCODE_ESCAPE) }
        ExtraKey("TAB") { sendKeyCode(KeyEvent.KEYCODE_TAB) }
        ExtraKey("CTRL", active = modifiers.ctrl) { modifiers.ctrl = !modifiers.ctrl }
        ExtraKey("ALT", active = modifiers.alt) { modifiers.alt = !modifiers.alt }
        ExtraKey("^B") { vm.terminal.write(byteArrayOf(0x02), 0, 1) } // tmux prefix
        ExtraKey("←") { sendKeyCode(KeyEvent.KEYCODE_DPAD_LEFT) }
        ExtraKey("↓") { sendKeyCode(KeyEvent.KEYCODE_DPAD_DOWN) }
        ExtraKey("↑") { sendKeyCode(KeyEvent.KEYCODE_DPAD_UP) }
        ExtraKey("→") { sendKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT) }
        ExtraKey("PGUP") { sendKeyCode(KeyEvent.KEYCODE_PAGE_UP) }
        ExtraKey("PGDN") { sendKeyCode(KeyEvent.KEYCODE_PAGE_DOWN) }
        for (c in "|-/~") ExtraKey(c.toString()) { sendChar(c) }
        ExtraKey("⚙") { onOpenSettings() }
    }
}

@Composable
private fun ExtraKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    color: Color = Color(0xFF2A2A2A),
    onClick: () -> Unit,
) {
    Box(
        modifier
            .background(if (active) MaterialTheme.colorScheme.primary else color)
            .widthIn(min = 44.dp)
            .height(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) MaterialTheme.colorScheme.onPrimary else Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
