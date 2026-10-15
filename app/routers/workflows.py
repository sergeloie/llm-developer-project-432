"""Durable-процессы: старт, статус, сигналы человека (шаг 3)."""
from fastapi import APIRouter, HTTPException

from app import schemas
from app.temporal.kickoff import get_client

router = APIRouter(tags=["workflows"])


@router.post("/workflows", status_code=202)
async def start_workflow(body: schemas.CardIn) -> dict:
    from app.temporal.kickoff import start_generation

    wf_id = await start_generation(f"manual-{body.supplier_text[:8]}", {"supplier_text": body.supplier_text, "hint": body.hint})
    return {"id": wf_id, "status": "started"}


@router.get("/workflows/{wf_id}")
async def workflow_status(wf_id: str) -> dict:
    client = await get_client()
    handle = client.get_workflow_handle(wf_id)
    try:
        desc = await handle.describe()
        phase = await handle.query("phase")
    except Exception as exc:
        raise HTTPException(404, f"процесс не найден: {exc}") from exc
    return {"id": wf_id, "run_status": str(desc.status), "phase": phase}


@router.post("/workflows/{wf_id}/approve")
async def approve(wf_id: str) -> dict:
    client = await get_client()
    await client.get_workflow_handle(wf_id).signal("approve")
    return {"id": wf_id, "signaled": "approve"}


@router.post("/workflows/{wf_id}/reject")
async def reject(wf_id: str) -> dict:
    client = await get_client()
    await client.get_workflow_handle(wf_id).signal("reject")
    return {"id": wf_id, "signaled": "reject"}


@router.get("/workflows/{wf_id}/result")
async def workflow_result(wf_id: str) -> dict:
    client = await get_client()
    handle = client.get_workflow_handle(wf_id)
    try:
        result = await handle.result()
    except Exception as exc:
        raise HTTPException(409, f"процесс ещё не завершён: {exc}") from exc
    return {"id": wf_id, "result": result}
