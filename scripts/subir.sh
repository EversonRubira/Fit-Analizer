#!/usr/bin/env bash
# Sobe o Postgres (container fit-pg) e a aplicação, se ainda não estiverem no ar.
# Idempotente: rodar de novo não duplica nada. Não cria Profile nem dados.
#
# Uso: scripts/subir.sh   (OWNER e API_URL opcionais; log da aplicação em /tmp/fit-app.log)

set -euo pipefail

OWNER="${OWNER:-everson}"
API_URL="${API_URL:-http://localhost:8080}"
CONTAINER="fit-pg"
IMAGEM="postgres:16-alpine"
IMAGEM_MIRROR="mirror.gcr.io/library/postgres:16-alpine"
LOG="/tmp/fit-app.log"
PID_FILE="/tmp/fit-app.pid"
RAIZ="$(cd "$(dirname "$0")/.." && pwd)"

erro() {
    echo "ERRO: $*" >&2
    exit 1
}

# Fim do log + a primeira causa (no Spring ela costuma ficar antes das últimas 20 linhas).
mostrar_log() {
    tail -n 20 "$LOG" >&2
    echo >&2
    echo "Primeira causa no log: $(grep -m1 -E 'Caused by:|APPLICATION FAILED|ERROR' "$LOG" || echo '(não encontrada)')" >&2
}

# Código HTTP do GET /profiles/<owner>; "000" = nada respondeu.
status_api() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$API_URL/profiles/$OWNER" || true
}

# --- 1) Chave da Claude: só verifica se existe, nunca imprime ---
if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "AVISO: ANTHROPIC_API_KEY não está definida neste terminal. Sem ela as análises falham."
    echo "       Defina antes de subir a aplicação: read -s ANTHROPIC_API_KEY && export ANTHROPIC_API_KEY"
fi

# --- 2) Postgres ---
command -v docker >/dev/null || erro "docker não instalado."
docker info >/dev/null 2>&1 || erro "o Docker não está a correr."

# inspect devolve true/false se o container existe; vazio se não existe.
ESTADO="$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)"
case "$ESTADO" in
    true)
        echo "Postgres: container $CONTAINER já está a correr."
        ;;
    false)
        echo "Postgres: iniciando o container $CONTAINER existente..."
        docker start "$CONTAINER" >/dev/null
        ;;
    *)
        if ! docker image inspect "$IMAGEM" >/dev/null 2>&1; then
            echo "Postgres: baixando $IMAGEM..."
            # O Docker Hub às vezes recusa (429, limite de downloads): tenta o mirror do Google.
            if ! docker pull -q "$IMAGEM" >/dev/null 2>&1; then
                echo "Postgres: Docker Hub recusou; tentando $IMAGEM_MIRROR..."
                docker pull -q "$IMAGEM_MIRROR" >/dev/null || erro "não foi possível baixar a imagem do Postgres."
                docker tag "$IMAGEM_MIRROR" "$IMAGEM"
            fi
        fi
        echo "Postgres: criando o container $CONTAINER (volume fit-pg-data)..."
        docker run -d --name "$CONTAINER" \
            -e POSTGRES_USER=fit_analizer -e POSTGRES_PASSWORD=fit_analizer -e POSTGRES_DB=fit_analizer \
            -p 5432:5432 -v fit-pg-data:/var/lib/postgresql/data "$IMAGEM" >/dev/null \
            || erro "docker run falhou (a porta 5432 já está em uso por outro Postgres?)."
        ;;
esac

# pg_isready dentro do container: só segue quando o banco aceita ligações.
for _ in $(seq 1 30); do
    if docker exec "$CONTAINER" pg_isready -U fit_analizer -d fit_analizer >/dev/null 2>&1; then
        echo "Postgres: pronto."
        break
    fi
    sleep 1
done
docker exec "$CONTAINER" pg_isready -U fit_analizer -d fit_analizer >/dev/null 2>&1 \
    || erro "o Postgres não ficou pronto em 30s. Veja: docker logs $CONTAINER"

# --- 3) Aplicação ---
STATUS="$(status_api)"
if [ "$STATUS" = "200" ] || [ "$STATUS" = "404" ]; then
    echo "Aplicação: já está a correr em $API_URL."
else
    echo "Aplicação: iniciando (log em $LOG)..."
    cd "$RAIZ"
    nohup ./mvnw -q spring-boot:run >"$LOG" 2>&1 &
    echo $! >"$PID_FILE"

    # 200 ou 404 = respondeu, então está no ar. Até 120s (o primeiro build é lento).
    STATUS="000"
    for _ in $(seq 1 120); do
        STATUS="$(status_api)"
        if [ "$STATUS" = "200" ] || [ "$STATUS" = "404" ]; then
            break
        fi
        if ! kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
            echo "ERRO: a aplicação terminou durante a subida. Últimas linhas de $LOG:" >&2
            mostrar_log
            exit 1
        fi
        sleep 1
    done
    if [ "$STATUS" != "200" ] && [ "$STATUS" != "404" ]; then
        echo "ERRO: a aplicação não respondeu em 120s. Últimas linhas de $LOG:" >&2
        mostrar_log
        exit 1
    fi
    echo "Aplicação: no ar em $API_URL."
fi

# --- 4) Profile ---
if [ "$STATUS" = "404" ]; then
    echo "AVISO: o owner '$OWNER' não tem Profile. Cadastre-o antes de analisar vagas"
    echo "       (README, seção \"Teste manual da F02\", ou docs/prd/F01-cadastro-perfil-tecnico.md)."
else
    echo "Profile de '$OWNER': ok."
fi
