#!/bin/bash
# AWG Key Management Server — deployment script
# Run this on your AmneziaWG server (VPS with Ubuntu/Debian)
#
# Usage:
#   curl -sSL https://raw.githubusercontent.com/YOUR/awg-telegram-fork/main/awg-key-server/deploy.sh | bash

set -euo pipefail

SERVER_DIR="/opt/awg-key-server"
SERVICE_NAME="awg-key-server"
CONFIG_DIR="/etc/awg-key-server"

echo "=== AWG Key Server Deployment ==="

# Install Go if not present
if ! command -v go &>/dev/null; then
    echo "Installing Go..."
    wget -q https://go.dev/dl/go1.21.0.linux-amd64.tar.gz
    sudo tar -C /usr/local -xzf go1.21.0.linux-amd64.tar.gz
    export PATH=$PATH:/usr/local/go/bin
    echo "export PATH=\$PATH:/usr/local/go/bin" >> ~/.bashrc
fi
echo "Go: $(go version)"

# Create directories
sudo mkdir -p "$SERVER_DIR" "$CONFIG_DIR" "$CONFIG_DIR/configs"

# Build the server
echo "Building awg-key-server..."
cd /tmp
cat > /tmp/server.go << 'GOEOF'
# (content from awg-key-server/cmd/server.go)
GOEOF

# Assuming source is available:
if [ -d "/tmp/awg-key-server" ]; then
    cd /tmp/awg-key-server
else
    echo "Clone or copy the awg-key-server directory to /tmp/awg-key-server first"
    exit 1
fi

CGO_ENABLED=0 go build -ldflags="-s -w" -o "$SERVER_DIR/awg-key-server" ./cmd/server.go

echo "Built: $SERVER_DIR/awg-key-server"

# Generate TLS certificate (self-signed for testing, use Let's Encrypt in production)
if [ ! -f "$CONFIG_DIR/cert.pem" ]; then
    echo "Generating TLS certificate..."
    openssl req -x509 -newkey rsa:4096 -keyout "$CONFIG_DIR/key.pem" \
        -out "$CONFIG_DIR/cert.pem" -days 365 -nodes \
        -subj "/CN=$(curl -s ifconfig.me)"
    sudo chmod 600 "$CONFIG_DIR/key.pem"
fi

# Generate master secret
MASTER_SECRET=$(openssl rand -base64 32)

# Create config
if [ ! -f "$CONFIG_DIR/config.json" ]; then
    cat > "$CONFIG_DIR/config.json" << EOF
{
  "listen_addr": ":8443",
  "tls_cert": "$CONFIG_DIR/cert.pem",
  "tls_key": "$CONFIG_DIR/key.pem",
  "master_secret": "$MASTER_SECRET",
  "wg_configs_dir": "$CONFIG_DIR/configs",
  "max_key_age_days": 30
}
EOF
    echo "Config created: $CONFIG_DIR/config.json"
    echo "SAVE THIS MASTER SECRET: $MASTER_SECRET"
fi

# Create systemd service
sudo tee /etc/systemd/system/$SERVICE_NAME.service > /dev/null << EOF
[Unit]
Description=AWG Key Management Server
After=network.target

[Service]
Type=simple
User=nobody
ExecStart=$SERVER_DIR/awg-key-server $CONFIG_DIR/config.json
Restart=always
RestartSec=5
WorkingDirectory=$CONFIG_DIR

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable $SERVICE_NAME
sudo systemctl restart $SERVICE_NAME

echo ""
echo "=== Deployment Complete ==="
echo "Server running on port 8443"
echo "Status: $(sudo systemctl is-active $SERVICE_NAME)"
echo ""
echo "=== Add a client ==="
echo ""
cat << 'CLIENTEOF'
# To add a new client, edit /etc/awg-key-server/clients.json:
# [
#   {
#     "client_id": "UUID from app",
#     "public_key": "WG public key of client",
#     "shared_secret": "base64 AES-256 key (SAME as in the app)",
#     "issued_at": "2026-01-01T00:00:00Z",
#     "expires_at": "2027-01-01T00:00:00Z",
#     "is_revoked": false,
#     "config_file": "/etc/awg-key-server/configs/client1.conf"
#   }
# ]
CLIENTEOF

echo ""
echo "To generate a shared secret for a client:"
echo "  openssl rand -base64 32"
echo ""
echo "Test health check:"
echo "  curl -k https://localhost:8443/v1/health"
