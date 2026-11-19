#!/bin/bash
# Compila libtsbridge (el puente de Tailscale) y la deja donde la app la busca:
#
#   core/resources@jvm/native/tsbridge/<so>-<arch>/libtsbridge.{dylib,so,dll}       (escritorio y pruebas de la JVM)
#   androidApp/jniLibs/<abi>/libtsbridge.so                                          (Android)
#
#   tools/tsbridge/build.sh                    # la del equipo actual
#   tools/tsbridge/build.sh android-arm64      # o android-x86_64; necesita el NDK (ANDROID_NDK_HOME o el del SDK)
#   tools/tsbridge/build.sh testnet            # la red de pruebas (control, DERP y nodo de salida) en build/tsbridge
#
# Las librerías no van al repositorio (pesan ~30 MB cada una): las construye el CI antes de compilar la app. Sin ellas,
# la app funciona igual, pero no ofrece Tailscale como motor de Otro País.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
TARGET="${1:-host}"
# Sin subir registros a Tailscale (además de desactivarlo en tiempo de ejecución) y sin lo que la app no usa.
TAGS="ts_omit_logtail,ts_omit_ssh,ts_omit_taildrop,ts_omit_drive"
LDFLAGS="-s -w"

java_include() {
  local home="${JAVA_HOME:-}"
  if [ -z "$home" ] && command -v /usr/libexec/java_home >/dev/null; then home="$(/usr/libexec/java_home)"; fi
  if [ -z "$home" ]; then home="$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")"; fi
  [ -f "$home/include/jni.h" ] || { echo "No encuentro jni.h: define JAVA_HOME con un JDK" >&2; exit 1; }
  echo "$home/include"
}

ndk() {
  local dir="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-}}"
  if [ -z "$dir" ]; then
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
    dir="$(ls -d "$sdk"/ndk/* 2>/dev/null | sort -V | tail -1)"
  fi
  [ -d "$dir" ] || { echo "No encuentro el NDK de Android: define ANDROID_NDK_HOME" >&2; exit 1; }
  echo "$dir"
}

cd "$HERE"
case "$TARGET" in
  testnet)
    mkdir -p "$ROOT/build/tsbridge"
    out="$ROOT/build/tsbridge/testnet"
    [ "$(go env GOOS)" = "windows" ] && out="$out.exe"
    go build -trimpath -o "$out" ./cmd/testnet
    echo "$out"
    ;;
  host)
    os="$(go env GOOS)"; arch="$(go env GOARCH)"
    case "$os" in
      darwin) name="libtsbridge.dylib"; dir="macos-$arch"; sub="darwin" ;;
      linux) name="libtsbridge.so"; dir="linux-$arch"; sub="linux" ;;
      windows) name="tsbridge.dll"; dir="windows-$arch"; sub="win32" ;;
      *) echo "Sistema no soportado: $os" >&2; exit 1 ;;
    esac
    inc="$(java_include)"
    out="$ROOT/core/resources@jvm/native/tsbridge/$dir/$name"
    mkdir -p "$(dirname "$out")"
    CGO_ENABLED=1 CGO_CFLAGS="-I$inc -I$inc/$sub" go build -trimpath -tags "$TAGS" -ldflags "$LDFLAGS" -buildmode=c-shared -o "$out" ./jni
    rm -f "${out%.*}.h"
    echo "$out"
    ;;
  android-arm64|android-x86_64)
    arch="${TARGET#android-}"
    case "$arch" in
      arm64) goarch=arm64; triple=aarch64-linux-android; abi=arm64-v8a ;;
      x86_64) goarch=amd64; triple=x86_64-linux-android; abi=x86_64 ;;
    esac
    n="$(ndk)"
    host="$(ls "$n/toolchains/llvm/prebuilt" | head -1)"
    cc="$n/toolchains/llvm/prebuilt/$host/bin/${triple}26-clang"
    out="$ROOT/androidApp/jniLibs/$abi/libtsbridge.so"
    mkdir -p "$(dirname "$out")"
    # jni.h viene en el NDK (sysroot), no hace falta un JDK.
    CGO_ENABLED=1 GOOS=android GOARCH="$goarch" CC="$cc" go build -trimpath -tags "$TAGS" -ldflags "$LDFLAGS" -buildmode=c-shared -o "$out" ./jni
    rm -f "${out%.*}.h"
    echo "$out"
    ;;
  *)
    echo "Destino desconocido: $TARGET" >&2; exit 1 ;;
esac
