// testnet levanta una red de Tailscale de pruebas (control, DERP y nodo de salida) y escribe en la salida estándar una
// línea JSON con lo necesario para conectarse: {"controlUrl", "authKey", "exitNode", "exitNodeIp"}. Sigue en marcha
// hasta que se cierra su entrada estándar (o muere quien la lanzó), así que no deja procesos huérfanos.
//
//	testnet [-listen 127.0.0.1] [-redirect 127.0.0.1:8000]
//
// Con -redirect, lo que llegue al nodo de salida para 203.0.113.0/24 se lleva a esa dirección: los nodos de salida no
// aceptan destinos privados, así que las pruebas apuntan a ese rango de documentación.
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"os"

	"github.com/drewdev02/proxyuci/tsbridge/testnet"
)

func main() {
	listen := flag.String("listen", "127.0.0.1", "dirección en la que escuchan el control y el DERP")
	redirect := flag.String("redirect", "", "a dónde lleva el nodo de salida las conexiones a 203.0.113.0/24")
	verbose := flag.Bool("v", false, "registro de Tailscale en la salida de error")
	flag.Parse()

	logf := func(string, ...any) {}
	if *verbose {
		logf = log.Printf
	}
	n, err := testnet.Start(context.Background(), *listen, *redirect, logf)
	if err != nil {
		fmt.Fprintln(os.Stderr, "testnet:", err)
		os.Exit(1)
	}
	b, _ := json.Marshal(n)
	fmt.Println(string(b))
	io.Copy(io.Discard, os.Stdin)
	n.Close()
}
