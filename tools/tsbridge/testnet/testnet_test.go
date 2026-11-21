package testnet

import (
	"net"
	"testing"

	"golang.org/x/net/dns/dnsmessage"
)

func query(t *testing.T, name string, qtype dnsmessage.Type) []byte {
	t.Helper()
	b := dnsmessage.NewBuilder(nil, dnsmessage.Header{ID: 0x1234, RecursionDesired: true})
	if err := b.StartQuestions(); err != nil {
		t.Fatal(err)
	}
	if err := b.Question(dnsmessage.Question{Name: dnsmessage.MustNewName(name), Type: qtype, Class: dnsmessage.ClassINET}); err != nil {
		t.Fatal(err)
	}
	out, err := b.Finish()
	if err != nil {
		t.Fatal(err)
	}
	return out
}

func parse(t *testing.T, reply []byte) (dnsmessage.Header, []net.IP) {
	t.Helper()
	var p dnsmessage.Parser
	h, err := p.Start(reply)
	if err != nil {
		t.Fatal(err)
	}
	if err := p.SkipAllQuestions(); err != nil {
		t.Fatal(err)
	}
	var ips []net.IP
	for {
		rh, err := p.AnswerHeader()
		if err == dnsmessage.ErrSectionDone {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
		if rh.Type != dnsmessage.TypeA {
			t.Fatalf("respuesta inesperada de tipo %v", rh.Type)
		}
		a, err := p.AResource()
		if err != nil {
			t.Fatal(err)
		}
		ips = append(ips, net.IP(a.A[:]))
	}
	return h, ips
}

// El nodo de salida resuelve los nombres como el DNS de un nodo de salida real: con la IP que ve este equipo.
func TestAnswerResolvesNames(t *testing.T) {
	reply, err := (&Net{}).answer(query(t, "localhost.", dnsmessage.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	h, ips := parse(t, reply)
	if h.ID != 0x1234 || !h.Response || h.RCode != dnsmessage.RCodeSuccess {
		t.Fatalf("cabecera inesperada: %+v", h)
	}
	if len(ips) == 0 || !ips[0].Equal(net.IPv4(127, 0, 0, 1)) {
		t.Fatalf("se esperaba 127.0.0.1, llegó %v", ips)
	}
}

// Un nombre que no existe se contesta enseguida («no existe»), para que el cliente no espere hasta agotar el plazo.
func TestAnswerUnknownNameIsNameError(t *testing.T) {
	reply, err := (&Net{}).answer(query(t, "nada.invalid.", dnsmessage.TypeA))
	if err != nil {
		t.Fatal(err)
	}
	h, ips := parse(t, reply)
	if h.RCode != dnsmessage.RCodeNameError || len(ips) != 0 {
		t.Fatalf("se esperaba «no existe» sin respuestas, llegó %v con %v", h.RCode, ips)
	}
}

// Otros tipos (AAAA, HTTPS…) se contestan vacíos: la app usa IPv4.
func TestAnswerOtherTypesIsEmpty(t *testing.T) {
	reply, err := (&Net{}).answer(query(t, "localhost.", dnsmessage.TypeAAAA))
	if err != nil {
		t.Fatal(err)
	}
	h, ips := parse(t, reply)
	if h.RCode != dnsmessage.RCodeSuccess || len(ips) != 0 {
		t.Fatalf("se esperaba una respuesta vacía, llegó %v con %v", h.RCode, ips)
	}
}
