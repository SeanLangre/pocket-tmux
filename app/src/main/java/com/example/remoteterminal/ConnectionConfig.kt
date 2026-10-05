package com.example.remoteterminal

import android.content.Context

enum class AuthMethod { KEY, PASSWORD }

data class ConnectionConfig(
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val authMethod: AuthMethod = AuthMethod.KEY,
    val password: String = "",
    val tmuxSession: String = "main",
    /** Overrides the default tmux command when not blank. */
    val customCommand: String = "",
) {
    val isComplete: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && port in 1..65535 &&
            (authMethod == AuthMethod.KEY || password.isNotEmpty())

    val remoteCommand: String
        get() = customCommand.ifBlank {
            "tmux new-session -A -s ${shellQuote(tmuxSession.ifBlank { "main" })}"
        }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** A previously used host/user, offered as a shortcut in Settings. */
data class RecentConnection(val username: String, val host: String, val port: Int) {
    val label: String
        get() = if (port == 22) "$username@$host" else "$username@$host:$port"

    fun encode() = "$username\t$host\t$port"

    companion object {
        fun decode(line: String): RecentConnection? {
            val parts = line.split('\t')
            if (parts.size != 3) return null
            return RecentConnection(parts[0], parts[1], parts[2].toIntOrNull() ?: return null)
        }
    }
}

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE)

    fun load() = ConnectionConfig(
        host = prefs.getString("host", "")!!,
        port = prefs.getInt("port", 22),
        username = prefs.getString("username", "")!!,
        authMethod = AuthMethod.valueOf(prefs.getString("authMethod", AuthMethod.KEY.name)!!),
        password = prefs.getString("password", "")!!,
        tmuxSession = prefs.getString("tmuxSession", "main")!!,
        customCommand = prefs.getString("customCommand", "")!!,
    )

    fun save(config: ConnectionConfig) {
        prefs.edit()
            .putString("host", config.host.trim())
            .putInt("port", config.port)
            .putString("username", config.username.trim())
            .putString("authMethod", config.authMethod.name)
            .putString("password", config.password)
            .putString("tmuxSession", config.tmuxSession.trim())
            .putString("customCommand", config.customCommand.trim())
            .putString("recent", recentWith(config).joinToString("\n") { it.encode() })
            .apply()
    }

    /** Most recently used first. */
    fun recentConnections(): List<RecentConnection> {
        val saved = prefs.getString("recent", "")!!.lines().mapNotNull(RecentConnection::decode)
        if (saved.isNotEmpty()) return saved
        // Settings saved before the recent list existed: seed it with the current connection.
        val current = load()
        return if (current.host.isNotBlank()) listOf(RecentConnection(current.username, current.host, current.port)) else emptyList()
    }

    private fun recentWith(config: ConnectionConfig): List<RecentConnection> {
        val entry = RecentConnection(config.username.trim(), config.host.trim(), config.port)
        return (listOf(entry) + recentConnections().filter { it != entry }).take(MAX_RECENT)
    }

    private companion object {
        const val MAX_RECENT = 5
    }
}
