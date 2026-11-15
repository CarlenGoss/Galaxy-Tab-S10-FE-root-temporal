package com.lenkosln.tabs10root;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity implements RootState.Listener {

    private static final int REQUEST_SHIZUKU = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;

    private static final String STATUS_SCRIPT = """
            export PATH=/system/bin:/system/xbin:$PATH
            T=/data/local/tmp
            U=""
            if [ -x $T/cve-2026-43499-root ]; then
              U=$($T/cve-2026-43499-root -c 'id -u' 2>/dev/null)
            fi
            echo "U=$U"
            echo "K=$(grep -c kernelsu /proc/modules 2>/dev/null)"
            echo "E=$(getenforce 2>/dev/null)"
            """;

    private final Handler main = new Handler(Looper.getMainLooper());

    private MaterialCardView statusCard;
    private ImageView statusIcon;
    private TextView statusTitle;
    private TextView statusDetail;
    private TextView statUid;
    private TextView statKsu;
    private TextView statSelinux;
    private TextView logText;
    private ScrollView logScroll;
    private LinearProgressIndicator progress;
    private MaterialButton runButton;

    private boolean destroyed;
    private int lastLogLength = -1;
    private boolean refreshing;
    private boolean statusErrorLogged;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != REQUEST_SHIZUKU) {
                    return;
                }
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    main.post(this::startRootService);
                } else {
                    toast("Permiso de Shizuku denegado");
                    main.post(this::render);
                }
            };

    private final Shizuku.OnBinderReceivedListener binderReceived = () -> main.post(() -> {
        render();
        refreshStatus();
    });

    private final Shizuku.OnBinderDeadListener binderDead = () -> main.post(this::render);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(!night);
        controller.setAppearanceLightNavigationBars(!night);
        setContentView(R.layout.activity_main);

        statusCard = findViewById(R.id.statusCard);
        statusIcon = findViewById(R.id.statusIcon);
        statusTitle = findViewById(R.id.statusTitle);
        statusDetail = findViewById(R.id.statusDetail);
        statUid = findViewById(R.id.statUid);
        statKsu = findViewById(R.id.statKsu);
        statSelinux = findViewById(R.id.statSelinux);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        progress = findViewById(R.id.progress);
        runButton = findViewById(R.id.runButton);

        applyInsets();
        runButton.setOnClickListener(v -> onRunClicked());
        ((MaterialButton) findViewById(R.id.checkButton)).setOnClickListener(v -> refreshStatus());
        ((MaterialButton) findViewById(R.id.shizukuButton)).setOnClickListener(v -> openShizuku());

        Shizuku.addBinderReceivedListenerSticky(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        RootState.addListener(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        RootState.removeListener(this);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        super.onDestroy();
    }

    private void applyInsets() {
        View root = findViewById(R.id.root);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(baseLeft + bars.left, baseTop + bars.top,
                    baseRight + bars.right, baseBottom + bars.bottom);
            return windowInsets;
        });
    }

    private void onRunClicked() {
        if (RootState.phase == RootState.Phase.RUNNING) {
            toast("El root ya se está ejecutando");
            return;
        }
        if (!ShizukuShell.binderAlive()) {
            toast("Shizuku no está activo. Ábrelo y actívalo con Depuración inalámbrica.");
            openShizuku();
            return;
        }
        if (!ShizukuShell.hasPermission()) {
            try {
                Shizuku.requestPermission(REQUEST_SHIZUKU);
            } catch (Throwable t) {
                toast("No se pudo pedir permiso a Shizuku: " + t.getMessage());
            }
            return;
        }
        startRootService();
    }

    private void startRootService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        }
        Intent intent = new Intent(this, RootService.class).setAction(RootService.ACTION_RUN);
        try {
            ContextCompat.startForegroundService(this, intent);
        } catch (Throwable t) {
            toast("No se pudo iniciar el servicio: " + t.getMessage());
        }
    }

    private void refreshStatus() {
        if (refreshing || RootState.phase == RootState.Phase.RUNNING || !ShizukuShell.ready()) {
            render();
            return;
        }
        refreshing = true;
        new Thread(() -> {
            try {
                String output = ShizukuShell.run(STATUS_SCRIPT);
                String uid = "";
                String ksu = "";
                String selinux = "";
                for (String line : output.split("\n")) {
                    line = line.trim();
                    if (line.startsWith("U=")) {
                        uid = line.substring(2).trim();
                    } else if (line.startsWith("K=")) {
                        ksu = line.substring(2).trim();
                    } else if (line.startsWith("E=")) {
                        selinux = line.substring(2).trim();
                    }
                }
                if (!uid.isEmpty()) {
                    RootState.setUid("0".equals(uid) ? "0 (root)" : uid);
                }
                if (!ksu.isEmpty()) {
                    RootState.setKernelsu(parseInt(ksu, 0) > 0 ? "Live" : "No");
                }
                if (!selinux.isEmpty()) {
                    RootState.setSelinux(selinux);
                }
                if (parseInt(ksu, 0) > 0 && "Enforcing".equals(selinux)
                        && RootState.phase != RootState.Phase.RUNNING) {
                    // KernelSU is live and SELinux is enforcing: root is
                    // available on this boot even if the app was restarted and
                    // no run happened in this process.
                    if (RootState.logEmpty()) {
                        RootState.appendLog("KernelSU ya activo en este arranque.");
                    }
                    RootState.finish(true, null);
                }
            } catch (Throwable t) {
                // Status refresh is best effort; report the first failure only.
                if (!statusErrorLogged) {
                    statusErrorLogged = true;
                    RootState.appendLog("Estado: " + t.getClass().getSimpleName()
                            + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                }
            } finally {
                refreshing = false;
                main.post(this::render);
            }
        }, "status-refresh").start();
    }

    @Override
    public void onChanged() {
        main.post(this::render);
    }

    private void render() {
        if (destroyed) {
            return;
        }
        RootState.Phase phase = RootState.phase;
        boolean binder = ShizukuShell.binderAlive();
        boolean granted = binder && ShizukuShell.hasPermission();
        boolean running = phase == RootState.Phase.RUNNING;

        int container;
        int onContainer;
        int icon;
        String title;
        String detail;

        switch (phase) {
            case RUNNING:
                container = com.google.android.material.R.attr.colorSecondaryContainer;
                onContainer = com.google.android.material.R.attr.colorOnSecondaryContainer;
                icon = R.drawable.ic_bolt;
                title = "Ejecutando";
                detail = RootState.stage;
                break;
            case SUCCESS:
                container = com.google.android.material.R.attr.colorTertiaryContainer;
                onContainer = com.google.android.material.R.attr.colorOnTertiaryContainer;
                icon = R.drawable.ic_check_circle;
                title = "Root activo";
                detail = "KernelSU Live · SELinux " + RootState.selinux;
                break;
            case FAILED:
                container = com.google.android.material.R.attr.colorErrorContainer;
                onContainer = com.google.android.material.R.attr.colorOnErrorContainer;
                icon = R.drawable.ic_shield;
                title = "No se pudo completar";
                detail = RootState.error.isEmpty()
                        ? "Reinicia el tablet y vuelve a intentarlo."
                        : RootState.error;
                break;
            default:
                container = com.google.android.material.R.attr.colorSurfaceVariant;
                onContainer = com.google.android.material.R.attr.colorOnSurfaceVariant;
                icon = R.drawable.ic_shield;
                if (!binder) {
                    title = "Shizuku no está activo";
                    detail = "Ábrelo y actívalo con Depuración inalámbrica.";
                } else if (!granted) {
                    title = "Permiso de Shizuku pendiente";
                    detail = "Pulsa el botón para conceder el acceso.";
                } else {
                    title = "Listo para empezar";
                    detail = "Root temporal por arranque · SBCZI2";
                }
                break;
        }

        statusCard.setCardBackgroundColor(color(container));
        int on = color(onContainer);
        statusTitle.setText(title);
        statusTitle.setTextColor(on);
        statusDetail.setText(detail);
        statusDetail.setTextColor(on);
        statusIcon.setImageResource(icon);
        statusIcon.setImageTintList(ColorStateList.valueOf(on));

        boolean rootLive = "Live".equals(RootState.kernelsu);
        statUid.setText(rootLive && "—".equals(RootState.uid) ? "0 (root)" : RootState.uid);
        statKsu.setText(RootState.kernelsu);
        statSelinux.setText(RootState.selinux);

        progress.setVisibility(running ? View.VISIBLE : View.GONE);
        runButton.setEnabled(!running);
        runButton.setText(running ? "Ejecutando…" : "Iniciar root temporal");

        String text = RootState.logText();
        if (text.length() != lastLogLength) {
            lastLogLength = text.length();
            logText.setText(text);
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private int color(int attr) {
        return MaterialColors.getColor(statusCard, attr);
    }

    private void openShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
        if (launch == null) {
            toast("Shizuku no está instalado");
            return;
        }
        try {
            startActivity(launch);
        } catch (Throwable t) {
            toast("No se pudo abrir Shizuku");
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            // The foreground service runs regardless of the notification grant.
            return;
        }
    }
}
