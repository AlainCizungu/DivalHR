#!/bin/sh
# MVP-111: creates a throwaway CA and a server certificate for the compose host name, shares only
# the CA certificate with Core (read-only volume), and starts the synthetic Graph provider. The keys
# live in this container's /tls and are regenerated on every start.
set -eu
umask 077
TLS=/tls
HOST="${PROVIDER_HOST:-graph.meta.test}"
SHARED=/shared
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$TLS/ca.key" -out "$TLS/ca.pem" \
  -days 2 -subj "/CN=DivalHR dev Meta Graph provider CA" >/dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$TLS/server.key" -out "$TLS/server.csr" \
  -subj "/CN=$HOST" >/dev/null 2>&1
printf 'subjectAltName=DNS:%s\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n' "$HOST" > "$TLS/ext.cnf"
openssl x509 -req -in "$TLS/server.csr" -CA "$TLS/ca.pem" -CAkey "$TLS/ca.key" -CAcreateserial \
  -out "$TLS/server.pem" -days 2 -extfile "$TLS/ext.cnf" >/dev/null 2>&1
cp "$TLS/ca.pem" "$SHARED/ca.pem"
chmod 0644 "$SHARED/ca.pem"
exec node /app/server.mjs
