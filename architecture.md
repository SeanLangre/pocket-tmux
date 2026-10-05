# Architecture

Remote Terminal is an Android app that opens straight into a tmux session on your PC over SSH.

> **Tap app → wait ~1 second → you're back in your tmux session.**

## Overall architecture

```text
┌──────────────────────────────────────────────────────────────────┐
│                           Android App                            │
│                                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                        UI / Compose                        │  │
│  │                                                            │  │
│  │  SettingsScreen              TerminalScreen                │  │
│  │  • PC address / port / user  • TerminalView (AndroidView)  │  │
│  │  • key or password           • status banner               │  │
│  │  • public key, host key      • extra keys: ESC CTRL ^B ←→  │  │
│  └─────────────────────────────┬──────────────────────────────┘  │
│                                │                                 │
│  ┌─────────────────────────────▼──────────────────────────────┐  │
│  │                     TerminalViewModel                      │  │
│  │  survives rotation; owns the session and the connection    │  │
│  └───────┬──────────────────────────────────────┬─────────────┘  │
│          │                                      │                │
│  ┌───────▼────────────────┐  bytes   ┌──────────▼─────────────┐  │
│  │     SshConnection      │◀────────▶│    TerminalSession     │  │
│  │                        │Transport │  (vendored Termux,     │  │
│  │ • sshj client          │          │   stream-backed)       │  │
│  │ • PTY + exec tmux      │          │                        │  │
│  │ • reconnect loop       │          │  TerminalEmulator      │  │
│  │ • network callback     │          │  • ANSI / VT100 parse  │  │
│  │ • ConnectionState flow │          │  • screen + scrollback │  │
│  └───────┬────────────────┘          └──────────┬─────────────┘  │
│          │ uses                                 │ renders        │
│  ┌───────▼────────────────┐          ┌──────────▼─────────────┐  │
│  │ KeyManager             │          │ TerminalView           │  │
│  │ TofuHostKeyVerifier    │          │ (vendored Termux)      │  │
│  │ ConfigStore            │          │ drawing, IME, select,  │  │
│  │ (Keystore + prefs)     │          │ pinch-zoom             │  │
│  └────────────────────────┘          └────────────────────────┘  │
└────────────────────────────────┬─────────────────────────────────┘
                                 │
                                 │ SSH (encrypted)
                                 ▼
                      ┌──────────────────────┐
                      │          PC          │
                      │                      │
                      │        sshd          │
                      │          │  exec     │
                      │          ▼           │
                      │  tmux new-session -A │
                      │          │           │
                      │          ▼           │
                      │    your processes    │
                      └──────────────────────┘
```

The important rule is to **keep SSH, the terminal, and the UI separate**. The only thing joining
SSH to the terminal is the small `TerminalSession.Transport` interface.

## 1. Project layout

```text
remote-terminal/
├── app/src/main/java/com/example/remoteterminal/
│   ├── RemoteTerminalApp.kt     installs the full BouncyCastle provider
│   ├── MainActivity.kt          switches between settings and terminal screens
│   ├── TerminalViewModel.kt     owns TerminalSession + SshConnection, sticky modifiers
│   ├── TerminalScreen.kt        TerminalView in Compose, status banner, extra keys
│   ├── SettingsScreen.kt        connection form, public key, trusted host key
│   ├── TerminalClients.kt       Termux callbacks (screen updates, clipboard, keys, zoom)
│   ├── SshConnection.kt         sshj connection, PTY, reconnect loop, ConnectionState
│   ├── ConnectionConfig.kt      config model + ConfigStore (SharedPreferences)
│   ├── KeyManager.kt            on-device SSH key, encrypted with Android Keystore
│   └── TofuHostKeyVerifier.kt   remembers and checks the PC's host key
├── terminal-emulator/           vendored Termux emulator (Apache 2.0, see NOTICE.md)
└── terminal-view/               vendored Termux Android view (Apache 2.0)
```

The app code is a single flat package for now. If it grows, split it into `ui/`, `connection/`,
`security/` and `settings/`.

## 2. Connection flow

```text
App starts
   │
   ▼
Load saved config (ConfigStore)
   │
   ├── incomplete ──► SettingsScreen (first-time setup)
   │
   └── complete
        │
        ▼
   SshConnection.start()
        │
        ▼
   SSH connect + check host key
        │
        ▼
   Authenticate (phone key or password)
        │
        ▼
   Allocate PTY (xterm-256color, current terminal size)
        │
        ▼
   exec "tmux new-session -A -s main"
        │
        ▼
   Pump bytes: SSH ⇄ TerminalSession ⇄ TerminalView
```

The key command is:

```bash
tmux new-session -A -s main
```

It handles both cases, so the app never needs to know whether `main` already exists:

```text
tmux session exists         ──► attach to it
tmux session doesn't exist  ──► create it
```

The session name can be changed in Settings. A custom command can replace the whole thing, for example
when tmux isn't on the non-login `PATH`.

### Why run tmux as the SSH command, not by typing it into a shell

We run tmux with `session.exec(command)` instead of starting a shell and writing `tmux …\r` into it.

- There's no race with the shell prompt and no echoed command on screen.
- When tmux exits or you detach, the SSH channel ends. That lets the app tell
  **"you detached"** (channel EOF, connection still up → `Ended`) apart from
  **"the network died"** (connection gone → reconnect).

## 3. SSH layer (`SshConnection`)

sshj is used directly. The only interface between it and the terminal is:

```java
// terminal-emulator/.../TerminalSession.java
public interface Transport {
    void write(byte[] data, int offset, int count) throws IOException; // user input → PC
    void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
}
```

Output goes the other way via `TerminalSession.appendRemoteOutput(...)`. While connected,
`SshConnection` attaches a `Transport`. When the connection drops it detaches it, so the emulator, the
screen and the scrollback all survive reconnects.

If the SSH library ever needs to change, `SshConnection` is the only file that imports sshj.

## 4. Terminal layer

This is the most important part of the project. A raw SSH stream isn't just text. Programs send control
sequences such as:

```text
ESC [ 2 J     clear screen
ESC [ H       move cursor home
ESC [ 31 m    red text
```

These have to be interpreted:

```text
SSH bytes
    │
    ▼
TerminalEmulator (VT100/ANSI/xterm parser)
    │
    ▼
Terminal state
    ├── characters + colors
    ├── cursor position
    ├── scrollback (5000 lines)
    └── screen dimensions
          │
          ▼
    TerminalView (Android View, hosted in Compose)
```

This is what makes `vim`, `htop`, `nano`, `git`, `top` and `tmux` itself behave like they do in a real terminal.

We reuse Termux's `terminal-emulator` and `terminal-view` instead of writing our own. The original
`TerminalSession` started a local process through JNI. It was rewritten to read and write a remote
`Transport`, and the native code was removed. See `NOTICE.md` for details.

Input on a phone needs help, so `TerminalScreen` adds an extra-key row:

- ESC and TAB
- sticky one-shot CTRL and ALT
- `^B` (tmux prefix)
- arrow keys, PGUP and PGDN

Long-press selects and copies text, and pinch changes the font size.

## 5. Reconnection

This matters on Android because the network disappears when the phone switches between Wi-Fi and mobile data.

```kotlin
sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val attempt: Int) : ConnectionState
    data object Connected : ConnectionState
    data class Reconnecting(val reason: String, val retryInSeconds: Int) : ConnectionState
    data class Failed(val reason: String, val hostKeyChanged: Boolean = false) : ConnectionState
    data object Ended : ConnectionState   // tmux exited or you detached
}
```

```text
Connected
   │
   ├── detach / tmux exit ──────────────► Ended        (no auto-retry; tap Reconnect)
   │
   ├── auth rejected / host key changed ─► Failed       (needs user action)
   │
   └── network lost / keepalive timeout
          │
          ▼
       Reconnecting ── backoff 1s → 2s → 4s … 30s ──► Connecting ──► Connected
          ▲                                               │
          └────────────────── failure ────────────────────┘
```

Reconnection is triggered in these ways:

- **Network change:** a `ConnectivityManager` default-network callback closes the old socket and
  reconnects immediately when Wi-Fi ↔ mobile switches. There's no waiting for a timeout.
- **Dead link:** keepalives every 10s, with 3 misses allowed, detect it within about 30s.
- **App resumed:** coming back to the foreground skips any remaining backoff.
- **Retry now:** a button in the status banner.

Because **tmux persists on the PC**, reconnecting is easy:

```text
Phone loses connection → SSH dies → tmux keeps running
→ phone reconnects → attach to "main" → everything is still there
```

There's no foreground service. If Android kills the app in the background, opening it again simply
reconnects and reattaches.

## 6. Security

**Client key.** The phone generates its own ECDSA P-256 key pair:

```text
Android Keystore (AES-GCM key, never leaves the device)
       │ encrypts
       ▼
filesDir/id_ecdsa.enc  (SSH private key)
```

Settings shows the public key in `authorized_keys` format, with Copy and Share buttons and a "New key"
action. Password login is also supported.

**Host key (trust on first use).** The PC's key is stored as `type SHA256:fingerprint` per host and port:

```text
First connection ──► store fingerprint
Later connections ──► must match, otherwise Failed(hostKeyChanged)
                      └─► user taps "Trust new key" to accept the new one
```

Settings shows the trusted fingerprint, with a "Forget host key" action.

## 7. Technologies

| Component       | Choice                                        |
| --------------- | --------------------------------------------- |
| Language        | Kotlin (app), Java (vendored terminal)        |
| UI              | Jetpack Compose + Material 3 (dark)           |
| Architecture    | MVVM (`AndroidViewModel`)                     |
| Async           | Kotlin Coroutines + `StateFlow`               |
| SSH             | sshj 0.40 + full BouncyCastle 1.81            |
| Terminal        | Termux terminal-emulator / terminal-view      |
| Persistence     | SharedPreferences                             |
| Key protection  | Android Keystore (AES-GCM wrapping)           |
| Build           | AGP 9.4, Gradle 9.6, Kotlin 2.2               |
| PC              | OpenSSH + tmux                                |
| Minimum Android | 8.0 (API 26)                                  |

## 8. Roadmap

**Done**

- [x] SSH → PC → tmux, auto-connect on launch
- [x] Real terminal: ANSI/VT100, colors, resize, scrollback, Ctrl/Alt/Esc/arrows, copy/paste, zoom
- [x] Keystore-encrypted on-device SSH key
- [x] Host key pinning (trust on first use)
- [x] Automatic reconnect with backoff and network-change detection
- [x] Connection status banner and settings screen

**Next**

- [ ] Confirm the host key fingerprint on first connect, instead of trusting it silently
- [ ] Encrypt the saved password, which is currently plain text in SharedPreferences
- [ ] Import an existing private key, e.g. `id_ed25519`
- [ ] Settings toggles: connect automatically / reconnect automatically
- [ ] Multiple PCs
- [ ] tmux session picker using `tmux list-sessions`:

```text
┌─────────────────────────┐
│ Select tmux session     │
├─────────────────────────┤
│ ● main                  │
│   development           │
│   server                │
│                         │
│       + New session     │
└─────────────────────────┘
```

- [ ] Optional foreground service to keep the connection alive in the background
- [ ] Move persistence to DataStore
