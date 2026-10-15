"""Сборка текстового отчёта GET /metrics из снимка репозитория.

Формат — строки `имя{метки} значение`, как в Prometheus exposition: текст
читается и человеком, и грепается без парсера.
"""
_SECTION_LLM = "# llm_calls"
_SECTION_JOBS = "# jobs"
_SECTION_DOCUMENTS = "# documents"


def render(snapshot: dict) -> str:
    lines = [_SECTION_LLM]
    lines.extend(_llm_lines(snapshot))
    lines.append(_SECTION_JOBS)
    lines.extend(_status_lines("jobs", snapshot["jobs_by_status"]))
    lines.append(_SECTION_DOCUMENTS)
    lines.extend(_status_lines("documents", snapshot["documents_by_status"]))
    return "\n".join(lines) + "\n"


def _llm_lines(snapshot: dict) -> list[str]:
    calls = snapshot["calls"]
    invalid_share = snapshot["calls_error"] / calls if calls else 0.0
    cost_avg = snapshot["cost_total"] / calls if calls else 0.0
    lines = [
        "# доля невалидных ответов модели = llm_calls_error / llm_calls_total",
        f"llm_calls_total {calls}",
        f"llm_calls_ok {snapshot['calls_ok']}",
        f"llm_calls_error {snapshot['calls_error']}",
        f"llm_calls_invalid_share {invalid_share:.6f}",
        f"llm_prompt_tokens_total {snapshot['prompt_tokens']}",
        f"llm_completion_tokens_total {snapshot['completion_tokens']}",
        f"llm_cost_total {snapshot['cost_total']:.6f}",
        f"llm_cost_avg {cost_avg:.6f}",
        f"llm_latency_ms_avg {snapshot['latency_avg_ms']:.1f}",
        f"llm_latency_ms_p95 {snapshot['latency_p95_ms']:.1f}",
    ]
    for row in snapshot["calls_by_agent"]:
        agent = _escape(row["agent"])
        lines.append(f'llm_calls_by_agent{{agent="{agent}"}} {row["calls"]}')
        lines.append(f'llm_cost_by_agent{{agent="{agent}"}} {row["cost"]:.6f}')
    for row in snapshot["calls_by_model"]:
        lines.append(f'llm_calls_by_model{{model="{_escape(row["model"])}"}} {row["calls"]}')
    return lines


def _status_lines(name: str, by_status: dict[str, int]) -> list[str]:
    lines = [f"{name}_total {sum(by_status.values())}"]
    for status, count in by_status.items():
        lines.append(f'{name}_total{{status="{_escape(status)}"}} {count}')
    return lines


def _escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')