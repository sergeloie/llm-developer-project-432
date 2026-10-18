"""Repository: таблица llm_calls — каждый вызов модели с ценой и латентностью."""
import psycopg


def insert_call(
    conn: psycopg.Connection,
    *,
    job_id: str | None,
    agent: str,
    model: str,
    prompt_tokens: int | None,
    completion_tokens: int | None,
    cost: float,
    latency_ms: int | None,
    outcome: str = "ok",
    error: str | None = None,
) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO llm_calls (job_id, agent, model, prompt_tokens, completion_tokens, "
            "cost, latency_ms, outcome, error) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)",
            (
                job_id,
                agent,
                model,
                prompt_tokens,
                completion_tokens,
                cost,
                latency_ms,
                outcome,
                error,
            ),
        )


def job_cost(conn: psycopg.Connection, job_id: str) -> dict:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT count(*), sum(cost), sum(prompt_tokens), sum(completion_tokens) "
            "FROM llm_calls WHERE job_id = %s",
            (job_id,),
        )
        n, cost, pt, ct = cur.fetchone()
    return {
        "calls": int(n or 0),
        "cost": float(cost or 0),
        "prompt_tokens": int(pt or 0),
        "completion_tokens": int(ct or 0),
    }


def by_model(conn: psycopg.Connection) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT model, count(*), sum(cost) FROM llm_calls GROUP BY model ORDER BY 3 DESC"
        )
        rows = cur.fetchall()
    return [{"model": r[0], "calls": int(r[1]), "cost": float(r[2] or 0)} for r in rows]


def unattributed_cost(conn: psycopg.Connection) -> dict:
    """Сумма по вызовам без job_id — то, что генерит eval-прогон вне процесса.

    Прогон метрик не привязан к задаче, поэтому его вызовы пишутся с job_id NULL.
    Базовая линия до/после документа даёт стоимость именно этого документа.
    """
    with conn.cursor() as cur:
        cur.execute(
            "SELECT count(*), sum(cost), sum(prompt_tokens), sum(completion_tokens) "
            "FROM llm_calls WHERE job_id IS NULL"
        )
        n, cost, pt, ct = cur.fetchone()
    return {
        "calls": int(n or 0),
        "cost": float(cost or 0),
        "prompt_tokens": int(pt or 0),
        "completion_tokens": int(ct or 0),
    }
