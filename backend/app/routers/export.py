"""ODP Repository Export Router."""

from fastapi import APIRouter, Depends, HTTPException
from fastapi.responses import Response
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.services import board as board_svc
from app.services.export_odp_repo import export_odp_repo, export_odp_repo_zip

router = APIRouter()


@router.post("/{board_id}")
def export_repo(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Generate ODP repository structure and return info."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    board_dir = board_svc.get_board_dir(board_id)
    export_dir = export_odp_repo(board_dir, board_id)

    # List generated files
    files = []
    for f in export_dir.rglob("*"):
        if f.is_file():
            files.append(str(f.relative_to(export_dir)))

    board_svc.log_activity(db, board, user, "export_odp", f"Exported {len(files)} files")
    return {"export_dir": str(export_dir), "files": sorted(files), "file_count": len(files)}


@router.post("/{board_id}/zip")
def export_repo_zip(board_id: str, db: Session = Depends(get_db), user: User = Depends(get_current_user)):
    """Generate ODP repository as downloadable ZIP."""
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")

    board_dir = board_svc.get_board_dir(board_id)
    zip_bytes = export_odp_repo_zip(board_dir, board_id)

    return Response(
        content=zip_bytes,
        media_type="application/zip",
        headers={"Content-Disposition": f"attachment; filename={board_id}-odp-repo.zip"},
    )
