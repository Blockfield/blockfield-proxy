"""Единый формат release-notes.md: генерация скелета, сверка пинов и проверка.

Скелет готовится из сверки пинов (mods/*.pw.toml, resources.lock.json) между
предыдущим и новым релизом той же линии и подтягивает release-notes.md
компонентов за весь промежуток версий. Текст разделов пишет человек в
релизном PR; CI только проверяет формат/соответствие версии и публикует файл.
Вызов внешней модели не нужен.
"""

import argparse
import io
import json
import re
import subprocess
import sys
import tarfile
from pathlib import Path

SCHEMA_VERSION = 1
KINDS = ('client', 'server', 'launcher', 'mod', 'resource')
REQUIRED_KEYS = ('schema_version', 'repository', 'version', 'previous_version',
                 'date', 'kind', 'backfilled')
REQUIRED_SECTIONS = ('Кратко', 'Для игроков', 'Технические изменения',
                     'Обновлённые компоненты', 'Совместимость и необходимые действия',
                     'Известные проблемы', 'Источники и ограничения полноты')

VERSION_RE = {'client': r'v\d+\.\d+\.\d+', 'server': r'v\d+\.\d+\.\d+',
              'mod': r'v\d+\.\d+\.\d+|bf\d+', 'resource': r'.+',
              'launcher': r'launcher-v\d+\.\d+\.\d+'}
# Незаполненный скелет не должен уйти в релиз.
PLACEHOLDER_RE = re.compile(r'\bTODO\b|ВНИМАНИЕ:')
RELEASE_URL_RE = re.compile(r'https://github\.com/([^/]+/[^/]+)/releases/download/([^/]+)/')


def version_key(tag):
    """(семейство, номера) для vX.Y.Z, bfN, music-vN, launcher-vX.Y.Z; иначе None.

    Сравнивать можно только ключи одного семейства: import-2.53.0 → bf9 — не откат.
    """
    m = re.fullmatch(r'([a-z]+-)?(v|bf)(\d+(?:\.\d+)*)', tag or '')
    return ((m.group(1) or '') + m.group(2), tuple(map(int, m.group(3).split('.')))) if m else None


def comparable(a, b):
    return a is not None and b is not None and a[0] == b[0]


def pin_from(url, name, filename, sha):
    m = RELEASE_URL_RE.match(url)
    # Modrinth и прочие CDN: версия — имя файла.
    return {'name': name, 'filename': filename, 'url': url, 'hash': sha,
            'repo': m.group(1) if m else None, 'tag': m.group(2) if m else filename}


def read_pins(repo_root, ref):
    """Пины сборки на ref: mods/*.pw.toml + resources.lock.json."""
    import tomllib  # только здесь: check должен работать на Python 3.10 (ubuntu-22.04)
    pins = {}
    raw = subprocess.check_output(['git', '-C', str(repo_root), 'archive', ref, 'mods'])
    with tarfile.open(fileobj=io.BytesIO(raw)) as tar:
        for member in tar.getmembers():
            if not member.name.endswith('.pw.toml'):
                continue
            mod = tomllib.loads(tar.extractfile(member).read().decode())
            download = mod.get('download') or {}
            pins[Path(member.name).name[:-len('.pw.toml')]] = pin_from(
                download.get('url', ''), mod.get('name', member.name),
                mod.get('filename', ''), download.get('hash', ''))
    try:
        lock = json.loads(subprocess.check_output(
            ['git', '-C', str(repo_root), 'show', f'{ref}:resources.lock.json'],
            stderr=subprocess.DEVNULL))
    except subprocess.CalledProcessError:
        lock = []
    for entry in lock:
        name = entry['url'].rsplit('/', 1)[-1]
        pins[f'resource:{name}'] = pin_from(entry['url'], name, name, entry['sha256'])
    return pins


def pin_diff(old_pins, new_pins):
    """(key, change, old, new, text); change: added/removed/url-change/re-pinned/rollback/update.

    Решает хеш, а не тег: перенос тех же байтов в другой репозиторий/тег — url-change.
    """
    rows = []
    for key in sorted(set(old_pins) | set(new_pins)):
        old, new = old_pins.get(key), new_pins.get(key)
        if old is None:
            rows.append((key, 'added', None, new, f"Добавлен {new['name']} ({new['tag']})."))
        elif new is None:
            rows.append((key, 'removed', old, None, f"Удалён {old['name']} ({old['tag']})."))
        elif old['hash'] == new['hash']:
            if old['url'] != new['url']:
                rows.append((key, 'url-change', old, new,
                             f"{new['name']}: байты те же ({old['tag']}), изменён только адрес загрузки."))
        elif old['tag'] == new['tag']:
            rows.append((key, 're-pinned', old, new,
                         f"{new['name']}: {new['tag']} пересобран (хеш изменился)."))
        else:
            ko, kn = version_key(old['tag']), version_key(new['tag'])
            change = 'rollback' if comparable(ko, kn) and kn < ko else 'update'
            word = 'откат' if change == 'rollback' else 'обновление'
            rows.append((key, change, old, new, f"{new['name']}: {word} {old['tag']} → {new['tag']}."))
    return rows


def section(text, title):
    m = re.search(rf'(?m)^##\s+{re.escape(title)}\s*$(.*?)(?=^##\s|\Z)', text, re.S)
    return m.group(1).strip() if m else ''


def gh_json(*args):
    return json.loads(subprocess.check_output(['gh', 'api', '--paginate', '--slurp', *args]))


def component_notes(old, new):
    """Выдержки release-notes.md компонента за промежуток и список пробелов.

    Обновление: версии (old, new]; откат: (new, old] — эти изменения сняты.
    """
    repo = new['repo'] if new['repo'] == old['repo'] else None
    ko, kn = version_key(old['tag']), version_key(new['tag'])
    if not repo or not comparable(ko, kn):
        return [], [f"{new['name']}: промежуток {old['tag']} → {new['tag']} не сверен автоматически"]
    lo, hi = sorted((ko, kn))
    releases = [r for page in gh_json(f'repos/{repo}/releases?per_page=100') for r in page
                if not r['draft'] and comparable(version_key(r['tag_name']), lo) and lo < version_key(r['tag_name']) <= hi]
    releases.sort(key=lambda r: version_key(r['tag_name']))
    prefix = 'снято откатом: ' if kn < ko else ''
    lines, gaps = [], []
    for rel in releases:
        asset = next((a for a in rel['assets'] if a['name'] == 'release-notes.md'), None)
        if not asset:
            gaps.append(f"{repo} {rel['tag_name']}: нет release-notes.md")
            continue
        text = subprocess.check_output(['gh', 'api', '-H', 'Accept: application/octet-stream',
                                        f"repos/{repo}/releases/assets/{asset['id']}"], text=True)
        for title in ('Кратко', 'Для игроков'):
            body = section(text, title)
            if body:
                lines.append(f"    - {prefix}{rel['tag_name']} — {title}: " + ' '.join(body.split()))
    return lines, gaps


def parse_front_matter(text):
    if not text.startswith('---\n'):
        return None, 'нет YAML-заголовка (файл должен начинаться с ---\n)'
    end = text.find('\n---\n', 4)
    if end < 0:
        return None, 'YAML-заголовок не закрыт строкой ---\n'
    meta = {}
    for lineno, line in enumerate(text[4:end].split('\n'), 1):
        if not line.strip() or line.strip().startswith('#'):
            continue
        m = re.fullmatch(r'([A-Za-z_]+):\s*(.*)', line.strip())
        if not m:
            return None, f'заголовок, строка {lineno}: ожидается `ключ: значение`, got {line!r}'
        key, value = m.group(1), m.group(2).strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in '"\'':
            value = value[1:-1]
        meta[key] = value
    return meta, None


def check(path, repository=None, version=None, kind=None):
    """Проверить release-notes.md. Возвращает список ошибок (пусто = ок)."""
    try:
        text = Path(path).read_text(encoding='utf-8')
    except OSError as exc:
        return [f'не читается: {exc}']
    meta, err = parse_front_matter(text)
    if err:
        return [err]
    errors = [f'заголовок: отсутствует ключ `{key}`' for key in REQUIRED_KEYS if key not in meta]
    if errors:
        return errors
    body = text.split('\n---\n', 1)[1]
    if meta['schema_version'] != str(SCHEMA_VERSION):
        errors.append(f'заголовок: schema_version={meta["schema_version"]!r}, ожидается {SCHEMA_VERSION}')
    if repository and meta['repository'] != repository:
        errors.append(f'заголовок: repository={meta["repository"]!r}, ожидается {repository!r}')
    if version and meta['version'] != version:
        errors.append(f'заголовок: version={meta["version"]!r}, ожидается {version!r}')
    if meta['kind'] not in KINDS:
        errors.append(f'заголовок: kind={meta["kind"]!r}, ожидается одно из {", ".join(KINDS)}')
    elif kind and meta['kind'] != kind:
        errors.append(f'заголовок: kind={meta["kind"]!r}, ожидается {kind!r}')
    pattern = VERSION_RE.get(meta['kind'], r'.+')
    if not re.fullmatch(pattern, meta['version']):
        errors.append(f'заголовок: version={meta["version"]!r} не соответствует {pattern} для kind={meta["kind"]}')
    if not meta['previous_version']:
        errors.append('заголовок: previous_version должен быть тегом или null')
    elif meta['previous_version'] == meta['version']:
        errors.append('заголовок: previous_version совпадает с version')
    if not re.fullmatch(r'\d{4}-\d{2}-\d{2}', meta['date']):
        errors.append(f'заголовок: date={meta["date"]!r}, ожидается YYYY-MM-DD')
    if meta['backfilled'] not in ('true', 'false'):
        errors.append('заголовок: backfilled должен быть true/false')
    for title in REQUIRED_SECTIONS:
        if not re.search(rf'(?m)^##\s+{re.escape(title)}\s*$', body):
            errors.append(f'раздел отсутствует: `## {title}`')
    if not section(body, 'Источники и ограничения полноты'):
        errors.append('раздел «Источники и ограничения полноты» пуст')
    for lineno, line in enumerate(text.split('\n'), 1):
        if PLACEHOLDER_RE.search(line):
            errors.append(f'строка {lineno}: незаполненный скелет ({line.strip()[:60]!r})')
    if re.search(r'(?i)изменений для игроков нет', body) and \
            not re.search(r'(?i)подтвержд', section(body, 'Для игроков') + section(body, 'Источники и ограничения полноты')):
        errors.append('«Изменений для игроков нет» без указания подтверждения в разделе/источниках')
    return errors


def skeleton(repository, version, previous_version, kind, date, components=()):
    lines = ['---', 'schema_version: 1', f'repository: {repository}', f'version: {version}',
             f'previous_version: {previous_version or "null"}', f'date: {date}',
             f'kind: {kind}', 'backfilled: false', '---', '', f'# {repository} {version}', '',
             '## Кратко', '', 'TODO: 2–4 предложения: что это за выпуск и зачем обновляться.', '',
             '## Для игроков', '',
             'TODO. Новое / исправления / изменения поведения своими словами по выдержкам компонентов ниже.',
             'Нет игровых изменений — только с подтверждением (например, совпал клиентский digest).', '',
             '## Технические изменения', '', 'TODO.', '', '## Обновлённые компоненты', '']
    gaps = []
    for _key, change, old, new, text in components:
        lines.append(f'- {text}')
        if change in ('update', 'rollback'):
            try:
                notes, missing = component_notes(old, new)
            except (subprocess.CalledProcessError, OSError, json.JSONDecodeError) as exc:
                notes, missing = [], [f"{new['name']}: release-notes не получены ({exc})"]
            lines += notes
            gaps += missing
    if not components:
        lines.append('Пины не изменились относительно предыдущего релиза (mods/*.pw.toml, resources.lock.json).')
    for gap in gaps:
        lines.append(f'- ВНИМАНИЕ: {gap} — восполнить или перенести в «Источники и ограничения полноты».')
    lines += ['', '## Совместимость и необходимые действия', '',
              'TODO: совместимость, нужен ли вайп/перезаход/обновление лаунчера.', '',
              '## Известные проблемы', '', 'TODO: известные проблемы или «Неизвестны».', '',
              '## Источники и ограничения полноты', '',
              f'- Сверка пинов {previous_version or "—"} → {version}.',
              '- TODO: релизный PR, коммиты, release-notes компонентов; явно указать, чего не хватает.', '']
    return '\n'.join(lines)


def main():
    parser = argparse.ArgumentParser(description='Скелет и проверка release-notes.md')
    sub = parser.add_subparsers(dest='cmd', required=True)
    c = sub.add_parser('check')
    c.add_argument('file')
    c.add_argument('--repository')
    c.add_argument('--version')
    c.add_argument('--kind', choices=KINDS)
    s = sub.add_parser('skeleton')
    s.add_argument('--repository', required=True)
    s.add_argument('--version', required=True, help='тег нового релиза (или ref, пока тега нет: HEAD)')
    s.add_argument('--ref', help='git ref нового состояния пинов (по умолчанию --version)')
    s.add_argument('--previous-version', default=None)
    s.add_argument('--kind', required=True, choices=KINDS)
    s.add_argument('--date', required=True)
    s.add_argument('--repo-root', default='.')
    args = parser.parse_args()
    if args.cmd == 'check':
        errs = check(args.file, args.repository, args.version, args.kind)
        for err in errs:
            print(f'ERROR: {err}')
        sys.exit(1 if errs else 0)
    rows = []
    if args.previous_version and Path(args.repo_root, 'mods').is_dir():
        rows = pin_diff(read_pins(args.repo_root, args.previous_version),
                        read_pins(args.repo_root, args.ref or args.version))
    print(skeleton(args.repository, args.version, args.previous_version, args.kind, args.date, rows))


if __name__ == '__main__':
    main()
