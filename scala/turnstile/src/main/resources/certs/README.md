# SSL Certificates Directory

This directory contains SSL/TLS certificates for HTTPS support.

## Development Certificate

To generate a self-signed certificate for development:

```bash
# Run from project root
./scripts/generate-dev-cert.sh
```

This creates `keystore.p12` with:
- Alias: `turnstile-mcp-dev`
- Password: `changeit`
- Type: PKCS12
- Valid for: 365 days

## Production Certificate

For production, use a certificate from a trusted Certificate Authority (CA):

1. **Let's Encrypt** (free):
   ```bash
   certbot certonly --standalone -d yourdomain.com
   ```

2. **Commercial CA** (DigiCert, Comodo, etc.):
   - Generate CSR
   - Submit to CA
   - Import signed certificate

See [HTTPS_SETUP.md](../../../../HTTPS_SETUP.md) for detailed instructions.

## Security Note

⚠️ **NEVER commit certificates or private keys to version control!**

This directory is gitignored by default. Keep certificates secure:
- Development: Local filesystem only
- Production: Secure secrets management (Vault, AWS Secrets Manager, etc.)

## File Contents

- `keystore.p12` - PKCS12 keystore with certificate and private key (gitignored)
- `.gitignore` - Prevents committing certificates
- `README.md` - This file
