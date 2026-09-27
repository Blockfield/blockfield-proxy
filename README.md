# Blockfield Proxy

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

```sh
cd proxy/plugin
./gradlew build --no-daemon
```

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание готовится в релизном PR: `release-notes/<тег>.md` по скелету `python3 scripts/release-notes-tool.py skeleton --repository Blockfield/blockfield-proxy --version <тег> --previous-version <прошлый тег или пусто> --kind server --date YYYY-MM-DD`, затем `check`. Без файла тег падает до публикации образа. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают.

До первого деплоя новой сборки работает прежний образ `ghcr.io/netherg-io/blockfield-proxy`. Его можно использовать для отката в Dokploy.
