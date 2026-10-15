"""Подключение к Temporal: одна точка на приложение.

Клиент создаётся на запрос — соединение дешёвое, а держать его в состоянии
приложения пришлось бы вместе с переподключением при рестарте сервера.
"""
from temporalio.client import Client

from app.core.config import settings


async def temporal_client() -> Client:
    return await Client.connect(settings.temporal_host)
