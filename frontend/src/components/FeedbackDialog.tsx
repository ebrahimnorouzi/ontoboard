/**
 * FeedbackDialog — Report issues or give feedback.
 * Opens a pre-filled GitHub issue on the ontoboard repo.
 */
import { useState } from "react";
import { useAuth } from "../auth";
import styles from "./FeedbackDialog.module.css";

const GITHUB_REPO = "https://github.com/ebrahimnorouzi/ontoboard/issues/new";

const TEMPLATES = [
  {
    id: "bug",
    icon: "\uD83D\uDC1B",
    label: "Bug Report",
    title: "[Bug] ",
    body: `## Bug Description\nA clear description of the bug.\n\n## Steps to Reproduce\n1. Go to '...'\n2. Click on '...'\n3. See error\n\n## Expected Behavior\nWhat should have happened.\n\n## Screenshots\nIf applicable, add screenshots.\n\n## Environment\n- Browser: \n- OS: \n`,
  },
  {
    id: "feature",
    icon: "\uD83D\uDCA1",
    label: "Feature Request",
    title: "[Feature] ",
    body: `## Feature Description\nA clear description of the feature you'd like.\n\n## Use Case\nWhy would this be useful?\n\n## Proposed Solution\nDescribe how it might work.\n\n## Alternatives Considered\nAny alternative solutions?\n`,
  },
  {
    id: "question",
    icon: "\u2753",
    label: "Question",
    title: "[Question] ",
    body: `## Question\nWhat would you like to know?\n\n## Context\nAny relevant context about what you're trying to do.\n`,
  },
  {
    id: "odk",
    icon: "\u2699\uFE0F",
    label: "ODK / ROBOT Issue",
    title: "[ODK] ",
    body: `## ODK/ROBOT Issue\nDescribe the problem with ODK or ROBOT commands.\n\n## Command / Workflow\nWhich operation failed? (e.g., Build, Reason, Refresh Imports)\n\n## Error Output\n\`\`\`\nPaste error output here\n\`\`\`\n\n## Board ID\n\n## Configuration\n`,
  },
  {
    id: "ux",
    icon: "\uD83C\uDFA8",
    label: "UI/UX Feedback",
    title: "[UX] ",
    body: `## UI/UX Feedback\nWhat could be improved in the user interface?\n\n## Current Behavior\nHow does it work now?\n\n## Suggested Improvement\nHow should it work?\n\n## Mockup / Screenshot\nOptional — attach an image.\n`,
  },
];

interface Props {
  onClose: () => void;
}

export default function FeedbackDialog({ onClose }: Props) {
  const { user } = useAuth();
  const [selectedTemplate, setSelectedTemplate] = useState<string | null>(null);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");

  const handleSelectTemplate = (tpl: typeof TEMPLATES[0]) => {
    setSelectedTemplate(tpl.id);
    setTitle(tpl.title);
    setBody(tpl.body);
  };

  const handleSubmit = () => {
    const fullBody = body + `\n\n---\n*Reported by ${user?.username || "anonymous"} via OntoBoard feedback dialog*`;
    const url = `${GITHUB_REPO}?title=${encodeURIComponent(title)}&body=${encodeURIComponent(fullBody)}&labels=${encodeURIComponent(selectedTemplate || "feedback")}`;
    window.open(url, "_blank");
    onClose();
  };

  return (
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className={styles.dialog}>
        <div className={styles.header}>
          <h2 className={styles.title}>Send Feedback</h2>
          <button className={styles.closeBtn} onClick={onClose}>&times;</button>
        </div>

        {!selectedTemplate ? (
          <div className={styles.body}>
            <p className={styles.hint}>What would you like to report?</p>
            <div className={styles.templateGrid}>
              {TEMPLATES.map((tpl) => (
                <button key={tpl.id} className={styles.templateCard} onClick={() => handleSelectTemplate(tpl)}>
                  <span className={styles.templateIcon}>{tpl.icon}</span>
                  <span className={styles.templateLabel}>{tpl.label}</span>
                </button>
              ))}
            </div>
            <p className={styles.footerHint}>
              Issues are created on <a href="https://github.com/ebrahimnorouzi/ontoboard/issues" target="_blank" rel="noreferrer">GitHub</a>.
              You'll need a GitHub account to submit.
            </p>
          </div>
        ) : (
          <div className={styles.body}>
            <button className={styles.backBtn} onClick={() => setSelectedTemplate(null)}>&larr; Back to templates</button>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>Title</label>
              <input className={styles.formInput} value={title} onChange={(e) => setTitle(e.target.value)}
                     placeholder="Brief description..." />
            </div>
            <div className={styles.formGroup}>
              <label className={styles.formLabel}>Description (Markdown)</label>
              <textarea className={styles.formTextarea} value={body} onChange={(e) => setBody(e.target.value)}
                        rows={12} />
            </div>
            <div className={styles.actions}>
              <button className={styles.cancelBtn} onClick={onClose}>Cancel</button>
              <button className={styles.submitBtn} onClick={handleSubmit} disabled={!title.trim()}>
                Open on GitHub &rarr;
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
