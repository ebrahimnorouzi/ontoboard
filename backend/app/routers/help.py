"""Help & Guidance Router — contextual tooltips and introductions for ODK concepts."""

from fastapi import APIRouter, HTTPException

from app.services import odk_help

router = APIRouter()


@router.get("/topics")
def list_topics():
    """List all available help topics."""
    return odk_help.get_all_topics()


@router.get("/topic/{topic_id}")
def get_topic(topic_id: str):
    """Get full help content for a topic (intro, fields, resources)."""
    content = odk_help.get_help(topic_id)
    if not content:
        raise HTTPException(status_code=404, detail=f"Help topic '{topic_id}' not found")
    return content


@router.get("/topic/{topic_id}/field/{field_id}")
def get_field(topic_id: str, field_id: str):
    """Get help for a specific field (tooltip content for ? icon)."""
    content = odk_help.get_field_help(topic_id, field_id)
    if not content:
        raise HTTPException(status_code=404, detail=f"Field '{field_id}' not found in topic '{topic_id}'")
    return content


@router.get("/import-step/{step_num}")
def get_import_step(step_num: int):
    """Get help for a specific import workflow step (1-6)."""
    step_map = {
        1: "1_declare", 2: "2_check_makefile", 3: "3_add_terms",
        4: "4_register", 5: "5_custom_makefile", 6: "6_configure",
    }
    key = step_map.get(step_num)
    if not key:
        raise HTTPException(status_code=404, detail=f"Import step {step_num} not found (1-6)")
    workflow = odk_help.get_help("import_workflow")
    if not workflow:
        raise HTTPException(status_code=500, detail="Import workflow help not found")
    step = workflow.get("steps", {}).get(key)
    if not step:
        raise HTTPException(status_code=404, detail=f"Step {step_num} not found")
    return step
