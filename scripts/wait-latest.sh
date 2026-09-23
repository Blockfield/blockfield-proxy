#!/bin/sh
# Ждать, пока GHCR отдаёт :latest с тем же digest, что у тега: Dokploy тянет :latest сразу после вебхука,
# и без этого пул падал на "content digest ... not found" (2026-09-15, v2.31.6). Короткий запас после совпадения:
# если пул всё же упадёт, check-server-deployed.py повторит вебхук через 150 с.
set -eu
image="${1:?image}" tag="${2:?tag}"
want=$(docker buildx imagetools inspect "$image:$tag" --format '{{json .Manifest.Digest}}')
for attempt in $(seq 1 20); do
    got=$(docker buildx imagetools inspect "$image:latest" --format '{{json .Manifest.Digest}}' 2>/dev/null || true)
    if [ "$got" = "$want" ]; then
        sleep 5
        echo "$image:latest = $tag ($want)"
        exit 0
    fi
    sleep 6
done
echo "$image:latest не указывает на $tag" >&2
exit 1
