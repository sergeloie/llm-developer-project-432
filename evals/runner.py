"""Eval-раннер: генерация карточек по golden-документам → таблица метрик.

Метрики:
  field_accuracy   — доля ожидаемых характеристик, совпавших после нормализации
  citation_hit     — доля карточек, где sources указывают на чанк с ожидаемым текстом
  faithfulness     — LLM-as-judge: подтверждаемость карточки контекстом (0-1)

Запуск: python -m evals.runner [--full]  (по умолчанию 2 документа из eval_defaults)
"""
import asyncio
import json
import math
import re
import sys
import time
from pathlib import Path

# Почему метрика может быть самой слабой — для отчёта наставнику.
WEAKEST_EXPLANATIONS = {
    "field_accuracy": (
        "Доля характеристик, извлечённых из документа. Низкая — модель не "
        "достала часть параметров (они лежат вне топ-чанков, в таблицах или в "
        "неполной спецификации, где их действительно нет), либо нормализация "
        "сравнения слишком строга к единицам измерения и пунктуации."
    ),
    "citation_hit": (
        "Доля карточек, чьи sources указывают на чанк с ожидаемым текстом. "
        "Низкая — retrieval отдал не те чанки, или модель сослалась на чанк, "
        "который не содержит проверочной фразы; бьёт по проверяемости цитат."
    ),
    "faithfulness": (
        "LLM-as-judge: подтверждаемость карточки контекстом (0-1). Низкая — "
        "карточка содержит утверждения вне контекста (вода, довыдумка), либо "
        "judge мягок к модели. Метрика помечается NaN, если модель-судья "
        "недоступна, и тогда слабейшей считается другая метрика."
    ),
}

from app.core import db
from app.core.logging import get_logger, setup_logging
from app.repositories import documents as docs_repo
from app.repositories import llm_calls as llm_calls_repo
from app.services.rag_pipeline import (
    SourceValidationError,
    retrieve_context_async,
    run_rag_pipeline,
)

log = get_logger(__name__)

DATA = Path(__file__).resolve().parent.parent / "data"
GOLDEN = json.loads((DATA / "golden_cards.json").read_text(encoding="utf-8"))


def normalize_value(v: str) -> str:
    """Нормализация для сравнения: регистр, пунктуация, пробелы, единицы."""
    v = str(v).lower().strip()
    v = re.sub(r"[\s ]+", "", v)
    v = re.sub(r"[.,;:«»\"'()\-–—]", "", v)
    return v


def field_accuracy(card_chars: dict, golden_chars: dict) -> float:
    """Доля golden-характеристик, найденных в карточке (ключ+значение)."""
    if not golden_chars:
        return 1.0
    norm_card = {normalize_value(k): normalize_value(v) for k, v in card_chars.items()}
    hits = 0
    for gk, gv in golden_chars.items():
        nk, nv = normalize_value(gk), normalize_value(gv)
        # точное совпадение пары или значение содержит ожидаемое
        if norm_card.get(nk) == nv:
            hits += 1
        else:
            for ck, cv in norm_card.items():
                if (nk in ck or ck in nk) and (nv == cv or nv in cv or cv in nv):
                    hits += 1
                    break
    return hits / len(golden_chars)


def citation_hit(card_sources: list[dict], golden_probes: dict, chunks_by_id: dict) -> float:
    """Доля проб, где заявленный source-чанк содержит ожидаемый текст."""
    if not golden_probes:
        return 1.0
    hits = 0
    claimed = {s["chunk_id"]: s for s in card_sources}
    for probe in golden_probes.values():
        expected = normalize_value(probe["text_contains"])
        # хоть один из заявленных чанков содержит ожидаемый текст
        for cid in claimed:
            chunk = chunks_by_id.get(cid)
            if chunk and expected in normalize_value(chunk["text"]):
                hits += 1
                break
    return hits / len(golden_probes)


async def _judge(context: str, card_json: str) -> float:
    """Faithfulness от LLM-as-judge.

    Глотаются только отказы модели — транспорт, таймаут, нечитаемый JSON:
    это неисправность сервиса моделей, а не отчёт о системе, и метрика
    помечается NaN. Ошибка самого вызова (TypeError на склейке контекста,
    AttributeError на разборе ответа) не ловится и валит прогон: иначе
    сломанная метрика тихо превращается в её отсутствие.
    """
    from openai import APIError
    from pydantic import ValidationError

    from app.services.json_utils import LLMJsonError
    from evals.judge import judge_faithfulness

    try:
        return (await judge_faithfulness(context, card_json)).faithfulness
    except (APIError, TimeoutError, LLMJsonError, ValidationError) as exc:
        log.warning("judge_failed", error=repr(exc))
        return float("nan")


async def eval_document(doc_id: str, golden: dict) -> dict:
    query = golden.get("product", "карточка товара характеристики")
    t0 = time.time()
    # Базовая линия стоимости: eval не привязан к задаче, его вызовы пишутся
    # с job_id NULL. Дельта до/после документа — стоимость именно этого документа.
    with db.connection() as conn:
        base = llm_calls_repo.unattributed_cost(conn)
    # retrieval один раз — и для пайплайна, и для судьи (без повторного прогона
    # детектора инъекций). run_rag_pipeline поднимает SourceValidationError, если
    # модель упорно ссылается на выдуманные/несуществующие источники: тогда
    # карточку показываем человеку (needs_review), а не валим eval.
    context, guard = await retrieve_context_async(query, [doc_id])
    try:
        draft, attempts, _verdict, status, _guard = await run_rag_pipeline(
            query, [doc_id], context=context, guard_report=guard
        )
    except SourceValidationError:
        dt = time.time() - t0
        with db.connection() as conn:
            after = llm_calls_repo.unattributed_cost(conn)
        return {
            "attempts": 0,
            "status": "needs_review",
            "latency_s": round(dt, 1),
            "field_accuracy": 0.0,
            "citation_hit": 0.0,
            "faithfulness": None,
            "confidence": 0.0,
            "missing_fields": ["источники не прошли валидацию модели"],
            "cost_usd": round(after["cost"] - base["cost"], 4),
            "prompt_tokens": after["prompt_tokens"] - base["prompt_tokens"],
            "completion_tokens": after["completion_tokens"] - base["completion_tokens"],
        }
    dt = time.time() - t0
    card = draft.model_dump()

    from app.repositories import chunks as chunks_repo

    with db.connection() as conn:
        all_source_ids = [s["chunk_id"] for s in card["sources"]]
        chunks_by_id = chunks_repo.get_chunks_by_ids(conn, all_source_ids) if all_source_ids else {}

    fa = field_accuracy(card["characteristics"], golden.get("characteristics", {}))
    ch = citation_hit(card["sources"], golden.get("source_probes", {}), chunks_by_id)

    faith = await _judge(context, draft.model_dump_json())

    with db.connection() as conn:
        after = llm_calls_repo.unattributed_cost(conn)

    return {
        "attempts": attempts,
        "status": status,
        "latency_s": round(dt, 1),
        "field_accuracy": round(fa, 3),
        "citation_hit": round(ch, 3),
        "faithfulness": None if math.isnan(faith) else round(faith, 3),
        "confidence": card["confidence"],
        "missing_fields": card["missing_fields"],
        "cost_usd": round(after["cost"] - base["cost"], 4),
        "prompt_tokens": after["prompt_tokens"] - base["prompt_tokens"],
        "completion_tokens": after["completion_tokens"] - base["completion_tokens"],
    }


def main() -> None:
    setup_logging()
    full = "--full" in sys.argv
    selected = list(GOLDEN["documents"].items())
    if not full:
        defaults = set(GOLDEN.get("eval_defaults", []))
        selected = [(n, g) for n, g in selected if n in defaults]

    with db.connection() as conn:
        docs = {d["filename"]: d["id"] for d in docs_repo.list_documents(conn)}

    results = {}
    excluded: set[str] = set()
    print(f"{'документ':25} {'field_acc':>9} {'cit_hit':>7} {'faith':>5} {'conf':>4} {'status':>11} {'lat':>5} {'tok':>6}")
    for name, golden in selected:
        doc_id = docs.get(name)
        if not doc_id:
            print(f"{name:25} ПРОПУСК: документ не загружен в БД")
            continue
        r = asyncio.run(eval_document(doc_id, golden))
        results[name] = r
        # Документы без золотых ожиданий (скан без текстового слоя) дают
        # field_accuracy/citation_hit = 1.00 по пустому множеству и завышают
        # средние — в средние они не идут, но остаются в таблице по документу.
        if not (golden.get("characteristics") or golden.get("source_probes")):
            excluded.add(name)
        toks = r.get("prompt_tokens", 0) + r.get("completion_tokens", 0)
        print(
            f"{name:25} {r['field_accuracy']:>9.2f} {r['citation_hit']:>7.2f} "
            f"{r['faithfulness']!s:>5} {r['confidence']:>4.2f} {r['status']:>11} "
            f"{r['latency_s']:>4.0f}s {toks:>6}"
        )

    weakest_name = None
    weakest_value = None
    if results:
        included = [n for n in results if n not in excluded]
        avg_fa = sum(results[n]["field_accuracy"] for n in included) / len(included)
        avg_ch = sum(results[n]["citation_hit"] for n in included) / len(included)
        faiths = [
            results[n]["faithfulness"]
            for n in included
            if results[n]["faithfulness"] is not None
        ]
        avg_faith = sum(faiths) / len(faiths) if faiths else float("nan")
        avg_cost = sum(results[n]["cost_usd"] for n in included) / len(included)
        avg_tok = sum(
            results[n].get("prompt_tokens", 0) + results[n].get("completion_tokens", 0)
            for n in included
        ) / len(included)
        print(f"{'СРЕДНЕЕ':25} {avg_fa:>9.2f} {avg_ch:>7.2f} {avg_faith:>5.2f}")
        print(f"Средняя стоимость карточки: ${avg_cost:.4f} (~{avg_tok:.0f} токенов)")
        if excluded:
            print(
                "Исключены из средних (нет золотых ожиданий — field_accuracy/"
                "citation_hit = 1.00 по пустому множеству): "
                + ", ".join(sorted(excluded))
            )

        metrics = {
            "field_accuracy": avg_fa,
            "citation_hit": avg_ch,
            "faithfulness": avg_faith,
        }
        weakest_name = min(
            metrics, key=lambda k: metrics[k] if not math.isnan(metrics[k]) else 2.0
        )
        weakest_value = metrics[weakest_name]
        print(
            f"\nСАМАЯ СЛАБАЯ МЕТРИКА: {weakest_name} = {weakest_value:.2f}\n"
            f"  почему: {WEAKEST_EXPLANATIONS[weakest_name]}"
        )

    report = dict(results)
    if results:
        included = [n for n in results if n not in excluded]
        report["avg_cost_usd"] = round(
            sum(results[n]["cost_usd"] for n in included) / len(included), 4
        )
        report["avg_tokens"] = round(
            sum(
                results[n].get("prompt_tokens", 0) + results[n].get("completion_tokens", 0)
                for n in included
            )
            / len(included)
        )
    report["weakest_metric"] = (
        {
            "name": weakest_name,
            "value": weakest_value,
            "explanation": WEAKEST_EXPLANATIONS[weakest_name],
        }
        if weakest_name is not None
        else None
    )
    out = Path(__file__).parent / "report.json"
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nСохранено: {out}")


if __name__ == "__main__":
    try:
        main()
    finally:
        db.close_pool()
