#!/bin/bash
# Generate self-signed certificate for development

set -e

# Configuration
CERT_DIR="src/main/resources/certs"
KEYSTORE_FILE="$CERT_DIR/keystore.p12"
KEYSTORE_PASS="changeit"
ALIAS="turnstile-mcp-dev"
VALIDITY_DAYS=365
KEY_SIZE=2048

# Distinguished Name
DNAME="CN=localhost, OU=Development, O=Turnstile, L=San Francisco, ST=CA, C=US"

echo "=========================================="
echo "Generating Development SSL Certificate"
echo "=========================================="
echo ""

# Create certificate directory
echo "Creating certificate directory: $CERT_DIR"
mkdir -p "$CERT_DIR"

# Check if keystore already exists
if [ -f "$KEYSTORE_FILE" ]; then
    read -p "Keystore already exists. Overwrite? (y/N) " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Aborted. Existing keystore preserved."
        exit 0
    fi
    echo "Removing existing keystore..."
    rm "$KEYSTORE_FILE"
fi

# Generate keystore with self-signed certificate
echo ""
echo "Generating PKCS12 keystore with self-signed certificate..."
echo "Parameters:"
echo "  - Alias: $ALIAS"
echo "  - Key size: $KEY_SIZE bits"
echo "  - Validity: $VALIDITY_DAYS days"
echo "  - DN: $DNAME"
echo ""

keytool -genkeypair \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize $KEY_SIZE \
  -validity $VALIDITY_DAYS \
  -keystore "$KEYSTORE_FILE" \
  -storetype PKCS12 \
  -storepass "$KEYSTORE_PASS" \
  -keypass "$KEYSTORE_PASS" \
  -dname "$DNAME"

if [ $? -eq 0 ]; then
    echo ""
    echo "✓ Certificate generated successfully!"
    echo ""
    echo "Keystore details:"
    keytool -list -v -keystore "$KEYSTORE_FILE" -storetype PKCS12 -storepass "$KEYSTORE_PASS" | head -20
    echo ""
    echo "=========================================="
    echo "Next Steps:"
    echo "=========================================="
    echo ""
    echo "1. Update application.conf to enable HTTPS:"
    echo ""
    echo "   turnstile.mcp-streaming {"
    echo "     ssl {"
    echo "       enabled = true"
    echo "       keystore-path = \"$KEYSTORE_FILE\""
    echo "       keystore-password = \"$KEYSTORE_PASS\""
    echo "       keystore-type = \"PKCS12\""
    echo "     }"
    echo "   }"
    echo ""
    echo "2. Start the server:"
    echo "   sbt \"runMain app.dragon.turnstile.controller.AkkaTurnstile\""
    echo ""
    echo "3. Test with curl (note -k for self-signed cert):"
    echo "   curl -k https://localhost:8082/mcp"
    echo ""
    echo "⚠ NOTE: This is a self-signed certificate for DEVELOPMENT ONLY."
    echo "   For production, use a certificate from a trusted CA."
    echo ""
else
    echo "✗ Failed to generate certificate"
    exit 1
fi
