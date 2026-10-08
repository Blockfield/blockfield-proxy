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

Drasl UUID подтверждается до ответа Minecraft LoginSuccess и используется в современном
Velocity forwarding для обоих backend. Вход по токену проверяет `hasJoined`; сохранённая
сессия проверяет HMAC свежего challenge и совпадение UUID в подписанном JWT с текущим
профилем точного ника у Drasl. Старые сессии без UUID нужно подтвердить через `/login` заново;
смена владельца ника не переносит сохранённый вход на другой аккаунт.
Некорректный UUID или недоступный профиль оставляет игрока без доступа в backend.
После первого `/login` или `/register` в Limbo клиент сохраняет сессию: нужно вручную
переподключиться к тому же адресу. До переподключения offline UUID не допускается в backend;
без секрета сохранённых сессий нужен вход через лаунчер Blockfield. Автоматического retry
после отключения в игре нет. `/logout` очищает сохранённую сессию и во время ожидания переподключения.

`Authentication` и `LoginForwarding` очищают данные при отключении игрока; маршрутизация сохраняет недавнее участие в игре на десять минут для доигрывания Кампании. `Gate` хранит правила доступа, `Sessions` — проверку HMAC. Модули собираются в один плагин. Limbo остаётся внутри контейнера прокси.

### Адреса игровых профилей

`BLOCKFIELD_WORKSHOP_HOST` задаёт отдельное имя хоста или IP мастерской на этом
прокси, без порта и `raknet;`. IPv6 можно записать в квадратных скобках. Регистр
DNS-имени и завершающая точка не влияют на сравнение; числовые IPv6 сравниваются
в нормализованном виде, без DNS-запросов. Некорректное значение останавливает
инициализацию плагина. Пустое значение отключает вход в мастерскую.

В лаунчере профиль `workshop` использует `workshopServer` (необязательное начальное
значение `BLOCKFIELD_WORKSHOP_SERVER`), профиль `game` — опубликованный адрес
игры. Укажите в `workshopServer` тот же хост, что в `BLOCKFIELD_WORKSHOP_HOST`, с
нужным портом и транспортом. Хосты игры и мастерской должны различаться: разные
порты или TCP/Raknet на одном хосте не разделяют профили. Адрес мастерской нужно
явно настроить с обеих сторон; DNS и развёртывание этим репозиторием не создаются.

Прокси выбирает профиль по хосту Minecraft-подключения (`Player.getVirtualHost`):
совпадение с `BLOCKFIELD_WORKSHOP_HOST` означает мастерскую; остальные существующие
адреса означают игру. Этот выбор не даёт прав: авторизация Drasl, ACL и `gate.json`
проверяются дополнительно при каждом входе в backend. Игра и мастерская могут
работать одновременно: допуск каждого backend задаётся отдельно. Если выбранный
backend закрыт или не отвечает на ping, игрок ждёт в Limbo и автоматически
возвращается в свой профиль. Закрытие мастерской или отзыв ACL не прерывает игру.
Игровой профиль не отправляется в мастерскую, профиль мастерской — в игру.
Ответ server-list ping использует сообщение допуска запрошенного хоста.
`[forced-hosts]` остаётся пустым: маршрутизацией владеет плагин, а запуском
backend — существующий контроллер.

Контроллер сохраняет `schema: 1` и прежние верхние поля `gate`, `backend`,
`message`, `allowed`, добавляя независимые правила:

```json
{
  "schema": 1,
  "revision": 4,
  "gate": "public",
  "backend": "game",
  "message": "",
  "allowed": [],
  "backends": {
    "game": { "gate": "public", "message": "", "allowed": [] },
    "workshop": { "gate": "builders", "message": "Мастерская", "allowed": [] }
  }
}
```

Если `backends` присутствует, прокси проверяет только запись выбранного backend:
отсутствующая или некорректная запись закрывает его, без возврата к верхним полям.
Некорректный объект `backends` закрывает оба входа. Для игры допустимы только
`public`, `draining`, `closed`, для мастерской — `builders`, `testers`, `closed`;
состояние другого backend закрывает выбранный вход. ACL мастерской по-прежнему
требует совпадения Drasl UUID и имени; `testers` ограничивает вход аналогично
`builders`. Старый файл без `backends` сохраняет прежний единственный активный
backend. Поэтому прокси с поддержкой нового формата нужно развернуть до включения
одновременной работы в контроллере.

Версии карты Limbo хранятся в общей истории Anvil; схема для запуска — производный экспорт.
Подключение выбранной версии и проверка её spawn описаны в [LIMBO.md](docs/LIMBO.md).

Условия использования: [LICENSE](LICENSE).
