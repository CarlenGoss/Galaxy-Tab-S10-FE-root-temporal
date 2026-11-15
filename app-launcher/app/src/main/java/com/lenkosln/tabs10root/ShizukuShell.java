package com.lenkosln.tabs10root;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import rikka.shizuku.Shizuku;

/**
 * Thin helpers around {@link Shizuku#newProcess}.  Every command runs as the
 * ADB shell user (uid 2000) with the {@code u:r:shell:s0} SELinux context,
 * which is exactly what the payload expects.
 *
 * <p>End of stream is used as the completion signal instead of
 * {@link Process#waitFor()}: some Shizuku server builds answer the exit
 * status request before the child has been reaped and throw
 * {@code IllegalArgumentException("process hasn't exited")}.</p>
 */
final class ShizukuShell {

    private static final String SH = "/system/bin/sh";
    private static final String PATH_EXPORT = "export PATH=/system/bin:/system/xbin:$PATH; ";

    private ShizukuShell() {
    }

    static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean ready() {
        return binderAlive() && hasPermission();
    }

    /** Starts {@code sh -c <script>} with stderr merged into stdout. */
    static Process start(String script) {
        return Shizuku.newProcess(
                new String[]{SH, "-c", "exec 2>&1\n" + PATH_EXPORT + script}, null, null);
    }

    /** Runs a script and returns everything it printed. */
    static String run(String script) throws IOException {
        Process process = start(script);
        String output = readAll(process.getInputStream());
        reap(process);
        return output;
    }

    /**
     * Streams {@code data} into {@code destination} through the shell user.
     * Closing the remote stdin gives {@code cat} its EOF, so no intermediate
     * file or shared storage is involved.
     */
    static void push(InputStream data, String destination) throws IOException {
        Process process = Shizuku.newProcess(new String[]{SH, "-c",
                "exec 2>&1; " + PATH_EXPORT
                        + "cat > " + destination + " && chmod 755 " + destination},
                null, null);
        try (OutputStream out = process.getOutputStream()) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = data.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
        String errors = readAll(process.getInputStream());
        reap(process);
        if (!errors.isEmpty()) {
            throw new IOException(errors.trim());
        }
    }

    private static void reap(Process process) {
        try {
            process.waitFor();
        } catch (Throwable ignored) {
            // The stream already reached EOF; the exit status is best effort.
        }
    }

    static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }
}
