package com.example.pockettmux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(vm: TerminalViewModel, onSaved: () -> Unit, onCancel: (() -> Unit)?) {
    val initial = vm.config
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var authMethod by rememberSaveable { mutableStateOf(initial.authMethod) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var tmuxSession by rememberSaveable { mutableStateOf(initial.tmuxSession) }
    var customCommand by rememberSaveable { mutableStateOf(initial.customCommand) }

    val draft = ConnectionConfig(
        host = host.trim(),
        port = port.toIntOrNull() ?: -1,
        username = username.trim(),
        authMethod = authMethod,
        password = password,
        tmuxSession = tmuxSession.trim(),
        customCommand = customCommand.trim(),
    )

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Connection", style = MaterialTheme.typography.headlineSmall)

        val recent = vm.recentConnections
        if (recent.isNotEmpty()) {
            Text("Recent", style = MaterialTheme.typography.titleSmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (entry in recent) {
                    FilterChip(
                        selected = entry.host == host.trim() && entry.username == username.trim() &&
                            entry.port.toString() == port,
                        onClick = {
                            host = entry.host
                            port = entry.port.toString()
                            username = entry.username
                        },
                        label = { Text(entry.label) },
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                host, { host = it }, Modifier.weight(1f),
                label = { Text("PC hostname or IP") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                port, { port = it.filter(Char::isDigit) }, Modifier.weight(0.4f),
                label = { Text("Port") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        OutlinedTextField(
            username, { username = it }, Modifier.fillMaxWidth(),
            label = { Text("Username") }, singleLine = true,
        )

        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AuthMethod.entries.forEachIndexed { index, method ->
                SegmentedButton(
                    selected = authMethod == method,
                    onClick = { authMethod = method },
                    shape = SegmentedButtonDefaults.itemShape(index, AuthMethod.entries.size),
                ) { Text(if (method == AuthMethod.KEY) "SSH key" else "Password") }
            }
        }

        if (authMethod == AuthMethod.PASSWORD) {
            OutlinedTextField(
                password, { password = it }, Modifier.fillMaxWidth(),
                label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        } else {
            PublicKeyCard(vm)
        }

        OutlinedTextField(
            tmuxSession, { tmuxSession = it }, Modifier.fillMaxWidth(),
            label = { Text("tmux session name") }, singleLine = true,
        )
        OutlinedTextField(
            customCommand, { customCommand = it }, Modifier.fillMaxWidth(),
            label = { Text("Custom remote command (optional)") },
            placeholder = { Text(draft.copy(customCommand = "").remoteCommand) },
            singleLine = true,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { vm.saveConfig(draft); onSaved() },
                enabled = draft.isComplete,
            ) { Text("Save & connect") }
            if (onCancel != null) OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }

        HostKeyRow(vm)
    }
}

@Composable
private fun PublicKeyCard(vm: TerminalViewModel) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val publicKey = remember(version) { vm.keyManager.authorizedKeysLine() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This phone's public key", style = MaterialTheme.typography.titleSmall)
            Text(
                "Append it to ~/.ssh/authorized_keys on your PC.",
                style = MaterialTheme.typography.bodySmall,
            )
            SelectionContainer {
                Text(publicKey, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SSH public key", publicKey))
                }) { Text("Copy") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, publicKey)
                    context.startActivity(Intent.createChooser(send, "Share public key"))
                }) { Text("Share") }
                TextButton(onClick = { vm.keyManager.regenerate(); version++ }) { Text("New key") }
            }
        }
    }
}

@Composable
private fun HostKeyRow(vm: TerminalViewModel) {
    val config = vm.config
    if (config.host.isBlank()) return
    var fingerprint by remember(config.host, config.port) {
        mutableStateOf(vm.hostKeys.fingerprint(config.host, config.port))
    }
    val known = fingerprint ?: return
    HorizontalDivider()
    Text("Trusted host key for ${config.host}:${config.port}", style = MaterialTheme.typography.titleSmall)
    Text(known, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    TextButton(onClick = {
        vm.hostKeys.forget(config.host, config.port)
        fingerprint = null
    }) { Text("Forget host key") }
}
