#!/usr/bin/env python3
"""Pone en androidApp/module.yaml la versión de la etiqueta que se va a publicar.

Uso: python3 tools/release/set-android-version.py 0.1.0        (también 0.1.0-rc1)

versionName es la versión tal cual. versionCode, que Android exige entero y creciente, es
mayor*10000 + menor*100 + parche (0.1.0 -> 100, 1.2.3 -> 10203). Una versión previa como 0.1.0-rc1 comparte
código con la final: sirve para repartir APK a mano, no para subir cada una a Google Play.
"""

import pathlib
import re
import sys

MODULE = pathlib.Path(__file__).resolve().parents[2] / "androidApp" / "module.yaml"
SEMVER = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-[0-9A-Za-z.]+)?$")


def version_code(version: str) -> int:
    match = SEMVER.match(version)
    if not match:
        raise SystemExit(f"Versión no válida: {version!r} (se espera 1.2.3 o 1.2.3-rc1)")
    major, minor, patch = (int(part) for part in match.groups())
    if minor > 99 or patch > 99:
        raise SystemExit("El código de versión solo admite menor y parche de 0 a 99")
    return major * 10000 + minor * 100 + patch


def replace_once(text: str, pattern: str, replacement: str, what: str) -> str:
    result, count = re.subn(pattern, replacement, text, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"No encontré exactamente una línea de {what} en {MODULE} (encontré {count})")
    return result


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    version = sys.argv[1].removeprefix("v")
    code = version_code(version)
    text = MODULE.read_text(encoding="utf-8")
    text = replace_once(text, r"^(\s*)versionCode:.*$", rf"\g<1>versionCode: {code}", "versionCode")
    text = replace_once(text, r"^(\s*)versionName:.*$", rf"\g<1>versionName: {version}", "versionName")
    MODULE.write_text(text, encoding="utf-8")
    print(f"androidApp: versionName {version}, versionCode {code}")


if __name__ == "__main__":
    main()
