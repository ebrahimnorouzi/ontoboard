"""Task management router — CRUD, comments, GitHub integration."""

from fastapi import APIRouter, Depends, HTTPException
from sqlalchemy.orm import Session

from app.deps import get_db, get_current_user
from app.models.user import User
from app.schemas.task import (
    TaskCreate, TaskUpdate, TaskOut,
    TaskCommentCreate, TaskCommentOut,
    GitHubIssueRequest,
)
from app.services import board as board_svc
from app.services import task as task_svc
from app.services import user as user_svc
from app.services import github as github_svc

router = APIRouter()


def _get_board(board_id: str, db: Session, user: User):
    board = board_svc.get_board_by_slug(db, board_id)
    if not board:
        raise HTTPException(status_code=404, detail="Board not found")
    if not board_svc.can_view(db, board, user):
        raise HTTPException(status_code=403, detail="Access denied")
    return board


# ── Tasks CRUD ─────────────────────────────────────────────────

@router.get("/{board_id}", response_model=list[TaskOut])
def list_tasks(
    board_id: str,
    status: str | None = None,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    tasks = task_svc.list_tasks(db, board.id, status=status)
    return [TaskOut(**task_svc.task_to_out(t)) for t in tasks]


@router.post("/{board_id}", response_model=TaskOut, status_code=201)
def create_task(
    board_id: str,
    body: TaskCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")

    assignee = None
    if body.assignee_username:
        assignee = user_svc.get_user_by_username(db, body.assignee_username)
        if not assignee:
            raise HTTPException(status_code=404, detail=f"User '{body.assignee_username}' not found")

    task = task_svc.create_task(
        db, board.id, body.title, user,
        description=body.description,
        assignee=assignee,
        entity_iri=body.entity_iri,
        priority=body.priority,
        due_date=body.due_date,
    )
    board_svc.log_activity(db, board, user, "task_created", f"Task: {body.title}")
    return TaskOut(**task_svc.task_to_out(task))


@router.patch("/{board_id}/{task_id}", response_model=TaskOut)
def update_task(
    board_id: str,
    task_id: int,
    body: TaskUpdate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    task = task_svc.get_task(db, task_id)
    if not task or task.board_id != board.id:
        raise HTTPException(status_code=404, detail="Task not found")

    fields = body.model_dump(exclude_unset=True)

    # Resolve assignee username → id
    if "assignee_username" in fields:
        username = fields.pop("assignee_username")
        if username:
            assignee = user_svc.get_user_by_username(db, username)
            if not assignee:
                raise HTTPException(status_code=404, detail=f"User '{username}' not found")
            fields["assignee_id"] = assignee.id
        else:
            fields["assignee_id"] = None

    task = task_svc.update_task(db, task, **fields)
    return TaskOut(**task_svc.task_to_out(task))


@router.delete("/{board_id}/{task_id}")
def delete_task(
    board_id: str,
    task_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    task = task_svc.get_task(db, task_id)
    if not task or task.board_id != board.id:
        raise HTTPException(status_code=404, detail="Task not found")
    if not board_svc.can_edit(db, board, user):
        raise HTTPException(status_code=403, detail="Edit access required")
    task_svc.delete_task(db, task)
    return {"detail": "Task deleted"}


# ── Comments ───────────────────────────────────────────────────

@router.get("/{board_id}/{task_id}/comments", response_model=list[TaskCommentOut])
def list_comments(
    board_id: str,
    task_id: int,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    task = task_svc.get_task(db, task_id)
    if not task or task.board_id != board.id:
        raise HTTPException(status_code=404, detail="Task not found")
    comments = task_svc.list_comments(db, task_id)
    return [TaskCommentOut(id=c.id, text=c.text, username=c.user.username, created_at=c.created_at) for c in comments]


@router.post("/{board_id}/{task_id}/comments", response_model=TaskCommentOut, status_code=201)
def add_comment(
    board_id: str,
    task_id: int,
    body: TaskCommentCreate,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    task = task_svc.get_task(db, task_id)
    if not task or task.board_id != board.id:
        raise HTTPException(status_code=404, detail="Task not found")
    comment = task_svc.add_comment(db, task_id, user.id, body.text)
    return TaskCommentOut(id=comment.id, text=comment.text, username=user.username, created_at=comment.created_at)


# ── GitHub integration ─────────────────────────────────────────

@router.post("/{board_id}/{task_id}/github")
def create_github_issue(
    board_id: str,
    task_id: int,
    body: GitHubIssueRequest,
    db: Session = Depends(get_db),
    user: User = Depends(get_current_user),
):
    board = _get_board(board_id, db, user)
    task = task_svc.get_task(db, task_id)
    if not task or task.board_id != board.id:
        raise HTTPException(status_code=404, detail="Task not found")

    issue_body = f"{task.description}\n\n---\n*Created from OntoBoard board `{board_id}`*"
    if task.entity_iri:
        issue_body += f"\n*Linked entity: `{task.entity_iri}`*"

    result = github_svc.create_github_issue(
        body.repo_owner, body.repo_name, body.github_token,
        title=task.title,
        body=issue_body,
        labels=body.labels,
    )

    if "error" in result:
        raise HTTPException(status_code=502, detail=result["error"])

    # Update task with GitHub link
    task_svc.update_task(db, task,
                         github_issue_url=result["url"],
                         github_issue_number=result["number"])
    board_svc.log_activity(db, board, user, "github_issue",
                           f"#{result['number']}: {task.title}")

    return {"issue_url": result["url"], "issue_number": result["number"]}
