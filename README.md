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

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание (`release-notes.md`) и анонс для Discord (`announcement.json`) генерирует CI action `release-text` из слитых PR промежутка; при сбое остаётся текст из фактов, тег не падает. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой ставит в Dokploy dockerImage `ghcr.io/blockfield/blockfield-proxy@sha256:<digest тега>` (Docker provider: только dockerImage, без registry credentials в полях приложения), перезапускает приложение и проверяет, что все задачи служат этот digest; при ошибке — обратно digest, который работал (откат — повторный Deploy proxy с прежним тегом). Нужны секреты репозитория `DOKPLOY_API_URL` (базовый URL дашборда Dokploy) и `DOKPLOY_API_TOKEN` (настройки профиля Dokploy → API/CLI; разовая настройка владельца — у Dokploy нет scoped-ролей, токен фактически административный, поэтому он живёт только в секретах репозитория и используется только этим workflow). Старый секрет `DOKPLOY_PROXY_WEBHOOK` больше не используется (можно удалить). Схема — [server/docs/DEPLOY.md](https://github.com/Blockfield/blockfield-server/blob/main/docs/DEPLOY.md).

С v2.54.1 (2026-09-27) прод работает на `ghcr.io/blockfield/blockfield-proxy`. Прежний образ `ghcr.io/netherg-io/blockfield-proxy:latest` (v2.54.0) с тем же digest лежит в `ghcr.io/blockfield/blockfield-proxy:v2.54.0`: откат на него — `Deploy proxy tag=v2.54.0`. Образ в netherg-io не удалять — запасной путь через Dokploy.

## Транспорт клиента

Игрок входит только по RakNet (решение владельца 2026-10-18): адрес
`raknet;play.blockfield.pro:25566`, Raknetify `0.1.0+alpha.5.196` (Velocity,
immutable Modrinth version `u3LRuC7t`, SHA256 закреплён в Dockerfile). TCP
`play.blockfield.pro:25565` обслуживает только status ping (launcher >= 1.0.11
пингует `raknet;` адрес по TCP 25565 того же хоста): `PreLoginEvent` отклоняет
login без pipeline handler `raknetify-velocity-event-listener`, который Raknetify
добавляет только каналам `RakNet$Config` (`RaknetOnly`). Исключений по адресу нет:
локальные стенды подключаются напрямую к backend, минуя Velocity. Если внутренности
Velocity/Raknetify после обновления не совпали, proxy пишет ERROR
`Velocity/Raknetify internals mismatch` и отклоняет **каждый** вход (fail closed,
решение владельца 2026-10-18); status ping не затронут. При обновлении Velocity или
Raknetify перепроверьте вход по RakNet и отказ TCP.
Backend Fabric не требует Raknetify: UDP завершается на Velocity, внутреннее
соединение с game/workshop остаётся TCP.

Velocity запускается с `-Draknetify.velocity.portOverride=25566` через `JVM_OPTS`.
При настройке `JVM_OPTS` в Dokploy сохраните эту property. UDP 25565 занят Simple
Voice Chat; его конфигурация не меняется.

Dokploy `blockfield-proxy-niocmj` публикует **UDP 25566 → 25566, mode host** (порт
открыт в firewall), TCP 25565 (status ping) и UDP 25565 (voice chat); `EXPOSE` не
публикует порт сам. Не снимайте TCP 25565: без него launcher не видит статус сервера.
Изменение Dokploy и Deploy proxy выполняет владелец отдельно; PR их не выполняет.

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
игры. Укажите в `workshopServer` тот же хост, что в `BLOCKFIELD_WORKSHOP_HOST`, в
виде `raknet;<host>:25566`: вход по TCP отклоняется и для мастерской. Хосты игры и
мастерской должны различаться: разные порты на одном хосте не разделяют профили. Адрес мастерской нужно
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

### Спящая мастерская

Когда контроллер останавливает мастерскую для экономии памяти, он публикует
состояние gate `sleeping` (после пробуждения — `STARTING`, пока backend
поднимается). Прокси такое состояние никогда не открывает: участник мастерской
остаётся в лобби (или отключается с тем же текстом, если лобби нет — как при
закрытом gate) и видит «Мастерская просыпается, это займёт 1–2 минуты.
Переподключитесь позже.».

При `sleeping` прокси не чаще одного раза в 60 с (на весь прокси) кладёт в
инбокс контроллера запрос `wake` атомарно (временный файл в том же каталоге +
rename): `{schema:1,id,kind:"wake",actor:{draslId,name},createdAt}`, имя файла
совпадает с `id`. Без примонтированного инбокса — только сообщение, без
запроса. Для `STARTING` запрос не пишется: пробуждение уже идёт. Запись идёт
через планировщик Velocity, поток событий не блокируется.

Хостовый инбокс `/srv/minecraft/workshop/inbox` (root:1000 0770) монтируется в
контейнер прокси read-write в `/workshop-inbox`, в окружении —
`BLOCKFIELD_WORKSHOP_INBOX_DIR=/workshop-inbox`. Монтирование и переменную
добавляет владелец в Dokploy; PR их не меняет.

Версии карты Limbo хранятся в общей истории Anvil; схема для запуска — производный экспорт.
Подключение выбранной версии и проверка её spawn описаны в [LIMBO.md](docs/LIMBO.md).

Условия использования: [LICENSE](LICENSE).
