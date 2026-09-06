#!/bin/sh
# Три попытки для сетевой команды: раннеры иногда не дозваниваются до api.github.com.
set -eu
for attempt in 1 2 3; do
    if "$@"; then
        exit 0
    fi
    echo "попытка $attempt не удалась: $*" >&2
    sleep 15
done
exit 1
