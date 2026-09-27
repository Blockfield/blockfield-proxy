# Blockfield Proxy

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

```sh
cd proxy/plugin
./gradlew build --no-daemon
```

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание готовится в релизном PR: `release-notes/<тег>.md` по скелету `python3 scripts/release-notes-tool.py skeleton --repository Blockfield/blockfield-proxy --version <тег> --previous-version <прошлый тег или пусто> --kind server --date YYYY-MM-DD`, затем `check`. Без файла тег падает до публикации образа. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой идёт через webhook Dokploy (секрет `DOKPLOY_PROXY_WEBHOOK`, API-ключа Dokploy в Actions нет): канал `ghcr.io/blockfield/blockfield-proxy:prod` переставляется на digest тега, при ошибке — обратно на работавший digest. Схема — `docs/DEPLOY.md` в blockfield-server.

До первого деплоя новой сборки работает прежний образ `ghcr.io/netherg-io/blockfield-proxy`; он вне `ghcr.io/blockfield`, поэтому первый Deploy proxy требует `without_rollback` (автоматического отката на него нет, вернуть его можно только вручную в Dokploy).
