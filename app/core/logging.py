import logging
import sys

import structlog


def _force_utf8(stream) -> None:
    """Переключить поток вывода на UTF-8, чтобы непредставимые в консольной
    кодировке символы (например, ₽) не ронили процесс на Windows-cp1251.

    Перехватываем ошибки реконфигурации: поток может быть обёрнут
    (colorama) или вообще не поддерживать reconfigure — тогда пишем как есть.
    """
    if stream is None:
        return
    try:
        stream.reconfigure(encoding="utf-8", errors="replace")
    except Exception:  # noqa: S110  переконфигурация — лучшее усилие, неудача не критична
        pass


def setup_logging() -> None:
    _force_utf8(getattr(sys, "stdout", None))
    _force_utf8(getattr(sys, "stderr", None))
    _force_utf8(getattr(sys, "__stdout__", None))
    _force_utf8(getattr(sys, "__stderr__", None))
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.dev.ConsoleRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
    )


def get_logger(*args, **kwargs):
    return structlog.get_logger(*args, **kwargs)
