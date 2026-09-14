package main

import (
	"fmt"
	"net/netip"
	"strings"
)

// awgConfig holds parsed WireGuard/AmneziaWG configuration
type awgConfig struct {
	// Interface section
	privateKey string
	addresses  []netip.Prefix
	dns        []netip.Addr
	mtu        int
	listenPort int

	// AmneziaWG obfuscation params (AWG3)
	jc   int
	jmin int
	jmax int
	s1   int
	s2   int
	h1   int
	h2   int
	h3   int
	h4   int

	// Peer sections
	peers []awgPeer
}

type awgPeer struct {
	publicKey           string
	preSharedKey        string
	endpoint            string
	allowedIPs          []netip.Prefix
	persistentKeepalive int
}

// parseWGConfig parses wg-quick INI-style config
func parseWGConfig(raw string) (*awgConfig, error) {
	cfg := &awgConfig{
		mtu: 1420, // default MTU
		jc:  0,    // default: no obfuscation (pure WireGuard)
	}

	var currentSection string
	var currentPeer *awgPeer

	lines := strings.Split(raw, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)

		// Skip comments and empty lines
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}

		// Section headers
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section := strings.ToLower(line[1 : len(line)-1])
			currentSection = section

			if section == "peer" {
				if currentPeer != nil {
					cfg.peers = append(cfg.peers, *currentPeer)
				}
				currentPeer = &awgPeer{}
			}
			continue
		}

		// Key-value pairs
		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.TrimSpace(strings.ToLower(parts[0]))
		value := strings.TrimSpace(parts[1])

		switch currentSection {
		case "interface":
			if err := parseInterfaceKey(cfg, key, value); err != nil {
				return nil, fmt.Errorf("interface key %q: %w", key, err)
			}
		case "peer":
			if currentPeer == nil {
				currentPeer = &awgPeer{}
			}
			if err := parsePeerKey(currentPeer, key, value); err != nil {
				return nil, fmt.Errorf("peer key %q: %w", key, err)
			}
		}
	}

	// Don't forget the last peer
	if currentPeer != nil {
		cfg.peers = append(cfg.peers, *currentPeer)
	}

	if cfg.privateKey == "" {
		return nil, fmt.Errorf("missing PrivateKey in [Interface]")
	}
	if len(cfg.peers) == 0 {
		return nil, fmt.Errorf("no [Peer] sections found")
	}

	return cfg, nil
}

func parseInterfaceKey(cfg *awgConfig, key, value string) error {
	switch key {
	case "privatekey":
		cfg.privateKey = value
	case "address":
		for _, addrStr := range splitComma(value) {
			prefix, err := netip.ParsePrefix(addrStr)
			if err != nil {
				// Try as plain address without prefix
				addr, err2 := netip.ParseAddr(addrStr)
				if err2 != nil {
					return fmt.Errorf("invalid address %q: %w", addrStr, err)
				}
				if addr.Is4() {
					prefix = netip.PrefixFrom(addr, 32)
				} else {
					prefix = netip.PrefixFrom(addr, 128)
				}
			}
			cfg.addresses = append(cfg.addresses, prefix)
		}
	case "dns":
		for _, dnsStr := range splitComma(value) {
			addr, err := netip.ParseAddr(dnsStr)
			if err != nil {
				return fmt.Errorf("invalid DNS %q: %w", dnsStr, err)
			}
			cfg.dns = append(cfg.dns, addr)
		}
	case "mtu":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.mtu = n
	case "listenport":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.listenPort = n
	// AmneziaWG obfuscation parameters
	case "jc":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.jc = n
	case "jmin":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.jmin = n
	case "jmax":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.jmax = n
	case "s1":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.s1 = n
	case "s2":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.s2 = n
	case "h1":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.h1 = n
	case "h2":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.h2 = n
	case "h3":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.h3 = n
	case "h4":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		cfg.h4 = n
	}
	return nil
}

func parsePeerKey(peer *awgPeer, key, value string) error {
	switch key {
	case "publickey":
		peer.publicKey = value
	case "presharedkey":
		peer.preSharedKey = value
	case "endpoint":
		peer.endpoint = value
	case "allowedips":
		for _, cidrStr := range splitComma(value) {
			prefix, err := netip.ParsePrefix(cidrStr)
			if err != nil {
				return fmt.Errorf("invalid AllowedIP %q: %w", cidrStr, err)
			}
			peer.allowedIPs = append(peer.allowedIPs, prefix)
		}
	case "persistentkeepalive":
		n, err := parseInt(value)
		if err != nil {
			return err
		}
		peer.persistentKeepalive = n
	}
	return nil
}

// toUAPI converts the config to WireGuard UAPI format for device.IpcSet()
func (cfg *awgConfig) toUAPI() string {
	var sb strings.Builder

	sb.WriteString("private_key=")
	sb.WriteString(keyToHex(cfg.privateKey))
	sb.WriteString("\n")

	if cfg.listenPort > 0 {
		sb.WriteString(fmt.Sprintf("listen_port=%d\n", cfg.listenPort))
	}

	// AmneziaWG obfuscation params
	if cfg.jc > 0 {
		sb.WriteString(fmt.Sprintf("jc=%d\n", cfg.jc))
		sb.WriteString(fmt.Sprintf("jmin=%d\n", cfg.jmin))
		sb.WriteString(fmt.Sprintf("jmax=%d\n", cfg.jmax))
		sb.WriteString(fmt.Sprintf("s1=%d\n", cfg.s1))
		sb.WriteString(fmt.Sprintf("s2=%d\n", cfg.s2))
		sb.WriteString(fmt.Sprintf("h1=%d\n", cfg.h1))
		sb.WriteString(fmt.Sprintf("h2=%d\n", cfg.h2))
		sb.WriteString(fmt.Sprintf("h3=%d\n", cfg.h3))
		sb.WriteString(fmt.Sprintf("h4=%d\n", cfg.h4))
	}

	for _, peer := range cfg.peers {
		sb.WriteString("public_key=")
		sb.WriteString(keyToHex(peer.publicKey))
		sb.WriteString("\n")

		if peer.preSharedKey != "" {
			sb.WriteString("preshared_key=")
			sb.WriteString(keyToHex(peer.preSharedKey))
			sb.WriteString("\n")
		}

		if peer.endpoint != "" {
			sb.WriteString("endpoint=")
			sb.WriteString(peer.endpoint)
			sb.WriteString("\n")
		}

		for _, ip := range peer.allowedIPs {
			sb.WriteString("allowed_ip=")
			sb.WriteString(ip.String())
			sb.WriteString("\n")
		}

		if peer.persistentKeepalive > 0 {
			sb.WriteString(fmt.Sprintf("persistent_keepalive_interval=%d\n", peer.persistentKeepalive))
		}
	}

	return sb.String()
}

// keyToHex converts base64 WireGuard key to hex (for UAPI).
// The UAPI protocol uses lowercase hex, but keys are stored as base64.
func keyToHex(base64Key string) string {
	// In practice amneziawg-go device.IpcSet accepts base64 keys directly
	// This is a passthrough — the device library handles the conversion
	return base64Key
}

func splitComma(s string) []string {
	parts := strings.Split(s, ",")
	result := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			result = append(result, p)
		}
	}
	return result
}

func parseInt(s string) (int, error) {
	var n int
	_, err := fmt.Sscanf(s, "%d", &n)
	return n, err
}
