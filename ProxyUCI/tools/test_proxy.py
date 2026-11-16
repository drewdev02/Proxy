#!/usr/bin/env python3
"""Proxy HTTP de pruebas para ProxyUCI: CONNECT con autenticación Basic o NTLMv2.

Sirve para probar la app contra sockets reales sin depender del proxy institucional.
La verificación NTLMv2 está escrita aparte de la de la app (otro lenguaje, hashlib),
así que un acierto aquí no es la app dándose la razón a sí misma.

Uso:
    python3 tools/test_proxy.py --auth ntlm --user andrey --password secreta7
    python3 tools/test_proxy.py --auth basic --port 3128
    python3 tools/test_proxy.py --auth none --upstream

Desde el emulador de Android el equipo anfitrión es 10.0.2.2; desde el simulador de iOS, 127.0.0.1.
Solo para desarrollo: escucha sin cifrar y no debe exponerse fuera del equipo.
"""

import argparse
import base64
import hashlib
import hmac
import os
import select
import socket
import struct
import threading
import time


def md4(data: bytes) -> bytes:
    """MD4 (RFC 1320). hashlib ya no lo trae en las versiones actuales de OpenSSL."""

    def rol(value, bits):
        return ((value << bits) | (value >> (32 - bits))) & 0xFFFFFFFF

    padded = data + b"\x80" + b"\x00" * ((55 - len(data)) % 64) + struct.pack("<Q", len(data) * 8)
    a, b, c, d = 0x67452301, 0xEFCDAB89, 0x98BADCFE, 0x10325476
    for offset in range(0, len(padded), 64):
        x = struct.unpack("<16I", padded[offset:offset + 64])
        aa, bb, cc, dd = a, b, c, d
        for i in range(0, 16, 4):
            a = rol((a + ((b & c) | (~b & d)) + x[i]) & 0xFFFFFFFF, 3)
            d = rol((d + ((a & b) | (~a & c)) + x[i + 1]) & 0xFFFFFFFF, 7)
            c = rol((c + ((d & a) | (~d & b)) + x[i + 2]) & 0xFFFFFFFF, 11)
            b = rol((b + ((c & d) | (~c & a)) + x[i + 3]) & 0xFFFFFFFF, 19)
        for i in range(4):
            a = rol((a + ((b & c) | (b & d) | (c & d)) + x[i] + 0x5A827999) & 0xFFFFFFFF, 3)
            d = rol((d + ((a & b) | (a & c) | (b & c)) + x[i + 4] + 0x5A827999) & 0xFFFFFFFF, 5)
            c = rol((c + ((d & a) | (d & b) | (a & b)) + x[i + 8] + 0x5A827999) & 0xFFFFFFFF, 9)
            b = rol((b + ((c & d) | (c & a) | (d & a)) + x[i + 12] + 0x5A827999) & 0xFFFFFFFF, 13)
        for i in (0, 2, 1, 3):
            a = rol((a + (b ^ c ^ d) + x[i] + 0x6ED9EBA1) & 0xFFFFFFFF, 3)
            d = rol((d + (a ^ b ^ c) + x[i + 8] + 0x6ED9EBA1) & 0xFFFFFFFF, 9)
            c = rol((c + (d ^ a ^ b) + x[i + 4] + 0x6ED9EBA1) & 0xFFFFFFFF, 11)
            b = rol((b + (c ^ d ^ a) + x[i + 12] + 0x6ED9EBA1) & 0xFFFFFFFF, 15)
        a, b, c, d = (a + aa) & 0xFFFFFFFF, (b + bb) & 0xFFFFFFFF, (c + cc) & 0xFFFFFFFF, (d + dd) & 0xFFFFFFFF
    return struct.pack("<4I", a, b, c, d)


assert md4(b"abc").hex() == "a448017aaf21d8525fc10ae87aa6729d", "MD4 mal implementado"

SIGNATURE = b"NTLMSSP\x00"
FLAG_UNICODE = 0x00000001
FLAG_NTLM = 0x00000200
FLAG_TARGET_TYPE_DOMAIN = 0x00010000
# Sin este indicador algunos clientes, como curl, responden con NTLMv1 en vez de NTLMv2.
FLAG_EXTENDED_SESSION_SECURITY = 0x00080000
FLAG_TARGET_INFO = 0x00800000
CHALLENGE_FLAGS = FLAG_UNICODE | FLAG_NTLM | FLAG_TARGET_TYPE_DOMAIN | FLAG_EXTENDED_SESSION_SECURITY | FLAG_TARGET_INFO


def av_pair(av_id: int, value: bytes) -> bytes:
    return struct.pack("<HH", av_id, len(value)) + value


def build_type2(server_challenge: bytes, domain: str) -> bytes:
    name = domain.encode("utf-16-le")
    # FILETIME actual: los servidores reales lo envían y obligan al cliente a devolverlo.
    filetime = int((time.time() + 11644473600) * 10_000_000)
    target_info = av_pair(2, name) + av_pair(7, struct.pack("<Q", filetime)) + av_pair(0, b"")
    header = SIGNATURE + struct.pack("<I", 2)
    header += struct.pack("<HHI", len(name), len(name), 48)
    header += struct.pack("<I", CHALLENGE_FLAGS)
    header += server_challenge + b"\x00" * 8
    header += struct.pack("<HHI", len(target_info), len(target_info), 48 + len(name))
    return header + name + target_info


def verify_type3(message: bytes, server_challenge: bytes, user: str, password: str):
    """Devuelve (correcto, descripción) recalculando la respuesta NTLMv2."""
    if len(message) < 64 or message[:8] != SIGNATURE or struct.unpack_from("<I", message, 8)[0] != 3:
        return False, "no es un mensaje Type 3"

    def field(offset):
        length, _, start = struct.unpack_from("<HHI", message, offset)
        return message[start:start + length]

    nt_response = field(20)
    sent_domain = field(28).decode("utf-16-le")
    sent_user = field(36).decode("utf-16-le")
    if sent_user.lower() != user.lower():
        return False, f"usuario '{sent_user}' desconocido"
    if len(nt_response) < 16 + 32:
        return False, "respuesta NT demasiado corta (¿NTLMv1?)"

    proof, blob = nt_response[:16], nt_response[16:]
    key = hmac.new(md4(password.encode("utf-16-le")), (sent_user.upper() + sent_domain).encode("utf-16-le"), hashlib.md5).digest()
    expected = hmac.new(key, server_challenge + blob, hashlib.md5).digest()
    ok = hmac.compare_digest(proof, expected)
    return ok, f"usuario '{sent_domain}\\{sent_user}', prueba {'correcta' if ok else 'incorrecta'}"


class Handler(threading.Thread):
    def __init__(self, client: socket.socket, options):
        super().__init__(daemon=True)
        self.client = client
        self.options = options
        self.challenge = None

    def log(self, text):
        print(f"[{self.name}] {text}", flush=True)

    def read_request(self):
        data = b""
        while b"\r\n\r\n" not in data:
            chunk = self.client.recv(4096)
            if not chunk:
                return None
            data += chunk
        lines = data.split(b"\r\n\r\n", 1)[0].decode("latin-1").split("\r\n")
        headers = {}
        for line in lines[1:]:
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.strip().lower()] = value.strip()
        return lines[0], headers

    def respond(self, status, headers=(), body=b""):
        head = f"HTTP/1.1 {status}\r\n" + "".join(f"{h}\r\n" for h in headers)
        if not status.startswith("200"):
            head += f"Content-Length: {len(body)}\r\n"
        self.client.sendall(head.encode() + b"\r\n" + body)

    def challenge_response(self, authenticate):
        body = b"<html><body>Proxy Authentication Required</body></html>"
        self.respond("407 Proxy Authentication Required",
                     [f"Proxy-Authenticate: {authenticate}", "Proxy-Connection: keep-alive"], body)

    def authorized(self, authorization):
        """Devuelve True, False (rechazado) o None (se envió un desafío intermedio)."""
        mode = self.options.auth
        if mode == "none":
            return True
        if mode == "basic":
            if authorization and authorization.lower().startswith("basic "):
                try:
                    given = base64.b64decode(authorization[6:]).decode()
                except Exception:
                    return False
                return given == f"{self.options.user}:{self.options.password}"
            return False
        # NTLM
        if authorization and authorization.upper().startswith("NTLM "):
            message = base64.b64decode(authorization[5:])
            message_type = struct.unpack_from("<I", message, 8)[0] if len(message) >= 12 else 0
            if message_type == 1:
                self.challenge = os.urandom(8)
                type2 = build_type2(self.challenge, self.options.domain)
                self.challenge_response("NTLM " + base64.b64encode(type2).decode())
                self.log("Type 1 recibido, desafío enviado")
                return None
            if message_type == 3 and self.challenge:
                ok, detail = verify_type3(message, self.challenge, self.options.user, self.options.password)
                self.log(f"Type 3: {detail}")
                self.challenge = None
                return ok
        return False

    def run(self):
        try:
            while True:
                request = self.read_request()
                if request is None:
                    return
                line, headers = request
                parts = line.split()
                if len(parts) < 2 or parts[0].upper() != "CONNECT":
                    self.respond("405 Method Not Allowed", body=b"Solo CONNECT")
                    return
                result = self.authorized(headers.get("proxy-authorization"))
                if result is None:
                    continue
                if not result:
                    scheme = {"basic": 'Basic realm="ProxyUCI pruebas"', "ntlm": "NTLM"}[self.options.auth]
                    self.log(f"{line} -> 407")
                    self.challenge_response(scheme)
                    continue
                self.tunnel(parts[1], line)
                return
        except (ConnectionError, OSError) as error:
            self.log(f"conexión terminada: {error}")
        finally:
            self.client.close()

    def tunnel(self, authority, line):
        upstream = None
        if self.options.upstream:
            host, _, port = authority.rpartition(":")
            try:
                upstream = socket.create_connection((host, int(port)), timeout=10)
            except OSError as error:
                self.log(f"{line} -> 502 ({error})")
                self.respond("502 Bad Gateway", body=str(error).encode())
                return
        self.log(f"{line} -> 200")
        self.respond("200 Connection established")
        if upstream is None:
            # Sin salida real: se consume lo que llegue hasta que el cliente cierre.
            while self.client.recv(4096):
                pass
            return
        with upstream:
            # Cada sentido se cierra por separado: si un extremo deja de enviar, el otro aún puede responder.
            open_sources = [self.client, upstream]
            while open_sources:
                readable, _, _ = select.select(open_sources, [], [], 60)
                if not readable:
                    return
                for source in readable:
                    target = upstream if source is self.client else self.client
                    data = source.recv(65536)
                    if data:
                        target.sendall(data)
                    else:
                        open_sources.remove(source)
                        try:
                            target.shutdown(socket.SHUT_WR)
                        except OSError:
                            pass


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1", help="interfaz de escucha (por defecto solo este equipo)")
    parser.add_argument("--port", type=int, default=3128)
    parser.add_argument("--auth", choices=["none", "basic", "ntlm"], default="ntlm")
    parser.add_argument("--user", default="andrey")
    parser.add_argument("--password", default="secreta7")
    parser.add_argument("--domain", default="UCI")
    parser.add_argument("--upstream", action="store_true", help="conectar de verdad con el destino en vez de simularlo")
    options = parser.parse_args()

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((options.host, options.port))
    server.listen()
    print(f"Proxy de pruebas en {options.host}:{options.port}, autenticación {options.auth}", flush=True)
    try:
        while True:
            client, _ = server.accept()
            Handler(client, options).start()
    except KeyboardInterrupt:
        pass
    finally:
        server.close()


if __name__ == "__main__":
    main()
