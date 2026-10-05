package com.termux.terminal;

import android.annotation.SuppressLint;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A terminal session backed by a remote byte stream (e.g. an SSH channel) instead of a local process.
 * <p>
 * Modified from the Termux original, which spawned a local subprocess through JNI. Here the session
 * outlives individual connections: a {@link Transport} is attached while connected and detached when
 * the connection drops, so the emulator state (and scrollback) survives reconnects.
 * <p>
 * Remote output is fed in with {@link #appendRemoteOutput(byte[], int, int)} from any thread. User
 * input is queued and forwarded to the current transport on a dedicated writer thread. All terminal
 * emulation and client callbacks happen on the main thread.
 */
public final class TerminalSession extends TerminalOutput {

    /** The remote end of the session. */
    public interface Transport {
        /** Called on a background thread with bytes produced by user input. */
        void write(byte[] data, int offset, int count) throws IOException;

        /** Called on the main thread when the terminal size changes. */
        void resize(int columns, int rows, int cellWidthPixels, int cellHeightPixels);
    }

    private static final int MSG_NEW_INPUT = 1;
    private static final int MSG_FINISHED = 4;

    public final String mHandle = UUID.randomUUID().toString();

    TerminalEmulator mEmulator;

    /** Written to by the transport's reader thread, read by the main thread and fed to the emulator. */
    final ByteQueue mRemoteToTerminalIOQueue = new ByteQueue(64 * 1024);
    /** Written to from the main thread due to user interaction, read by the writer thread. */
    final ByteQueue mTerminalToRemoteIOQueue = new ByteQueue(4096);
    /** Buffer to write translate code points into utf8 before writing to mTerminalToRemoteIOQueue */
    private final byte[] mUtf8InputBuffer = new byte[5];

    /** Callback which gets notified when a session finishes or changes title. */
    TerminalSessionClient mClient;

    private volatile Transport mTransport;
    private boolean mFinished;

    /** Set by the application for user identification of session, not by terminal. */
    public String mSessionName;

    final Handler mMainThreadHandler = new MainThreadHandler();

    private final Integer mTranscriptRows;

    private static final String LOG_TAG = "TerminalSession";

    public TerminalSession(Integer transcriptRows, TerminalSessionClient client) {
        this.mTranscriptRows = transcriptRows;
        this.mClient = client;

        new Thread("TermSessionOutputWriter") {
            @Override
            public void run() {
                final byte[] buffer = new byte[4096];
                while (true) {
                    int bytesToWrite = mTerminalToRemoteIOQueue.read(buffer, true);
                    if (bytesToWrite == -1) return;
                    Transport transport = mTransport;
                    if (transport == null) continue; // Disconnected: drop input.
                    try {
                        transport.write(buffer, 0, bytesToWrite);
                    } catch (IOException e) {
                        Logger.logWarn(mClient, LOG_TAG, "Write to transport failed: " + e.getMessage());
                    }
                }
            }
        }.start();
    }

    /**
     * @param client The {@link TerminalSessionClient} interface implementation to allow
     *               for communication between {@link TerminalSession} and its client.
     */
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;

        if (mEmulator != null)
            mEmulator.updateTerminalSessionClient(client);
    }

    /** Attach the transport of a new connection, or null when disconnected. */
    public void setTransport(Transport transport) {
        mTransport = transport;
        if (transport != null && mEmulator != null) {
            transport.resize(mEmulator.mColumns, mEmulator.mRows, mEmulator.getCellWidthPixels(), mEmulator.getCellHeightPixels());
        }
    }

    /** Feed bytes received from the remote end. May be called from any thread. */
    public void appendRemoteOutput(byte[] data, int offset, int count) {
        if (offset != 0) {
            byte[] copy = new byte[count];
            System.arraycopy(data, offset, copy, 0, count);
            data = copy;
        }
        if (mRemoteToTerminalIOQueue.write(data, 0, count)) {
            mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
        }
    }

    /** Print a local status message (e.g. "[Disconnected]") into the terminal. May be called from any thread. */
    public void appendLocalMessage(String message) {
        byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        appendRemoteOutput(bytes, 0, bytes.length);
    }

    /** Inform the remote end of the new size and reflow or initialize the emulator. */
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) {
            mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, mTranscriptRows, mClient);
        } else {
            mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        }
        Transport transport = mTransport;
        if (transport != null) transport.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        // Process anything received before the emulator existed.
        mMainThreadHandler.sendEmptyMessage(MSG_NEW_INPUT);
    }

    /** The terminal title as set through escape sequences or null if none set. */
    public String getTitle() {
        return (mEmulator == null) ? null : mEmulator.getTitle();
    }

    /** Write data to the remote end. */
    @Override
    public void write(byte[] data, int offset, int count) {
        if (!mFinished) mTerminalToRemoteIOQueue.write(data, offset, count);
    }

    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }

    public TerminalEmulator getEmulator() {
        return mEmulator;
    }

    /** Notify the {@link #mClient} that the screen has changed. */
    protected void notifyScreenUpdate() {
        mClient.onTextChanged(this);
    }

    /** Reset state for terminal emulator state. */
    public void reset() {
        if (mEmulator == null) return; // Not shown yet (e.g. first-time setup): nothing to reset.
        mEmulator.reset();
        notifyScreenUpdate();
    }

    /** Permanently finish this session and stop its threads. */
    public void finishIfRunning() {
        mMainThreadHandler.sendEmptyMessage(MSG_FINISHED);
    }

    @Override
    public void titleChanged(String oldTitle, String newTitle) {
        mClient.onTitleChanged(this);
    }

    public synchronized boolean isRunning() {
        return !mFinished;
    }

    public synchronized int getExitStatus() {
        return 0;
    }

    @Override
    public void onCopyTextToClipboard(String text) {
        mClient.onCopyTextToClipboard(this, text);
    }

    @Override
    public void onPasteTextFromClipboard() {
        mClient.onPasteTextFromClipboard(this);
    }

    @Override
    public void onBell() {
        mClient.onBell(this);
    }

    @Override
    public void onColorsChanged() {
        mClient.onColorsChanged(this);
    }

    @SuppressLint("HandlerLeak")
    class MainThreadHandler extends Handler {

        final byte[] mReceiveBuffer = new byte[64 * 1024];

        MainThreadHandler() {
            super(Looper.getMainLooper());
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_FINISHED) {
                if (mFinished) return;
                synchronized (TerminalSession.this) {
                    mFinished = true;
                }
                mTransport = null;
                mTerminalToRemoteIOQueue.close();
                mRemoteToTerminalIOQueue.close();
                mClient.onSessionFinished(TerminalSession.this);
                return;
            }

            if (mEmulator == null) return; // Keep queued until the view gives us a size.
            int bytesRead = mRemoteToTerminalIOQueue.read(mReceiveBuffer, false);
            if (bytesRead > 0) {
                mEmulator.append(mReceiveBuffer, bytesRead);
                notifyScreenUpdate();
            }
        }

    }

}
