# Blockfield Proxy

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

```sh
cd proxy/plugin
./gradlew build --no-daemon
```

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>`. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают.

До первого деплоя новой сборки работает прежний образ `ghcr.io/netherg-io/blockfield-proxy`. Его можно использовать для отката в Dokploy.
