# Прокси Blockfield

Приватная сборка Velocity, плагина авторизации и лобби Limbo. История выделена из `netherg-io/blockfield-modpack`; клиент, игровой сервер и JAR-файлы модпака в неё не входят.

Для Linux и Windows нужны Python 3.12+, Node.js 22, Just 1.57.0 и JDK 21 (`JAVA_HOME`). Инструменты проверок устанавливаются в кеш проекта. Команды запускаются из корня клона.

```sh
just setup
just check
just build
just format
```

`just --list` показывает все команды.

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание (`release-notes.md`) и анонс для Discord (`announcement.json`) генерирует CI action `release-text` из слитых PR промежутка; при сбое остаётся текст из фактов, тег не падает. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой идёт через webhook Dokploy (секрет `DOKPLOY_PROXY_WEBHOOK`, API-ключа Dokploy в Actions нет): канал `ghcr.io/blockfield/blockfield-proxy:latest` переставляется на digest тега, при ошибке — обратно на работавший digest. Схема — [server/docs/DEPLOY.md](https://github.com/Blockfield/blockfield-server/blob/main/docs/DEPLOY.md).

С v2.54.1 (2026-09-27) прод работает на `ghcr.io/blockfield/blockfield-proxy`. Прежний образ `ghcr.io/netherg-io/blockfield-proxy:latest` (v2.54.0) с тем же digest лежит в `ghcr.io/blockfield/blockfield-proxy:v2.54.0`: откат на него — `Deploy proxy tag=v2.54.0`. Образ в netherg-io не удалять — запасной путь через Dokploy.

## Транспорт клиента

По умолчанию клиент подключается к `raknet;play.blockfield.pro:25566` через
Raknetify `0.1.0+alpha.5.196` (Velocity, immutable Modrinth version `u3LRuC7t`,
SHA256 закреплён в Dockerfile). TCP `play.blockfield.pro:25565` остаётся доступен
для ручного подключения и однократного fallback клиента до завершения login.
Клиент запоминает TCP для этого сервера до закрытия игры; отмена подключения и
отключение после login не вызывают retry. Backend Fabric не требует Raknetify:
UDP завершается на Velocity, внутреннее соединение с game/workshop остаётся TCP.

Velocity запускается с `-Draknetify.velocity.portOverride=25566` через `JVM_OPTS`.
При настройке `JVM_OPTS` в Dokploy сохраните эту property. UDP 25565 занят Simple
Voice Chat; его конфигурация не меняется. Перед выпуском клиента требуется
проверка Raknetify с закреплённым Velocity **3.5.1 build 615**: plugin собран
против 3.4.0, совместимость и вход через Limbo/auth/PackVersionGate ещё не проверены.

В Dokploy для приложения `blockfield-proxy-niocmj` требуется добавить публикацию
**UDP published 25566 → target 25566, mode host** и разрешить UDP 25566 в firewall.
Сохраните TCP 25565 и UDP 25565 (voice chat). `EXPOSE` не публикует порт сам.
Изменение Dokploy и Deploy proxy выполняет владелец отдельно; PR их не выполняет.
Порядок включения: публикация порта и проверенный proxy, затем клиент с RakNet default.

Raknetify all-in-one распространяется под **GPLv3** (включает netty-raknet);
исходники и условия: [Raknetify](https://github.com/RelativityMC/raknetify),
[Modrinth](https://modrinth.com/mod/raknetify). Эта лицензия относится к стороннему
JAR и не заменяется LICENSE Blockfield. При распространении образа сохраняйте
лицензию и предоставляйте соответствующие исходники и notices зависимостей.

## Плагин Velocity

[`BlockfieldProxy`](proxy/plugin/src/main/java/io/blockfield/proxy/BlockfieldProxy.java) регистрирует три модуля:

- [`Authentication`](proxy/plugin/src/main/java/io/blockfield/proxy/Authentication.java): Drasl, `/login`, `/register`, `/logout`, сохранённые сессии и лимиты попыток.
- [`BackendRouting`](proxy/plugin/src/main/java/io/blockfield/proxy/BackendRouting.java): `gate.json`, ACL мастерской, лобби и возврат после рестарта.
- [`LoginForwarding`](proxy/plugin/src/main/java/io/blockfield/proxy/LoginForwarding.java): ответы о версии пака и контракте совместимости, а также перенос конфигурации TaCZ Tweaks из login в play.

`Authentication` и `LoginForwarding` очищают данные при отключении игрока; маршрутизация сохраняет недавнее участие в игре на десять минут для доигрывания Кампании. `Gate` хранит правила доступа, `Sessions` — проверку HMAC. Модули собираются в один плагин; образ, переменные окружения и протоколы не меняются. Limbo остаётся внутри контейнера прокси.

Версии карты Limbo хранятся в общей истории Anvil; схема для запуска — производный экспорт.
Подключение выбранной версии и проверка её spawn описаны в [LIMBO.md](docs/LIMBO.md).

Условия использования: [LICENSE](LICENSE).
