#!/usr/bin/env bash
# Analisa uma vaga real contra a API local e regista a SUA decisão antes de ver a do sistema.
#
# Uso: scripts/analisar-vaga.sh <minha-nota> [--url <id-ou-link>] [--frente TECH|COMEX] [arquivo|-]
#   minha-nota: cv_prioritario | cv_carta | cv_carta_com_aviso | nao_candidatar | fora_escopo
#   origem:     arquivo; "-" lê do stdin (cole e Ctrl+D); sem origem abre o $EDITOR (nano)
#   --url:      opcional; sem ele o servidor faz o dedup pelo hash do texto
#
# Variáveis de ambiente (opcionais):
#   OWNER           dono do Profile (padrão: everson)
#   API_URL         endereço da API (padrão: http://localhost:8080)
#   FIT_CSV         histórico (padrão: ~/fit-analises.csv, fora do repositório)
#   VAGA_MAX_CHARS  limite do texto (padrão: 15000, igual a fitanalizer.match.vaga-max-chars)
#   EDITOR          editor usado quando não há origem (padrão: nano)
#
# Dependências: bash, curl, jq. O texto da vaga só vai no corpo do POST; nunca é
# impresso nem gravado (só a primeira linha, como título, entra no CSV).

set -euo pipefail

OWNER="${OWNER:-everson}"
API_URL="${API_URL:-http://localhost:8080}"
FIT_CSV="${FIT_CSV:-$HOME/fit-analises.csv}"
VAGA_MAX_CHARS="${VAGA_MAX_CHARS:-15000}"

# Ordem do melhor para o pior: a distância entre duas decisões é a diferença de posição.
DECISOES="cv_prioritario cv_carta cv_carta_com_aviso nao_candidatar fora_escopo"

CABECALHO_ANTIGO="data,vagaUrl,minha_nota,nota_sistema,aderencia,revisar,distancia,mudei_de_ideia"
# titulo vai no fim para a migração do arquivo antigo não precisar interpretar o CSV.
CABECALHO="data,id,minha_nota,nota_sistema,aderencia,revisar,distancia,mudei_de_ideia,titulo"

# Todos os temporários entram nesta lista e são apagados ao sair, em sucesso, erro
# ou Ctrl+C (o trap de INT/TERM chama exit, que dispara o trap de EXIT).
TEMPORARIOS=""
limpar_temporarios() {
    local f
    for f in $TEMPORARIOS; do
        rm -f "$f"
    done
}
trap limpar_temporarios EXIT
trap 'exit 130' INT TERM

# Cria um temporário e devolve o caminho em TEMP_NOVO. NÃO usar via substituição de
# comando: ela roda num subshell, e o registo em TEMPORARIOS se perderia (o trap
# não apagaria o arquivo).
novo_temporario() {
    TEMP_NOVO="$(mktemp /tmp/fit-vaga.XXXXXX)"
    chmod 600 "$TEMP_NOVO"
    TEMPORARIOS="$TEMPORARIOS $TEMP_NOVO"
}

erro() {
    echo "ERRO: $*" >&2
    exit 1
}

uso() {
    sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//' >&2
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

# Número de caracteres (code points) de um arquivo.
caracteres() {
    jq -Rs 'length' <"$1"
}

sha256() {
    if command -v sha256sum >/dev/null; then
        sha256sum | cut -d' ' -f1
    else
        shasum -a 256 | cut -d' ' -f1 # macOS
    fi
}

# --- 1) Nota: validada antes de qualquer outra coisa, inclusive antes de ler a vaga ---
[ $# -ge 1 ] || uso
MINHA_NOTA="$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')"
[ -n "$(posicao "$MINHA_NOTA")" ] \
    || erro "nota inválida: '$1'. Use uma de: $DECISOES (decida ANTES de rodar)."
shift

# --- 2) Opções e origem ---
VAGA_URL=""
FRENTE="TECH"
ORIGEM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --url)
            [ $# -ge 2 ] || erro "--url precisa de um valor."
            VAGA_URL="$2"
            shift 2
            ;;
        --frente)
            [ $# -ge 2 ] || erro "--frente precisa de um valor."
            FRENTE="$(printf '%s' "$2" | tr '[:lower:]' '[:upper:]')"
            shift 2
            ;;
        --*) erro "opção desconhecida: $1" ;;
        *)
            [ -z "$ORIGEM" ] || erro "mais de uma origem para a vaga: '$ORIGEM' e '$1'."
            ORIGEM="$1"
            shift
            ;;
    esac
done
case "$FRENTE" in
    TECH | COMEX) ;;
    *) erro "frente inválida: '$FRENTE'. Use TECH ou COMEX." ;;
esac
command -v jq >/dev/null || erro "jq não instalado."
command -v curl >/dev/null || erro "curl não instalado."

# --- 3) Ler a vaga para um temporário (chmod 600, apagado no fim) ---
novo_temporario
BRUTO="$TEMP_NOVO"
if [ "$ORIGEM" = "-" ]; then
    cat >"$BRUTO"
elif [ -n "$ORIGEM" ]; then
    [ -f "$ORIGEM" ] || erro "arquivo da vaga não encontrado: $ORIGEM"
    cat "$ORIGEM" >"$BRUTO"
else
    echo "Abrindo o editor: cole a vaga, grave e feche." >&2
    "${EDITOR:-nano}" "$BRUTO" || erro "o editor terminou com erro; nada foi enviado."
fi

# --- 4) Limpeza mínima e conservadora ---
# Ordem: links markdown [texto](url) -> texto; <url> -> nada; url solta -> nada (sem
# comer a pontuação final da frase); espaços no fim da linha; no máximo 1 linha
# vazia seguida. Nada mais é alterado.
novo_temporario
LIMPO="$TEMP_NOVO"
sed -E \
    -e 's/\[([^]]*)\]\(https?:\/\/[^)[:space:]]*\)/\1/g' \
    -e 's/<https?:\/\/[^>[:space:]]*>//g' \
    -e 's/https?:\/\/[^[:space:]<>()]*[^[:space:]<>().,;:!?]//g' \
    -e 's/[[:space:]]+$//' \
    "$BRUTO" | awk 'NF { vazias = 0; print; next } vazias == 0 { print; vazias = 1 }' >"$LIMPO"

CHARS_ANTES="$(caracteres "$BRUTO")"
CHARS_DEPOIS="$(caracteres "$LIMPO")"
if ! grep -q '[^[:space:]]' "$LIMPO"; then
    erro "a vaga está vazia (depois da limpeza). Nada foi enviado."
fi
echo "Limpeza: $((CHARS_ANTES - CHARS_DEPOIS)) caracteres removidos ($CHARS_DEPOIS restantes)."
if [ "$CHARS_DEPOIS" -gt "$VAGA_MAX_CHARS" ]; then
    erro "a vaga tem $CHARS_DEPOIS caracteres, acima do limite de $VAGA_MAX_CHARS. Corte o que não for a vaga (rodapé, outras vagas) e tente de novo. Nada foi enviado."
fi

# Título (1ª linha não vazia, até 80 caracteres) e id para o CSV.
TITULO="$(jq -Rrs 'split("\n") | map(select(test("\\S"))) | (.[0] // "") | .[0:80]' <"$LIMPO")"
if [ -n "$VAGA_URL" ]; then
    ID="$VAGA_URL"
else
    # Id só local: o servidor normaliza o texto antes do hash, então não é a vaga_chave do banco.
    ID="hash:$(sha256 <"$LIMPO" | cut -c1-8)"
fi

# --- 5) CSV: preparado ANTES do POST, para não pagar uma análise que não fica registada ---
if [ ! -f "$FIT_CSV" ]; then
    echo "$CABECALHO" >"$FIT_CSV"
else
    PRIMEIRA="$(head -n 1 "$FIT_CSV" | tr -d '\r')"
    if [ "$PRIMEIRA" = "$CABECALHO_ANTIGO" ]; then
        # Migração linha a linha: renomeia vagaUrl -> id e acrescenta titulo vazio no fim.
        # Só é seguro se nenhuma célula tiver quebra de linha (aspas balanceadas por linha).
        if awk -F'"' 'NF > 0 && NF % 2 == 0 { exit 1 }' "$FIT_CSV"; then
            cp "$FIT_CSV" "$FIT_CSV.antes-da-migracao"
            novo_temporario
            NOVO="$TEMP_NOVO"
            { echo "$CABECALHO"; tail -n +2 "$FIT_CSV" | tr -d '\r' | sed 's/$/,""/'; } >"$NOVO"
            cat "$NOVO" >"$FIT_CSV"
            echo "CSV migrado para o formato novo ($(($(wc -l <"$FIT_CSV") - 1)) linhas mantidas;" \
                "cópia do original em $FIT_CSV.antes-da-migracao)."
        else
            ANTIGO="$FIT_CSV.antigo-$(date +%Y%m%d%H%M%S)"
            mv "$FIT_CSV" "$ANTIGO"
            echo "$CABECALHO" >"$FIT_CSV"
            echo "AVISO: o CSV antigo tem células com quebra de linha; não foi migrado automaticamente." \
                "Foi movido para $ANTIGO e um CSV novo foi criado."
        fi
    elif [ "$PRIMEIRA" != "$CABECALHO" ]; then
        erro "cabeçalho desconhecido em $FIT_CSV; corrija ou mova o arquivo. Nada foi enviado."
    fi
fi

# --- 6) API no ar e owner com Profile? GET barato, não chama a Claude ---
STATUS_PROFILE="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$API_URL/profiles/$OWNER" || true)"
case "$STATUS_PROFILE" in
    200) ;;
    000 | "") erro "a API não respondeu em $API_URL. Rode scripts/subir.sh." ;;
    404) erro "owner '$OWNER' não tem Profile cadastrado (GET /profiles/$OWNER deu 404)." ;;
    *) erro "GET /profiles/$OWNER respondeu HTTP $STATUS_PROFILE." ;;
esac

# --- 7) POST /matches ---
# --rawfile escapa aspas, acentos e quebras de linha. Sem --url, vagaUrl não é enviado.
CORPO="$(jq -n \
    --arg owner "$OWNER" \
    --arg frente "$FRENTE" \
    --arg vagaUrl "$VAGA_URL" \
    --rawfile textoVaga "$LIMPO" \
    '{owner: $owner, frente: $frente, textoVaga: $textoVaga}
     + (if $vagaUrl == "" then {} else {vagaUrl: $vagaUrl} end)')"

novo_temporario
RESPOSTA="$TEMP_NOVO"
# --max-time 90: o servidor espera a Claude por até 60s; a folga evita cortar uma
# análise que ainda vai ser salva.
HTTP="$(printf '%s' "$CORPO" | curl -s -o "$RESPOSTA" -w '%{http_code}' --max-time 90 \
    -X POST "$API_URL/matches" -H 'Content-Type: application/json' --data-binary @- || true)"

case "$HTTP" in
    201) ORIGEM_RESULTADO="análise nova (ou reanálise por versão de prompt) — gastou crédito" ;;
    200) ORIGEM_RESULTADO="resultado já salvo — sem custo" ;;
    000 | "") erro "POST /matches sem resposta (timeout ou API caiu). Nada foi registado." ;;
    *)
        MSG="$(jq -r '.message // empty' "$RESPOSTA" 2>/dev/null || true)"
        erro "POST /matches respondeu HTTP $HTTP${MSG:+: $MSG}. Nada foi registado."
        ;;
esac

# --- 8) Resumo legível ---
NOTA_SISTEMA="$(jq -r '.decisao | ascii_downcase' "$RESPOSTA")"
ADERENCIA="$(jq -r '.aderenciaPct' "$RESPOSTA")"
REVISAR="$(jq -r '.revisar' "$RESPOSTA")"
POS_SISTEMA="$(posicao "$NOTA_SISTEMA")"
[ -n "$POS_SISTEMA" ] || erro "decisão desconhecida na resposta: $NOTA_SISTEMA"
DISTANCIA=$(($(posicao "$MINHA_NOTA") - POS_SISTEMA))
DISTANCIA=${DISTANCIA#-} # valor absoluto

echo
echo "HTTP $HTTP: $ORIGEM_RESULTADO"
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

# --- 9) Linha no CSV (@csv do jq: texto entre aspas, aspas internas dobradas) ---
jq -rn \
    --arg data "$(date +%Y-%m-%dT%H:%M:%S)" \
    --arg id "$ID" \
    --arg minha "$MINHA_NOTA" \
    --arg sistema "$NOTA_SISTEMA" \
    --argjson aderencia "$ADERENCIA" \
    --arg revisar "$REVISAR" \
    --argjson distancia "$DISTANCIA" \
    --arg titulo "$TITULO" \
    '[$data, $id, $minha, $sistema, $aderencia, $revisar, $distancia, "", $titulo] | @csv' >>"$FIT_CSV"

echo
echo "Registado em $FIT_CSV como $ID (coluna mudei_de_ideia fica para você preencher)."
