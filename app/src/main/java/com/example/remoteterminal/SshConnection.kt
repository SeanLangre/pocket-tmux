package com.example.remoteterminal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val reason: String, val retryInSeconds: Int) : ConnectionState
    /** Terminal state that needs user action; no automatic retries. */
    data class Failed(val reason: String, val hostKeyChanged: Boolean = false) : ConnectionState
    /** The remote command exited (tmux detached or the session was killed). */
    data object Ended : ConnectionState
}

/**
 * Keeps an SSH connection to the PC running the tmux command, feeding the remote PTY into a
 * [TerminalSession]. Drops (network loss, Wi-Fi change, PC sleep) are retried with backoff; a
 * network change triggers an immediate reconnect instead of waiting for keepalive timeouts.
 */
class SshConnection(
    context: Context,
    private val scope: CoroutineScope,
    private val terminal: TerminalSession,
    private val keyManager: KeyManager,
    private val hostKeyVerifier: TofuHostKeyVerifier,
) {
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state: StateFlow<ConnectionState> = _state

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    /** Wakes the retry backoff early (network came back, app resumed, user tapped retry). */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null
    @Volatile private var client: SSHClient? = null
    @Volatile private var connectedNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val current = connectedNetwork
            if (current != null && current != network) {
                // Default network switched (e.g. Wi-Fi <-> mobile). The old socket is dead even if
                // it hasn't noticed yet, so drop it now and reconnect on the new network.
                Log.i(TAG, "Default network changed, reconnecting")
                closeClient()
            }
            wakeUp.trySend(Unit)
        }

        override fun onLost(network: Network) {
            if (network == connectedNetwork) closeClient()
        }
    }

    init {
        connectivity.registerDefaultNetworkCallback(networkCallback)
    }

    fun start(config: ConnectionConfig) {
        val previous = job
        closeClient()
        job = scope.launch(Dispatchers.IO) {
            // Let the old loop finish its cleanup so it can't detach the new connection's transport.
            previous?.cancelAndJoin()
            runLoop(config)
        }
    }

    /** Skip the remaining backoff, or start again after [ConnectionState.Ended]/[ConnectionState.Failed]. */
    fun retryNow(config: ConnectionConfig) {
        when (_state.value) {
            is ConnectionState.Reconnecting -> wakeUp.trySend(Unit)
            is ConnectionState.Ended, is ConnectionState.Failed, ConnectionState.Idle -> start(config)
            else -> Unit
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        closeClient()
        _state.value = ConnectionState.Idle
    }

    fun release() {
        stop()
        connectivity.unregisterNetworkCallback(networkCallback)
    }

    private suspend fun runLoop(config: ConnectionConfig) {
        var attempt = 0
        var backoffSeconds = 1
        while (currentCoroutineContext().isActive) {
            attempt++
            _state.value = ConnectionState.Connecting(attempt)
            try {
                runSession(config)
                // Remote command exited normally: don't fight the user by reattaching.
                terminal.appendLocalMessage("\r\n\u001b[33m[Session ended]\u001b[0m\r\n")
                _state.value = ConnectionState.Ended
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive() // stop() closed the socket under us
                Log.w(TAG, "Connection attempt $attempt failed", e)
                hostKeyVerifier.lastMismatch?.let { mismatch ->
                    _state.value = ConnectionState.Failed(mismatch.message!!, hostKeyChanged = true)
                    return
                }
                if (e is UserAuthException) {
                    _state.value = ConnectionState.Failed(authFailureMessage(config))
                    return
                }
                val reason = describe(e)
                if (wasConnected) {
                    terminal.appendLocalMessage("\r\n\u001b[31m[Disconnected: $reason]\u001b[0m\r\n")
                    backoffSeconds = 1
                }
                _state.value = ConnectionState.Reconnecting(reason, backoffSeconds)
                withTimeoutOrNull(backoffSeconds * 1000L) { wakeUp.receive() }
                backoffSeconds = (backoffSeconds * 2).coerceAtMost(MAX_BACKOFF_SECONDS)
            }
        }
    }

    @Volatile private var wasConnected = false

    /** Connects, attaches, and pumps output until the channel closes. Throws on failure. */
    private fun runSession(config: ConnectionConfig) {
        wasConnected = false
        val sshConfig = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val ssh = SSHClient(sshConfig)
        client = ssh
        try {
            ssh.addHostKeyVerifier(hostKeyVerifier)
            ssh.connectTimeout = CONNECT_TIMEOUT_MS
            hostKeyVerifier.clearMismatch()
            connectedNetwork = connectivity.activeNetwork
            ssh.connect(config.host, config.port)
            (ssh.connection.keepAlive as KeepAliveRunner).apply {
                keepAliveInterval = KEEPALIVE_INTERVAL_SECONDS
                maxAliveCount = KEEPALIVE_MAX_MISSED
            }

            when (config.authMethod) {
                AuthMethod.KEY -> ssh.authPublickey(config.username, KeyPairWrapper(keyManager.keyPair()))
                AuthMethod.PASSWORD -> ssh.authPassword(config.username, config.password)
            }

            val session = ssh.startSession() as SessionChannel
            val emulator = terminal.emulator
            session.allocatePTY(
                "xterm-256color",
                emulator?.mColumns ?: 80,
                emulator?.mRows ?: 24,
                emulator?.cellWidthPixels ?: 0,
                emulator?.cellHeightPixels ?: 0,
                emptyMap(),
            )
            session.exec(config.remoteCommand)

            val output = session.outputStream
            terminal.setTransport(object : TerminalSession.Transport {
                override fun write(data: ByteArray, offset: Int, count: Int) {
                    output.write(data, offset, count)
                    output.flush()
                }

                override fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
                    scope.launch(Dispatchers.IO) {
                        try {
                            session.changeWindowDimensions(columns, rows, cellWidthPixels, cellHeightPixels)
                        } catch (e: IOException) {
                            Log.w(TAG, "Resize failed", e)
                        }
                    }
                }
            })
            wasConnected = true
            _state.value = ConnectionState.Connected

            val input = session.inputStream
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                terminal.appendRemoteOutput(buffer, 0, read)
            }
            // EOF can mean the remote command exited or the transport died underneath us.
            if (!ssh.isConnected || !ssh.transport.isRunning) throw IOException("Connection lost")
            // Drain stderr so e.g. "tmux: command not found" is visible.
            val stderr = session.errorStream.readBytes()
            if (stderr.isNotEmpty()) terminal.appendRemoteOutput(stderr, 0, stderr.size)
        } finally {
            terminal.setTransport(null)
            if (client === ssh) client = null
            connectedNetwork = null
            try {
                ssh.disconnect()
            } catch (_: IOException) {
            }
        }
    }

    private fun closeClient() {
        val ssh = client ?: return
        scope.launch(Dispatchers.IO) {
            try {
                ssh.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun authFailureMessage(config: ConnectionConfig) = when (config.authMethod) {
        AuthMethod.KEY -> "Public key rejected for ${config.username}@${config.host}.\n" +
            "Add this app's public key (see Settings) to ~/.ssh/authorized_keys on the PC."
        AuthMethod.PASSWORD -> "Wrong password for ${config.username}@${config.host}."
    }

    private fun describe(e: Exception): String = when {
        e is TransportException && e.cause != null -> e.cause!!.message ?: e.toString()
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "SshConnection"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val KEEPALIVE_INTERVAL_SECONDS = 10
        const val KEEPALIVE_MAX_MISSED = 3
        const val MAX_BACKOFF_SECONDS = 30
    }
}
