"""ROBOT commands router — expose all ROBOT operations."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import FileResponse
from sqlalchemy.orm import Session

from app.config import DATA_DIR
from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.robot_commands import (
    AnnotateRequest, RenameCommandRequest, ExtractRequest, FilterRequest,
    MergeCommandRequest, ExplainRequest, ConvertRequest, RobotCommandResult,
)
from app.services import board as board_svc
from app.services.robot import run_robot
from app.services.odk import find_owl_file

router = APIRouter()


def _check_edit(board_id, db, user):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    return board


def _owl_path(board_id):
    board_dir = DATA_DIR / board_id
    owl = find_owl_file(board_dir)
    if not owl:
        raise HTTPException(status_code=404, detail="No OWL file")
    return board_dir, owl


async def _run(board_dir, command, user, db, board, action_name):
    result = await run_robot(board_dir, command)
    board_svc.log_activity(db, board, user, action_name, f"exit={result.exit_code}")
    return RobotCommandResult(command=command, exit_code=result.exit_code,
                               stdout=result.stdout[-500:], stderr=result.stderr[-500:])


@router.post("/{board_id}/annotate", response_model=RobotCommandResult)
async def robot_annotate(board_id: str, body: AnnotateRequest,
                          db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    ann_args = " ".join(f'--annotation "{k}" "{v}"' for k, v in body.annotations.items())
    cmd = f"robot annotate -i /work/{rel} {ann_args} -o /work/{rel}"
    return await _run(board_dir, cmd, user, db, board, "robot_annotate")


@router.post("/{board_id}/repair", response_model=RobotCommandResult)
async def robot_repair(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    return await _run(board_dir, f"robot repair -i /work/{rel} -o /work/{rel}", user, db, board, "robot_repair")


@router.post("/{board_id}/extract", response_model=RobotCommandResult)
async def robot_extract(board_id: str, body: ExtractRequest,
                         db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    terms = " ".join(f"--term {t}" for t in body.term_iris)
    out = f"/work/src/ontology/extracted.{body.output_format}"
    cmd = f"robot extract --method {body.method} -i /work/{rel} {terms} -o {out}"
    result = await _run(board_dir, cmd, user, db, board, "robot_extract")
    result.output_file = f"src/ontology/extracted.{body.output_format}"
    return result


@router.post("/{board_id}/filter", response_model=RobotCommandResult)
async def robot_filter(board_id: str, body: FilterRequest,
                        db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    cmd = f"robot filter -i /work/{rel} --select \"{body.select}\" -o /work/src/ontology/filtered.owl"
    return await _run(board_dir, cmd, user, db, board, "robot_filter")


@router.post("/{board_id}/expand", response_model=RobotCommandResult)
async def robot_expand(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    return await _run(board_dir, f"robot expand -i /work/{rel} -o /work/{rel}", user, db, board, "robot_expand")


@router.post("/{board_id}/collapse", response_model=RobotCommandResult)
async def robot_collapse(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    return await _run(board_dir, f"robot collapse -i /work/{rel} -o /work/{rel}", user, db, board, "robot_collapse")


@router.post("/{board_id}/relax", response_model=RobotCommandResult)
async def robot_relax(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    return await _run(board_dir, f"robot relax -i /work/{rel} -o /work/{rel}", user, db, board, "robot_relax")


@router.post("/{board_id}/merge", response_model=RobotCommandResult)
async def robot_merge(board_id: str, body: MergeCommandRequest,
                       db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    inputs = " ".join(f"-i /work/{s}" for s in body.sources)
    cmd = f"robot merge {inputs} -o /work/{owl.relative_to(board_dir)}"
    return await _run(board_dir, cmd, user, db, board, "robot_merge")


@router.post("/{board_id}/unmerge", response_model=RobotCommandResult)
async def robot_unmerge(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    return await _run(board_dir, f"robot unmerge -i /work/{rel} -o /work/{rel}", user, db, board, "robot_unmerge")


@router.post("/{board_id}/explain", response_model=RobotCommandResult)
async def robot_explain(board_id: str, body: ExplainRequest,
                         db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    cmd = f'robot explain -i /work/{rel} --axiom "{body.axiom}" -o /work/explanation.md'
    result = await _run(board_dir, cmd, user, db, board, "robot_explain")
    result.output_file = "explanation.md"
    return result


@router.post("/{board_id}/convert", response_model=RobotCommandResult)
async def robot_convert_format(board_id: str, body: ConvertRequest,
                                db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    ext_map = {"ttl": "ttl", "obo": "obo", "ofn": "ofn", "nt": "nt", "jsonld": "jsonld", "owl": "owl"}
    ext = ext_map.get(body.output_format, "owl")
    out = f"/work/src/ontology/{board_id}.{ext}"
    cmd = f"robot convert -i /work/{rel} --format {body.output_format} -o {out}"
    result = await _run(board_dir, cmd, user, db, board, "robot_convert")
    result.output_file = f"src/ontology/{board_id}.{ext}"
    return result


@router.post("/{board_id}/rename-cmd", response_model=RobotCommandResult)
async def robot_rename_cmd(board_id: str, body: RenameCommandRequest,
                            db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    board = _check_edit(board_id, db, user)
    board_dir, owl = _owl_path(board_id)
    rel = owl.relative_to(board_dir)
    mappings = " ".join(f'--mapping "{old} {new}"' for old, new in body.mappings.items())
    cmd = f"robot rename -i /work/{rel} {mappings} -o /work/{rel}"
    return await _run(board_dir, cmd, user, db, board, "robot_rename")
