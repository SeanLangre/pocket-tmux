package com.example.remoteterminal

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

/** Sticky on-screen modifier keys; reading one consumes it, like a one-shot modifier. */
class ModifierKeys {
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun consumeCtrl() = ctrl.also { ctrl = false }
    fun consumeAlt() = alt.also { alt = false }
}

/**
 * Holds the terminal session and SSH connection so they survive configuration changes. The
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
    private lateinit var promptDetector: PromptDetector
    private val sessionClient = SessionClient(app, { terminalView }, { promptDetector.onOutput() })
    val terminal = TerminalSession(TRANSCRIPT_ROWS, sessionClient)
    val connection = SshConnection(app, viewModelScope, terminal, keyManager, hostKeys)

    /** The currently attached view, if any (cleared when the activity is destroyed). */
    var terminalView: TerminalView? = null

    init {
        promptDetector = PromptDetector(terminal, notifier)
        if (config.isComplete) connection.start(config)
    }

    fun saveConfig(newConfig: ConnectionConfig) {
        val hostChanged = newConfig.host != config.host || newConfig.port != config.port
        configStore.save(newConfig)
        config = configStore.load()
        recentConnections = configStore.recentConnections()
        if (hostChanged) terminal.reset()
        if (config.isComplete) connection.start(config) else connection.stop()
    }

    fun forgetHostKeyAndRetry() {
        hostKeys.forget(config.host, config.port)
        connection.start(config)
    }

    fun retry() = connection.retryNow(config)

    /** Called when the app comes back to the foreground. */
    fun onForeground() {
        promptDetector.inForeground = true
        if (config.isComplete && connection.state.value is ConnectionState.Reconnecting) connection.retryNow(config)
    }

    fun onBackground() {
        promptDetector.inForeground = false
    }

    override fun onCleared() {
        getApplication<Application>().stopService(Intent(getApplication(), KeepAliveService::class.java))
        connection.release()
        terminal.finishIfRunning()
    }

    private companion object {
        const val TRANSCRIPT_ROWS = 5000
    }
}
