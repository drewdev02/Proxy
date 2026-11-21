#!/usr/bin/env python3
"""Servicio de cuotas de la UCI, de mentira, y cliente para preguntar al de verdad.

La UCI da a cada cuenta una cuota de navegación (MB al mes). La app UCIProxy 5.0 (2019) la consulta con un servicio
SOAP 1.1 de la intranet, sin pasar por el proxy:

    POST https://cuotas.uci.cu/servicios/v1/InetCuotasWS.php
    SOAPAction: "https://cuotas.uci.cu/servicios/v1/InetCuotasWS"
    método ObtenerCuota (namespace https://cuotas.uci.cu/) con usuario, clave y dominio=uci.cu

y lee la respuesta POR POSICIÓN: cuota asignada (entero), cuota usada (decimal) y nivel de navegación (texto). Los
nombres de esos campos no aparecen en la app; los de aquí son supuestos. Por eso el cliente de ProxyUCI debe leerlos por
posición, como UCIProxy, y este simulador puede cambiarlos (--field-names) para comprobar que no dependen de ellos.

Servidor de mentira, para las pruebas y el CI:
    python3 tools/test_cuotas.py serve --user andrey --password secreta7 --quota 2000 --used 1420.5
    python3 tools/test_cuotas.py serve --tls            # HTTPS con un certificado que se genera al vuelo

Cliente, para probar en la red de la UCI (pide la contraseña sin que quede en el historial):
    python3 tools/test_cuotas.py ask --user andrey                    # el servicio real
    python3 tools/test_cuotas.py ask --user andrey --insecure         # si el certificado es de una CA interna
    python3 tools/test_cuotas.py wsdl                                 # la descripción del servicio, para ver los campos
    python3 tools/test_cuotas.py ask --url http://127.0.0.1:8443/servicios/v1/InetCuotasWS.php --user andrey

Solo para desarrollo: el servidor no debe exponerse fuera del equipo.
"""

import argparse
import getpass
import http.server
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

SERVICE_PATH = "/servicios/v1/InetCuotasWS.php"
REAL_URL = "https://cuotas.uci.cu" + SERVICE_PATH
SOAP_ACTION = "https://cuotas.uci.cu/servicios/v1/InetCuotasWS"
NAMESPACE = "https://cuotas.uci.cu/"
METHOD = "ObtenerCuota"
SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"

# Supuestos: la app original no los usa. Se pueden cambiar con --field-names para probar que el cliente lee por posición.
DEFAULT_FIELDS = ("cuota", "cuota_usada", "nivel_navegacion")


# ---------------------------------------------------------------------------------------------------------------------
# Mensajes SOAP


def build_request(user: str, password: str, domain: str = "uci.cu") -> bytes:
    """La petición tal como la arma ksoap2 en UCIProxy (SOAP 1.1, .NET): el método con sus tres parámetros."""
    return (
        '<?xml version="1.0" encoding="utf-8"?>'
        f'<v:Envelope xmlns:v="{SOAP_ENV}" xmlns:i="http://www.w3.org/2001/XMLSchema-instance">'
        "<v:Header/>"
        "<v:Body>"
        f'<{METHOD} xmlns="{NAMESPACE}">'
        f"<usuario>{escape(user)}</usuario>"
        f"<clave>{escape(password)}</clave>"
        f"<dominio>{escape(domain)}</dominio>"
        f"</{METHOD}>"
        "</v:Body>"
        "</v:Envelope>"
    ).encode("utf-8")


def build_response(quota: int, used: float, level: str, fields=DEFAULT_FIELDS) -> bytes:
    values = (str(quota), repr(float(used)), level)
    inner = "".join(f"<{name}>{escape(value)}</{name}>" for name, value in zip(fields, values))
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<SOAP-ENV:Envelope xmlns:SOAP-ENV="{SOAP_ENV}" xmlns:ns1="{NAMESPACE}">'
        "<SOAP-ENV:Body>"
        f"<ns1:{METHOD}Response><{METHOD}Result>{inner}</{METHOD}Result></ns1:{METHOD}Response>"
        "</SOAP-ENV:Body>"
        "</SOAP-ENV:Envelope>"
    ).encode("utf-8")


def build_fault(code: str, message: str) -> bytes:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        f'<SOAP-ENV:Envelope xmlns:SOAP-ENV="{SOAP_ENV}">'
        "<SOAP-ENV:Body><SOAP-ENV:Fault>"
        f"<faultcode>{escape(code)}</faultcode><faultstring>{escape(message)}</faultstring>"
        "</SOAP-ENV:Fault></SOAP-ENV:Body>"
        "</SOAP-ENV:Envelope>"
    ).encode("utf-8")


def local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_request(body: bytes):
    """Los parámetros de una petición ObtenerCuota, por nombre (sin namespace), o None si no lo es."""
    try:
        root = ET.fromstring(body)
    except ET.ParseError:
        return None
    soap_body = next((e for e in root if local(e.tag) == "Body"), None)
    call = next(iter(soap_body), None) if soap_body is not None else None
    if call is None or local(call.tag) != METHOD:
        return None
    return {local(child.tag): (child.text or "") for child in call}


class QuotaFault(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


def parse_response(body: bytes):
    """(cuota, usada, nivel) leídos por posición, como UCIProxy. Lanza QuotaFault si el servicio devolvió un fallo."""
    root = ET.fromstring(body)
    soap_body = next(e for e in root if local(e.tag) == "Body")
    first = next(iter(soap_body))
    if local(first.tag) == "Fault":
        code = next((c.text or "" for c in first if local(c.tag) == "faultcode"), "")
        message = next((c.text or "" for c in first if local(c.tag) == "faultstring"), "")
        raise QuotaFault(code, message)
    result = next(iter(first))  # <ObtenerCuotaResult>
    values = [child.text or "" for child in result]
    if len(values) < 3:
        raise ValueError(f"respuesta con {len(values)} campos, se esperaban 3: {[local(c.tag) for c in result]}")
    return int(float(values[0])), float(values[1]), values[2]


# ---------------------------------------------------------------------------------------------------------------------
# Servidor de mentira


class QuotaState:
    """Las cuentas que el servidor conoce y su consumo, que puede crecer con cada consulta (--grow)."""

    def __init__(self, user, password, quota, used, level, grow, fields):
        self.accounts = {user: password}
        self.quota = quota
        self.used = used
        self.level = level
        self.grow = grow
        self.fields = fields
        self.requests = 0
        self.lock = threading.Lock()


WSDL = f"""<?xml version="1.0" encoding="UTF-8"?>
<!-- WSDL de mentira: el real hay que pedirlo en la red de la UCI (python3 tools/test_cuotas.py wsdl). -->
<definitions xmlns="http://schemas.xmlsoap.org/wsdl/" xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"
    xmlns:tns="{NAMESPACE}" xmlns:xsd="http://www.w3.org/2001/XMLSchema" targetNamespace="{NAMESPACE}" name="InetCuotasWS">
  <message name="{METHOD}Request">
    <part name="usuario" type="xsd:string"/><part name="clave" type="xsd:string"/><part name="dominio" type="xsd:string"/>
  </message>
  <message name="{METHOD}Response"><part name="{METHOD}Result" type="tns:Cuota"/></message>
  <portType name="InetCuotasWSPort">
    <operation name="{METHOD}"><input message="tns:{METHOD}Request"/><output message="tns:{METHOD}Response"/></operation>
  </portType>
  <binding name="InetCuotasWSBinding" type="tns:InetCuotasWSPort">
    <soap:binding style="rpc" transport="http://schemas.xmlsoap.org/soap/http"/>
    <operation name="{METHOD}"><soap:operation soapAction="{SOAP_ACTION}"/></operation>
  </binding>
  <service name="InetCuotasWS">
    <port name="InetCuotasWSPort" binding="tns:InetCuotasWSBinding"><soap:address location="{REAL_URL}"/></port>
  </service>
</definitions>
"""


def make_handler(state: QuotaState):
    class Handler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            print(f"[cuotas] {self.address_string()} {fmt % args}", flush=True)

        def send(self, status: int, body: bytes, content_type="text/xml; charset=utf-8"):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path == SERVICE_PATH and query.lower() == "wsdl":
                self.send(200, WSDL.encode("utf-8"))
            else:
                self.send(404, b"no encontrado\n", "text/plain; charset=utf-8")

        def do_POST(self):
            if self.path.partition("?")[0] != SERVICE_PATH:
                self.send(404, b"no encontrado\n", "text/plain; charset=utf-8")
                return
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            action = (self.headers.get("SOAPAction") or "").strip('"')
            params = parse_request(body)
            if params is None or action != SOAP_ACTION:
                self.send(500, build_fault("SOAP-ENV:Client", f"Petición no válida (SOAPAction «{action}»)"))
                return
            user, password, domain = params.get("usuario", ""), params.get("clave", ""), params.get("dominio", "")
            with state.lock:
                state.requests += 1
                if domain != "uci.cu" or state.accounts.get(user) != password:
                    print(f"[cuotas] {user}@{domain}: credenciales incorrectas", flush=True)
                    self.send(500, build_fault("SOAP-ENV:Server", "Usuario o contraseña incorrectos"))
                    return
                used = state.used
                state.used = min(state.quota, state.used + state.grow)
            print(f"[cuotas] {user}: {used:.2f} de {state.quota} MB ({state.level})", flush=True)
            self.send(200, build_response(state.quota, used, state.level, state.fields))

    return Handler


def self_signed_context(directory: str) -> ssl.SSLContext:
    """Un certificado de un día para «cuotas.uci.cu» y 127.0.0.1, hecho con openssl."""
    openssl = shutil.which("openssl")
    if openssl is None:
        sys.exit("--tls necesita openssl en el PATH")
    cert, key = os.path.join(directory, "cert.pem"), os.path.join(directory, "key.pem")
    subprocess.run(
        [openssl, "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1", "-subj", "/CN=cuotas.uci.cu",
         "-addext", "subjectAltName=DNS:cuotas.uci.cu,DNS:localhost,IP:127.0.0.1", "-keyout", key, "-out", cert],
        check=True, capture_output=True,
    )
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(cert, key)
    return context


def start_server(state: QuotaState, host="127.0.0.1", port=0, tls=False):
    """Arranca el servidor en otro hilo. Devuelve (servidor, url del servicio)."""
    server = http.server.ThreadingHTTPServer((host, port), make_handler(state))
    scheme = "http"
    if tls:
        server.socket = self_signed_context(tempfile.mkdtemp(prefix="cuotas-")).wrap_socket(server.socket, server_side=True)
        scheme = "https"
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, f"{scheme}://{host}:{server.server_address[1]}{SERVICE_PATH}"


# ---------------------------------------------------------------------------------------------------------------------
# Cliente


def ssl_context(insecure: bool):
    if not insecure:
        return ssl.create_default_context()
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    return context


def no_proxy_opener(insecure: bool):
    # Como UCIProxy: el servicio es de la intranet y se pregunta sin proxy, aunque el equipo tenga uno configurado.
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl_context(insecure)))


def ask(url: str, user: str, password: str, insecure=False, timeout=15.0):
    """Pregunta la cuota. Devuelve (cuota, usada, nivel, respuesta en bruto)."""
    request = urllib.request.Request(
        url,
        data=build_request(user, password),
        headers={"Content-Type": "text/xml; charset=utf-8", "SOAPAction": f'"{SOAP_ACTION}"'},
        method="POST",
    )
    try:
        with no_proxy_opener(insecure).open(request, timeout=timeout) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        # SOAP devuelve los fallos con 500 y el detalle en el cuerpo.
        with error:
            raw = error.read()
    return (*parse_response(raw), raw)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    serve = commands.add_parser("serve", help="servicio de cuotas de mentira")
    serve.add_argument("--host", default="127.0.0.1")
    serve.add_argument("--port", type=int, default=8443)
    serve.add_argument("--tls", action="store_true", help="HTTPS con un certificado propio generado al vuelo")
    serve.add_argument("--user", default="andrey")
    serve.add_argument("--password", default="secreta7")
    serve.add_argument("--quota", type=int, default=2000, help="cuota asignada, en MB")
    serve.add_argument("--used", type=float, default=1420.53, help="cuota usada al empezar, en MB")
    serve.add_argument("--level", default="Profesores", help="nivel de navegación")
    serve.add_argument("--grow", type=float, default=0.0, help="MB que se suman al consumo en cada consulta")
    serve.add_argument("--field-names", default=",".join(DEFAULT_FIELDS),
                       help="nombres de los tres campos de la respuesta, separados por comas (el cliente no debe depender de ellos)")

    client = commands.add_parser("ask", help="pregunta la cuota a un servicio (por defecto, el real)")
    client.add_argument("--url", default=REAL_URL)
    client.add_argument("--user", required=True)
    client.add_argument("--password", help="si no se da, se pide sin mostrarla")
    client.add_argument("--insecure", action="store_true", help="no verificar el certificado (CA interna)")
    client.add_argument("--raw", action="store_true", help="mostrar también la respuesta XML tal cual")

    wsdl = commands.add_parser("wsdl", help="descarga la descripción del servicio (por defecto, el real)")
    wsdl.add_argument("--url", default=REAL_URL)
    wsdl.add_argument("--insecure", action="store_true")

    args = parser.parse_args()

    if args.command == "serve":
        fields = tuple(name.strip() for name in args.field_names.split(","))
        if len(fields) != 3:
            sys.exit("--field-names necesita exactamente tres nombres")
        state = QuotaState(args.user, args.password, args.quota, args.used, args.level, args.grow, fields)
        server, url = start_server(state, args.host, args.port, args.tls)
        print(f"Servicio de cuotas de pruebas en {url} (usuario {args.user}, {args.used} de {args.quota} MB)", flush=True)
        try:
            threading.Event().wait()
        except KeyboardInterrupt:
            server.shutdown()
    elif args.command == "ask":
        password = args.password if args.password is not None else getpass.getpass(f"Contraseña de {args.user}: ")
        try:
            quota, used, level, raw = ask(args.url, args.user, password, args.insecure)
        except QuotaFault as fault:
            sys.exit(f"El servicio respondió con un fallo: {fault}")
        except ssl.SSLCertVerificationError as error:
            sys.exit(f"Certificado no verificado ({error.verify_message}). Si es de la CA de la UCI, repite con --insecure.")
        except (urllib.error.URLError, OSError) as error:
            sys.exit(f"No se pudo llegar al servicio: {error}")
        if args.raw:
            print(raw.decode("utf-8", "replace"))
        percent = 100 * used / quota if quota else 0
        print(f"Cuota: {quota} MB · usada: {used:.2f} MB ({percent:.0f} %) · nivel: {level}")
    else:
        url = args.url if "?" in args.url else args.url + "?wsdl"
        with no_proxy_opener(args.insecure).open(url, timeout=15) as response:
            print(response.read().decode("utf-8", "replace"))


if __name__ == "__main__":
    main()
