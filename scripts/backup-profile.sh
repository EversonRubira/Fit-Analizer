#!/usr/bin/env bash
# Backup do Profile: scripts/backup-profile.sh  ->  ~/fit-profile-backup-AAAA-MM-DD.json (OWNER, API_URL opcionais)

set -euo pipefail

OWNER="${OWNER:-everson}"
API_URL="${API_URL:-http://localhost:8080}"
DESTINO="$HOME/fit-profile-backup-$(date +%Y-%m-%d).json"

command -v jq >/dev/null || { echo "ERRO: jq não instalado." >&2; exit 1; }

TEMP="$(mktemp)"
trap 'rm -f "$TEMP"' EXIT

HTTP="$(curl -s -o "$TEMP" -w '%{http_code}' --max-time 10 "$API_URL/profiles/$OWNER" || true)"
case "$HTTP" in
    200) ;;
    000 | "") echo "ERRO: a API não respondeu em $API_URL." >&2; exit 1 ;;
    *) echo "ERRO: GET /profiles/$OWNER respondeu HTTP $HTTP." >&2; exit 1 ;;
esac

# jq valida que é JSON e grava formatado; só substitui o backup do dia se der certo.
jq . "$TEMP" >"$TEMP.fmt" && mv "$TEMP.fmt" "$DESTINO"
echo "Backup gravado em $DESTINO"
