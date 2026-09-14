#!/bin/bash
# add-client.sh — Add a new client to the AWG Key Server
#
# Usage:
#   ./add-client.sh <client_id> <wg_public_key> <config_file_path>
#
# The script will:
#   1. Generate a random shared secret (AES-256)
#   2. Add the client to clients.json
#   3. Print the shared secret for embedding in the Android app

set -euo pipefail

CONFIG_DIR="/etc/awg-key-server"
CLIENTS_FILE="$CONFIG_DIR/clients.json"

CLIENT_ID="${1:-$(uuidgen | tr '[:upper:]' '[:lower:]')}"
PUBLIC_KEY="${2:-}"
CONFIG_FILE="${3:-$CONFIG_DIR/configs/$CLIENT_ID.conf}"

if [ -z "$PUBLIC_KEY" ]; then
    echo "Usage: $0 <client_id> <wg_public_key> [config_file]"
    echo "Example: $0 my-client ABCD... /etc/wireguard/client.conf"
    exit 1
fi

# Generate shared secret
SHARED_SECRET=$(openssl rand -base64 32)

# Calculate expiry (30 days from now)
ISSUED_AT=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
EXPIRES_AT=$(date -u -d "+30 days" +"%Y-%m-%dT%H:%M:%SZ" 2>/dev/null || \
             date -u -v+30d +"%Y-%m-%dT%H:%M:%SZ")  # macOS fallback

# Create new client entry
NEW_CLIENT=$(cat << EOF
{
    "client_id": "$CLIENT_ID",
    "public_key": "$PUBLIC_KEY",
    "shared_secret": "$SHARED_SECRET",
    "issued_at": "$ISSUED_AT",
    "expires_at": "$EXPIRES_AT",
    "is_revoked": false,
    "config_file": "$CONFIG_FILE"
}
EOF
)

# Add to clients.json
if [ -f "$CLIENTS_FILE" ]; then
    # Append to existing array
    TMP=$(mktemp)
    python3 -c "
import json, sys
with open('$CLIENTS_FILE') as f:
    clients = json.load(f)
new_client = $NEW_CLIENT
clients.append(new_client)
print(json.dumps(clients, indent=2))
" > "$TMP"
    sudo cp "$TMP" "$CLIENTS_FILE"
else
    echo "[$NEW_CLIENT]" | sudo tee "$CLIENTS_FILE" > /dev/null
fi

echo ""
echo "=== Client Added Successfully ==="
echo ""
echo "Client ID:     $CLIENT_ID"
echo "Public Key:    $PUBLIC_KEY"
echo "Shared Secret: $SHARED_SECRET"
echo "Expires:       $EXPIRES_AT"
echo ""
echo "=== Configure in Android App ==="
echo ""
echo "In AWG Plugin Settings:"
echo "  URL сервера:    https://$(curl -s ifconfig.me):8443"
echo "  Client ID:      $CLIENT_ID"
echo "  Shared Secret:  $SHARED_SECRET"
echo ""
echo "=== Revoke a key ==="
echo "  python3 -c \"import json; ..."
echo "  or restart the server after editing clients.json"

# Restart server to reload clients
sudo systemctl restart awg-key-server 2>/dev/null || true
echo "Server reloaded."
