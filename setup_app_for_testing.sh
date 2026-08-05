#!/usr/bin/env bash
#
# setup_app_for_testing.sh
#
# Zieht den Arbeits-Branch, baut ETL-Processor und Testservice neu und startet beide.
# Fasst den manuellen Reset-Ablauf zusammen, der sonst nach jeder Codeaenderung noetig
# waere (git pull, npm build, gradlew bootBuildImage, docker build testservice,
# docker compose up, docker run testservice).
#
# Voraussetzung: deploy/.env existiert bereits und ist ausgefuellt (siehe
# deploy/env-sample.lmu.env). Wird hier nicht angelegt/veraendert.
#
# Proxy: fuer Build-Zeit-Downloads (npm-Registry, Docker Hub, Maven) wird der
# Klinikums-Proxy genutzt. Auf einem Netz ohne Proxy-Pflicht einfach
# PROXY_HOST="" ./setup_app_for_testing.sh aufrufen.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$REPO_ROOT"

BRANCH="${BRANCH:-claude/mitmpolpat-server-deployment-99yizd}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-mv64e-etl-processor}"
ETL_IMAGE="${ETL_IMAGE:-mv64e-etl-processor:lmu-local}"
TESTSERVICE_IMAGE="${TESTSERVICE_IMAGE:-etl-testservice}"
PROXY_HOST="${PROXY_HOST:-medwww.med.uni-muenchen.de}"
PROXY_PORT="${PROXY_PORT:-8080}"

if [ -n "$PROXY_HOST" ]; then
    PROXY_URL="http://${PROXY_HOST}:${PROXY_PORT}"
else
    PROXY_URL=""
fi

log() { echo; echo "==> $*"; }

if [ ! -f deploy/.env ]; then
    echo "FEHLER: deploy/.env fehlt - erst 'cp deploy/env-sample.lmu.env deploy/.env' und ausfuellen." >&2
    exit 1
fi

log "git pull ($BRANCH), lokale Aenderungen verwerfen (z.B. package-lock.json nach npm install)"
# Dieser Server ist reines Deploy-Ziel - hier wird nie manuell editiert, daher unbedenklich,
# den lokalen Stand immer hart auf origin zurueckzusetzen. deploy/.env ist .gitignored und
# bleibt davon unberuehrt.
git fetch origin "$BRANCH"
git checkout "$BRANCH" 2>/dev/null || git checkout -b "$BRANCH" "origin/$BRANCH"
git reset --hard "origin/$BRANCH"

log "CSS/JS-Bundles bauen (npm)"
if [ -n "$PROXY_URL" ]; then
    export http_proxy="$PROXY_URL"
    export https_proxy="$PROXY_URL"
fi
npm install
npm run build

log "ETL-Image bauen ($ETL_IMAGE)"
if [ -n "$PROXY_URL" ]; then
    export GRADLE_OPTS="-Dhttp.proxyHost=${PROXY_HOST} -Dhttp.proxyPort=${PROXY_PORT} -Dhttps.proxyHost=${PROXY_HOST} -Dhttps.proxyPort=${PROXY_PORT} -Dhttp.nonProxyHosts=localhost|127.0.0.1"
fi
./gradlew bootBuildImage --imageName="$ETL_IMAGE"

log "Testservice-Image bauen ($TESTSERVICE_IMAGE)"
if [ -n "$PROXY_URL" ]; then
    docker build \
        --build-arg HTTP_PROXY="$PROXY_URL" --build-arg http_proxy="$PROXY_URL" \
        --build-arg HTTPS_PROXY="$PROXY_URL" --build-arg https_proxy="$PROXY_URL" \
        -t "$TESTSERVICE_IMAGE" examples/dev/testservice
else
    docker build -t "$TESTSERVICE_IMAGE" examples/dev/testservice
fi

ETL_PORT="$(grep -E '^DNPM_MONITORING_HTTP_PORT=' deploy/.env | tail -n1 | cut -d= -f2)"
ETL_PORT="${ETL_PORT:-8080}"

log "ETL-Stack starten (docker compose, Port $ETL_PORT)"
(
    cd deploy
    docker compose -p "$COMPOSE_PROJECT" -f docker-compose.yaml -f docker-compose.lmu-override.yml up -d --force-recreate
)

log "Testservice starten (Port 5000, ETL_BASE_URL -> :$ETL_PORT)"
docker rm -f etl-testservice >/dev/null 2>&1 || true
docker run -d --name etl-testservice --restart unless-stopped \
    -p 5000:5000 --add-host=host.docker.internal:host-gateway \
    -e ETL_BASE_URL="http://host.docker.internal:${ETL_PORT}" \
    "$TESTSERVICE_IMAGE"

log "Fertig."
echo "    ETL-Monitoring: http://localhost:${ETL_PORT}/configs"
echo "    Testservice:    http://localhost:5000"
