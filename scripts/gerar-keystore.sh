#!/usr/bin/env bash
# Gera a chave que assina o APK. GUARDE o arquivo e as senhas: sem elas não dá para publicar atualizações.
set -euo pipefail
command -v keytool >/dev/null || { echo "keytool não encontrado (instale o JDK 17)."; exit 1; }
SENHA=$(openssl rand -base64 24 | tr -d '/+=' | cut -c1-24)
ALIAS=botsync
keytool -genkeypair -v -keystore botsync.jks -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$SENHA" -keypass "$SENHA" -dname "CN=BotSync, O=BotSync, C=BR" >/dev/null
base64 -w0 botsync.jks > keystore.b64 2>/dev/null || base64 botsync.jks | tr -d '\n' > keystore.b64
echo
echo "Cadastre em GitHub → Settings → Secrets and variables → Actions → Secrets:"
echo "  KEYSTORE_BASE64   = (conteúdo do arquivo keystore.b64)"
echo "  KEYSTORE_PASSWORD = $SENHA"
echo "  KEY_PASSWORD      = $SENHA"
echo "  KEY_ALIAS         = $ALIAS"
echo
echo "Depois APAGUE keystore.b64 e guarde botsync.jks em local seguro (NUNCA suba para o GitHub)."
