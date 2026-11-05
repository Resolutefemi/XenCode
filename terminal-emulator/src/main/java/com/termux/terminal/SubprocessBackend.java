package com.termux.terminal;

import android.system.Os;
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;

/**
 * {@link SessionBackend} backed by a local pseudoterminal subprocess created via {@link JNI}
 * (the upstream Termux mechanism). This is what the local PRoot container mode uses — it needs
 * a real PTY for interactive shells (readline, ANSI, full-screen programs).
 *
 * <p>Owns the pty master file descriptor and the process id. {@link #getInputStream()} /
 * {@link #getOutputStream()} wrap the fd; {@link #resize} calls {@link JNI#setPtyWindowSize};
 * {@link #waitForExit} calls {@link JNI#waitFor}; {@link #close} calls {@link JNI#close} and
 * sends SIGKILL if still running.
 */
final class SubprocessBackend implements SessionBackend {

    private final int mPtyFd;
    private final int mPid;
    private final FileDescriptor mWrappedFd;
    private final InputStream mInputStream;
    private final OutputStream mOutputStream;

    SubprocessBackend(String shellPath, String cwd, String[] args, String[] env, int rows, int columns) {
        int[] processId = new int[1];
        mPtyFd = JNI.createSubprocess(shellPath, cwd, args, env, processId, rows, columns);
        mPid = processId[0];
        // On native failure (/dev/ptmx cannot be opened, fork denied) it returns fd=-1 with pid still 0, but
        // only prints a perror line to stderr (which lands in logcat, not the app log). Without failing here the
        // session would look "running" (0 != -1) forever — blank terminal, no logs, the reported symptom. Throw so upper layers enter the failed state.
        if (mPtyFd < 0 || mPid <= 0) {
            throw new IllegalStateException(
                "createSubprocess failed: ptyFd=" + mPtyFd + " pid=" + mPid
                    + " shell=" + shellPath + " cwd=" + cwd
                    + " (/dev/ptmx or fork denied by the kernel/SELinux; see logcat for perror details)");
        }
        mWrappedFd = wrapFileDescriptor(mPtyFd);
        mInputStream = new FileInputStream(mWrappedFd);
        mOutputStream = new FileOutputStream(mWrappedFd);
    }

    int getPid() {
        return mPid;
    }

    @Override
    public InputStream getInputStream() {
        return mInputStream;
    }

    @Override
    public OutputStream getOutputStream() {
        return mOutputStream;
    }

    @Override
    public void resize(int columns, int rows) {
        JNI.setPtyWindowSize(mPtyFd, rows, columns);
    }

    @Override
    public int waitForExit() {
        return JNI.waitFor(mPid);
    }

    @Override
    public void close() {
        if (mPid > 0) {
            try {
                Os.kill(mPid, OsConstants.SIGKILL);
            } catch (Exception ignored) {
            }
        }
        JNI.close(mPtyFd);
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) {
        FileDescriptor result = new FileDescriptor();
        try {
            Field descriptorField;
            try {
                descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
            } catch (NoSuchFieldException e) {
                // For desktop java:
                descriptorField = FileDescriptor.class.getDeclaredField("fd");
            }
            descriptorField.setAccessible(true);
            descriptorField.set(result, fileDescriptor);
        } catch (NoSuchFieldException | IllegalAccessException | IllegalArgumentException e) {
            throw new RuntimeException("Error accessing FileDescriptor#descriptor private field", e);
        }
        return result;
    }
}
