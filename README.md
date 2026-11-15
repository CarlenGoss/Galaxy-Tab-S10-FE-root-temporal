# Galaxy Tab S10 FE — Per-boot temporary root (SM-X520)

[🇪🇸 Leer este README en español](README.es.md)

Per-boot, **temporary and reversible** root for the **Samsung Galaxy Tab S10 FE
(Wi-Fi, SM-X520, Exynos 1580)** running build **X520XXSBCZI2**, without
unlocking the bootloader, flashing with Odin, or modifying the boot image (Knox
remains intact). It uses the Root My Galaxy port of the **CVE-2026-43499
("GhostLock")** kernel exploit and then loads **KernelSU** as a late-loaded
module with the exact `vermagic` for this build.

This repository includes an **Android launcher app** (`app-launcher/`) that runs
the complete chain on the tablet through [Shizuku]. After the initial setup, no
computer is required after each reboot: open the app, press one button, and
restore `uid 0` with SELinux in `Enforcing` mode.

> Use only on devices that you own or are explicitly authorized to test. Root
> exists in memory only until the next reboot; no partition is modified.

---

## Hardware validation

The complete chain was tested on a real SM-X520 with build X520XXSBCZI2 in
November 2026:

| Check | Result |
| --- | --- |
| Root from the launcher after a clean boot | `uid=0` |
| KernelSU | `kernelsu … Live`; manager shows `LKM` / `Jailbreak mode` |
| SELinux after completion | `Enforcing` |
| Second run without rebooting | Detects KernelSU and finishes within seconds |
| Clean exploit failure | No root, no kernel reboot, SELinux returns to `Enforcing` |
| Shizuku stopped | App reports it, opens Shizuku, and recovers when it returns |
| Progress notification | `root_status`, foreground, ongoing |
| Knox / bootloader | `warranty_bit=0`, `knox=0`, `flash_locked=1`, `verifiedboot=green`, `vbmeta=locked` |

See the detailed test record in [docs/VALIDACION.md](docs/VALIDACION.md).

## Supported device

| Requirement | Value |
| --- | --- |
| Model | `SM-X520` (Galaxy Tab S10 FE, Wi-Fi) |
| Firmware (AP/PDA) | `X520XXSBCZI2` (Android 16, One UI 8) |
| Kernel | `6.6.102-android15-8-abX520XXSBCZI2-4k` |

The payload is **pinned to this exact build**: kernel symbol offsets differ
between firmware versions. The app and `root.sh` refuse to run on another
build. If you have updated the tablet, re-port the payload using
[PORTING.md](PORTING.md).

---

## Installation and usage

### Option A — Launcher app on the tablet (recommended)

This option does not require a computer after the initial installation.

Requirements: Wireless debugging must be paired once so that Shizuku can start
its server as the Android `shell` user.

1. **Build and install the app:**

   ```sh
   cd app-launcher
   export ANDROID_HOME=/path/to/android-sdk   # or create local.properties
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Build requirements: JDK 17 and Android SDK 35. The Gradle wrapper downloads
   Gradle 8.9 automatically. The project was verified with AGP 8.7.3.

2. **Install Shizuku** from its [releases] and start it once using Wireless
   debugging → Pair device. On the validated tablet, the Shizuku server starts
   again after reboot when Wireless debugging remains paired.

3. Open **Tab S10 FE Root** and grant the Shizuku permission when requested.

4. Press **Start temporary root**. The app:
   - copies `x520.so`, `cve-2026-43499-root`, and `ksud` into
     `/data/local/tmp` through Shizuku stdin, without shared storage;
   - runs the GhostLock chain with up to 12 attempts;
   - loads the KernelSU module;
   - verifies `uid 0` + `kernelsu Live` + SELinux `Enforcing`.

   The status card, three indicators, and live log show progress. A foreground
   notification mirrors the operation. The complete exploit log remains at
   `/data/local/tmp/tab-s10-root.log`.

5. If the exploit misses, reboot the tablet and try again. The race is
   probabilistic; no permanent change is made and SELinux returns to
   `Enforcing`.

6. Install the [KernelSU Manager releases] (tested with v3.2.5,
   versionCode 32525) if you need to grant superuser access to other apps. It
   should report `LKM` / `Jailbreak mode`.

> **Every reboot returns the tablet to stock.** Repeat the launcher steps after
> each boot. Automatic exploit execution at boot is intentionally not enabled.

### Option B — ADB from a computer

```sh
./root.sh                 # tablet connected and authorized
ATTEMPTS=16 ./root.sh     # more race attempts if necessary
```

Requirements: USB debugging enabled and authorized, with `adb` in `PATH`.

`root.sh`:

1. pushes the exploit payload, root helper, and patched `ksud` to
   `/data/local/tmp`;
2. runs the GhostLock chain from the `shell` context (KASLR leak → PI-futex
   stack reclamation → fake fops → arbitrary kernel R/W through configfs →
   `uid 0`);
3. stages KernelSU through the DEFEX-safe path and loads the module
   (`kernelsu … Live`, while SELinux remains `Enforcing`);
4. verifies the module in `/proc/modules`.

---

## How it works

```text
app (Shizuku, uid 2000)  or  adb shell
        │
        ├─ /data/local/tmp/x520.so                 (GhostLock exploit)
        ├─ /data/local/tmp/cve-2026-43499-root     (daemon + late-load helper)
        └─ /data/local/tmp/ksud-zi2                (KernelSU, exact vermagic)
                │
                ├─ 1. KASLR leak through tracefs
                ├─ 2. PI-futex stack reclamation
                ├─ 3. Fake fops → arbitrary kernel R/W through configfs
                ├─ 4. Usermode helper → uid 0 daemon
                └─ 5. ksud late-load --package-name me.weishu.kernelsu
                        └─ KernelSU Live · SELinux Enforcing
```

Important implementation details:

- **Abstract socket:** the daemon listens on the Linux abstract socket
  `cve43499-superuser`, not on a file. This avoids stale socket files in
  `/data/local/tmp` that the `shell` domain cannot remove.
- **No `--ephemeral`:** on SBCZI2, `ksud` is invoked with
  `late-load --package-name me.weishu.kernelsu`; the ephemeral mode is not
  accepted by this build.
- **`waitFor()` is best-effort:** some Shizuku server versions return the exit
  status before reaping the child and can throw `process hasn't exited`. The
  app uses EOF from the process stream as the completion signal.
- After `late-load`, the exploit daemon is no longer accessible to `shell`.
  This is expected: KernelSU provides root from that point onward.

## Repository layout

```text
app-launcher/            Android Material 3 launcher using Shizuku
  app/src/main/assets/   x520.so, cve-2026-43499-root, ksud
bin/                     Reference binaries with published hashes
root.sh                  One-command ADB chain
tools/                   Porting utilities
docs/VALIDACION.md       Hardware validation record (Spanish)
PORTING.md               Procedure for re-deriving a payload
NOTICE                  Credits and changes from the upstream project
```

## Binaries and hashes

Built with Android NDK r29 and the android15-6.6 DDK:

| File | SHA-256 |
| --- | --- |
| `bin/x520.so` | `31798c25a3c724aa0a7f39a92a3c10167fe1198edf73181bdf81f8ea7b94a12e` |
| `bin/cve-2026-43499-root` | `39bfecffbf966eaa6558f5c8b6ea829eb326a7991720326b417a33a1ac4e7ba5` |
| `bin/ksud` | `025782064f277eca007cf9a65ce8911c206cdd053729c4f33070cbf3227b0ed7` |

## Troubleshooting

- **The exploit fails cleanly:** reboot the tablet and try again. The race is
  probabilistic; validation succeeded on attempts 1, 2, 4, and 5 on different
  boots, while one boot failed all 8 attempts. Do not stack many attempts on a
  boot that has already failed.
- **`su: connect daemon: Permission denied` after a successful run:** this is
  normal. KernelSU now provides root and the exploit daemon is no longer
  available to `shell`. An old `/data/local/tmp/temp_su.sock` is harmless.
- **`device unauthorized`:** accept the USB debugging RSA prompt on the tablet,
  or revoke authorizations and reconnect.
- **“Shizuku is not active”:** press **Open Shizuku** and start it with Wireless
  debugging. The launcher recovers when Shizuku returns.
- **OTA updates:** do not accept a build with a different kernel until the
  payload has been re-ported. Kernel offsets will change; see
  [PORTING.md](PORTING.md).

## Porting to another build or device

[PORTING.md](PORTING.md) documents the complete procedure: firmware
identification and download, kernel extraction, symbol/BTF recovery, offset
derivation (`tools/derive_offsets.py`), P0 fingerprint generation, exploit
build, exact-vermagic KernelSU module rebuild, hardware validation, and the
lessons learned during bring-up.

## Build information

| Element | Value |
| --- | --- |
| AI assistance | **DeepSeek V4.1 Flash Free** through the OpenCode agent (November 2026) |
| Payload build | Android NDK r29 + `android15-6.6` DDK |
| App build | JDK 17 · Android SDK 35 · Gradle 8.9 · AGP 8.7.3 |
| Validation device | Real SM-X520, `X520XXSBCZI2`, Android 16 / One UI 8 |
| Tools | adb, Shizuku 13.6.0.r1318, KernelSU Manager v3.2.5 (32525) |

The validation used real hardware, clean boots, induced failures, and security
state checks before and after the runs.

## Credits and provenance

- Base repository: [quillfinch/Galaxy-Tab-S10FE-Root] (Apache-2.0).
- Exploit chain and target build system: [BuSung-dev/Root-My-Galaxy] and
  [BuSung-dev/Root-My-Galaxy-Payloads] (Apache-2.0).
- Original CVE-2026-43499 research: Nebula Security (“IonStack” / GhostLock).
- [KernelSU] (GPL-3.0 for kernel components), with Samsung KDP/RKP/DEFEX
  late-load patches from the payloads project.
- [Shizuku], used to run the chain as the `shell` user from the app.

Changes from the upstream project are listed in [NOTICE](NOTICE).

## License and legal notice

Apache-2.0, inherited from Root-My-Galaxy / Root-My-Galaxy-Payloads (see
[LICENSE](LICENSE)). KernelSU components remain under GPL-3.0.

This tool may void warranties or violate terms of service when used on devices
that are not yours. Use it only on devices you own or are explicitly authorized
to test, and at your own risk.

[Shizuku]: https://shizuku.rikka.app/
[releases]: https://github.com/RikkaApps/Shizuku/releases
[KernelSU]: https://github.com/tiann/KernelSU
[KernelSU Manager releases]: https://github.com/tiann/KernelSU/releases
[quillfinch/Galaxy-Tab-S10FE-Root]: https://github.com/quillfinch/Galaxy-Tab-S10FE-Root
[BuSung-dev/Root-My-Galaxy]: https://github.com/BuSung-dev/Root-My-Galaxy
[BuSung-dev/Root-My-Galaxy-Payloads]: https://github.com/BuSung-dev/Root-My-Galaxy-Payloads
