package com.lenkosln.tabs10root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Foreground worker that stages the SBCZI2 payloads and runs the GhostLock
 * chain through Shizuku.  The exploit itself is probabilistic, so the payload
 * supervisor performs several attempts; a failure that reboots the kernel is
 * expected behaviour and is reported as such.
 */
public final class RootService extends Service {

    static final String ACTION_RUN = "com.lenkosln.tabs10root.action.RUN";
    static final String ACTION_STOP = "com.lenkosln.tabs10root.action.STOP";

    private static final String CHANNEL_ID = "root_status";
    private static final int NOTIFICATION_ID = 4321;
    private static final String TMP = "/data/local/tmp";

    private static final Pattern ATTEMPT =
            Pattern.compile("exploit attempt=(\\d+)/(\\d+)");
    private static final Pattern ANSI =
            Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]");

    private static final String SCRIPT = """
            export PATH=/system/bin:/system/xbin:$PATH
            T=/data/local/tmp
            say() { echo "@@$1"; }
            enforce_now() {
              E=$(getenforce 2>/dev/null)
              if [ "$E" != "Enforcing" ]; then
                $T/cve-2026-43499-root -c "/system/bin/setenforce 1" >/dev/null 2>&1
                E=$(getenforce 2>/dev/null)
              fi
              echo "$E"
            }
            BUILD=$(getprop ro.build.version.incremental)
            if [ "$BUILD" != "X520XXSBCZI2" ]; then
              say "ERROR=Firmware no soportado: $BUILD (se requiere X520XXSBCZI2)"
              say "DONE=fail"
              exit 20
            fi
            say "STAGE=Comprobando estado del dispositivo"
            KSU=$(grep -c kernelsu /proc/modules 2>/dev/null)
            if [ "${KSU:-0}" -ge 1 ]; then
              say "STAGE=KernelSU ya esta activo en este arranque"
              say "KSU=$KSU"
              E=$(enforce_now)
              say "ENFORCE=$E"
              say "DAEMON_UID=$($T/cve-2026-43499-root -c 'id -u' 2>/dev/null)"
              if [ "$E" = "Enforcing" ]; then
                say "DONE=ok"
              else
                say "ERROR=SELinux quedo en $E"
                say "DONE=fail"
              fi
              exit 0
            fi
            UID0=$($T/cve-2026-43499-root -c 'id -u' 2>/dev/null)
            if [ "$UID0" = "0" ]; then
              say "STAGE=Root ya disponible; cargando KernelSU"
            else
              say "STAGE=Ejecutando exploit"
              EXPLOIT_ATTEMPTS=12 P0_ATTEMPT_TIMEOUT_SEC=115 EXPLOIT_ATTEMPT_TIMEOUT_SEC=600 \\
                $T/cve-2026-43499-root --run-payload $T/x520.so \\
                $T/cve-2026-43499-root $T/tab-s10-root.log
              say "EXPLOIT_RC=$?"
              UID0=$($T/cve-2026-43499-root -c 'id -u' 2>/dev/null)
            fi
            say "DAEMON_UID=$UID0"
            if [ "$UID0" != "0" ]; then
              say "ERROR=El exploit no consiguio el daemon de root"
              say "DONE=fail"
              exit 1
            fi
            say "STAGE=Cargando KernelSU"
            $T/cve-2026-43499-root -c "/system/bin/cp $T/ksud-zi2 $T/ksud-s25u-kdp \\
              && /system/bin/cp $T/ksud-zi2 $T/.ksud-stage \\
              && /system/bin/chmod 755 $T/ksud-s25u-kdp $T/.ksud-stage" >/dev/null 2>&1
            say "STAGE_CP=$?"
            $T/cve-2026-43499-root --late-load
            say "LATE_RC=$?"
            KSU=$(grep -c kernelsu /proc/modules 2>/dev/null)
            say "KSU=${KSU:-0}"
            E=$(enforce_now)
            say "ENFORCE=$E"
            say "STAGE=Verificando"
            if [ "${KSU:-0}" -ge 1 ] && [ "$E" = "Enforcing" ]; then
              say "STAGE=Root activo"
              say "DONE=ok"
            else
              say "ERROR=KernelSU o SELinux no quedaron en el estado esperado"
              say "DONE=fail"
            fi
            """;

    private volatile Process process;
    private volatile boolean running;
    private volatile String doneFlag;
    private volatile String errorMessage;
    private volatile int kernelsuCount = -1;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Root temporal", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Progreso del root temporal por arranque");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat(buildNotification("Preparando payload…", true));
        if (!running) {
            running = true;
            RootState.begin();
            Thread worker = new Thread(this::runChain, "root-runner");
            worker.setDaemon(false);
            worker.start();
        }
        return START_NOT_STICKY;
    }

    private void runChain() {
        try {
            RootState.setStage("Preparando payload");
            updateNotification("Preparando payload…");
            stagePayloads();
            RootState.appendLog("Payloads preparados en " + TMP);

            RootState.setStage("Ejecutando exploit");
            updateNotification("Ejecutando exploit…");
            process = ShizukuShell.start(SCRIPT);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                handleLine(line);
            }
            process.waitFor();
        } catch (Throwable t) {
            errorMessage = describe(t);
            RootState.appendLog("ERROR: " + errorMessage);
            StackTraceElement[] trace = t.getStackTrace();
            for (int i = 0; i < Math.min(trace.length, 5); i++) {
                RootState.appendLog("  at " + trace[i]);
            }
        } finally {
            process = null;
            finishRun();
        }
    }

    private void finishRun() {
        boolean success = "ok".equals(doneFlag) && kernelsuCount >= 1;
        if (success) {
            RootState.setUid("0 (root)");
            RootState.setKernelsu("Live");
            RootState.setStage("Root activo");
            RootState.appendLog("Root temporal activo en este arranque.");
            RootState.finish(true, null);
            updateNotification("Root activo · KernelSU Live");
        } else {
            String reason = errorMessage;
            if (reason == null || reason.isEmpty()) {
                reason = "El exploit no completó la escalada. Reinicia el tablet y vuelve a intentarlo.";
            }
            RootState.setStage("Sin root");
            RootState.appendLog("Proceso finalizado sin root.");
            RootState.finish(false, reason);
            updateNotification("Root no disponible");
        }
        running = false;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Parses one relayed line: markers update state, everything else is log. */
    private void handleLine(String raw) {
        String line = ANSI.matcher(raw.replace("\r", "")).replaceAll("").trim();
        if (line.isEmpty()) {
            return;
        }
        if (line.startsWith("@@")) {
            String body = line.substring(2);
            int split = body.indexOf('=');
            String key = split < 0 ? body : body.substring(0, split);
            String value = split < 0 ? "" : body.substring(split + 1);
            switch (key) {
                case "STAGE":
                    RootState.setStage(value);
                    RootState.appendLog("» " + value);
                    updateNotification(value + "…");
                    break;
                case "DAEMON_UID":
                    if ("0".equals(value)) {
                        RootState.setUid("0 (root)");
                    } else if (!value.isEmpty()) {
                        RootState.setUid(value);
                    }
                    break;
                case "KSU":
                    kernelsuCount = parseInt(value, -1);
                    RootState.setKernelsu(kernelsuCount > 0 ? "Live" : "No");
                    break;
                case "ENFORCE":
                    RootState.setSelinux(value.isEmpty() ? "—" : value);
                    break;
                case "EXPLOIT_RC":
                case "LATE_RC":
                case "STAGE_CP":
                    RootState.appendLog(key.toLowerCase() + " = " + value);
                    break;
                case "ERROR":
                    errorMessage = value;
                    RootState.appendLog("ERROR: " + value);
                    break;
                case "DONE":
                    doneFlag = value;
                    break;
                default:
                    break;
            }
            return;
        }

        Matcher attempt = ATTEMPT.matcher(line);
        if (attempt.find()) {
            RootState.setStage("Exploit: intento " + attempt.group(1) + " de " + attempt.group(2));
        }
        RootState.appendLog(line);
    }

    private void stagePayloads() throws Exception {
        stageAsset("x520.so", TMP + "/x520.so");
        stageAsset("cve-2026-43499-root", TMP + "/cve-2026-43499-root");
        stageAsset("ksud", TMP + "/ksud-zi2");
    }

    private void stageAsset(String asset, String destination) throws Exception {
        long expected = assetLength(asset);
        String temporary = destination + ".new";
        RootState.appendLog("Preparando " + asset + " (" + expected + " bytes)");

        // Fast path: stream the asset through the shell user's stdin.  The
        // payload is written next to its destination and renamed into place,
        // so a helper that is still running is never truncated (ETXTBSY).
        try (InputStream in = getAssets().open(asset)) {
            ShizukuShell.push(in, temporary);
            ShizukuShell.run("mv -f " + temporary + " " + destination);
            if (remoteSize(destination) == expected) {
                RootState.appendLog("  → " + destination + " listo");
                return;
            }
            RootState.appendLog("  → tamaño inesperado, usando copia alternativa");
        } catch (Throwable t) {
            RootState.appendLog("  → envío directo falló: " + describe(t));
        }

        // Fallback: write to app storage and copy with the shell user.
        File staged = new File(getExternalFilesDir(null), asset);
        copyAsset(asset, staged);
        ShizukuShell.run("cp '" + staged.getAbsolutePath() + "' " + temporary
                + " && chmod 755 " + temporary
                + " && mv -f " + temporary + " " + destination);
        if (remoteSize(destination) != expected) {
            throw new IOException("No se pudo preparar " + asset);
        }
        RootState.appendLog("  → " + destination + " listo (copia)");
    }

    private long remoteSize(String path) {
        try {
            String out = ShizukuShell.run("stat -c %s " + path + " 2>/dev/null").trim();
            return Long.parseLong(out);
        } catch (Throwable t) {
            return -1;
        }
    }

    private long assetLength(String asset) throws IOException {
        try (InputStream in = getAssets().open(asset)) {
            long total = 0;
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                total += read;
            }
            return total;
        }
    }

    private void copyAsset(String asset, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("No se pudo crear " + parent);
        }
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        if (message == null || message.isEmpty()) {
            message = t.getClass().getSimpleName();
        }
        if (t instanceof SecurityException) {
            return "Shizuku denegó el acceso: " + message;
        }
        return message;
    }

    private Notification buildNotification(String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_root)
                .setContentTitle("Tab S10 FE Root")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(String text) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification(text, running));
    }

    @Override
    public void onDestroy() {
        Process current = process;
        if (current != null) {
            current.destroy();
        }
        super.onDestroy();
    }
}
