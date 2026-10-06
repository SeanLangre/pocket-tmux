package com.example.remoteterminal

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope

/** Sticky on-screen modifier keys; reading one consumes it, like a one-shot modifier. */
class ModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun consumeCtrl() = ctrl.also { ctrl = false }
    fun consumeAlt() = alt.also { alt = false }
}

/** One terminal tab: its own emulator, SSH connection and prompt detector. */
class TerminalPane(
    app: Application,
    scope: CoroutineScope,
    keyManager: KeyManager,
    hostKeys: TofuHostKeyVerifier,
    notifier: PromptNotifier,
    val index: Int,
) {
    private lateinit var promptDetector: PromptDetector
    private val sessionClient = SessionClient(app, { terminalView }, { promptDetector.onOutput() })
    val terminal = TerminalSession(TRANSCRIPT_ROWS, sessionClient)
    val connection = SshConnection(app, scope, terminal, keyManager, hostKeys)

    /** The currently attached view, if any (cleared when the activity is destroyed). */
    var terminalView: TerminalView? = null

    /** Extra panes connect lazily, the first time they are shown. */
    var started = false

    init {
        promptDetector = PromptDetector(terminal, notifier)
    }

    /** Pane 0 uses the configured command; later panes attach to tmux session "<name><n+1>" (main2, ...). */
    fun configFor(config: ConnectionConfig) =
        if (index == 0) config
        else config.copy(tmuxSession = "${config.tmuxSession.ifBlank { "main" }}${index + 1}", customCommand = "")

    fun start(config: ConnectionConfig) {
        started = true
        connection.start(configFor(config))
    }

    fun setForeground(foreground: Boolean) {
        promptDetector.inForeground = foreground
    }

    fun release() {
        connection.release()
        terminal.finishIfRunning()
    }
}

/**
 * Holds the terminal panes and their SSH connections so they survive configuration changes. The
 * [TerminalView] is re-attached whenever the activity recreates it.
 */
class TerminalViewModel(app: Application) : AndroidViewModel(app) {
    private val configStore = ConfigStore(app)
    val keyManager = KeyManager(app)
    val hostKeys = TofuHostKeyVerifier(app)
    val modifiers = ModifierKeys()

    var config by mutableStateOf(configStore.load())
        private set

    var recentConnections by mutableStateOf(configStore.recentConnections())
        private set

    var fontSizePx by mutableStateOf(0)

    private val notifier = PromptNotifier(app)
    val panes = List(PANE_COUNT) { TerminalPane(app, viewModelScope, keyManager, hostKeys, notifier, it) }

    var activeIndex by mutableIntStateOf(0)
        private set
    val active: TerminalPane get() = panes[activeIndex]

    init {
        if (config.isComplete) panes[0].start(config)
    }

    fun selectPane(index: Int) {
        activeIndex = index
        val pane = panes[index]
        if (!pane.started && config.isComplete) pane.start(config)
    }

    fun saveConfig(newConfig: ConnectionConfig) {
        val hostChanged = newConfig.host != config.host || newConfig.port != config.port
        configStore.save(newConfig)
        config = configStore.load()
        recentConnections = configStore.recentConnections()
        if (hostChanged) panes.forEach { it.terminal.reset() }
        for (pane in panes) {
            if (!pane.started && pane.index != activeIndex) continue
            if (config.isComplete) pane.start(config) else pane.connection.stop()
        }
    }

    fun forgetHostKeyAndRetry() {
        hostKeys.forget(config.host, config.port)
        active.start(config)
    }

    fun retry() = active.connection.retryNow(active.configFor(config))

    /** Called when the app comes back to the foreground. */
    fun onForeground() {
        panes.forEach { it.setForeground(true) }
        for (pane in panes) {
            if (config.isComplete && pane.connection.state.value is ConnectionState.Reconnecting) {
                pane.connection.retryNow(pane.configFor(config))
            }
        }
    }

    fun onBackground() {
        panes.forEach { it.setForeground(false) }
    }

    override fun onCleared() {
        getApplication<Application>().stopService(Intent(getApplication(), KeepAliveService::class.java))
        panes.forEach { it.release() }
    }

    private companion object {
        const val PANE_COUNT = 2
    }
}

private const val TRANSCRIPT_ROWS = 5000
