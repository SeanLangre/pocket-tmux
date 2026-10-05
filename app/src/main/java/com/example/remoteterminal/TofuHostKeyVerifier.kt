package com.example.remoteterminal

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

class HostKeyChangedException(val host: String, val expected: String, val actual: String) :
    Exception("Host key for $host changed!\nExpected $expected\nGot      $actual")

/**
 * Trust-on-first-use host key verification: the first key seen for host:port is remembered, and any
 * later mismatch is rejected (possible man-in-the-middle) until the user explicitly forgets it.
 */
class TofuHostKeyVerifier(context: Context) : HostKeyVerifier {
    private val prefs = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    /** Set when the last verification failed because of a changed key. */
    @Volatile
    var lastMismatch: HostKeyChangedException? = null
        private set

    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
        val id = id(hostname, port)
        val actual = describe(key)
        val expected = prefs.getString(id, null)
        if (expected == null) {
            prefs.edit().putString(id, actual).apply()
            return true
        }
        if (expected == actual) return true
        lastMismatch = HostKeyChangedException(id, expected, actual)
        return false
    }

    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> {
        val known = prefs.getString(id(hostname, port), null) ?: return emptyList()
        return listOf(known.substringBefore(' '))
    }

    fun fingerprint(hostname: String, port: Int): String? = prefs.getString(id(hostname, port), null)

    fun forget(hostname: String, port: Int) {
        prefs.edit().remove(id(hostname, port)).apply()
        lastMismatch = null
    }

    fun clearMismatch() {
        lastMismatch = null
    }

    private fun id(hostname: String, port: Int) = "$hostname:$port"

    private fun describe(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val digest = MessageDigest.getInstance("SHA-256").digest(blob)
        val fp = Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        return "${KeyType.fromKey(key)} SHA256:$fp"
    }
}
