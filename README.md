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

Версионированные хуки (`.githooks` через `core.hooksPath`): pre-commit форматирует
staged-файлы теми же закреплёнными форматтерами (`just format-staged`, без Gradle)
и пере-stage'ит их, поэтому коммит всегда отформатирован. Хуки включаются
автоматически при любом `just`-вызове: свежий клон получает хуки на первом `just`
(обход только через `--no-verify`).
Если git уже берёт хуки из другого места (`core.hooksPath` в любом конфиге или
активные хуки в `.git/hooks`), `just` их не трогает и только предупреждает.
Если записать `core.hooksPath` не удалось, `just setup` завершается ошибкой.

Blacksmith проверяет main и PR. Тег `v*` публикует `ghcr.io/blockfield/blockfield-proxy:<tag>` и GitHub Release с описанием. Описание (`release-notes.md`) и анонс для Discord (`announcement.json`) генерирует CI action `release-text` из слитых PR промежутка; при сбое остаётся текст из фактов, тег не падает. Deploy proxy запускается вручную: обновление прокси прерывает подключения игроков. Клиентские и серверные релизы его не перезапускают. Деплой идёт через webhook Dokploy (секрет `DOKPLOY_PROXY_WEBHOOK`, API-ключа Dokploy в Actions нет): релизный канал `ghcr.io/blockfield/blockfield-proxy:prod` (им же настроен Dokploy; main-сборки его не трогают — `build.yml` пушит только теги версий) переставляется на digest тега, при ошибке — обратно на работавший digest (откат — повторный Deploy proxy с прежним тегом). Схема — [server/docs/DEPLOY.md](https://github.com/Blockfield/blockfield-server/blob/main/docs/DEPLOY.md).

С v2.54.1 (2026-09-27) прод работает на `ghcr.io/blockfield/blockfield-proxy`. Прежний образ `ghcr.io/netherg-io/blockfield-proxy:latest` (v2.54.0) с тем же digest лежит в `ghcr.io/blockfield/blockfield-proxy:v2.54.0`: откат на него — `Deploy proxy tag=v2.54.0`. Образ в netherg-io не удалять — запасной путь через Dokploy.

## Транспорт клиента

Основной вход — RakNet: Game — `raknet;play.blockfield.pro:25566`, Workshop —
`raknet;workshop.blockfield.pro:25567`. Raknetify — сборка Blockfield `bf3`
(`0.1.0+alpha.5.1-bf3`, GitHub Release `Blockfield/raknetify`, SHA256 закреплён в Dockerfile,
`tests/test_raknetify_pin.py` проверяет пин): upstream `76ab30a` + фикс утечки SYNC FrameData +
второй UDP-порт мастерской (`raknetify.velocity.workshopPort`). Клиентский пак закрепляет fabric-jar
того же релиза. TCP `play.blockfield.pro:25565` обслуживает status ping (launcher >= 1.0.11 пингует `raknet;`
адрес по TCP 25565 того же хоста) и принимает вход для
игроков, у которых режут UDP (proxy#74): TCP-вход проходит тот же Drasl-шлюз и всегда попадает в
Game (порт 25565 не порт мастерской). RakNet-вход `PreLoginEvent` узнаёт по pipeline handler
`raknetify-velocity-event-listener`, который Raknetify добавляет только каналам `RakNet$Config`
(`RaknetOnly`). Исключений по адресу нет: локальные стенды подключаются напрямую к backend, минуя
Velocity. Если внутренности Velocity/Raknetify после обновления не совпали, proxy пишет ERROR
`Velocity/Raknetify internals mismatch` и отклоняет **каждый** вход (fail closed, решение владельца
2026-10-18): из тех же внутренностей берётся порт, выбирающий Game или Workshop; status ping не
затронут. Отказы входа считаются по причинам; в лог (WARN `login-rejections first <причина>`)
попадает только первый отказ каждой причины с текущими счётчиками. При обновлении Velocity или
Raknetify перепроверьте вход по RakNet (оба порта) и по TCP.
Backend Fabric не требует Raknetify: UDP завершается на Velocity, внутреннее
соединение с game/workshop остаётся TCP.

Velocity получает RakNet-порты флагами `-Draknetify.velocity.portOverride` и
`-Draknetify.velocity.workshopPort`, которые собирает `start-proxy.sh` из env
при старте контейнера (та же идея, что интерполяция velocity.toml у itzg):
`BLOCKFIELD_RAKNET_GAME_PORT` (default `25566`) и
`BLOCKFIELD_RAKNET_WORKSHOP_PORT` (default `25567`). Dockerfile больше не
хардкодит порты, а `JVM_OPTS` из Dokploy чисто добавочный: лишние java-флаги
уже не могут уронить порты. UDP 25565 занят Simple Voice Chat; его
конфигурация не меняется.

Dokploy `blockfield-proxy-niocmj` публикует **UDP 25566 → 25566 и UDP 25567 → 25567,
mode host** (порты открыты в firewall), TCP 25565 (status ping и TCP-вход) и UDP 25565
(voice chat); `EXPOSE` не публикует порт сам. Не снимайте TCP 25565: без него
launcher не видит статус сервера.
Изменение Dokploy и Deploy proxy выполняет владелец отдельно; PR их не выполняет. `BLOCKFIELD_WORKSHOP_HOST` (имя хоста мастерской) остаётся для
совместимости ping-выдачи; выбор профиля при входе — только по входящему
RakNet-порту: порт `BLOCKFIELD_RAKNET_WORKSHOP_PORT` (default `25567`)
означает мастерскую, остальные RakNet-порты — игру. Порт игры задаётся
`BLOCKFIELD_RAKNET_GAME_PORT` (default `25566`). Оба значения видны и
меняются в Dokploy; дефолты совпадают с продом, поэтому отдельная настройка
не требуется.

Raknetify all-in-one распространяется под **GPLv3** (включает netty-raknet);
исходники и условия: [Raknetify](https://github.com/RelativityMC/raknetify),
[Modrinth](https://modrinth.com/mod/raknetify). Эта лицензия относится к стороннему
JAR и не заменяется LICENSE Blockfield. При распространении образа сохраняйте
лицензию и предоставляйте соответствующие исходники и notices зависимостей.

## Плагин Velocity

[`BlockfieldProxy`](proxy/plugin/src/main/java/io/blockfield/proxy/BlockfieldProxy.java) регистрирует три модуля:

- [`Authentication`](proxy/plugin/src/main/java/io/blockfield/proxy/Authentication.java): Drasl, `/login`, `/register`, `/logout`, сохранённые сессии и лимиты попыток.
- [`BackendRouting`](proxy/plugin/src/main/java/io/blockfield/proxy/BackendRouting.java): гейт control-plane (push/ACK), ACL мастерской, лобби и возврат после рестарта.
- [`LoginForwarding`](proxy/plugin/src/main/java/io/blockfield/proxy/LoginForwarding.java): ответы о версии пака и контракте совместимости, а также перенос конфигурации TaCZ Tweaks из login в play.

Drasl UUID подтверждается до ответа Minecraft LoginSuccess и используется в современном
Velocity forwarding для обоих backend. Вход по токену проверяет `hasJoined`; сохранённая
сессия проверяет HMAC свежего challenge и совпадение UUID в подписанном JWT с текущим
профилем точного ника у Drasl. Старые сессии без UUID нужно подтвердить через `/login` заново;
смена владельца ника не переносит сохранённый вход на другой аккаунт.
Некорректный UUID или недоступный профиль оставляет игрока без доступа в backend.
Drasl — жёсткая зависимость каждого входа, включая сохранённые сессии без grace:
пока Drasl недоступен или медленнее 5 с, игрок с валидной сессией ждёт в лобби
(и может войти через `/login`, когда Drasl вернётся), в backend не пускает никого.
После первого `/login` или `/register` в Limbo клиент сохраняет сессию: нужно вручную
переподключиться к тому же адресу. До переподключения offline UUID не допускается в backend;
без секрета сохранённых сессий нужен вход через лаунчер Blockfield. Автоматического retry
после отключения в игре нет. `/logout` очищает сохранённую сессию и во время ожидания переподключения.

`Authentication` и `LoginForwarding` очищают данные при отключении игрока; маршрутизация сохраняет недавнее участие в игре на десять минут для доигрывания Кампании. `Gate` хранит правила доступа, `Sessions` — проверку HMAC. Модули собираются в один плагин. Limbo остаётся внутри контейнера прокси.

### Адреса игровых профилей

`BLOCKFIELD_WORKSHOP_HOST` задаёт отдельное имя хоста или IP мастерской на этом
прокси, без порта и `raknet;`. IPv6 можно записать в квадратных скобках. Регистр
DNS-имени и завершающая точка не влияют на сравнение; числовые IPv6 сравниваются
в нормализованном виде, без DNS-запросов. Некорректное значение не роняет плагин:
мастерская отключается, как при пустом значении, а в лог пишется ошибка
(ронять весь плагин нельзя — незарегистрированные gate-хуки открывали бы
обход авторизации через `/server`). Пустое значение отключает вход в мастерскую.

В лаунчере профиль `workshop` использует `workshopServer` (необязательное начальное
значение `BLOCKFIELD_WORKSHOP_SERVER`), профиль `game` — опубликованный адрес
игры. Укажите в `workshopServer` RakNet-адрес мастерской
(`raknet;workshop.blockfield.pro:25567`), в game — `raknet;play.blockfield.pro:25566`.
Адрес мастерской нужно явно настроить с обеих сторон; DNS и развёртывание этим
репозиторием не создаются. Разные хосты на одном порту профили не разделяют
(клиент 1.21.1 присылает rDNS/IP вместо набранного хоста) — профили разделяют
только входящие RakNet-порты.

Прокси выбирает профиль по входящему RakNet-порту (`Player.getVirtualHost` для
выбора больше не используется): порт `BLOCKFIELD_RAKNET_WORKSHOP_PORT`
(default `25567`)
означает мастерскую, остальные порты (включая TCP 25565) — игру. `BLOCKFIELD_WORKSHOP_HOST`
остался только для совместимости ping-выдачи. Этот выбор не даёт прав: авторизация Drasl, ACL и гейт control-plane
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

Если `backends` присутствует, проверка хостового файла (deploy probe
`entry_liveness.py --gate-file`) читает только запись выбранного backend:
отсутствующая или некорректная запись закрывает его, без возврата к верхним полям.
Некорректный объект `backends` закрывает оба входа. Для игры допустимы только
`public`, `draining`, `closed`, для мастерской — `builders`, `testers`, `closed`;
состояние другого backend закрывает выбранный вход. ACL мастерской по-прежнему
требует совпадения Drasl UUID и имени; `testers` ограничивает вход аналогично
`builders`. Старый файл без `backends` сохраняет прежний единственный активный
backend.

CP-C2 cutover: прокси больше не читает этот файл — гейт приходит только из
control-plane push/ACK (`ControlSubscriber`); хостовый файл остаётся для
deploy-проверки и будет удалён отдельным юнитом.

При обрыве control-plane прокси продолжает пускать по последнему проверенному
снимку (fail-static), а не закрывает вход через 60 с: переход пишется в лог
разово со счётчиком (`control-plane v2 fail-static ... (transition N)`),
следующий проверенный снимок или кадр возвращает обычный режим. Снимок старше
`BLOCKFIELD_CP_FAIL_STATIC_MAX_AGE_MS` (по умолчанию 24 ч) закрывает вход
снова; снимок или кадр, не прошедший проверку, не обслуживается никогда.

`BLOCKFIELD_CP_TOKEN` прокси продлевает сам: за 7 дней до истечения (для коротких
тестовых сроков — за четверть срока) запрашивает у control-plane новую учётку,
сохраняет её в `BLOCKFIELD_CP_STATE_FILE` (по умолчанию
`/workshop-gate/.proxy-cp-credential.json`, права 0600) и только потом подтверждает,
после чего старая отзывается. При старте файл важнее env, пока он продолжает цепочку
от текущего env-токена; новый токен в env начинает цепочку заново.

`BLOCKFIELD_OWNER_ALERT_URL` (секрет, задаёт владелец в Dokploy; пустое значение —
поведение по умолчанию — полностью отключает отправку): webhook, куда прокси
разово сообщает, когда игровой вход закрыт дольше 2 минут вне объявленного
техобслуживания или control-plane недоступен дольше 2 минут, и когда состояние
восстановилось. Формат — JSON с одинаковым текстом в полях `text`/`content`/`message`
(прямо подходят Discord, Slack и ntfy-совместимые приёмники; для штатного Telegram
Bot API нужен релей из-за `chat_id`).

`BLOCKFIELD_WORKSHOP_PACK_CONTRACT` задаёт ожидаемый хэш Workshop-пака (contract-часть
ответа `blockfield:pack_contract`, тот же формат, что `compatibility` в build.json):
на Workshop-леге входит только Workshop-пак, игровой или ванильный клиент видит
«Нужен пак мастерской: обновите Workshop-профиль в лаунчере». Пустое значение отключает
проверку; игровой лег не проверяется никогда. Хэши в код не зашиты. Значение задаёт
владелец в Dokploy из `compatibility` в `workshop-build.json` клиентского релиза
(server#233 U3 публикует его в blockfield-releases; тот же хэш печатает шаг
«Record workshop pack hashes» тегового прогона); PR его не меняет.

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
