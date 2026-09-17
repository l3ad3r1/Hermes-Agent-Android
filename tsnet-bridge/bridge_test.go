package tsbridge

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestParseInterfaces(t *testing.T) {
	ifaces, err := parseInterfaces(`[
	  {"name":"wlan0","index":5,"mtu":1500,"up":true,"multicast":true,"addrs":["192.168.8.194/24","fe80::1/64","nonsense"]},
	  {"name":"lo","index":1,"mtu":65536,"up":true,"loopback":true,"addrs":["127.0.0.1/8"]}
	]`)
	if err != nil {
		t.Fatalf("parseInterfaces: %v", err)
	}
	if len(ifaces) != 2 {
		t.Fatalf("got %d interfaces, want 2", len(ifaces))
	}
	wlan := ifaces[0]
	if wlan.Name != "wlan0" || wlan.Index != 5 || wlan.MTU != 1500 {
		t.Errorf("wlan0 fields wrong: %+v", wlan.Interface)
	}
	if wlan.Flags&net.FlagUp == 0 || wlan.Flags&net.FlagMulticast == 0 {
		t.Errorf("wlan0 flags wrong: %v", wlan.Flags)
	}
	if len(wlan.AltAddrs) != 2 { // the unparseable address is skipped, not fatal
		t.Errorf("got %d addrs, want 2: %v", len(wlan.AltAddrs), wlan.AltAddrs)
	}
	if !ifaces[1].IsLoopback() {
		t.Errorf("lo should be loopback")
	}
	if _, err := parseInterfaces("   "); err == nil {
		t.Errorf("empty input should be an error")
	}
}

func TestCheckTailnetHost(t *testing.T) {
	for _, ok := range []string{"iamlegion.tail7dc9bd.ts.net", "IAMLEGION.TAIL7DC9BD.TS.NET.", "100.117.117.69"} {
		if err := checkTailnetHost(ok); err != nil {
			t.Errorf("%s should be allowed: %v", ok, err)
		}
	}
	for _, bad := range []string{"example.com", "192.168.8.183", "127.0.0.1", "evil-ts.net"} {
		if err := checkTailnetHost(bad); err == nil {
			t.Errorf("%s should be refused", bad)
		}
	}
}

func TestProxyForwardsToTailnetDial(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Upstream-Path", r.URL.Path)
		w.Write([]byte(`{"status":"ok"}`))
	}))
	defer upstream.Close()

	var dialedAddr string
	h := &proxyHandler{token: "s3cret", dial: func(ctx context.Context, network, addr string) (net.Conn, error) {
		dialedAddr = addr // what tsnet would resolve inside the tailnet
		return net.Dial(network, upstream.Listener.Addr().String())
	}}
	req := httptest.NewRequest(http.MethodGet, "http://iamlegion.tail7dc9bd.ts.net:8642/health", nil)
	req.Header.Set("Proxy-Authorization", "Bearer s3cret")
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("got %d: %s", rec.Code, rec.Body.String())
	}
	if dialedAddr != "iamlegion.tail7dc9bd.ts.net:8642" {
		t.Errorf("dialed %q", dialedAddr)
	}
	if rec.Body.String() != `{"status":"ok"}` {
		t.Errorf("body %q", rec.Body.String())
	}
	if rec.Header().Get("X-Upstream-Path") != "/health" {
		t.Errorf("upstream saw path %q", rec.Header().Get("X-Upstream-Path"))
	}
}

func TestProxyRejectsWrongToken(t *testing.T) {
	h := &proxyHandler{token: "s3cret"}
	req := httptest.NewRequest(http.MethodGet, "http://iamlegion.tail7dc9bd.ts.net:8642/health", nil)
	req.Header.Set("Proxy-Authorization", "Bearer wrong")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusProxyAuthRequired {
		t.Fatalf("got %d, want 407", rec.Code)
	}
}

func TestProxyRejectsNonTailnetHost(t *testing.T) {
	h := &proxyHandler{token: "s3cret"}
	req := httptest.NewRequest(http.MethodGet, "http://example.com/", nil)
	req.Header.Set("Proxy-Authorization", "Bearer s3cret")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("got %d, want 403", rec.Code)
	}
}
