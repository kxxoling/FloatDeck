#!/bin/bash
# Generate release signing keystore for FloatDeck
# Usage: ./scripts/generate-keystore.sh [password]
# If no password provided, a random one will be generated.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
KEYSTORE_DIR="$PROJECT_DIR/keystore"
KEYSTORE_FILE="$KEYSTORE_DIR/floatdeck-release.jks"

PASSWORD="${1:-}"
if [ -z "$PASSWORD" ]; then
    PASSWORD=$(openssl rand -base64 24 | tr -d '=/+' | head -c 20)
    echo "Generated random password: $PASSWORD"
fi

mkdir -p "$KEYSTORE_DIR"

keytool -genkeypair \
    -v \
    -keystore "$KEYSTORE_FILE" \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -alias floatdeck \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=FloatDeck, OU=Dev, O=FloatDeck, C=CN"

echo ""
echo "✅ Keystore generated: $KEYSTORE_FILE"
echo ""
echo "GitHub Secrets to set:"
echo "  KEYSTORE_BASE64 → $(base64 -i "$KEYSTORE_FILE")"
echo "  KEYSTORE_PASSWORD → $PASSWORD"
echo "  KEY_ALIAS → floatdeck"
echo "  KEY_PASSWORD → $PASSWORD"
echo ""
echo "⚠️  Add keystore/ to .gitignore! Never commit the .jks file."

# Add keystore/ to .gitignore if not already there
if ! grep -q "^keystore/" "$PROJECT_DIR/.gitignore" 2>/dev/null; then
    echo "keystore/" >> "$PROJECT_DIR/.gitignore"
    echo "Added keystore/ to .gitignore"
fi
