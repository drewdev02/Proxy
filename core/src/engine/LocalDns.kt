package org.uci.proxy.core.engine

import org.uci.proxy.core.dns.LocalDnsPolicy
import org.uci.proxy.core.dns.LocalDnsResolver

/** El DNS de la red local tal como lo usa el túnel: qué nombres le tocan y quién los resuelve. */
class LocalDns(val policy: LocalDnsPolicy, val resolver: LocalDnsResolver)
