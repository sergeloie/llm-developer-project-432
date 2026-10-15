"""Устойчивый процесс наружу: статус, сигналы, результат.

Запускается процесс через POST /jobs — там же создаётся задача в базе,
и её идентификатор служит идентификатором процесса.
"""
from fastapi import APIRouter, HTTPException

from app.temporal.client import temporal_client

router = APIRouter(tags=["workflows"])


@router.get("/workflows/{wf_id}")
async def workflow_status(wf_id: str):
    client = await temporal_client()
    try:
        handle = client.get_workflow_handle(wf_id)
        desc = await handle.describe()
        status_query = await handle.query("current_status")
    except Exception:
        raise HTTPException(404, "workflow not found")
    return {
        "id": wf_id,
        "run_status": str(desc.status),
        "status": status_query,
    }


@router.post("/workflows/{wf_id}/approve")
async def approve(wf_id: str):
    client = await temporal_client()
    handle = client.get_workflow_handle(wf_id)
    await handle.signal("approve")
    return {"id": wf_id, "signaled": "approve"}


@router.post("/workflows/{wf_id}/request-changes")
async def request_changes(wf_id: str):
    client = await temporal_client()
    handle = client.get_workflow_handle(wf_id)
    await handle.signal("request_changes")
    return {"id": wf_id, "signaled": "request_changes"}


@router.get("/workflows/{wf_id}/result")
async def workflow_result(wf_id: str):
    client = await temporal_client()
    handle = client.get_workflow_handle(wf_id)
    try:
        result = await handle.result()
    except Exception as exc:
        raise HTTPException(409, f"not finished: {exc}")
    return {"id": wf_id, "result": result}
