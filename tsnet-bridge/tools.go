//go:build tools

// Keeps golang.org/x/mobile in go.mod: `gomobile bind` refuses to run without it, and
// `go mod tidy` would otherwise drop it since no ordinary file imports it.
package tsbridge

import _ "golang.org/x/mobile/bind"
