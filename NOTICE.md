# Third-party code

`terminal-emulator/` and `terminal-view/` are vendored from
[termux/termux-app](https://github.com/termux/termux-app) (commit `8629e632`), which in turn derive from
[jackpal/Android-Terminal-Emulator](https://github.com/jackpal/Android-Terminal-Emulator). These two
libraries are licensed under the Apache License 2.0 (see `LICENSE-APACHE-2.0.txt`).

Modifications:
- Removed the JNI/native PTY code (`JNI.java`, `src/main/jni`).
- Rewrote `TerminalSession` to be backed by a pluggable remote `Transport` (an SSH channel) instead of a
  local subprocess, so the session can survive reconnects.
- Replaced the Groovy build scripts with Kotlin DSL ones.
