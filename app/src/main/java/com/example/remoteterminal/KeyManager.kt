package com.example.remoteterminal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Owns the app's SSH identity: an ECDSA P-256 key pair generated on the device.
 *
 * The private key is stored in app-private storage, encrypted with an AES key that lives in the
 * Android Keystore (so the file alone is useless if copied off the device).
 */
class KeyManager(context: Context) {
    private val privateFile = File(context.filesDir, "id_ecdsa.enc")
    private val publicFile = File(context.filesDir, "id_ecdsa.pub.der")

    @Volatile
    private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val pair = if (privateFile.exists() && publicFile.exists()) load() else generate()
        cached = pair
        return pair
    }

    /** The public key in OpenSSH `authorized_keys` format. */
    fun authorizedKeysLine(): String {
        val pub = keyPair().public
        val blob = Buffer.PlainBuffer().putPublicKey(pub).compactData
        return "${KeyType.fromKey(pub)} ${Base64.encodeToString(blob, Base64.NO_WRAP)} remote-terminal@android"
    }

    @Synchronized
    fun regenerate() {
        privateFile.delete()
        publicFile.delete()
        cached = null
        keyPair()
    }

    private fun generate(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()

        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val encrypted = cipher.doFinal(pair.private.encoded)
        privateFile.writeBytes(byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted)
        publicFile.writeBytes(pair.public.encoded)
        return pair
    }

    private fun load(): KeyPair {
        val data = privateFile.readBytes()
        val ivLength = data[0].toInt()
        val iv = data.copyOfRange(1, 1 + ivLength)
        val cipher = Cipher.getInstance(AES_MODE)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, iv))
        val privateBytes = cipher.doFinal(data, 1 + ivLength, data.size - 1 - ivLength)

        val factory = KeyFactory.getInstance("EC")
        return KeyPair(
            factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
            factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)),
        )
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as SecretKey?)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_ALIAS = "ssh_identity_wrapper"
        const val AES_MODE = "AES/GCM/NoPadding"
    }
}
