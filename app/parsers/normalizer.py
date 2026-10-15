"""Нормализация текста: колонтитулы, дефисы на переносах, лигатуры, пробелы."""
import re

_LIGATURES = {
    "ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl", "ﬃ": "ffi", "ﬄ": "ffl",
    "­": "",  # soft hyphen
    " ": " ",  # nbsp
}


def clean_text(text: str) -> str:
    """Очистка одного блока: лигатуры, дефис-перенос, пробелы."""
    for ch, repl in _LIGATURES.items():
        text = text.replace(ch, repl)
    # Дефис на переносе строки: «мощ-\nность» → «мощность»
    text = re.sub(r"(\w)-\n(\w)", r"\1\2", text)
    # Схлопывание пробелов
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def strip_footers(blocks) -> list:
    """Удаление повторяющихся колонтитулов.

    Колонтитул — строка, встречающаяся на >= половине страниц документа
    (номер страницы в ней может отличаться, сравниваем по шаблону без цифр).
    Колонтитулом считаем строку на КАЖДОЙ странице: порог «половина»
    на коротком документе зачислит в колонтитулы любой обычный текст.
    """
    pages = {b.page for b in blocks}
    if len(pages) < 2:
        return blocks

    template_pages: dict[str, set[int]] = {}
    for b in blocks:
        if b.kind != "text":
            continue
        for line in b.content.split("\n"):
            if not line.strip():
                continue
            template = re.sub(r"\d+", "#", line.strip())
            template_pages.setdefault(template, set()).add(b.page)

    footers = {t for t, pgs in template_pages.items() if len(pgs) == len(pages)}

    result = []
    for b in blocks:
        if b.kind != "text":
            result.append(b)
            continue
        kept = [
            line for line in b.content.split("\n")
            if re.sub(r"\d+", "#", line.strip()) not in footers
        ]
        content = "\n".join(kept).strip()
        if content:
            from copy import copy

            nb = copy(b)
            nb.content = content
            result.append(nb)
    return result


def normalize(blocks) -> list:
    blocks = strip_footers(blocks)
    for b in blocks:
        b.content = clean_text(b.content)
    return [b for b in blocks if b.content]
