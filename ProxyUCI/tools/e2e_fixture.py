#!/usr/bin/env python3
"""Prepara el directorio que sirve la prueba de integración del túnel: un archivo de 5 MB aleatorio y su SHA-256.

Uso: python3 tools/e2e_fixture.py <directorio>
Deja <directorio>/big.bin y <directorio>/big.sha (la huella en hexadecimal).
"""

import hashlib
import os
import sys

SIZE = 5 * 1024 * 1024


def main():
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    data = os.urandom(SIZE)
    with open(os.path.join(directory, "big.bin"), "wb") as file:
        file.write(data)
    with open(os.path.join(directory, "big.sha"), "w") as file:
        file.write(hashlib.sha256(data).hexdigest())


if __name__ == "__main__":
    main()
