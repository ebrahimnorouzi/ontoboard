/**
 * SwrlPanel — View/create/delete SWRL rules.
 * Integrated into ReasoningPanel as a collapsible section.
 */

import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../../api";
import styles from "./SwrlPanel.module.css";

interface SwrlRule {
  id: string;
  label: string;
  antecedent: string;
  consequent: string;
}

interface Props {
  boardId: string;
}

export default function SwrlPanel({ boardId }: Props) {
  const [rules, setRules] = useState<SwrlRule[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [showAdd, setShowAdd] = useState(false);
  const [antecedent, setAntecedent] = useState("");
  const [consequent, setConsequent] = useState("");
  const [adding, setAdding] = useState(false);

  const loadRules = useCallback(async () => {
    try {
      const data = await apiJson<SwrlRule[]>(`/api/reasoning/${boardId}/swrl`);
      setRules(data);
    } catch { setRules([]); }
    finally { setLoading(false); }
  }, [boardId]);

  useEffect(() => { loadRules(); }, [loadRules]);

  const addRule = useCallback(async () => {
    if (!antecedent.trim() || !consequent.trim()) return;
    setAdding(true);
    setError("");
    try {
      await apiJson(`/api/reasoning/${boardId}/swrl`, {
        method: "POST",
        body: JSON.stringify({ antecedent: antecedent.trim(), consequent: consequent.trim() }),
      });
      setAntecedent("");
      setConsequent("");
      setShowAdd(false);
      await loadRules();
    } catch (e: any) { setError(e.message || "Failed to add rule"); }
    finally { setAdding(false); }
  }, [boardId, antecedent, consequent, loadRules]);

  const deleteRule = useCallback(async (ruleId: string) => {
    try {
      await apiJson(`/api/reasoning/${boardId}/swrl/${encodeURIComponent(ruleId)}`, { method: "DELETE" });
      await loadRules();
    } catch (e: any) { setError(e.message || "Delete failed"); }
  }, [boardId, loadRules]);

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h4 className={styles.title}>SWRL Rules ({rules.length})</h4>
        <button className={styles.addToggle} onClick={() => setShowAdd(!showAdd)}>
          {showAdd ? "Cancel" : "+ Add Rule"}
        </button>
      </div>

      {error && <div className={styles.error} onClick={() => setError("")}>{error}</div>}

      {showAdd && (
        <div className={styles.addForm}>
          <div className={styles.formRow}>
            <label className={styles.formLabel}>If (antecedent):</label>
            <textarea className={styles.formInput} value={antecedent}
              onChange={(e) => setAntecedent(e.target.value)} rows={2}
              placeholder="Person(?x), hasAge(?x, ?a), greaterThan(?a, 18)" />
          </div>
          <div className={styles.formArrow}>-&gt;</div>
          <div className={styles.formRow}>
            <label className={styles.formLabel}>Then (consequent):</label>
            <textarea className={styles.formInput} value={consequent}
              onChange={(e) => setConsequent(e.target.value)} rows={2}
              placeholder="Adult(?x)" />
          </div>
          <button className={styles.addBtn} onClick={addRule}
            disabled={adding || !antecedent.trim() || !consequent.trim()}>
            {adding ? "Adding..." : "Add Rule"}
          </button>
        </div>
      )}

      {loading ? (
        <div className={styles.hint}>Loading rules...</div>
      ) : rules.length === 0 ? (
        <div className={styles.hint}>No SWRL rules defined.</div>
      ) : (
        <div className={styles.ruleList}>
          {rules.map((rule) => (
            <div key={rule.id} className={styles.ruleCard}>
              <div className={styles.ruleBody}>
                <span className={styles.ruleAnt}>{rule.antecedent}</span>
                <span className={styles.ruleArrow}>-&gt;</span>
                <span className={styles.ruleCon}>{rule.consequent}</span>
              </div>
              <button className={styles.ruleDelete} onClick={() => deleteRule(rule.id)}
                title="Delete rule">&times;</button>
            </div>
          ))}
        </div>
      )}

      <div className={styles.infoText}>
        SWRL rules extend OWL with if-then logic. Format: <code>Atom(?x), Atom(?y) -&gt; Atom(?z)</code>
      </div>
    </div>
  );
}
