#!/usr/bin/env bash
# gen-dev-keys.sh — generate RSA keypair for local development
# DO NOT use these keys in production — generate fresh ones per environment

set -euo pipefail

OUTDIR="$(cd "$(dirname "$0")/.." && pwd)/dev-keys"
mkdir -p "$OUTDIR"

echo "Generating 2048-bit RSA keypair..."
openssl genrsa 2048 | openssl pkcs8 -topk8 -nocrypt -out "$OUTDIR/private.pem"
openssl rsa -in "$OUTDIR/private.pem" -pubout -out "$OUTDIR/public.pem"

echo ""
echo "✅  Keys written to $OUTDIR/"
echo ""
echo "To use in your shell:"
echo "  export JWT_PRIVATE_KEY=\$(cat $OUTDIR/private.pem)"
echo "  export JWT_PUBLIC_KEY=\$(cat $OUTDIR/public.pem)"
echo ""
echo "⚠️  NEVER commit private.pem to version control."
