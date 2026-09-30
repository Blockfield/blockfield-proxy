# Blockfield Proxy

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

```sh
cd proxy/plugin
just setup
just check
just build --no-daemon
```

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание готовится в релизном PR: `release-notes/<тег>.md` по скелету `just python scripts/release-notes-tool.py skeleton --repository Blockfield/blockfield-proxy --version <тег> --previous-version <прошлый тег или пусто> --kind server --date YYYY-MM-DD`, затем `check`. Без файла тег падает до публикации образа. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой идёт через webhook Dokploy (секрет `DOKPLOY_PROXY_WEBHOOK`, API-ключа Dokploy в Actions нет): канал `ghcr.io/blockfield/blockfield-proxy:prod` переставляется на digest тега, при ошибке — обратно на работавший digest. Схема — `docs/DEPLOY.md` в blockfield-server.

С v2.54.1 (2026-09-27) прод работает на `ghcr.io/blockfield/blockfield-proxy`. Прежний образ `ghcr.io/netherg-io/blockfield-proxy:latest` (v2.54.0) с тем же digest лежит в `ghcr.io/blockfield/blockfield-proxy:v2.54.0`: откат на него — `Deploy proxy tag=v2.54.0`. Образ в netherg-io не удалять — запасной путь через Dokploy.

## Developer checks

Install Python 3.12+, Node.js 22 and Just 1.57.0, native JDK 21 (`JAVA_HOME`) on Linux or Windows. Quality tools stay in the project cache.

```sh
just setup
just check
just format
```

`just --list` lists supported build and application commands.
