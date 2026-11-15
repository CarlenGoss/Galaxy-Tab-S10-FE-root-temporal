## Galaxy Tab S10 FE Root Temporal v1.0.0

First public release of the per-boot temporary root launcher for the Samsung
Galaxy Tab S10 FE Wi-Fi (SM-X520) on firmware `X520XXSBCZI2`.

### Included

- Signed Android launcher APK.
- Target-specific GhostLock payload and KernelSU loader bundled in the app.
- Shizuku-based one-button flow from the tablet.
- Verification of UID 0, KernelSU Live, and SELinux Enforcing.
- SHA-256 checksum in `SHA256SUMS.txt`.

### Requirements and limitations

- Supported only on `SM-X520` with firmware `X520XXSBCZI2`.
- Root is temporary and disappears after reboot.
- Shizuku and Wireless debugging must be set up first.
- KernelSU Manager is distributed separately by the official project:
  https://github.com/tiann/KernelSU/releases

Read the [English README](https://github.com/CarlenGoss/Galaxy-Tab-S10-FE-root-temporal/blob/main/README.md)
before installing. [Spanish README](https://github.com/CarlenGoss/Galaxy-Tab-S10-FE-root-temporal/blob/main/README.es.md)
is also available.
