// Package main provides a CGo-exported AmneziaWG userspace tunnel
// with an embedded SOCKS5 proxy server.
//
// This allows Android apps to tunnel traffic through AmneziaWG
// WITHOUT using Android's VpnService (no system VPN lock icon,
// no conflicts with other VPN apps).
//
// Architecture:
//   Java/Kotlin App
//     → SOCKS5 (127.0.0.1:PORT)
//     → awg userspace netstack (gVisor/lwIP)
//     → encrypted UDP → AmneziaWG server
//     → Telegram DCs
package main

/*
#include <stdlib.h>
#include <string.h>
*/
import "C"

import (
	"context"
	"fmt"
	"log"
	"net"
	"net/netip"
	"os"
	"runtime"
	"strconv"
	"sync"
	"unsafe"

	"golang.org/x/net/proxy"

	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	awgconn "github.com/amnezia-vpn/amneziawg-go/conn"
)

// TunnelHandle represents an active AWG tunnel with embedded SOCKS5
type TunnelHandle struct {
	device      *device.Device
	tnet        *netstack.Net
	socksPort   int
	cancelSocks context.CancelFunc
	stats       *TunnelStats
}

// TunnelStats holds transfer statistics
type TunnelStats struct {
	mu      sync.RWMutex
	rxBytes int64
	txBytes int64
}

var (
	mu      sync.Mutex
	handles = make(map[int]*TunnelHandle)
	nextID  = 1
)

//export AWGCreate
// AWGCreate creates a new AmneziaWG tunnel with embedded SOCKS5 proxy.
// config: WireGuard/AmneziaWG config in wg-quick format (INI-style)
// Returns: tunnel handle (>=1) on success, negative error code on failure
func AWGCreate(config *C.char) C.int {
	goConfig := C.GoString(config)

	cfg, err := parseConfig(goConfig)
	if err != nil {
		log.Printf("AWGCreate: parse error: %v", err)
		return -1
	}

	// Build the list of local addresses for the virtual interface
	localAddrs := make([]netip.Addr, 0, len(cfg.addresses))
	for _, addr := range cfg.addresses {
		localAddrs = append(localAddrs, addr.Addr())
	}

	// Create userspace TUN + netstack (no VpnService needed!)
	tunDev, tnet, err := netstack.CreateNetTUN(
		localAddrs,
		cfg.dns,
		cfg.mtu,
	)
	if err != nil {
		log.Printf("AWGCreate: CreateNetTUN error: %v", err)
		return -2
	}

	// Create WireGuard/AmneziaWG device
	logger := device.NewLogger(device.LogLevelError, "[AWG] ")
	dev := device.NewDevice(tunDev, awgconn.NewDefaultBind(), logger)

	// Configure the device using wg UAPI format
	uapi := cfg.toUAPI()
	if err := dev.IpcSet(uapi); err != nil {
		log.Printf("AWGCreate: IpcSet error: %v", err)
		dev.Close()
		return -3
	}

	// Bring the device up
	if err := dev.Up(); err != nil {
		log.Printf("AWGCreate: Up() error: %v", err)
		dev.Close()
		return -4
	}

	// Start embedded SOCKS5 proxy over the netstack
	ctx, cancel := context.WithCancel(context.Background())
	socksPort, err := startSocks5Server(ctx, tnet)
	if err != nil {
		log.Printf("AWGCreate: SOCKS5 start error: %v", err)
		cancel()
		dev.Close()
		return -5
	}

	handle := &TunnelHandle{
		device:      dev,
		tnet:        tnet,
		socksPort:   socksPort,
		cancelSocks: cancel,
		stats:       &TunnelStats{},
	}

	mu.Lock()
	id := nextID
	nextID++
	handles[id] = handle
	mu.Unlock()

	log.Printf("AWGCreate: tunnel %d created, SOCKS5 on port %d", id, socksPort)
	return C.int(id)
}

//export AWGGetSocksPort
// AWGGetSocksPort returns the local SOCKS5 port for the given tunnel handle.
// The caller should configure Telegram's built-in proxy to use 127.0.0.1:port.
func AWGGetSocksPort(handle C.int) C.int {
	mu.Lock()
	h, ok := handles[int(handle)]
	mu.Unlock()
	if !ok {
		return -1
	}
	return C.int(h.socksPort)
}

//export AWGGetStats
// AWGGetStats returns a JSON string with tunnel statistics.
// Caller must free the returned string with AWGFreeString.
func AWGGetStats(handle C.int) *C.char {
	mu.Lock()
	h, ok := handles[int(handle)]
	mu.Unlock()
	if !ok {
		return C.CString("{}")
	}

	h.stats.mu.RLock()
	rx := h.stats.rxBytes
	tx := h.stats.txBytes
	h.stats.mu.RUnlock()

	// Also get stats from WireGuard device IPC
	ipcStats, _ := h.device.IpcGet()
	_ = ipcStats

	json := fmt.Sprintf(`{"rx":%d,"tx":%d,"port":%d}`, rx, tx, h.socksPort)
	return C.CString(json)
}

//export AWGDestroy
// AWGDestroy tears down the tunnel and frees all resources.
func AWGDestroy(handle C.int) {
	mu.Lock()
	h, ok := handles[int(handle)]
	if ok {
		delete(handles, int(handle))
	}
	mu.Unlock()

	if !ok {
		return
	}

	h.cancelSocks()
	h.device.Close()
	log.Printf("AWGDestroy: tunnel %d destroyed", handle)
}

//export AWGFreeString
// AWGFreeString frees a C string returned by AWG functions.
func AWGFreeString(s *C.char) {
	C.free(unsafe.Pointer(s))
}

//export AWGVersion
// AWGVersion returns the version string of the AWG library.
func AWGVersion() *C.char {
	return C.CString("amneziawg-telegram/1.0.0 go/" + runtime.Version())
}

// startSocks5Server starts a SOCKS5 proxy that routes through the AWG netstack.
// It binds to 127.0.0.1 on a random port and returns that port number.
func startSocks5Server(ctx context.Context, tnet *netstack.Net) (int, error) {
	// Bind SOCKS5 listener on localhost random port
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("listen: %w", err)
	}

	port := listener.Addr().(*net.TCPAddr).Port

	// Create a dialer that routes through the AWG netstack
	// (not through the host network stack)
	awgDialer := &awgSocksDialer{tnet: tnet}

	// Create SOCKS5 server
	socks5Server, err := proxy.SOCKS5("tcp", "", nil, awgDialer)
	_ = socks5Server
	if err != nil {
		listener.Close()
		return 0, fmt.Errorf("socks5: %w", err)
	}

	go func() {
		defer listener.Close()
		for {
			select {
			case <-ctx.Done():
				return
			default:
			}

			conn, err := listener.Accept()
			if err != nil {
				select {
				case <-ctx.Done():
					return
				default:
					log.Printf("SOCKS5 accept error: %v", err)
					continue
				}
			}
			go handleSocks5Connection(ctx, conn, tnet)
		}
	}()

	return port, nil
}

// awgSocksDialer implements proxy.Dialer using the AWG netstack
type awgSocksDialer struct {
	tnet *netstack.Net
}

func (d *awgSocksDialer) Dial(network, addr string) (net.Conn, error) {
	return d.tnet.Dial(network, addr)
}

// handleSocks5Connection handles a single SOCKS5 client connection
func handleSocks5Connection(ctx context.Context, clientConn net.Conn, tnet *netstack.Net) {
	defer clientConn.Close()

	// Parse SOCKS5 header manually
	buf := make([]byte, 1024)

	// Read version and nmethods
	if _, err := clientConn.Read(buf[:2]); err != nil {
		return
	}
	if buf[0] != 0x05 {
		return // Not SOCKS5
	}

	nMethods := int(buf[1])
	if _, err := clientConn.Read(buf[:nMethods]); err != nil {
		return
	}

	// Send: no authentication required
	clientConn.Write([]byte{0x05, 0x00})

	// Read the request
	if _, err := clientConn.Read(buf[:4]); err != nil {
		return
	}

	if buf[0] != 0x05 || buf[1] != 0x01 {
		return // Only CONNECT supported
	}

	var targetAddr string
	switch buf[3] {
	case 0x01: // IPv4
		addr := make([]byte, 4)
		clientConn.Read(addr)
		port := make([]byte, 2)
		clientConn.Read(port)
		targetAddr = fmt.Sprintf("%d.%d.%d.%d:%d",
			addr[0], addr[1], addr[2], addr[3],
			int(port[0])<<8|int(port[1]))
	case 0x03: // Domain
		lenBuf := make([]byte, 1)
		clientConn.Read(lenBuf)
		domain := make([]byte, lenBuf[0])
		clientConn.Read(domain)
		port := make([]byte, 2)
		clientConn.Read(port)
		targetAddr = fmt.Sprintf("%s:%d", string(domain),
			int(port[0])<<8|int(port[1]))
	case 0x04: // IPv6
		addr := make([]byte, 16)
		clientConn.Read(addr)
		port := make([]byte, 2)
		clientConn.Read(port)
		targetAddr = fmt.Sprintf("[%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x:%02x%02x]:%d",
			addr[0], addr[1], addr[2], addr[3],
			addr[4], addr[5], addr[6], addr[7],
			addr[8], addr[9], addr[10], addr[11],
			addr[12], addr[13], addr[14], addr[15],
			int(port[0])<<8|int(port[1]))
	default:
		return
	}

	// Connect through AWG netstack
	targetConn, err := tnet.Dial("tcp", targetAddr)
	if err != nil {
		// Reply: connection refused
		clientConn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
		return
	}
	defer targetConn.Close()

	// Reply: success
	clientConn.Write([]byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0})

	// Bidirectional relay
	done := make(chan struct{}, 2)
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := clientConn.Read(buf)
			if n > 0 {
				targetConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := targetConn.Read(buf)
			if n > 0 {
				clientConn.Write(buf[:n])
			}
			if err != nil {
				break
			}
		}
		done <- struct{}{}
	}()

	select {
	case <-ctx.Done():
	case <-done:
	}
}

// parseConfig parses a wg-quick style config file
func parseConfig(raw string) (*awgConfig, error) {
	// Delegate to the config parser in config.go
	return parseWGConfig(raw)
}

func main() {
	// Not used when compiled as shared library
	// For testing only:
	if len(os.Args) > 1 && os.Args[1] == "test" {
		fmt.Println("AWG Telegram Library v1.0.0")
		fmt.Println("Go version:", runtime.Version())
		_ = strconv.Itoa(0)
	}
}
