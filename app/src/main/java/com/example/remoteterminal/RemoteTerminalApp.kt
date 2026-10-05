package com.example.remoteterminal

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class RemoteTerminalApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC" provider that lacks algorithms sshj needs
        // (e.g. X25519 for curve25519 key exchange). Replace it with the full BouncyCastle.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
