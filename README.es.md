# Galaxy Tab S10 FE — root temporal por arranque (SM-X520)

[🇬🇧 Read this README in English](README.md)

Root **temporal, por arranque y reversible** para la **Samsung Galaxy Tab S10 FE
(Wi-Fi, SM-X520, Exynos 1580)** sobre la build **X520XXSBCZI2**, sin desbloquear
el bootloader, sin flashear con Odin y sin modificar la imagen de arranque (Knox
intacto). Usa el port de Root My Galaxy del exploit de kernel
**CVE-2026-43499 ("GhostLock")** y a continuación carga **KernelSU** como módulo
tardío con el `vermagic` exacto de esta build.

Incluye una **app Android lanzadora** (`app-launcher/`) que ejecuta toda la
cadena desde la propia tablet a través de [Shizuku], de modo que **después de
cada reinicio no hace falta ningún ordenador**: abres la app, pulsas un botón y
recuperas `uid 0` con SELinux en `Enforcing`.

> Solo para dispositivos propios o con autorización explícita. El root vive en
> memoria hasta el siguiente reinicio; no se toca ninguna partición.

---

## Estado verificado en hardware

Todo lo siguiente se probó en un SM-X520 real con la build X520XXSBCZI2
(kernel `6.6.102-android15-8-abX520XXSBCZI2-4k`), en noviembre de 2026:

| Comprobación | Resultado |
| --- | --- |
| Root desde la app tras arranque limpio | `uid=0` |
| KernelSU | `kernelsu … Live` · gestor «Funcionando ‹LKM› [Jailbreak mode]» |
| SELinux al terminar | `Enforcing` |
| Segundo intento sin reiniciar | Detecta KernelSU activo y termina en segundos |
| Fallo del exploit (8 intentos) | Sin root, sin reinicio del kernel, SELinux vuelve a `Enforcing` |
| Shizuku detenido | La app avisa, abre Shizuku y se recupera sola al volver |
| Notificación de progreso | `channel=root_status`, `FOREGROUND_SERVICE`, `ONGOING` |
| Knox / bootloader | `warranty_bit=0`, `knox=0`, `flash_locked=1`, `verifiedboot=green`, `vbmeta=locked` |

El detalle de las pruebas está en [docs/VALIDACION.md](docs/VALIDACION.md).

## Dispositivo compatible

| Requisito | Valor |
| --- | --- |
| Modelo | `SM-X520` (Galaxy Tab S10 FE, Wi-Fi) |
| Firmware (AP/PDA) | `X520XXSBCZI2` (Android 16, One UI 8) |
| Kernel | `6.6.102-android15-8-abX520XXSBCZI2-4k` |

El payload está **fijado a esta build exacta**: los offsets de símbolos cambian
entre firmwares. La app y `root.sh` se niegan a ejecutarse en otra build. Si has
actualizado, recompila el payload siguiendo [PORTING.md](PORTING.md).

---

## Instalación y uso

### Descargar una release precompilada

La opción recomendada para usuarios normales es descargar el APK desde la
[página de Releases](https://github.com/CarlenGoss/Galaxy-Tab-S10-FE-root-temporal/releases).
Cada release incluye el APK y `SHA256SUMS.txt`. El APK ya contiene el payload y
el cargador de KernelSU para `X520XXSBCZI2`; no es necesario compilar el
proyecto.

KernelSU Manager se distribuye por separado de forma intencionada. Descárgalo
desde las [releases oficiales de KernelSU](https://github.com/tiann/KernelSU/releases)
para poder actualizarlo de forma independiente y mantener clara su procedencia.

### Vía A — App en la tablet (recomendada, sin ordenador)

Requisitos: la tablet ya emparejada una vez con **Depuración inalámbrica**
(para que Shizuku pueda arrancar su servidor como usuario `shell`).

1. **Instala la app** (APK compilado en `app-launcher/app/build/outputs/apk/debug/`):

   ```sh
   adb install -r app-debug.apk
   ```

   O compílala tú mismo:

   ```sh
   cd app-launcher
   export ANDROID_HOME=/ruta/al/android-sdk   # o crea local.properties con sdk.dir=...
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   Requisitos de compilación: JDK 17, Android SDK 35 y Gradle 8.9 (el wrapper
   `./gradlew` lo descarga solo). Verificado con AGP 8.7.3.

2. **Instala Shizuku** ([releases de Shizuku]) y arráncalo una vez con
   *Depuración inalámbrica* → *Emparejar dispositivo*. En este dispositivo el
   servidor de Shizuku se reinicia solo tras cada arranque.

3. **Abre la app «Tab S10 FE Root»** y concede el permiso de Shizuku cuando lo
   pida.

4. Pulsa **Iniciar root temporal**. La app:
   - copia `x520.so`, `cve-2026-43499-root` y `ksud` a `/data/local/tmp`
     (enviados por la entrada estándar del proceso de Shizuku, sin almacenamiento
     compartido),
   - ejecuta la cadena GhostLock con hasta 12 intentos,
   - carga el módulo KernelSU,
   - verifica `uid 0` + `kernelsu Live` + SELinux `Enforcing`.

   La tarjeta de estado, los tres indicadores y el registro en vivo muestran el
   progreso; una notificación en primer plano lo refleja fuera de la app. El log
   completo del exploit queda en `/data/local/tmp/tab-s10-root.log`.

5. Si el exploit falla (es probabilístico), la app te pide **reiniciar** y
   reintentar. No se modifica nada permanente y SELinux vuelve a `Enforcing`.

6. Instala **KernelSU Manager** ([releases de KernelSU], probado con v3.2.5 /
   versionCode 32525) para conceder superusuario a las apps que lo necesiten. Debe
   mostrar «Funcionando ‹LKM› [Jailbreak mode]».

> **Cada reinicio vuelve a stock.** Repite los pasos 3–4 tras cada arranque; no
> hay autoarranque, por diseño.

### Vía B — ADB desde un ordenador

```sh
./root.sh                 # con la tablet conectada y autorizada
ATTEMPTS=16 ./root.sh     # más intentos si la carrera falla
```

Requisitos: depuración USB activada y autorizada, `adb` en el `PATH`.

Qué hace `root.sh`:

1. copia el payload, el helper de root y el `ksud` parcheado a `/data/local/tmp`;
2. ejecuta la cadena GhostLock desde el contexto `shell` (fuga de KASLR →
   reclamación de pila PI-futex → fops falsos → lectura/escritura arbitraria de
   kernel vía configfs → `uid 0`);
3. prepara el cargador de KernelSU por la ruta segura para DEFEX y carga el módulo
   (`kernelsu … Live`, SELinux sigue **Enforcing**);
4. comprueba el módulo en `/proc/modules`.

---

## Cómo funciona

```
app (Shizuku, uid 2000)  ó  adb shell
        │
        ├─ /data/local/tmp/x520.so                 (exploit GhostLock)
        ├─ /data/local/tmp/cve-2026-43499-root     (helper: daemon + late-load)
        └─ /data/local/tmp/ksud-zi2                (KernelSU con vermagic exacto)
                │
                ├─ 1. KASLR leak (tracefs)
                ├─ 2. reclamación de pila PI-futex (CVE-2026-43499)
                ├─ 3. fops falsos → R/W arbitrario de kernel (configfs)
                ├─ 4. usermode helper → daemon uid 0
                └─ 5. ksud late-load --package-name me.weishu.kernelsu
                        └─ módulo KernelSU Live · SELinux Enforcing
```

Detalles que importan:

- **Socket abstracto**: el daemon escucha en el socket abstracto de Linux
  `cve43499-superuser`, no en un fichero. Así no queda un socket huérfano en
  `/data/local/tmp` que el dominio `shell` no pueda borrar (causa del error
  `connect daemon: Permission denied` en versiones anteriores).
- **Sin `--ephemeral`**: en SBCZI2 el `ksud` se invoca con
  `late-load --package-name me.weishu.kernelsu`; el modo efímero no funciona en
  esta build.
- **`waitFor()` best-effort**: algunas versiones de Shizuku responden el estado
  de salida antes de recolectar el hijo (`process hasn't exited`); el fin real se
  detecta por el EOF del flujo.
- Tras el `late-load` el daemon deja de ser accesible para `shell` (es lo
  esperado: el root lo pasa a proporcionar KernelSU).

## Estructura del repositorio

```
app-launcher/            App Android (Material 3) que ejecuta la cadena con Shizuku
  app/src/main/assets/   x520.so, cve-2026-43499-root, ksud
bin/                     Binarios de referencia con hashes publicados
root.sh                  Cadena por ADB, un comando
tools/                   Utilidades de portado (derive_offsets.py, etc.)
PORTING.md               Procedimiento para re-derivar el payload en otra build
docs/VALIDACION.md       Registro de pruebas en hardware
NOTICE                   Créditos y cambios respecto al proyecto original
```

### Binarios y hashes

Construidos con NDK r29 y el DDK android15-6.6:

| Fichero | SHA-256 |
| --- | --- |
| `bin/x520.so` | `31798c25a3c724aa0a7f39a92a3c10167fe1198edf73181bdf81f8ea7b94a12e` |
| `bin/cve-2026-43499-root` | `39bfecffbf966eaa6558f5c8b6ea829eb326a7991720326b417a33a1ac4e7ba5` |
| `bin/ksud` | `025782064f277eca007cf9a65ce8911c206cdd053729c4f33070cbf3227b0ed7` |

## Solución de problemas

- **El exploit falla sin más** (aborta limpio, sin reinicio): reinicia la tablet y
  vuelve a intentarlo. La carrera es probabilística; en las pruebas acertó en los
  intentos 1, 2, 4 y 5 de distintos arranques, y un arranque falló los 8
  intentos. No apiles muchos intentos en un arranque ya fallido.
- **`su: connect daemon: Permission denied` tras un root correcto**: normal. El
  root ya lo sirve KernelSU; el daemon del exploit deja de ser accesible para
  `shell`. Un `/data/local/tmp/temp_su.sock` antiguo es inofensivo.
- **`device unauthorized`**: acepta el aviso RSA de depuración USB en la tablet
  (o revoca autorizaciones y vuelve a conectar).
- **«Shizuku no está activo»**: pulsa *Abrir Shizuku* y arráncalo con
  *Depuración inalámbrica*. La app se recupera sola al volver.
- **Actualizaciones OTA**: no aceptes una build con otro kernel hasta re-portar el
  payload ([PORTING.md](PORTING.md)); los offsets cambian.

## Portar a otra build o dispositivo

[PORTING.md](PORTING.md) documenta el proceso completo: identificación y descarga
del firmware, extracción del kernel, recuperación de símbolos/BTF, derivación de
offsets (`tools/derive_offsets.py`), generación de la huella P0, compilación del
exploit, recompilación del módulo KernelSU con `vermagic` exacto, validación en
hardware y los tropiezos aprendidos durante el bring-up.

## Cómo se construyó este repositorio

| Elemento | Valor |
| --- | --- |
| Contribuidor de IA | **GitHub Copilot**, mediante el agente OpenCode (noviembre de 2026) |
| Compilación del payload | Android NDK r29 + DDK `android15-6.6` |
| Compilación de la app | JDK 17 · Android SDK 35 · Gradle 8.9 · AGP 8.7.3 |
| Dispositivo de validación | SM-X520 real, `X520XXSBCZI2`, Android 16 / One UI 8 |
| Herramientas | adb, Shizuku 13.6.0.r1318, KernelSU Manager v3.2.5 (32525) |

Las pruebas se hicieron en hardware real, con arranques limpios, fallos
provocados y comprobación del estado de seguridad antes y después de cada una.

## Créditos y procedencia

- Base de este repositorio: [quillfinch/Galaxy-Tab-S10FE-Root] (Apache-2.0).
- Cadena de exploit y sistema de compilación por objetivo:
  [BuSung-dev/Root-My-Galaxy] y [BuSung-dev/Root-My-Galaxy-Payloads]
  (Apache-2.0).
- Investigación original de CVE-2026-43499: Nebula Security («IonStack» /
  GhostLock).
- [KernelSU] (GPL-3.0 en los componentes de kernel) con los parches de carga
  tardía KDP/RKP/DEFEX de Samsung.
- [Shizuku], que permite ejecutar la cadena como usuario `shell` desde la app.

Los cambios respecto al proyecto original están listados en [NOTICE](NOTICE).

## Licencia y aviso legal

Apache-2.0, heredada de Root-My-Galaxy / Root-My-Galaxy-Payloads (ver
[LICENSE](LICENSE)). Los componentes de KernelSU siguen bajo GPL-3.0.

El uso de esta herramienta puede invalidar garantías o incumplir términos de
servicio si se aplica a dispositivos que no son tuyos. Úsala solo en equipos
propios o con autorización explícita, y bajo tu responsabilidad.

[Shizuku]: https://shizuku.rikka.app/
[releases de Shizuku]: https://github.com/RikkaApps/Shizuku/releases
[KernelSU]: https://github.com/tiann/KernelSU
[releases de KernelSU]: https://github.com/tiann/KernelSU/releases
[quillfinch/Galaxy-Tab-S10FE-Root]: https://github.com/quillfinch/Galaxy-Tab-S10FE-Root
[BuSung-dev/Root-My-Galaxy]: https://github.com/BuSung-dev/Root-My-Galaxy
[BuSung-dev/Root-My-Galaxy-Payloads]: https://github.com/BuSung-dev/Root-My-Galaxy-Payloads
