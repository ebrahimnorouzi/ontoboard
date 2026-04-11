"""Task service — CRUD for tasks and comments."""

from sqlalchemy.orm import Session

from app.models.task import Task, TaskComment
from app.models.user import User


def list_tasks(db: Session, board_id: int, status: str | None = None, assignee_id: int | None = None) -> list[Task]:
    q = db.query(Task).filter(Task.board_id == board_id)
    if status:
        q = q.filter(Task.status == status)
    if assignee_id:
        q = q.filter(Task.assignee_id == assignee_id)
    return q.order_by(Task.created_at.desc()).all()


def get_task(db: Session, task_id: int) -> Task | None:
    return db.query(Task).filter(Task.id == task_id).first()


def create_task(
    db: Session,
    board_id: int,
    title: str,
    created_by: User,
    description: str = "",
    assignee: User | None = None,
    entity_iri: str = "",
    priority: str = "medium",
    due_date=None,
) -> Task:
    task = Task(
        board_id=board_id,
        title=title,
        description=description,
        created_by_id=created_by.id,
        assignee_id=assignee.id if assignee else None,
        entity_iri=entity_iri,
        priority=priority,
        due_date=due_date,
    )
    db.add(task)
    db.commit()
    db.refresh(task)
    return task


def update_task(db: Session, task: Task, **fields) -> Task:
    for key, val in fields.items():
        if val is not None and hasattr(task, key):
            setattr(task, key, val)
    db.commit()
    db.refresh(task)
    return task


def delete_task(db: Session, task: Task) -> None:
    db.delete(task)
    db.commit()


def list_comments(db: Session, task_id: int) -> list[TaskComment]:
    return db.query(TaskComment).filter(TaskComment.task_id == task_id).order_by(TaskComment.created_at).all()


def add_comment(db: Session, task_id: int, user_id: int, text: str) -> TaskComment:
    comment = TaskComment(task_id=task_id, user_id=user_id, text=text)
    db.add(comment)
    db.commit()
    db.refresh(comment)
    return comment


def task_to_out(task: Task) -> dict:
    """Convert Task ORM to dict matching TaskOut schema."""
    return {
        "id": task.id,
        "title": task.title,
        "description": task.description,
        "status": task.status,
        "priority": task.priority,
        "assignee_username": task.assignee.username if task.assignee else None,
        "assignee_id": task.assignee_id,
        "created_by_username": task.created_by.username if task.created_by else "",
        "entity_iri": task.entity_iri,
        "github_issue_url": task.github_issue_url,
        "github_issue_number": task.github_issue_number,
        "comments_count": len(task.comments),
        "due_date": task.due_date,
        "created_at": task.created_at,
        "updated_at": task.updated_at,
    }
