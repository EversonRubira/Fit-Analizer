#!/usr/bin/env bash
# Para a aplicação iniciada pelo scripts/subir.sh. O Postgres (fit-pg) e o volume ficam.
#
# Uso: scripts/parar.sh

set -euo pipefail

PID_FILE="/tmp/fit-app.pid"
API_URL="${API_URL:-http://localhost:8080}"
# Só processos cujo executável é java e que rodam a classe principal da aplicação.
# Ancorado no início: um shell ou editor que só MENCIONE a classe não é atingido.
PADRAO_JAVA='^[^ ]*java .*com[.]fitanalizer[.]FitAnalizerApplication'

# O mvnw abre um processo Java filho com a aplicação; parar só o pai pode deixar o
# Java a correr. Por isso: filhos do PID guardado, o próprio PID e, por garantia, o
# processo Java pela classe principal.
if [ -f "$PID_FILE" ]; then
    PID="$(cat "$PID_FILE")"
    pkill -TERM -P "$PID" 2>/dev/null || true
    kill -TERM "$PID" 2>/dev/null || true
    rm -f "$PID_FILE"
fi
pkill -TERM -f "$PADRAO_JAVA" 2>/dev/null || true

# Espera a porta fechar E o processo Java sair (o Spring fecha a porta antes de
# terminar o desligamento).
for _ in $(seq 1 20); do
    if ! curl -s -o /dev/null --max-time 2 "$API_URL" 2>/dev/null \
        && ! pgrep -f "$PADRAO_JAVA" >/dev/null; then
        echo "Aplicação parada. O Postgres (fit-pg) continua a correr; para pará-lo: docker stop fit-pg"
        exit 0
    fi
    sleep 1
done
echo "AVISO: algo ainda responde em $API_URL. Veja: ps aux | grep -i fitanalizer" >&2
exit 1
