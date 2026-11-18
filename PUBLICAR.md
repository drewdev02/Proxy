# Publicar una versión

Al subir una etiqueta `vX.Y.Z`, GitHub Actions ([release.yml](.github/workflows/release.yml)) pasa las pruebas,
construye los tres archivos con esa versión y crea la Release con ellos:

| Archivo | Plataforma | Estado de la firma |
|---|---|---|
| `ProxyUCI-X.Y.Z-android.apk` | Android 8.0 o superior | Firmado con tu keystore |
| `ProxyUCI-X.Y.Z-windows-x64.exe` | Windows 10/11 de 64 bits, un solo archivo | **Sin firmar**: SmartScreen puede avisar |
| `ProxyUCI-X.Y.Z-macos-arm64.dmg` | macOS con Apple Silicon | **Sin firmar ni notarizar**: la primera vez, clic derecho › Abrir |

Además van `SHA256SUMS.txt` y una atestación de procedencia. Una etiqueta con guion (`v0.1.0-rc1`) sale como pre-release.

## Publicar

```sh
git tag v0.1.0
git push origin v0.1.0
```

La versión sale de la etiqueta: no hay que editar nada. Si falta cualquier archivo, o las pruebas fallan, no se publica.

## Ensayar sin publicar

Actions › **Publicar versión** › *Run workflow*, con la versión que quieras (por ejemplo `0.0.1-rc1`). Construye todo y deja
los archivos como artefactos del trabajo, pero no crea ninguna Release. Si todavía no hay secretos de Android, el APK se
firma con una keystore desechable y se llama `…-android-PRUEBA.apk`.

## Una sola vez: la firma de Android

Un APK sin firmar no se instala, así que **una etiqueta falla si faltan estos secretos** (en lugar de publicar algo inservible).

1. Crea un `keystore.properties` fuera del repositorio con las cuatro claves y genera la keystore:

   ```properties
   storeFile=/ruta/segura/release.keystore
   storePassword=...
   keyAlias=proxyuci
   keyPassword=...
   ```

   ```sh
   ./kotlin tool generate-keystore --properties-file /ruta/segura/keystore.properties --dn "CN=ProxyUCI"
   ```

2. Guarda la keystore y sus contraseñas en un gestor de contraseñas. **Si las pierdes no podrás publicar actualizaciones
   que Android acepte instalar sobre la versión anterior.** Nunca las subas al repositorio (`.gitignore` ya excluye
   `keystore.properties`, `*.keystore` y `*.jks`).
3. Sube los cuatro secretos al repositorio (cada `gh secret set` te pide el valor; no queda en el historial de la terminal):

   ```sh
   base64 -i /ruta/segura/release.keystore | gh secret set ANDROID_KEYSTORE_BASE64 -R drewdev02/Proxy
   gh secret set ANDROID_KEYSTORE_PASSWORD -R drewdev02/Proxy
   gh secret set ANDROID_KEY_ALIAS -R drewdev02/Proxy
   gh secret set ANDROID_KEY_PASSWORD -R drewdev02/Proxy
   ```

## Comprobar lo descargado

```sh
shasum -a 256 -c SHA256SUMS.txt                       # las sumas
gh attestation verify ProxyUCI-0.1.0-android.apk -R drewdev02/Proxy   # salió de este repositorio
```

## Cómo se construye cada uno

- **Android:** `./kotlin build -m androidApp -p android -v release`, con `versionName` y `versionCode` escritos por
  [tools/release/set-android-version.py](tools/release/set-android-version.py) (`v0.1.0` → código 100).
- **Windows:** [tools/windows-exe](tools/windows-exe): un arranque en C# con una Java recortada y la app dentro; extrae
  la primera vez (unos 3 s) y las siguientes arranca directo.
- **macOS:** [tools/macos-dmg](tools/macos-dmg): `ProxyUCI.app` con una Java recortada, empaquetada con `jpackage`.

Windows y macOS no usan el jar ejecutable de Amper: guarda las librerías por nombre de archivo y ocho se llaman igual en
grupos distintos, así que unas pisan a otras y la ventana no abre. Ambos toman de `./kotlin run` el classpath exacto.

## Pendiente

- Firmar el `.exe` (certificado de firma de código) y firmar y notarizar el `.dmg` (Developer ID y `notarytool`).
- `.dmg` para Intel y `.exe` para ARM64.
- `.ipa` para iOS (capa 7).
