# Spike: an embedded Tailscale node (tsnet)

**Question.** Can Hermes reach the PC gateway over Tailscale *without* the Tailscale app, and
without taking Android's single VPN slot?

**Status.** Built and running on the host; device result recorded at the bottom.

## What was built

| Piece | Where |
| --- | --- |
| Go module: tsnet node + loopback HTTP proxy | `tsnet-bridge/bridge.go` |
| Go tests (interface parsing, proxy auth, host allowlist, forwarding) | `tsnet-bridge/bridge_test.go` |
| Build script → `app/libs/tsbridge.aar` | `tsnet-bridge/build.sh` |
| Kotlin wrapper, interface provider, OkHttp wiring | `app/src/main/kotlin/com/hermes/agent/data/remote/TailnetNode.kt` |
| Start/stop/sign-in card | Settings → Connections → **Tailnet (experimental)** |

## How it works

The node runs in userspace inside the app process. Nothing is routed system-wide, so there is
no `VpnService`, no VPN permission, and no conflict with another VPN app.

Traffic reaches the tailnet through a loopback HTTP proxy that `tsnet-bridge` starts on a random
port; `TailnetNode.wrap()` hands `GatewayApiClient` an OkHttp client pointed at it. Because every
app on the phone shares loopback, the proxy:

- requires a per-run random token (`Proxy-Authorization: Bearer …`), attached preemptively rather
  than after a 407, and
- forwards only to tailnet destinations (`*.ts.net` or `100.64.0.0/10`), so it cannot be used as a
  general-purpose relay by another app.

Responses are flushed as they stream, so the gateway's SSE run events are not buffered.

**The Android-specific part:** an app may not read the routing table over netlink, so Go cannot
enumerate interfaces itself (`netlinkrib: permission denied`). `AndroidInterfaces` supplies the
list from `java.net.NetworkInterface` as JSON, which the Go side registers through
`netmon.RegisterInterfaceGetter` — the same arrangement Tailscale's own Android app uses.

Node logs stay on the device: `TS_NO_LOGS_NO_SUPPORT=true` stops tsnet uploading them.

## Costs measured

- **AAR:** 14.6 MB (the `libgojni.so` inside is 41.5 MB uncompressed, arm64 only).
- **Build:** `gomobile bind` takes about 2 minutes. It needs Go ≥ 1.26.6, gomobile/gobind, an NDK,
  and `javac` on `PATH` — gomobile shells out to `javac` and otherwise fails **with exit code 0**,
  leaving a 0-byte AAR behind.

## Known gaps (this is a spike, not a feature)

1. **The AAR is not in git** (`app/libs/*.aar` is ignored) and CI has no Go step, so CI cannot build
   this branch. Merging means either a Gradle task that runs `gomobile bind`, or publishing the AAR.
2. **No lifecycle.** The node runs only while something keeps the process alive; there is no
   foreground service, no start-on-boot, and no reconnect policy.
3. **Node keys sit in `filesDir/tailnet`** in the clear, unlike the API key, which the Keystore
   encrypts.
4. **Start/stop is manual** and not persisted; the app does not restart the node by itself.
5. **Only absolute-URI HTTP is proxied.** `CONNECT` (HTTPS through the tailnet) is refused, which is
   fine for `http://…ts.net:8642` but would need adding for a TLS gateway.
6. **Sign-in is a browser round trip** to the URL the node reports. Node key expiry (180 days by
   default) is not handled; turn expiry off for the node in the Tailscale admin console.

## Device result

Recorded after installing on the S24 Ultra (SM-S928B) with the Tailscale app **not** installed.

Sequence observed in logcat (tag `GoLog`, prefix `tsbridge:`) with the Tailscale app **not**
installed and no VPN permission granted:

1. `start: 4 interfaces from the app (err=<nil>)` — the Kotlin interface list is accepted, with
   `wlan0` and the cellular interface and their real addresses. This was the main unknown.
2. WireGuard device up, `magicsock` up, state written to `files/tailnet/tailscaled.state`.
3. Reached `controlplane.tailscale.com`, so DNS and TLS work from Go on Android with no
   `/etc/resolv.conf`.
4. `RegisterReq: got response … authURL=true`, and the card showed **Sign in to Tailscale**.

So the node runs on the device and gets as far as the login. **Signing in, and therefore the
first request through the node, is not done yet** — it needs a Tailscale account login by hand.

Two harmless warnings: `failed to force-set UDP read/write buffer size … operation not permitted`
(Android denies `SO_*BUF` bumps; throughput only) and the fake tun/router/DNS configurators, which
are expected in userspace mode.

### Fixes this needed

- `panic: no safe place found to store log state` — Android has no `HOME`, no XDG dirs and no
  writable `/tmp`, so tailscale's log-directory search panicked and **took the whole app down**.
  Fixed by pointing `HOME`, `XDG_CACHE_HOME` and `TMPDIR` at the app's own storage, plus a
  `recover()` in `Start` so a future panic returns an error instead of killing the process.
- `tsnet.Server.Start()` alone never begins a login, so the node sat in `NeedsLogin` with no URL.
  `Up()` now runs in the background to start the interactive login.
- `go mod tidy` kept dropping `golang.org/x/mobile`, after which `gomobile bind` refuses to run;
  `tools.go` pins it.
