# 0003. «Otro País» con un cliente OpenVPN propio

Estado: aceptada
Fecha: decidida durante la capa 9 (Otro País); ver el [ROADMAP](../../ROADMAP.md)

## Contexto

Un perfil puede salir por otro país encadenando una VPN detrás del proxy institucional. Había que elegir cómo hablar
OpenVPN en cuatro plataformas, tres de ellas con túnel.

## Decisión

La app es también el cliente OpenVPN, en Kotlin común: `OvpnConfig` lee el `.ovpn` y `OpenVpnClient` hace el handshake y
el canal de datos. La criptografía y el TLS los aporta cada plataforma por los puertos `OpenVpnCrypto` y
`TlsSessionFactory` (Android y escritorio comparten la implementación Java con el alias `jvmAndAndroid`; iOS enlaza una
versión «no disponible»). El túnel solo conoce un `CountryEngine`; más tarde Tailscale entró como un segundo motor
detrás de la misma interfaz (capa 12).

## Descartado

- **Lanzar un `openvpn` instalado aparte:** exige que la persona lo instale, privilegios extra y no existe en Android.

## Consecuencias

- Sin programas externos y con el mismo comportamiento en Android, macOS y Windows.
- El cliente cubre un subconjunto: TCP, `tls-crypt`, usuario y contraseña, AES-GCM. Lo demás se rechaza al cargar el archivo
  con un motivo concreto (`OvpnConfigError`), no al conectar.
- Todo el tráfico de la VPN pasa por el proxy (`ProxyDialer`), con el mismo NTLM y los mismos nombres `sslip.io` que el
  resto.
