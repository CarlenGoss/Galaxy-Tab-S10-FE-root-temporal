# Validación en hardware

Registro de las pruebas realizadas sobre un dispositivo real durante el
desarrollo. Sirve como evidencia de que la cadena y la app funcionan en la build
soportada, y como lista de comprobación para futuras revisiones.

## Entorno

| Elemento | Valor |
| --- | --- |
| Dispositivo | Samsung Galaxy Tab S10 FE (Wi-Fi), `SM-X520` |
| Build | `X520XXSBCZI2` (Android 16, One UI 8) |
| Kernel | `6.6.102-android15-8-abX520XXSBCZI2-4k` |
| Shizuku | 13.6.0.r1318 (`moe.shizuku.privileged.api`) |
| KernelSU Manager | v3.2.5 (versionCode 32525) |
| App | `com.lenkosln.tabs10root` 1.0 |
| Fecha | 14–15 de noviembre de 2026 |

## 1. Cadena completa desde ADB

```
$ adb shell 'cd /data/local/tmp; EXPLOIT_ATTEMPTS=16 ... ./cve-2026-43499-root --run-payload ...'
[+] pipe physrw pid=... done=1 root=1 kaslr=1 read_ok=1 write_ok=1 rw64=1/1 uid=2000->0
[+] exploit completed attempt=4/8
$ adb shell './cve-2026-43499-root --late-load'; echo enforce=$(getenforce)
late_rc=0
enforce=Enforcing
$ adb shell 'grep kernelsu /proc/modules'
kernelsu 172032 0 - Live 0x0000000000000000 (O)
```

Resultado: `uid 0`, módulo KernelSU en `Live`, SELinux en `Enforcing`.

## 2. Cadena completa desde la app, tras arranque limpio

Secuencia: `adb reboot` → espera a `sys.boot_completed=1` → Shizuku arranca solo
por depuración inalámbrica → abrir la app → pulsar **Iniciar root temporal**.

```
STATUS: Ejecutando | Exploit: intento 2 de 8
STATUS: Root activo | KernelSU Live · SELinux Enforcing | UID 0 (root) | KSU Live | SEL Enforcing
LOG: » Root activo / Root temporal activo en este arranque.
```

Repetido en varios arranques; los aciertos del exploit se dieron en los intentos
**1, 2, 4 y 5** según el arranque. El log completo queda en
`/data/local/tmp/tab-s10-root.log`.

## 3. Fallo del exploit (caso negativo)

Arranque real en el que la carrera no acertó en 8 intentos:

```
STATUS: No se pudo completar | El exploit no consiguio el daemon de root
LOG: [-] exploit attempt=8/8 failed status=1
     [!] exploit failed after 8 independent attempts
```

Comprobaciones posteriores:

- `getenforce` → `Enforcing`
- `/proc/modules` sin `kernelsu`
- el kernel **no** se reinició (`/proc/uptime` continuo)
- la UI muestra el botón habilitado y la instrucción de reiniciar

## 4. Segundo intento sin reiniciar

Con KernelSU ya cargado, pulsar el botón otra vez no repite el exploit:

```
» Comprobando estado del dispositivo
» KernelSU ya esta activo en este arranque
Root temporal activo en este arranque.
```

Termina en segundos y deja la UI en «Root activo».

## 5. Reabrir la app con el root ya activo

`am force-stop` + `am start`: la app consulta el estado y se marca como
«Root activo · KernelSU Live · SELinux Enforcing» con `UID raíz = 0 (root)`
(antes de la corrección mostraba «Esperando instrucciones…»).

## 6. Shizuku detenido

```
$ adb shell 'am force-stop moe.shizuku.privileged.api; kill $(pgrep -f ^shizuku_server$)'
STATUS: Shizuku no está activo | Ábrelo y actívalo con Depuración inalámbrica.
```

Al pulsar el botón se abre Shizuku, que arranca su servidor por depuración
inalámbrica:

```
Starting with wireless adb...
info: shizuku_server pid is 11283
Service started
```

Al volver a la app, el estado se recupera solo (sin reiniciar nada).

## 7. Notificación en primer plano

```
StatusBarNotification(pkg=com.lenkosln.tabs10root id=4321
  Notification(channel=root_status ... flags=ONGOING_EVENT|ONLY_ALERT_ONCE|NO_CLEAR|FOREGROUND_SERVICE ...))
```

## 8. Interfaz

- Revisada en modo claro y oscuro del sistema (`cmd uimode night yes|no`).
- Paleta propia índigo/teal con `android:forceDarkAllowed=false`, para que el
  modo oscuro use `values-night/colors.xml` y no se lave.
- Estado, tres indicadores (UID, KernelSU, SELinux), botón, registro con
  autoscroll y barra de progreso comprobados con la jerarquía de accesibilidad
  (`uiautomator dump`).

## 9. Integridad del estado de seguridad

Antes y después de las pruebas:

```
warranty_bit=0
knox=0
flash_locked=1
verifiedboot=green
vbmeta=locked
selinux=Enforcing
```

El gestor de KernelSU muestra «Funcionando ‹LKM› [Jailbreak mode]», versión
32525-2 y kernel `6.6.102-android15-8-abX520XXSBCZI2-4k`.

## Defectos encontrados y corregidos durante la validación

| Síntoma | Causa | Corrección |
| --- | --- | --- |
| `Text file busy` al reemplazar el helper | se truncaba un binario en ejecución | escribir `.new` + `mv -f` |
| `IllegalArgumentException: process hasn't exited` | Shizuku responde el estado antes de recolectar el hijo | fin por EOF del flujo; `waitFor()` best-effort |
| `sh: inaccessible or not found` | `PATH` vacío en el shell de Shizuku | `/system/bin/sh` + `export PATH=/system/bin:/system/xbin` |
| `su: connect daemon: Permission denied` | socket de fichero huérfano en `/data/local/tmp` | socket abstracto `cve43499-superuser` |
| Modo oscuro con colores claros | `DynamicColors` aplicaba la paleta clara | paleta propia con recursos `values-night` |
| Estado «Esperando instrucciones…» con root activo | estado en memoria tras reiniciar la app | consulta de estado + línea de log coherente |

## Cómo reproducir la validación

```sh
# 1. Estado de partida (debe ser stock)
adb shell 'getenforce; grep kernelsu /proc/modules || echo sin_kernelsu'

# 2. Cadena desde ADB
./root.sh

# 3. Comprobación
adb shell 'id; getenforce; grep kernelsu /proc/modules'

# 4. Reinicio y prueba desde la app
adb reboot
# esperar arranque, abrir la app, pulsar «Iniciar root temporal»
```
