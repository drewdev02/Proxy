#!/bin/bash
# Servidor OpenVPN de pruebas para el cliente de ProxyUCI: certificados propios, TCP, tls-crypt y AES-256-GCM.
# Genera en <directorio> la CA, el certificado del servidor, la clave tls-crypt y un perfil de cliente (client.ovpn),
# y arranca el servidor en primer plano. No crea interfaz de red (--dev null): solo sirve para el handshake y el
# canal de datos, no necesita permisos de administrador.
#
#   tools/ovpn_test_server.sh <directorio> [puerto] [verbosidad]
set -euo pipefail

DIR="${1:?Uso: $0 <directorio> [puerto] [verbosidad]}"
PORT="${2:-11943}"
VERB="${3:-4}"
OPENVPN="${OPENVPN:-$(command -v openvpn || echo /opt/homebrew/sbin/openvpn)}"
mkdir -p "$DIR"
cd "$DIR"

if [ ! -f ca.crt ]; then
  openssl req -x509 -newkey rsa:2048 -nodes -keyout ca.key -out ca.crt -days 30 -subj "/CN=ProxyUCI Test CA" 2>/dev/null
  openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=server" 2>/dev/null
  printf 'basicConstraints=CA:FALSE\nextendedKeyUsage=serverAuth\nkeyUsage=digitalSignature,keyEncipherment\n' > ext.cnf
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial -out server.crt -days 30 -extfile ext.cnf 2>/dev/null
  "$OPENVPN" --genkey tls-crypt tc.key
  cat > check.sh <<'S'
#!/bin/sh
# Acepta cualquier usuario cuya contraseña sea "secreta".
[ "$(sed -n 2p "$1")" = "secreta" ]
S
  chmod +x check.sh
  {
    echo "client"; echo "dev tun"; echo "proto tcp"; echo "remote 127.0.0.1 $PORT"
    echo "cipher AES-256-GCM"; echo "auth-user-pass"; echo "remote-cert-tls server"; echo "nobind"; echo "reneg-sec 0"
    echo "<ca>"; cat ca.crt; echo "</ca>"
    echo "<tls-crypt>"; cat tc.key; echo "</tls-crypt>"
  } > client.ovpn
fi

exec "$OPENVPN" --mode server --tls-server --proto tcp-server --port "$PORT" --dev null \
  --server 10.77.0.0 255.255.255.0 --topology subnet --ifconfig-noexec --route-noexec \
  --ca ca.crt --cert server.crt --key server.key --dh none --tls-crypt tc.key \
  --cipher AES-256-GCM --data-ciphers AES-256-GCM --verify-client-cert none --username-as-common-name \
  --auth-user-pass-verify ./check.sh via-file --script-security 2 \
  --push "dhcp-option DNS 10.77.0.1" --keepalive 10 60 --verb "$VERB"
