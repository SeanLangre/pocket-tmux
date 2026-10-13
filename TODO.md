# TODO

**Done**

- [x] SSH → PC → tmux, auto-connect on launch
- [x] Real terminal: ANSI/VT100, colors, resize, scrollback, Ctrl/Alt/Esc/arrows, copy/paste, zoom
- [x] Keystore-encrypted on-device SSH key
- [x] Host key pinning (trust on first use)
- [x] Automatic reconnect with backoff and network-change detection
- [x] Connection status banner and settings screen
- [x] Two terminal panes (tabs)
- [x] Foreground service and input-needed notifications for AI CLI prompts
- [x] Quick Yes/No keys and quick-command menu

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

- [ ] Move persistence to DataStore
