import { useState } from "react";
import { useTasks, TaskOut } from "../../hooks/useTasks";
import styles from "./TaskBoard.module.css";

interface Props {
  boardId: string;
  members?: string[];
}

const COLUMNS = [
  { id: "todo", label: "To Do" },
  { id: "in_progress", label: "In Progress" },
  { id: "review", label: "Review" },
  { id: "done", label: "Done" },
];

const PRIORITIES: Record<string, string> = {
  critical: "\u{1F534}",
  high: "\u{1F7E0}",
  medium: "\u{1F7E1}",
  low: "\u{1F7E2}",
};

export default function TaskBoard({ boardId, members = [] }: Props) {
  const { tasks, loading, createTask, updateTask, deleteTask } = useTasks(boardId);
  const [showCreate, setShowCreate] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newDesc, setNewDesc] = useState("");
  const [newPriority, setNewPriority] = useState("medium");
  const [newAssignee, setNewAssignee] = useState("");

  const handleCreate = async () => {
    if (!newTitle.trim()) return;
    await createTask({
      title: newTitle,
      description: newDesc || undefined,
      priority: newPriority,
      assignee_username: newAssignee || undefined,
    });
    setNewTitle("");
    setNewDesc("");
    setShowCreate(false);
  };

  const handleDrop = (taskId: number, newStatus: string) => {
    updateTask(taskId, { status: newStatus });
  };

  if (loading) return <div className={styles.loading}>Loading tasks...</div>;

  return (
    <div className={styles.container}>
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.count}>{tasks.length} tasks</span>
        <button className={styles.addBtn} onClick={() => setShowCreate(!showCreate)}>
          {showCreate ? "Cancel" : "+ Task"}
        </button>
      </div>

      {/* Create form */}
      {showCreate && (
        <div className={styles.createForm}>
          <input
            className={styles.input}
            placeholder="Task title..."
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && handleCreate()}
            autoFocus
          />
          <textarea
            className={styles.input}
            placeholder="Description (optional)..."
            value={newDesc}
            onChange={(e) => setNewDesc(e.target.value)}
            rows={2}
            style={{ resize: "vertical" }}
          />
          <div className={styles.createRow}>
            <select className={styles.select} value={newPriority} onChange={(e) => setNewPriority(e.target.value)}>
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
              <option value="critical">Critical</option>
            </select>
            <input
              className={styles.input}
              placeholder="Assignee username"
              list="member-suggestions"
              value={newAssignee}
              onChange={(e) => setNewAssignee(e.target.value)}
            />
            <datalist id="member-suggestions">
              {members.map((m) => (
                <option key={m} value={m} />
              ))}
            </datalist>
            <button className={styles.submitBtn} onClick={handleCreate}>Create</button>
          </div>
        </div>
      )}

      {/* Kanban columns */}
      <div className={styles.kanban}>
        {COLUMNS.map((col) => {
          const colTasks = tasks.filter((t) => t.status === col.id);
          return (
            <div
              key={col.id}
              className={styles.column}
              onDragOver={(e) => e.preventDefault()}
              onDrop={(e) => {
                const taskId = parseInt(e.dataTransfer.getData("taskId"), 10);
                if (taskId) handleDrop(taskId, col.id);
              }}
            >
              <div className={styles.colHeader}>
                <span className={styles.colTitle}>{col.label}</span>
                <span className={styles.colCount}>{colTasks.length}</span>
              </div>
              <div className={styles.colCards}>
                {colTasks.map((task) => (
                  <TaskCard
                    key={task.id}
                    task={task}
                    onDelete={() => deleteTask(task.id)}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function TaskCard({ task, onDelete }: { task: TaskOut; onDelete: () => void }) {
  return (
    <div
      className={styles.card}
      draggable
      onDragStart={(e) => e.dataTransfer.setData("taskId", String(task.id))}
    >
      <div className={styles.cardHeader}>
        <span className={styles.priorityDot} title={task.priority}>
          {PRIORITIES[task.priority] || "\u26AA"}
        </span>
        <span className={styles.cardTitle}>{task.title}</span>
      </div>
      {task.description && (
        <p className={styles.cardDesc}>{task.description}</p>
      )}
      <div className={styles.cardMeta}>
        {task.assignee_username && (
          <span className={styles.assignee}>{task.assignee_username}</span>
        )}
        {task.entity_iri && (
          <span className={styles.entityLink} title={task.entity_iri}>entity</span>
        )}
        {task.github_issue_url && (
          <a href={task.github_issue_url} target="_blank" rel="noreferrer" className={styles.ghLink}>
            #{task.github_issue_number}
          </a>
        )}
        {task.comments_count > 0 && (
          <span className={styles.commentCount}>{task.comments_count} comments</span>
        )}
        <button className={styles.deleteBtn} onClick={onDelete} title="Delete">&times;</button>
      </div>
    </div>
  );
}
