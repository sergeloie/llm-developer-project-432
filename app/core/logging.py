"""structlog: JSON-логи, контекст запроса/задачи привязывается через bind_contextvars."""
import logging
import sys

import structlog


def setup_logging() -> None:
    logging.basicConfig(level=logging.INFO, stream=sys.stdout, format="%(message)s")
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.format_exc_info,
            structlog.dev.ConsoleRenderer(colors=False) if _is_tty() else structlog.processors.JSONRenderer(),
        ],
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def _is_tty() -> bool:
    return sys.stdout.isatty()


def get_logger(name: str):
    return structlog.get_logger(name)


def bind(**kwargs) -> None:
    """Привязать ключи контекста логов (request_id, job_id, document_id...)."""
    structlog.contextvars.bind_contextvars(**kwargs)


def clear_context() -> None:
    structlog.contextvars.clear_contextvars()
