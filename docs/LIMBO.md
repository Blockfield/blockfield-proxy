# Версии карты Limbo

История карты принадлежит [workshop](https://github.com/Blockfield/blockfield-workshop/blob/main/docs/OPERATIONS.md): Anvil, `map.json`, manifest и независимый HEAD карты `limbo`. Sponge-v2 `.schem` — проверенный производный файл для закреплённого Limbo; он не заменяет архив или рабочую копию карты.

## Подключение

На старте `proxy/start-proxy.sh` запускает `LimboSelection` перед открытием портов.
Без `BLOCKFIELD_LIMBO_SELECTION_FILE` используется схема из образа. Для общей истории нужны все три переменные:

| Переменная                        | Значение в контейнере               | Host mount                                                        |
| --------------------------------- | ----------------------------------- | ----------------------------------------------------------------- |
| `BLOCKFIELD_MAP_ACTIVE_DIR`       | `/maps-active`                      | `/srv/minecraft/maps-active`, read-only, SSD                      |
| `BLOCKFIELD_LIMBO_SELECTION_FILE` | `/maps-active/limbo/selection.json` | Часть того же каталога; отдельный bind файла мешает atomic rename |
| `BLOCKFIELD_LIMBO_ARTIFACTS_DIR`  | `/limbo-artifacts`                  | `/mnt/media-hdd/blockfield/maps/limbo/artifacts`, read-only, HDD  |

Текущий контейнер читает только эти mounts. Нужная схема копируется в его собственный `/opt/lobby/lobby.schem`, а spawn записывается в `/opt/lobby/server.properties`. Остальные properties сохраняются. Переменные и новые mounts требуют отдельного согласованного rollout; наличие кода не подтверждает их настройку в production.

## Контракт

Контроллер атомарно пишет `limbo/selection.json`:

```json
{
  "schema": 1,
  "mapId": "limbo",
  "versionDigest": "<SHA256 manifest.json>",
  "configSha256": "<SHA256 map.json>",
  "schematicSha256": "<SHA256 производной схемы>",
  "artifact": "<versionDigest>-<schematicSha256>.schem",
  "format": "Sponge-v2",
  "width": 176,
  "height": 48,
  "length": 176,
  "spawn": { "x": 88.5, "y": 17, "z": 99.5, "yaw": 180, "pitch": 0 }
}
```

Размеры и spawn берутся из `map.json.mode_data.limbo`; `bounds` имеет `minX/minZ = 0`, `maxX = width`, `maxZ = length`. Максимумы: ширина/длина/высота 256, объём 2 000 000 блоков, сжатая схема 4 МиБ. Spawn находится внутри объёма; yaw/pitch конечны, pitch от −90 до 90, отсутствующие yaw/pitch равны нулю.

Reader связывает selection с `limbo/head.json`, manifest выбранного `limbo/versions/<versionDigest>` и checksum `map.json` из manifest. Проверяет размеры, spawn, имя и checksum схемы, запрещает symlink и повторно читает HEAD/selection перед записью. При отсутствующих, битых или несовместимых данных настроенный запуск завершается с ошибкой. Тихого перехода на старую схему нет.

Формат Sponge-v2 и содержимое экспорта проверяет trusted adapter workshop. Прокси проверяет целостность полученного неизменяемого файла; собственный конвертер Anvil/NBT ему не нужен. JSON читает Gson, который уже находится в собранном Limbo JAR; новых runtime-зависимостей нет.

## Выбор и откат

Экспорт всегда привязан к точной неизменяемой версии, а не к редактируемому draft. Контроллер выбирает HEAD с `expectedHead` и требует готовый производный экспорт и доказанно остановленный настроенный proxy. Новый spawn и геометрия применяются вместе на следующем запуске. Старые версии и производные файлы сохраняются.

Остановка и ручной Deploy прокси прерывают подключения игроков и требуют явного подтверждения владельца. Эти операции не выполняются проверкой документации или сборкой.

При изменении контракта обновляйте этот документ, руководство workshop и [карту workspace](https://github.com/Blockfield/blockfield-workspace/blob/main/docs/ARCHITECTURE.md). Проверяйте producer, controller и proxy вместе.

Проверки: `just check`, `just build`. `LimboSelectionTest` проверяет привязку версии/config/spawn, сохранение остальных properties и отказ до замены runtime при повреждённом файле или устаревшем HEAD. Runtime приёмка экспорта и перезапуска ведётся отдельно.
