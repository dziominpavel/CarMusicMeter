#!/usr/bin/env python3
"""Импорт выгрузки CarMusicMeter в локальный архив `data/` (в git не попадает).

Один раз импортированная выгрузка остаётся на ПК: отчёт читает архив, а не
флешку. Новые дни приезжают новым ZIP, старые в нём уже есть, поэтому
повторно приносить предыдущие выгрузки не нужно.

    py scripts/import_export.py "G:\\CarMusicMeter-<id>-all-20261106-1816.zip"
    py scripts/import_export.py data\\exports\\2026-11-04        # папку тоже можно
    py scripts/report.py data\\exports\\*

Имя импорта по умолчанию — дата выгрузки из имени ZIP (…-all-YYYYMMDD-HHMM.zip)
или первый день в журнале.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
EXPORTS = DATA / "exports"
ZIPS = DATA / "zips"

STAMP = re.compile(r"-all-(\d{8})-(\d{4})\.zip$", re.IGNORECASE)
DAY = re.compile(r"^\d{4}-\d{2}-\d{2}$")


def read_manifest(folder: Path) -> dict:
    path = folder / "manifest.json"
    if not path.is_file():
        return {}
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except ValueError:
        return {}


def day_from_name(path: Path) -> str | None:
    match = STAMP.search(path.name)
    if not match:
        return None
    raw = match.group(1)
    return f"{raw[:4]}-{raw[4:6]}-{raw[6:]}"


def logs_in(folder: Path) -> list[str]:
    logs = folder / "logs"
    if not logs.is_dir():
        return []
    return sorted(p.stem[len("events-"):] for p in logs.glob("events-*.jsonl"))


def first_day(folder: Path) -> str | None:
    for day in logs_in(folder):
        if DAY.match(day):
            return day
    return None


def unpack(archive: Path, target: Path) -> None:
    """Распаковка без выхода за пределы target."""
    with zipfile.ZipFile(archive) as zf:
        root = target.resolve()
        for member in zf.namelist():
            path = (target / member).resolve()
            if not str(path).startswith(str(root)):
                raise ValueError(f"подозрительный путь в архиве: {member}")
        zf.extractall(target)


def copy_tree(source: Path, target: Path) -> None:
    shutil.copytree(source, target, dirs_exist_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Импорт выгрузки CarMusicMeter в data/")
    parser.add_argument("source", help="ZIP-выгрузка или распакованная папка")
    parser.add_argument("--name", help="имя импорта (по умолчанию дата выгрузки)")
    parser.add_argument("--force", action="store_true", help="перезаписать существующий импорт")
    args = parser.parse_args()

    source = Path(args.source).expanduser()
    if not source.exists():
        print(f"[ERROR] нет файла или папки: {source}")
        return 1

    is_zip = source.is_file() and source.suffix.lower() == ".zip"
    if source.is_file() and not is_zip:
        print(f"[ERROR] ожидается ZIP или папка выгрузки: {source}")
        return 1

    name = args.name
    if not name:
        name = day_from_name(source) if is_zip else None
        if not name and is_zip and DAY.match(source.stem):
            name = source.stem
        name = name or (first_day(source) if source.is_dir() else None)
        name = name or f"import-{source.stem}"
    if not DAY.match(name) and not re.fullmatch(r"[a-z0-9._-]+", name):
        print(f"[ERROR] имя импорта содержит недопустимые символы: {name}")
        return 1

    target = EXPORTS / name
    if target.exists() and not args.force:
        print(f"[ERROR] импорт {name} уже есть: {target}")
        print("        перезаписать: --force; добавить как другой: --name <имя>")
        return 1

    EXPORTS.mkdir(parents=True, exist_ok=True)
    ZIPS.mkdir(parents=True, exist_ok=True)

    if is_zip:
        dest_zip = ZIPS / f"{name}.zip"
        if source.resolve() != dest_zip.resolve():
            shutil.copy2(source, dest_zip)
        if target.exists():
            shutil.rmtree(target)
        unpack(source, target)
    else:
        copy_tree(source, target)

    manifest = read_manifest(target)
    days = logs_in(target)
    print(f"[OK] импорт {name}: {target}")
    if days:
        print(f"     журналы: {', '.join(days)}")
    if manifest.get("deviceId"):
        print(f"     устройство: {manifest.get('deviceId')}, "
              f"версия: {manifest.get('appVersion')} ({manifest.get('versionCode')})")
    print()
    print("Отчёт по всем импортам:")
    print(r"    py scripts\report.py data\exports\*")
    return 0


if __name__ == "__main__":
    sys.exit(main())
