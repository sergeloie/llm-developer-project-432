"""Repository: агрегаты для GET /metrics. Единственный владелец SQL.

Источники строк:
- llm_calls  — каждый вызов модели, включая исчерпанные попытки (outcome='error');
- jobs       — одна строка на задачу;
- documents  — одна строка на загруженный документ.
"""
import psycopg

_CALLS_TOTALS = """
SELECT count(*),
       count(*) FILTER (WHERE outcome = 'ok'),
       count(*) FILTER (WHERE outcome <> 'ok'),
       coalesce(sum(prompt_tokens), 0),
       coalesce(sum(completion_tokens), 0),
       coalesce(sum(cost), 0),
       avg(latency_ms) FILTER (WHERE latency_ms IS NOT NULL),
       percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)
         FILTER (WHERE latency_ms IS NOT NULL)
FROM llm_calls
"""

_CALLS_BY_AGENT = """
SELECT agent, count(*), coalesce(sum(cost), 0)
FROM llm_calls GROUP BY agent ORDER BY count(*) DESC, agent
"""

_CALLS_BY_MODEL = """
SELECT model, count(*), coalesce(sum(cost), 0)
FROM llm_calls GROUP BY model ORDER BY count(*) DESC, model
"""

_JOBS_BY_STATUS = "SELECT status, count(*) FROM jobs GROUP BY status ORDER BY status"

_DOCUMENTS_BY_STATUS = (
    "SELECT status, count(*) FROM documents GROUP BY status ORDER BY status"
)


def snapshot(conn: psycopg.Connection) -> dict:
    """Один снимок всех метрик: достаточно одного обхода для согласованного вывода."""
    with conn.cursor() as cur:
        cur.execute(_CALLS_TOTALS)
        totals = cur.fetchone()
        cur.execute(_CALLS_BY_AGENT)
        by_agent = cur.fetchall()
        cur.execute(_CALLS_BY_MODEL)
        by_model = cur.fetchall()
        cur.execute(_JOBS_BY_STATUS)
        jobs_by_status = cur.fetchall()
        cur.execute(_DOCUMENTS_BY_STATUS)
        documents_by_status = cur.fetchall()
    return {
        "calls": int(totals[0] or 0),
        "calls_ok": int(totals[1] or 0),
        "calls_error": int(totals[2] or 0),
        "prompt_tokens": int(totals[3] or 0),
        "completion_tokens": int(totals[4] or 0),
        "cost_total": float(totals[5] or 0),
        "latency_avg_ms": float(totals[6] or 0),
        "latency_p95_ms": float(totals[7] or 0),
        "calls_by_agent": [
            {"agent": r[0] or "", "calls": int(r[1]), "cost": float(r[2] or 0)}
            for r in by_agent
        ],
        "calls_by_model": [
            {"model": r[0] or "", "calls": int(r[1]), "cost": float(r[2] or 0)}
            for r in by_model
        ],
        "jobs_by_status": {r[0]: int(r[1]) for r in jobs_by_status},
        "documents_by_status": {r[0]: int(r[1]) for r in documents_by_status},
    }