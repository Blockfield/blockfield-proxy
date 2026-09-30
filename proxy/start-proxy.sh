#!/bin/bash
set -euo pipefail
: "${VELOCITY_FORWARDING_SECRET:?нужен VELOCITY_FORWARDING_SECRET (тот же, что FABRIC_PROXY_SECRET у сервера)}"

sed -i "s|^forwarding-secrets=.*|forwarding-secrets=${VELOCITY_FORWARDING_SECRET}|" /opt/lobby/server.properties
# ponytail: лобби живёт в контейнере прокси под циклом перезапуска; вынести в отдельное приложение, если начнёт падать или расти.
# Limbo читает консоль из stdin и на EOF завершается, поэтому держим stdin открытым (uutils tail -f /dev/null в образе падает).
# cd только в подоболочке: run-bungeecord.sh качает и запускает Velocity в текущем каталоге, а конфиги кладёт в /server.
(cd /opt/lobby && while :; do
    gosu 1000:1000 java -Xms32m -Xmx160m -jar limbo.jar --nogui < <(sleep infinity) || true
    sleep 2
done) &

exec /usr/bin/run-bungeecord.sh "$@"
