"""Repository: таблица pipeline_traces — шаги пайплайна для визуального инспекта.

Единственный владелец SQL для трассы. Пишется слоем LLM-клиента и activity
пайплайна; читается debug-роутером и фронтендом инспекта (/debug-ui).
"""
import psycopg
from psycopg.types.json import Json


def insert_step(
    conn: psycopg.Connection,
    *,
    job_id: str,
    step: str,
    agent: str | None = None,
    model: str | None = None,
    prompt_text: str | None = None,
    raw_completion: str | None = None,
    parsed_output=None,
    usage_json=None,
) -> None:
    with conn.cursor() as cur:
        cur.execute(
            "INSERT INTO pipeline_traces "
            "(job_id, ordinal, step, agent, model, prompt_text, raw_completion, "
            "parsed_output, usage_json) "
            "VALUES (%s, "
            "  coalesce((SELECT max(ordinal) FROM pipeline_traces WHERE job_id=%s), 0) + 1, "
            "  %s, %s, %s, %s, %s, %s, %s)",
            (
                job_id,
                job_id,
                step,
                agent,
                model,
                prompt_text,
                raw_completion,
                Json(parsed_output) if parsed_output is not None else None,
                Json(usage_json) if usage_json is not None else None,
            ),
        )


def get_trace(conn: psycopg.Connection, job_id: str) -> list[dict]:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT ordinal, step, agent, model, prompt_text, raw_completion, "
            "parsed_output, usage_json, created_at "
            "FROM pipeline_traces WHERE job_id = %s ORDER BY ordinal",
            (job_id,),
        )
        rows = cur.fetchall()
    out = []
    for r in rows:
        out.append(
            {
                "ordinal": r[0],
                "step": r[1],
                "agent": r[2],
                "model": r[3],
                "prompt_text": r[4],
                "raw_completion": r[5],
                "parsed_output": r[6],
                "usage": r[7],
                "created_at": r[8].isoformat() if r[8] else None,
            }
        )
    return out
