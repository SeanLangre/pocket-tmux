# Remote Terminal

Android app that opens straight into a tmux session on your PC over SSH.

```
Android app (Compose + Termux terminal view) ──SSH──▶ PC sshd ──▶ tmux new-session -A -s main
```

- Connects automatically on launch and attaches to (or creates) the tmux session.
- Reconnects automatically: on Wi-Fi/mobile switches immediately, otherwise with backoff (1s → 30s).
  Keepalives detect a dead link within ~30s. tmux keeps your session alive in between.
- Detaching (`prefix d`) or exiting tmux stops auto-reconnect; tap **Reconnect** to reattach.
- Auth with a key pair generated on the phone (ECDSA P-256; the private key is encrypted with an
  Android Keystore key), or with a password.
- Host keys are trusted on first use; a changed key blocks the connection until you accept it.
- Extra key row: ESC, TAB, sticky CTRL/ALT, `^B` (tmux prefix), arrows, PGUP/PGDN. Pinch to zoom,
  long-press to select/copy.

## PC setup

1. Make sure `sshd` and `tmux` are installed and sshd is running (`sudo systemctl enable --now sshd`).
2. Find the PC's LAN IP (`ip -4 addr`) — a static DHCP lease makes life easier.
3. Open the app, enter IP, username, and copy/share the shown public key, then on the PC:
   ```sh
   echo 'ecdsa-sha2-nistp256 AAAA... remote-terminal@android' >> ~/.ssh/authorized_keys
   ```
4. Tap **Save & connect**.

Optional `~/.tmux.conf` niceties for a phone:
```
set -g mouse on          # tap to select panes, swipe to scroll
set -g aggressive-resize on
```

## Build

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Layout

See `architecture.md` for the full design.

- `app/` — Compose UI, `SshConnection` (sshj, reconnect loop), `KeyManager`, `TofuHostKeyVerifier`.
- `terminal-emulator/`, `terminal-view/` — vendored Termux terminal libraries (Apache 2.0, see `NOTICE.md`).

## Limitations

- No foreground service: if Android kills the app in the background, it simply reconnects (and tmux
  reattaches) when you open it again.
- Reaching the PC from outside your LAN needs a VPN such as Tailscale/WireGuard; just use that address
  as the host.
# remote-terminal
# remote-terminal
