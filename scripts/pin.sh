#!/usr/bin/env bash
# Mostra os pins SPKI (sha256) de cada certificado da cadeia do seu servidor, para a variável BOTSYNC_PINS.
# Use o pin do certificado INTERMEDIÁRIO (ou raiz), não o do folha, e informe 2 pins (separados por vírgula) para ter reserva.
set -euo pipefail
HOST="${1:?uso: ./pin.sh seu-site.squareweb.app}"
openssl s_client -showcerts -servername "$HOST" -connect "$HOST:443" </dev/null 2>/dev/null \
 | awk '/BEGIN CERT/{c++} c{print > "/tmp/cert" c ".pem"} /END CERT/{close("/tmp/cert" c ".pem")}'
for f in /tmp/cert*.pem; do
  echo "$(openssl x509 -in "$f" -noout -subject | cut -c1-80)"
  echo "  sha256/$(openssl x509 -in "$f" -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | openssl enc -base64)"
done
rm -f /tmp/cert*.pem
