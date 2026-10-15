"""Массовая загрузка набора из data/ тем же путём, что и одиночный POST /documents.

Скрипт ходит в работающий API, а не в базу: дедупликация по хэшу, запуск
разбора и статусы остаются ровно теми же, что у клиента. Повторный запуск
безопасен — дубликаты по content_hash API отдаёт как «already indexed».

Запуск: make bulk  (uv run python scripts/bulk_load.py [--base-url URL] [--dir PATH])
"""
import argparse
import sys
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
EXTENSIONS = (".pdf", ".docx", ".xlsx")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Массовая загрузка документов поставщиков")
    parser.add_argument("--base-url", default="http://localhost:8000")
    parser.add_argument("--dir", default=str(ROOT / "data"))
    parser.add_argument("--timeout", type=float, default=120.0)
    return parser.parse_args()


def collect(folder: Path) -> list[Path]:
    return sorted(
        path
        for path in folder.iterdir()
        if path.is_file() and path.suffix.lower() in EXTENSIONS
    )


def upload(client: httpx.Client, url: str, path: Path, timeout: float) -> tuple[bool, str]:
    with path.open("rb") as handle:
        response = client.post(
            f"{url}/documents",
            files={"file": (path.name, handle)},
            timeout=timeout,
        )
    if response.status_code >= 400:
        return False, f"HTTP {response.status_code} {response.text[:200]}"
    body = response.json()
    return True, f"id={body.get('id')} status={body.get('status')}"


def main() -> int:
    args = parse_args()
    folder = Path(args.dir)
    if not folder.is_dir():
        print(f"Каталог не найден: {folder}")
        return 2
    files = collect(folder)
    if not files:
        print(f"В {folder} нет файлов {', '.join(EXTENSIONS)}")
        return 0
    print(f"Загрузка {len(files)} файл(ов) из {folder} через {args.base_url}/documents")
    failures = 0
    with httpx.Client() as client:
        for path in files:
            ok, detail = upload(client, args.base_url, path, args.timeout)
            failures += 0 if ok else 1
            print(f"{'OK  ' if ok else 'FAIL'} {path.name}: {detail}")
    print(f"Итог: {len(files) - failures} загружено, {failures} с ошибкой")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())