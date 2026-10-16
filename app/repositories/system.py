"""Repository: системные проверки БД. Единственный владелец SQL для здоровья."""
import psycopg


def ping(conn: psycopg.Connection) -> bool:
    """Соединение с базой живо."""
    with conn.cursor() as cur:
        cur.execute("SELECT 1")
        return cur.fetchone() is not None


def has_vector_extension(conn: psycopg.Connection) -> bool:
    """Расширение pgvector установлено — без него поиск и миграции не работают."""
    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM pg_extension WHERE extname = 'vector'")
        return cur.fetchone() is not None
