#!/usr/bin/env bash
# Construye ProxyUCI-<versión>-macos-arm64.dmg: ProxyUCI.app con una Java recortada dentro, sin firmar.
#
# Uso: tools/macos-dmg/build-dmg.sh <versión> [directorio de salida (por defecto build/dmg)]
# Requisitos: macOS en Apple Silicon y un JDK 25 completo en JAVA_HOME (jlink con jmods, jdeps, jar y jpackage).
#
# Como en Windows (tools/windows-exe/build-exe.ps1), no se usa el jar ejecutable de Amper: guarda las librerías
# por nombre de archivo y ocho se llaman igual en grupos distintos, así que unas pisan a otras. Se toma de
# `./kotlin run` el classpath exacto con el que Amper ejecuta la app, se copia cada librería con un prefijo
# numérico y se enlazan, en orden, desde un app.jar que solo lleva un manifiesto.
set -euo pipefail

VERSION="${1:?Falta la versión (por ejemplo 0.1.0)}"
OUT="${2:-build/dmg}"
[[ -n "${JAVA_HOME:-}" ]] || { echo "Falta JAVA_HOME (hace falta un JDK 25 completo)" >&2; exit 1; }
for tool in jlink jdeps jar jpackage; do
  [[ -x "$JAVA_HOME/bin/$tool" ]] || { echo "Falta $tool en $JAVA_HOME/bin" >&2; exit 1; }
done
[[ "$(uname -m)" == "arm64" ]] || { echo "Este script solo construye para Apple Silicon (arm64)" >&2; exit 1; }

# Los números de versión de macOS son solo cifras y puntos: «0.1.0-rc1» se queda en «0.1.0».
NUMERIC_VERSION="${VERSION%%-*}"
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$REPO"
OUT="$(mkdir -p "$OUT" && cd "$OUT" && pwd)"
WORK="$OUT/work"
rm -rf "$WORK" "$OUT"/*.dmg
mkdir -p "$WORK/input/lib" "$WORK/stub"

# 1. El classpath con el que Amper ejecuta la app. La autoprueba lo escribe en su informe, y de paso comprueba
#    que la app arranca sin empaquetar.
./kotlin run -m desktopApp --no-compose-hot-reload -- --self-test "$WORK/informe-sin-empaquetar.txt" >/dev/null
[[ "$(head -1 "$WORK/informe-sin-empaquetar.txt")" == "OK" ]] \
  || { echo "La autoprueba sin empaquetar falló:" >&2; cat "$WORK/informe-sin-empaquetar.txt" >&2; exit 1; }
grep '^classpath=' "$WORK/informe-sin-empaquetar.txt" | head -1 | sed 's/^classpath=//' | tr ':' '\n' | grep -v '^$' > "$WORK/classpath.txt"

# 2. Cada librería con un prefijo numérico: los nombres no se repiten y el orden del classpath se conserva.
index=0
: > "$WORK/enlazadas.txt"
while IFS= read -r entry; do
  [[ -f "$entry" ]] || { echo "El classpath trae algo que no es un archivo: $entry" >&2; exit 1; }
  index=$((index + 1))
  name="$(printf '%03d-%s' "$index" "$(basename "$entry")")"
  cp "$entry" "$WORK/input/lib/$name"
  echo "lib/$name" >> "$WORK/enlazadas.txt"
done < "$WORK/classpath.txt"
echo "Librerías: $index"

# app.jar solo trae un manifiesto: la clase principal y el classpath, en orden y con rutas relativas.
# Un manifiesto no admite líneas de más de 72 bytes: se parten, y cada continuación empieza con un espacio.
python3 - "$WORK" <<'PYTHON'
import sys
work = sys.argv[1]
names = open(f"{work}/enlazadas.txt").read().split()
line = "Class-Path: " + " ".join(names)
out, rest = line[:70], line[70:]
while rest:
    out += "\r\n " + rest[:69]
    rest = rest[69:]
with open(f"{work}/manifest.txt", "w", newline="") as f:
    f.write("Manifest-Version: 1.0\r\nMain-Class: org.uci.proxy.MainKt\r\n" + out + "\r\n")
PYTHON
echo "ProxyUCI: las librerías están en lib/, enlazadas desde el manifiesto de este jar." > "$WORK/stub/LEEME.txt"
"$JAVA_HOME/bin/jar" --create --file "$WORK/input/app.jar" --manifest "$WORK/manifest.txt" -C "$WORK/stub" .

# 3. Módulos de Java: se analizan todas las librerías juntas. jdk.crypto.ec no se ve por análisis (se carga por
#    reflexión) y hace falta para TLS con curvas elípticas.
MODULES="$("$JAVA_HOME/bin/jdeps" --multi-release 25 --ignore-missing-deps --print-module-deps \
  -cp "$WORK/input/lib/*" "$WORK"/input/lib/*.jar | tail -1),jdk.crypto.ec"
echo "Módulos de Java: $MODULES"

# 4. Runtime mínimo.
"$JAVA_HOME/bin/jlink" --module-path "$JAVA_HOME/jmods" --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress zip-6 --output "$WORK/runtime"

# 5. ProxyUCI.app. jpackage no admite versiones que empiecen por 0 en macOS: se le da 1.0.0 y se corrige después.
"$JAVA_HOME/bin/jpackage" --type app-image --name ProxyUCI --dest "$WORK/app" \
  --input "$WORK/input" --main-jar app.jar --main-class org.uci.proxy.MainKt \
  --runtime-image "$WORK/runtime" --app-version 1.0.0 --vendor "ProxyUCI" \
  --java-options "--enable-native-access=ALL-UNNAMED" --java-options "-Dfile.encoding=UTF-8"
APP="$WORK/app/ProxyUCI.app"
PLIST="$APP/Contents/Info.plist"
/usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $NUMERIC_VERSION" -c "Set :CFBundleVersion $NUMERIC_VERSION" "$PLIST"
# Cambiar el Info.plist invalida la firma ad hoc que jpackage dejó; en Apple Silicon hace falta al menos esa.
codesign --force --deep --sign - "$APP"

# 6. Autoprueba de la app ya empaquetada (Java recortada incluida).
"$APP/Contents/MacOS/ProxyUCI" --self-test "$WORK/informe-empaquetado.txt"
[[ "$(head -1 "$WORK/informe-empaquetado.txt")" == "OK" ]] \
  || { echo "La autoprueba de ProxyUCI.app falló:" >&2; cat "$WORK/informe-empaquetado.txt" >&2; exit 1; }
cat "$WORK/informe-empaquetado.txt" | grep -v '^classpath='

# 7. Imagen de disco con ProxyUCI.app y el acceso directo a Aplicaciones, sin firmar ni notarizar.
mkdir -p "$WORK/dmg"
cp -R "$APP" "$WORK/dmg/"
ln -s /Applications "$WORK/dmg/Applications"
DMG="$OUT/ProxyUCI-$VERSION-macos-arm64.dmg"
hdiutil create -volname "ProxyUCI" -srcfolder "$WORK/dmg" -ov -format UDZO "$DMG" >/dev/null

echo "$(basename "$DMG"): $(du -h "$DMG" | cut -f1)"
echo "SHA-256: $(shasum -a 256 "$DMG" | cut -d' ' -f1)"
# La app sin comprimir se deja para las pruebas de CI (ventana, motor); lo demás es temporal.
mv "$APP" "$OUT/ProxyUCI.app"
rm -rf "$WORK"
