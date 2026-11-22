#!/usr/bin/env python3
"""Comprueba que los documentos no apuntan a nada que ya no existe.

Para cada archivo .md de la raíz y de docs/:
  - cada enlace relativo `[texto](ruta)` o `![](ruta)` tiene que existir, y su ancla (`#algo`) también;
  - cada ruta de código entre comillas invertidas que empieza por un módulo o carpeta del repositorio
    (`core/src/engine`, `tools/check_docs.py`...) tiene que existir.

Los documentos de docs/capas son históricos: cuentan cómo se construyó cada capa y citan código que luego cambió de
sitio. De ellos solo se comprueban los enlaces, no las rutas de código.

Las rutas con `<...>`, `*` o `{...}` son patrones y se saltan. Sale con 1 si algo no existe.

Uso: python3 tools/check_docs.py
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TOP = {p.name for p in ROOT.iterdir() if p.is_dir() and not p.name.startswith(".")} - {
    "build", "rea", "superproxy", "apks", "node_modules",
}
LINK = re.compile(r"!?\[[^\]]*\]\(([^)\s]+)\)")
CODE = re.compile(r"`([^`\n]+)`")
HEADING = re.compile(r"^#{1,6}\s+(.*?)\s*$", re.M)


def slug(title: str) -> str:
    """El ancla que GitHub genera para un título."""
    title = re.sub(r"[`*_]", "", title.lower().strip())
    title = re.sub(r"[^\w\s-]", "", title, flags=re.U)
    return re.sub(r"\s", "-", title)


def anchors(path: Path) -> set[str]:
    return {slug(h) for h in HEADING.findall(path.read_text())}


def documents():
    yield from sorted(list(ROOT.glob("*.md")) + list((ROOT / "docs").rglob("*.md")))


def check(path: Path) -> list[str]:
    errors = []
    text = path.read_text()
    here = path.relative_to(ROOT)
    historic = "docs/capas" in path.as_posix()
    for line_no, line in enumerate(text.split("\n"), 1):
        for target in LINK.findall(line):
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            file_part, _, anchor = target.partition("#")
            dest = path if not file_part else (path.parent / file_part).resolve()
            if file_part and not dest.exists():
                errors.append(f"{here}:{line_no}: el enlace «{target}» no existe")
            elif anchor and dest.suffix == ".md" and dest.exists() and anchor not in anchors(dest):
                errors.append(f"{here}:{line_no}: el ancla «#{anchor}» no está en {dest.relative_to(ROOT)}")
        for token in [] if historic else CODE.findall(line):
            if not token.split("/")[0] in TOP or "/" not in token or re.search(r"[<>*{}]", token):
                continue
            candidate = token.split()[0].rstrip(".,;:)").rstrip("/")
            if not (ROOT / candidate).exists():
                errors.append(f"{here}:{line_no}: la ruta `{candidate}` no existe")
    return errors


def main() -> int:
    errors = [e for doc in documents() for e in check(doc)]
    for e in errors:
        print(e)
    print(f"{len(errors)} problema(s)" if errors else "Documentos al día")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
