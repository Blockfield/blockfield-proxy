# Blockfield Proxy

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

Для Linux и Windows нужны Python 3.12+, Node.js 22, Just 1.57.0 и JDK 21 (`JAVA_HOME`). Инструменты проверок устанавливаются в кеш проекта. Команды запускаются из корня клона.

```sh
just setup
just check
just build
just format
```

`just --list` показывает все команды.

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание готовится в релизном PR: `release-notes/<тег>.md` по скелету `just python scripts/release-notes-tool.py skeleton --repository Blockfield/blockfield-proxy --version <тег> --previous-version <прошлый тег или пусто> --kind server --date YYYY-MM-DD`, затем `check`. Без файла тег падает до публикации образа. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой идёт через webhook Dokploy (секрет `DOKPLOY_PROXY_WEBHOOK`, API-ключа Dokploy в Actions нет): канал `ghcr.io/blockfield/blockfield-proxy:prod` переставляется на digest тега, при ошибке — обратно на работавший digest. Схема — [server/docs/DEPLOY.md](https://github.com/Blockfield/blockfield-server/blob/main/docs/DEPLOY.md).

С v2.54.1 (2026-09-27) прод работает на `ghcr.io/blockfield/blockfield-proxy`. Прежний образ `ghcr.io/netherg-io/blockfield-proxy:latest` (v2.54.0) с тем же digest лежит в `ghcr.io/blockfield/blockfield-proxy:v2.54.0`: откат на него — `Deploy proxy tag=v2.54.0`. Образ в netherg-io не удалять — запасной путь через Dokploy.

## Плагин Velocity

[`BlockfieldProxy`](proxy/plugin/src/main/java/io/blockfield/proxy/BlockfieldProxy.java) регистрирует три модуля:

- [`Authentication`](proxy/plugin/src/main/java/io/blockfield/proxy/Authentication.java): Drasl, `/login`, `/register`, `/logout`, сохранённые сессии и лимиты попыток.
- [`BackendRouting`](proxy/plugin/src/main/java/io/blockfield/proxy/BackendRouting.java): `gate.json`, ACL мастерской, лобби и возврат после рестарта.
- [`LoginForwarding`](proxy/plugin/src/main/java/io/blockfield/proxy/LoginForwarding.java): ответы о версии пака и перенос конфигурации TaCZ Tweaks из login в play.

`Authentication` и `LoginForwarding` очищают данные при отключении игрока; маршрутизация сохраняет недавнее участие в игре на десять минут для доигрывания Кампании. `Gate` хранит правила доступа, `Sessions` — проверку HMAC. Модули собираются в один плагин; образ, переменные окружения и протоколы не меняются. Limbo остаётся внутри контейнера прокси.
