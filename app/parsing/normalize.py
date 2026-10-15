"""Нормализация: колонтитулы, дефисные переносы, лигатуры, лишние пробелы."""
import re


def normalize_text(text: str) -> str:
    """Склейка переносов/лигатур/пробелов для отдельного куска текста."""
    s = text
    # лигатуры
    for ugly, good in (
        ("\ufb00", "ff"),
        ("\ufb01", "fi"),
        ("\ufb02", "fl"),
        ("\ufb03", "ffi"),
        ("\ufb04", "ffl"),
        ("\u00ad", ""),  # soft hyphen
        ("\u2010", "-"),
        ("\u00a0", " "),
    ):
        s = s.replace(ugly, good)
    # дефисный перенос в конце строки: «мощ-
    # ность» → «мощность»
    s = re.sub(r"(\w)-\s*\n\s*(\w)", r"\1\2", s)
    # пробельные схлопывания (внутри строк)
    s = re.sub(r"[ \t]+", " ", s)
    s = re.sub(r" ?\n ?", "\n", s)
    s = re.sub(r"\n{3,}", "\n\n", s)
    return s.strip()


def normalize_cell(value: object) -> str:
    """Ячейка таблицы/листа: строка, без переносов и двойных пробелов."""
    if value is None:
        return ""
    s = str(value).replace("\u00a0", " ").replace("\u2010", "-")
    s = re.sub(r"\s+", " ", s)
    return s.strip()


def strip_headers_footers(
    pages: list[list[str]], min_pages_for_majority: int = 3
) -> None:
    """Вычистить повторяющиеся строки-колонтитулы постранично (in place).

    Правила (осторожность из задания):
    - строка удаляется, только если она на КАЖДОЙ странице (или, при >=3 страницах,
      хотя бы на 60%);
    - и при этом похожа на колонтитул: короткая, содержит «стр./©/www./email»
      или заканчивается на номер страницы.
    Простое «большинство от двух» не годится: от двух страниц «большинство» — одна.
    Номер страницы в хвосте колонтитула нормализуется перед сравнением.
    """
    if len(pages) < 2:
        return
    from collections import Counter

    total = len(pages)
    counts: Counter = Counter()
    for lines in pages:
        counts.update({_canon_key(ln) for ln in lines if ln.strip()})
    for lines in pages:
        keep: list[str] = []
        for ln in lines:
            c = counts.get(_canon_key(ln), 0)
            if _is_boilerplate(ln, c, total, min_pages_for_majority):
                continue
            keep.append(ln)
        lines[:] = keep


_canon_footer_num = re.compile(r"(—\s*стр\.?\s*\d+|—\s*page\s*\d+|\bстр\.?\s*\d+\b)", re.IGNORECASE)


def _canon_key(line: str) -> str:
    """Ключ для сравнения: номер страницы в колонтитуле у страницы свой."""
    return _canon_footer_num.sub("— стр. N", line.strip())


def _is_boilerplate(line: str, count: int, total: int, min_pages_for_majority: int) -> bool:
    if count == 0:
        return False
    on_all_pages = count == total
    on_majority = len(line) < 200 and total >= min_pages_for_majority and count >= 0.6 * total
    if not (on_all_pages or on_majority):
        return False
    looks_like_footer = bool(
        re.search(r"(стр\.?|статья|©|www\.|\.example|© 20\d\d|— стр\.? ?\d+)", line, re.IGNORECASE)
    )
    return looks_like_footer
