// Librería nativa (libtsbridge) que la app carga con System.load: expone el puente de Tailscale como métodos nativos
// de org.uci.proxy.core.vpn.tailscale.TsBridgeNative. La misma librería sirve para Android y para la JVM de escritorio:
// solo usa JNI, sin gomobile ni JNA.
//
// Todo lo que no son paquetes viaja como JSON, para que la superficie nativa sea mínima.
package main

/*
#include <jni.h>
#include <stdlib.h>

static const char* tsGetString(JNIEnv* env, jstring s) { return (*env)->GetStringUTFChars(env, s, NULL); }
static void tsReleaseString(JNIEnv* env, jstring s, const char* c) { (*env)->ReleaseStringUTFChars(env, s, c); }
static jstring tsNewString(JNIEnv* env, const char* c) { return (*env)->NewStringUTF(env, c); }
static jsize tsArrayLength(JNIEnv* env, jbyteArray a) { return (*env)->GetArrayLength(env, a); }
static void tsGetBytes(JNIEnv* env, jbyteArray a, jsize len, void* buf) { (*env)->GetByteArrayRegion(env, a, 0, len, (jbyte*)buf); }
static void tsSetBytes(JNIEnv* env, jbyteArray a, jsize len, const void* buf) { (*env)->SetByteArrayRegion(env, a, 0, len, (const jbyte*)buf); }
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"
	"unsafe"

	"github.com/drewdev02/proxyuci/tsbridge/bridge"
)

// Version la ve la app para saber con qué librería habla.
const Version = "1"

var (
	mu       sync.Mutex
	sessions = map[int64]*bridge.Session{}
	nextID   int64
	logs     = make(chan string, 2000)
)

func init() {
	// Lo antes posible: después ya no se puede fijar la función de proxy de Tailscale.
	if err := bridge.Setup(); err != nil {
		logf("No se pudo preparar Tailscale: %v", err)
	}
}

func logf(format string, a ...any) {
	select {
	case logs <- fmt.Sprintf(format, a...):
	default: // nadie lee los registros: se descartan
	}
}

func goString(env *C.JNIEnv, s C.jstring) string {
	if s == 0 {
		return ""
	}
	c := C.tsGetString(env, s)
	defer C.tsReleaseString(env, s, c)
	return C.GoString(c)
}

func jString(env *C.JNIEnv, s string) C.jstring {
	c := C.CString(s)
	defer C.free(unsafe.Pointer(c))
	return C.tsNewString(env, c)
}

func jsonOf(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

type errorBody struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func errorJSON(err error) string {
	var e *bridge.Error
	if errors.As(err, &e) {
		return jsonOf(map[string]any{"error": errorBody{e.Code, e.Message}})
	}
	return jsonOf(map[string]any{"error": errorBody{bridge.CodeInternal, err.Error()}})
}

func session(h int64) *bridge.Session {
	mu.Lock()
	defer mu.Unlock()
	return sessions[h]
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_version
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_version(env *C.JNIEnv, cls C.jclass) C.jstring {
	return jString(env, Version)
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_start
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_start(env *C.JNIEnv, cls C.jclass, config C.jstring) C.jstring {
	var cfg bridge.Config
	if err := json.Unmarshal([]byte(goString(env, config)), &cfg); err != nil {
		return jString(env, errorJSON(&bridge.Error{Code: bridge.CodeConfig, Message: err.Error()}))
	}
	s, err := bridge.Start(context.Background(), cfg, logf)
	if err != nil {
		return jString(env, errorJSON(err))
	}
	mu.Lock()
	nextID++
	h := nextID
	sessions[h] = s
	mu.Unlock()
	return jString(env, jsonOf(map[string]any{"handle": h, "network": s.Network()}))
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_write
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_write(env *C.JNIEnv, cls C.jclass, h C.jlong, packet C.jbyteArray, length C.jint) C.jint {
	s := session(int64(h))
	if s == nil {
		return -1
	}
	n := int(length)
	if n <= 0 || n > int(C.tsArrayLength(env, packet)) {
		return 0
	}
	buf := make([]byte, n)
	C.tsGetBytes(env, packet, C.jsize(n), unsafe.Pointer(&buf[0]))
	if err := s.WritePacket(buf); err != nil {
		return -1
	}
	return 0
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_read
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_read(env *C.JNIEnv, cls C.jclass, h C.jlong, buffer C.jbyteArray, timeoutMs C.jint) C.jint {
	s := session(int64(h))
	if s == nil {
		return -1
	}
	p, err := s.ReadPacket(time.Duration(timeoutMs) * time.Millisecond)
	if err == io.EOF {
		return -1
	}
	if len(p) == 0 {
		return 0
	}
	capacity := int(C.tsArrayLength(env, buffer))
	if len(p) > capacity {
		return 0 // no cabe: se descarta, como un paquete mayor que la MTU
	}
	C.tsSetBytes(env, buffer, C.jsize(len(p)), unsafe.Pointer(&p[0]))
	return C.jint(len(p))
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_exitNodes
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_exitNodes(env *C.JNIEnv, cls C.jclass, h C.jlong) C.jstring {
	s := session(int64(h))
	if s == nil {
		return jString(env, errorJSON(&bridge.Error{Code: bridge.CodeInternal, Message: "la sesión no existe"}))
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	nodes, err := s.ExitNodes(ctx)
	if err != nil {
		return jString(env, errorJSON(err))
	}
	if nodes == nil {
		nodes = []bridge.ExitNode{}
	}
	return jString(env, jsonOf(map[string]any{"exitNodes": nodes}))
}

//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_close
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_close(env *C.JNIEnv, cls C.jclass, h C.jlong) {
	mu.Lock()
	s := sessions[int64(h)]
	delete(sessions, int64(h))
	mu.Unlock()
	if s != nil {
		s.Close()
	}
}

// nextLog espera una línea de registro; devuelve null si no llega ninguna a tiempo.
//
//export Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_nextLog
func Java_org_uci_proxy_core_vpn_tailscale_TsBridgeNative_nextLog(env *C.JNIEnv, cls C.jclass, timeoutMs C.jint) C.jstring {
	select {
	case l := <-logs:
		return jString(env, l)
	case <-time.After(time.Duration(timeoutMs) * time.Millisecond):
		return 0
	}
}

func main() {}
