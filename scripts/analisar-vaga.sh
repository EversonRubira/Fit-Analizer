#!/usr/bin/env bash
# Analisa uma vaga real contra a API local e regista a SUA decisão antes de ver a do sistema.
#
# Uso: scripts/analisar-vaga.sh <arquivo-da-vaga> <vagaUrl-ou-id-estável> <minha-nota> [frente]
#   minha-nota: cv_prioritario | cv_carta | cv_carta_com_aviso | nao_candidatar | fora_escopo
#   frente:     TECH (padrão) | COMEX
#
# Variáveis de ambiente (opcionais):
#   OWNER    dono do Profile (padrão: everson)
#   API_URL  endereço da API (padrão: http://localhost:8080)
#   FIT_CSV  arquivo do histórico (padrão: ~/fit-analises.csv, fora do repositório)
#
# Dependências: bash, curl, jq. O texto da vaga só vai no corpo do POST; nunca é
# impresso nem gravado.

set -euo pipefail

OWNER="${OWNER:-everson}"
API_URL="${API_URL:-http://localhost:8080}"
FIT_CSV="${FIT_CSV:-$HOME/fit-analises.csv}"

# Ordem do melhor para o pior: a distância entre duas decisões é a diferença de posição.
DECISOES="cv_prioritario cv_carta cv_carta_com_aviso nao_candidatar fora_escopo"

erro() {
    echo "ERRO: $*" >&2
    exit 1
}

# Posição (0..4) de uma decisão na ordem acima; vazio se não existir.
posicao() {
    local i=0 d
    for d in $DECISOES; do
        if [ "$d" = "$1" ]; then
            echo "$i"
            return
        fi
        i=$((i + 1))
    done
}

# --- 1) Argumentos e arquivo: tudo validado antes de qualquer chamada de rede ---
if [ $# -lt 3 ] || [ $# -gt 4 ]; then
    sed -n '2,6p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 1
fi

ARQUIVO="$1"
VAGA_URL="$2"
MINHA_NOTA="$(printf '%s' "$3" | tr '[:upper:]' '[:lower:]')"
FRENTE="$(printf '%s' "${4:-TECH}" | tr '[:lower:]' '[:upper:]')"

[ -n "$(posicao "$MINHA_NOTA")" ] \
    || erro "nota inválida: '$3'. Use uma de: $DECISOES (decida ANTES de rodar)."
[ -n "$VAGA_URL" ] || erro "vagaUrl/id vazio: use um valor estável (a mesma vaga deve ter sempre o mesmo id)."
[ -f "$ARQUIVO" ] || erro "arquivo da vaga não encontrado: $ARQUIVO"
[ -s "$ARQUIVO" ] || erro "arquivo da vaga está vazio: $ARQUIVO"
case "$FRENTE" in
    TECH | COMEX) ;;
    *) erro "frente inválida: '$4'. Use TECH ou COMEX." ;;
esac
command -v jq >/dev/null || erro "jq não instalado."
command -v curl >/dev/null || erro "curl não instalado."

# --- 2) API no ar e owner com Profile? GET barato, não chama a Claude ---
# curl -w devolve só o código HTTP; "000" ou falha do curl = sem resposta.
STATUS_PROFILE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$API_URL/profiles/$OWNER" || true)"
case "$STATUS_PROFILE" in
    200) ;;
    000 | "") erro "a API não respondeu em $API_URL. Ela está rodando (./mvnw spring-boot:run)?" ;;
    404) erro "owner '$OWNER' não tem Profile cadastrado (GET /profiles/$OWNER deu 404)." ;;
    *) erro "GET /profiles/$OWNER respondeu HTTP $STATUS_PROFILE." ;;
esac

# --- 3) POST /matches ---
# --rawfile lê o arquivo inteiro como string JSON: aspas, acentos e quebras de linha
# ficam corretamente escapados, sem montar JSON à mão.
CORPO="$(jq -n \
    --arg owner "$OWNER" \
    --arg frente "$FRENTE" \
    --arg vagaUrl "$VAGA_URL" \
    --rawfile textoVaga "$ARQUIVO" \
    '{owner: $owner, frente: $frente, vagaUrl: $vagaUrl, textoVaga: $textoVaga}')"

RESPOSTA="$(mktemp)"
trap 'rm -f "$RESPOSTA"' EXIT

# --max-time 90: o servidor espera a Claude por até 60s; a folga evita cortar uma
# análise que ainda vai ser salva.
HTTP="$(printf '%s' "$CORPO" | curl -s -o "$RESPOSTA" -w '%{http_code}' --max-time 90 \
    -X POST "$API_URL/matches" -H 'Content-Type: application/json' --data-binary @- || true)"

case "$HTTP" in
    201) ORIGEM="análise nova (ou reanálise por versão de prompt) — gastou crédito" ;;
    200) ORIGEM="resultado já salvo — sem custo" ;;
    000 | "") erro "POST /matches sem resposta (timeout ou API caiu). Nada foi registado." ;;
    *)
        MSG="$(jq -r '.message // empty' "$RESPOSTA" 2>/dev/null || true)"
        erro "POST /matches respondeu HTTP $HTTP${MSG:+: $MSG}. Nada foi registado."
        ;;
esac

# --- 4) Resumo legível ---
NOTA_SISTEMA="$(jq -r '.decisao | ascii_downcase' "$RESPOSTA")"
ADERENCIA="$(jq -r '.aderenciaPct' "$RESPOSTA")"
REVISAR="$(jq -r '.revisar' "$RESPOSTA")"
POS_SISTEMA="$(posicao "$NOTA_SISTEMA")"
[ -n "$POS_SISTEMA" ] || erro "decisão desconhecida na resposta: $NOTA_SISTEMA"
DISTANCIA=$(($(posicao "$MINHA_NOTA") - POS_SISTEMA))
DISTANCIA=${DISTANCIA#-} # valor absoluto

echo "HTTP $HTTP: $ORIGEM"
echo
echo "Decisão do sistema: $NOTA_SISTEMA   (a sua: $MINHA_NOTA)"
echo "Aderência:          $ADERENCIA%"
echo "Revisar:            $REVISAR"
jq -r '"Prompt/modelo:      \(.versaoPrompt) / \(.modelo)"' "$RESPOSTA"
if [ "$DISTANCIA" -gt 0 ]; then
    echo
    echo "AVISO: o sistema discorda da sua nota em $DISTANCIA faixa(s)."
fi

# Requisitos agrupados por classificação, na ordem forte -> parcial -> nenhum.
# Marcas: [eliminatório], [mín. N anos], [fora do perfil].
for CLASSE in FORTE PARCIAL NENHUM; do
    jq -r --arg c "$CLASSE" '
        [.requisitos[] | select(.classificacao == $c)] as $lista
        | if ($lista | length) == 0 then empty else
            "\n\($c | ascii_downcase) (\($lista | length)):",
            ($lista[] | "  - \(.descricao)"
                + (if .evidenciaRef then "  <- \(.evidenciaRef)" else "" end)
                + (if .eliminatorio then "  [eliminatório]" else "" end)
                + (if .anosMinimos then "  [mín. \(.anosMinimos) anos]" else "" end)
                + (if .foraDoPerfil then "  [fora do perfil]" else "" end))
          end' "$RESPOSTA"
done

jq -r '
    if (.gapsRiscos | length) == 0 then "\nGaps e riscos: nenhum" else
        "\nGaps e riscos:",
        (.gapsRiscos[] | "  - \(.gap)\n      risco: \(.risco)")
    end' "$RESPOSTA"

# --- 5) Histórico em CSV (fora do repositório) ---
# @csv do jq faz o escape: campos de texto entre aspas, aspas internas dobradas.
if [ ! -f "$FIT_CSV" ]; then
    echo "data,vagaUrl,minha_nota,nota_sistema,aderencia,revisar,distancia,mudei_de_ideia" >"$FIT_CSV"
fi
jq -rn \
    --arg data "$(date +%Y-%m-%dT%H:%M:%S)" \
    --arg vagaUrl "$VAGA_URL" \
    --arg minha "$MINHA_NOTA" \
    --arg sistema "$NOTA_SISTEMA" \
    --argjson aderencia "$ADERENCIA" \
    --arg revisar "$REVISAR" \
    --argjson distancia "$DISTANCIA" \
    '[$data, $vagaUrl, $minha, $sistema, $aderencia, $revisar, $distancia, ""] | @csv' >>"$FIT_CSV"

echo
echo "Registado em $FIT_CSV (coluna mudei_de_ideia fica para você preencher)."
