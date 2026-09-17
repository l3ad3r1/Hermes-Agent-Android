// Package tsbridge embeds a userspace Tailscale node (tsnet) in the Hermes Android app so
// the PC gateway is reachable without the Tailscale app and without Android's VPN slot.
//
// Android apps may not read the routing table over netlink, so the interface list is
// supplied by Kotlin through [InterfaceProvider] — the same arrangement Tailscale's own
// Android app uses (netmon.RegisterInterfaceGetter).
//
// Tailnet traffic reaches OkHttp through a loopback HTTP proxy: tsnet is userspace, so
// nothing else on the phone is rerouted. Loopback is shared by every app on the device,
// so the proxy demands a per-run token and only forwards to tailnet hosts.
package tsbridge

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/netip"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

// InterfaceProvider is implemented in Kotlin. InterfacesJSON returns a JSON array of
// objects: {"name","index","mtu","up","loopback","pointToPoint","multicast","addrs":["ip/bits"]}.
type InterfaceProvider interface {
	InterfacesJSON() (string, error)
}

var (
	mu     sync.Mutex
	srv    *tsnet.Server
	proxy  *http.Server
	addr   string
	token  string
	logBuf = newRing(200)
)

// Start brings up the node, storing its keys in stateDir, and starts the loopback proxy.
// It returns as soon as the backend is started: the node may still need a login, which
// [Status] reports as an auth URL. Calling Start on a running node is a no-op.
func Start(stateDir, hostname string, ifaces InterfaceProvider) (err error) {
	mu.Lock()
	defer mu.Unlock()
	if srv != nil {
		return nil
	}
	// tailscale panics rather than returning an error on some startup paths; a spike must
	// not abort the whole app process.
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("tailnet node panicked during start: %v", r)
		}
	}()

	// Keep the node's logs on the device; tsnet otherwise uploads them to Tailscale.
	os.Setenv("TS_NO_LOGS_NO_SUPPORT", "true")

	// Android has no HOME, no XDG dirs and no writable /tmp, so tailscale's log-state
	// directory search ends in panic("no safe place found to store log state"). Point every
	// candidate it checks at this app's own storage.
	for env, sub := range map[string]string{"HOME": "", "XDG_CACHE_HOME": "cache", "TMPDIR": "tmp"} {
		dir := stateDir
		if sub != "" {
			dir = filepath.Join(stateDir, sub)
		}
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
		os.Setenv(env, dir)
	}

	netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
		raw, err := ifaces.InterfacesJSON()
		if err != nil {
			return nil, err
		}
		return parseInterfaces(raw)
	})

	if err := os.MkdirAll(stateDir, 0o700); err != nil {
		return err
	}
	s := &tsnet.Server{
		Dir:       stateDir,
		Hostname:  hostname,
		Logf:      logBuf.logf,
		UserLogf:  logBuf.logf,
		Ephemeral: false,
	}
	logBuf.logf("start: dir=%s hostname=%s", stateDir, hostname)
	if raw, ierr := ifaces.InterfacesJSON(); ierr != nil {
		logBuf.logf("start: interface provider failed: %v", ierr)
	} else {
		parsedIfaces, perr := parseInterfaces(raw)
		logBuf.logf("start: %d interfaces from the app (err=%v)", len(parsedIfaces), perr)
	}
	if err := s.Start(); err != nil {
		logBuf.logf("start: tsnet failed: %v", err)
		return err
	}
	logBuf.logf("start: tsnet backend started")

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		s.Close()
		return err
	}
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		ln.Close()
		s.Close()
		return err
	}
	srv, token, addr = s, base64.RawURLEncoding.EncodeToString(buf), ln.Addr().String()
	proxy = &http.Server{
		Handler:           &proxyHandler{dial: s.Dial, token: token},
		ReadHeaderTimeout: 10 * time.Second,
	}
	go proxy.Serve(ln)

	// Start() alone brings the backend up but never begins a login, so a fresh node would sit
	// in NeedsLogin with no auth URL. Up() kicks off the interactive login and blocks until the
	// node is running, so it goes in the background; the auth URL shows up in Status().
	go func() {
		if _, err := s.Up(context.Background()); err != nil {
			logBuf.logf("tailnet: up failed: %v", err)
		}
	}()
	return nil
}

// Stop shuts the proxy and the node down. The node's keys stay in stateDir, so a later
// Start comes back up without another login.
func Stop() error {
	mu.Lock()
	defer mu.Unlock()
	if srv == nil {
		return nil
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	proxy.Shutdown(ctx)
	err := srv.Close()
	srv, proxy, addr, token = nil, nil, "", ""
	return err
}

// ProxyAddr is the loopback address of the proxy ("127.0.0.1:port"), or "" when stopped.
func ProxyAddr() string {
	mu.Lock()
	defer mu.Unlock()
	return addr
}

// ProxyToken is this run's proxy credential, sent as "Proxy-Authorization: Bearer <token>".
func ProxyToken() string {
	mu.Lock()
	defer mu.Unlock()
	return token
}

// Status reports the node as JSON: {"running","state","authURL","ips":[],"hostname"}.
// state is a tailscale backend state such as NeedsLogin, Starting or Running.
func Status() string {
	mu.Lock()
	s := srv
	mu.Unlock()
	out := map[string]any{"running": s != nil, "state": "Stopped", "ips": []string{}}
	if s == nil {
		return encode(out)
	}
	lc, err := s.LocalClient()
	if err != nil {
		out["error"] = err.Error()
		return encode(out)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	st, err := lc.StatusWithoutPeers(ctx)
	if err != nil {
		out["error"] = err.Error()
		return encode(out)
	}
	out["state"] = st.BackendState
	out["authURL"] = st.AuthURL
	if st.Self != nil {
		out["hostname"] = st.Self.HostName
		ips := make([]string, 0, len(st.Self.TailscaleIPs))
		for _, ip := range st.Self.TailscaleIPs {
			ips = append(ips, ip.String())
		}
		out["ips"] = ips
	}
	return encode(out)
}

// Logs returns the node's recent log lines, for the spike's diagnostics.
func Logs() string { return logBuf.String() }

type proxyHandler struct {
	dial  func(ctx context.Context, network, addr string) (net.Conn, error)
	token string
}

func (h *proxyHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	got := strings.TrimPrefix(r.Header.Get("Proxy-Authorization"), "Bearer ")
	if subtle.ConstantTimeCompare([]byte(got), []byte(h.token)) != 1 {
		http.Error(w, "proxy auth required", http.StatusProxyAuthRequired)
		return
	}
	if r.Method == http.MethodConnect || !r.URL.IsAbs() {
		http.Error(w, "only absolute-URI HTTP requests are proxied", http.StatusBadRequest)
		return
	}
	if err := checkTailnetHost(r.URL.Hostname()); err != nil {
		http.Error(w, err.Error(), http.StatusForbidden)
		return
	}

	out := r.Clone(r.Context())
	out.RequestURI = ""
	out.Header.Del("Proxy-Authorization")
	out.Header.Del("Proxy-Connection")
	transport := &http.Transport{DialContext: h.dial, ResponseHeaderTimeout: 60 * time.Second}
	defer transport.CloseIdleConnections()
	resp, err := transport.RoundTrip(out)
	if err != nil {
		http.Error(w, "tailnet request failed: "+err.Error(), http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	for k, vs := range resp.Header {
		for _, v := range vs {
			w.Header().Add(k, v)
		}
	}
	w.WriteHeader(resp.StatusCode)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 16<<10)
	for {
		n, err := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush() // SSE run events must not sit in a buffer.
			}
		}
		if err != nil {
			return
		}
	}
}

// checkTailnetHost keeps the loopback proxy from becoming an open relay for other apps:
// only MagicDNS names and Tailscale's own 100.64.0.0/10 range are forwarded.
func checkTailnetHost(host string) error {
	if ip, err := netip.ParseAddr(host); err == nil {
		if netip.MustParsePrefix("100.64.0.0/10").Contains(ip) {
			return nil
		}
		return fmt.Errorf("refusing to proxy to non-tailnet address %s", host)
	}
	if strings.HasSuffix(strings.ToLower(strings.TrimSuffix(host, ".")), ".ts.net") {
		return nil
	}
	return fmt.Errorf("refusing to proxy to non-tailnet host %s", host)
}

type jsonInterface struct {
	Name         string   `json:"name"`
	Index        int      `json:"index"`
	MTU          int      `json:"mtu"`
	Up           bool     `json:"up"`
	Loopback     bool     `json:"loopback"`
	PointToPoint bool     `json:"pointToPoint"`
	Multicast    bool     `json:"multicast"`
	Addrs        []string `json:"addrs"`
}

func parseInterfaces(raw string) ([]netmon.Interface, error) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return nil, errors.New("no interfaces reported by the app")
	}
	var parsed []jsonInterface
	if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
		return nil, err
	}
	out := make([]netmon.Interface, 0, len(parsed))
	for _, p := range parsed {
		var flags net.Flags
		if p.Up {
			flags |= net.FlagUp | net.FlagRunning
		}
		if p.Loopback {
			flags |= net.FlagLoopback
		}
		if p.PointToPoint {
			flags |= net.FlagPointToPoint
		}
		if p.Multicast {
			flags |= net.FlagMulticast
		}
		iface := netmon.Interface{Interface: &net.Interface{
			Index: p.Index, MTU: p.MTU, Name: p.Name, Flags: flags,
		}}
		for _, a := range p.Addrs {
			pfx, err := netip.ParsePrefix(a)
			if err != nil {
				continue // An address we cannot parse is skipped, not fatal.
			}
			iface.AltAddrs = append(iface.AltAddrs, &net.IPNet{
				IP:   net.IP(pfx.Addr().AsSlice()),
				Mask: net.CIDRMask(pfx.Bits(), pfx.Addr().BitLen()),
			})
		}
		out = append(out, iface)
	}
	if len(out) == 0 {
		return nil, errors.New("no usable interfaces reported by the app")
	}
	return out, nil
}

func encode(v any) string {
	b, err := json.Marshal(v)
	if err != nil {
		return `{"running":false,"state":"Stopped","error":"encode failed"}`
	}
	return string(b)
}

type ring struct {
	mu    sync.Mutex
	lines []string
	max   int
}

func newRing(max int) *ring { return &ring{max: max} }

func (r *ring) logf(format string, args ...any) {
	line := time.Now().Format("15:04:05") + " " + fmt.Sprintf(format, args...)
	log.Println("tsbridge: " + line) // gomobile mirrors Go's log to logcat
	r.mu.Lock()
	defer r.mu.Unlock()
	r.lines = append(r.lines, line)
	if len(r.lines) > r.max {
		r.lines = r.lines[len(r.lines)-r.max:]
	}
}

func (r *ring) String() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return strings.Join(r.lines, "\n")
}
