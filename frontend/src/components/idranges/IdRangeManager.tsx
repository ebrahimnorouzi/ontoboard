/**
 * IdRangeManager — Protege-style ID range allocation, editing, and reservation.
 * Allows editing prefix/bounds of existing ranges, and prompts for details on creation.
 */

import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../../api";
import styles from "./IdRangeManager.module.css";

interface IdRange {
  owner: string;
  lower: string;
  upper: string;
  prefix: string;
  current: string;
  used: number;
}

interface Props {
  boardId: string;
}

export default function IdRangeManager({ boardId }: Props) {
  const [ranges, setRanges] = useState<IdRange[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [allocating, setAllocating] = useState(false);
  const [lastReserved, setLastReserved] = useState<string | null>(null);

  // Allocation form state
  const [showAllocateForm, setShowAllocateForm] = useState(false);
  const [allocPrefix, setAllocPrefix] = useState("");
  const [allocPrefixName, setAllocPrefixName] = useState("");

  // Edit state
  const [editingOwner, setEditingOwner] = useState<string | null>(null);
  const [editPrefix, setEditPrefix] = useState("");
  const [editLower, setEditLower] = useState("");
  const [editUpper, setEditUpper] = useState("");

  const loadRanges = useCallback(async () => {
    setLoading(true);
    try {
      const data = await apiJson<IdRange[]>(`/api/idranges/${boardId}`);
      setRanges(data);
    } catch { setRanges([]); }
    setLoading(false);
  }, [boardId]);

  useEffect(() => { loadRanges(); }, [loadRanges]);

  const handleAllocate = async () => {
    setAllocating(true);
    setError("");
    try {
      const prefix = allocPrefix || (allocPrefixName ? `http://example.org/${allocPrefixName}#` : "");
      await apiJson(`/api/idranges/${boardId}/allocate`, {
        method: "POST",
        body: JSON.stringify({ prefix }),
      });
      setAllocPrefix("");
      setAllocPrefixName("");
      setShowAllocateForm(false);
      await loadRanges();
    } catch (e: any) {
      setError(e.message || "Allocation failed");
    }
    setAllocating(false);
  };

  const handleReserve = async () => {
    setError("");
    try {
      const data = await apiJson<{ iri: string }>(`/api/idranges/${boardId}/reserve`, {
        method: "POST",
      });
      setLastReserved(data.iri);
      setTimeout(() => setLastReserved(null), 5000);
    } catch (e: any) {
      setError(e.message || "No available IDs in your range");
    }
  };

  const startEdit = (r: IdRange) => {
    setEditingOwner(r.owner);
    setEditPrefix(r.prefix);
    setEditLower(r.lower);
    setEditUpper(r.upper);
  };

  const cancelEdit = () => setEditingOwner(null);

  const saveEdit = async () => {
    if (!editingOwner) return;
    setError("");
    try {
      await apiJson(`/api/idranges/${boardId}/${editingOwner}`, {
        method: "PUT",
        body: JSON.stringify({ prefix: editPrefix, lower: editLower, upper: editUpper }),
      });
      setEditingOwner(null);
      await loadRanges();
    } catch (e: any) {
      setError(e.message || "Update failed");
    }
  };

  const deleteRange = async (owner: string) => {
    if (!confirm(`Delete ID range for "${owner}"?`)) return;
    setError("");
    try {
      await apiJson(`/api/idranges/${boardId}/${owner}`, { method: "DELETE" });
      await loadRanges();
    } catch (e: any) {
      setError(e.message || "Delete failed");
    }
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <h3 className={styles.title}>ID Ranges</h3>
        <button className={styles.reserveBtn} onClick={handleReserve}>Reserve Next ID</button>
      </div>

      {error && <div className={styles.error} onClick={() => setError("")}>{error}</div>}
      {lastReserved && <div className={styles.success}>Reserved: <code>{lastReserved}</code></div>}

      <div className={styles.hint}>
        ID ranges ensure unique identifiers per contributor. Each user gets a non-overlapping range.
      </div>

      {/* Allocate new range — expanded form */}
      {showAllocateForm ? (
        <div className={styles.allocateForm}>
          <div className={styles.formTitle}>Allocate New Range</div>
          <div className={styles.formField}>
            <label className={styles.formLabel}>Prefix name (short)</label>
            <input className={styles.input} placeholder="e.g. myonto"
                   value={allocPrefixName} onChange={(e) => setAllocPrefixName(e.target.value)} />
          </div>
          <div className={styles.formField}>
            <label className={styles.formLabel}>Full prefix IRI (optional, auto-generated if empty)</label>
            <input className={styles.input} placeholder="http://example.org/ontology#"
                   value={allocPrefix} onChange={(e) => setAllocPrefix(e.target.value)} />
          </div>
          {allocPrefixName && !allocPrefix && (
            <div className={styles.formHint}>
              Will use: <code>http://example.org/{allocPrefixName}#</code>
            </div>
          )}
          <div className={styles.formActions}>
            <button className={styles.allocateBtn} onClick={handleAllocate} disabled={allocating}>
              {allocating ? "Allocating..." : "Allocate"}
            </button>
            <button className={styles.cancelBtn} onClick={() => setShowAllocateForm(false)}>Cancel</button>
          </div>
        </div>
      ) : (
        <button className={styles.allocateToggle} onClick={() => setShowAllocateForm(true)}>
          + Allocate New Range
        </button>
      )}

      {/* Ranges table */}
      <div className={styles.table}>
        {loading ? (
          <div className={styles.empty}>Loading...</div>
        ) : ranges.length === 0 ? (
          <div className={styles.empty}>
            <p>No ID ranges allocated yet.</p>
            <p className={styles.emptyHint}>Click "Allocate New Range" to claim your ID range.</p>
          </div>
        ) : (
          <>
            <div className={styles.tableHeader}>
              <span>Owner</span>
              <span>Range</span>
              <span>Prefix</span>
              <span>Used</span>
              <span></span>
            </div>
            {ranges.map((r) => (
              editingOwner === r.owner ? (
                <div key={r.owner} className={styles.editRow}>
                  <span className={styles.owner}>{r.owner}</span>
                  <div className={styles.editFields}>
                    <div className={styles.editField}>
                      <label className={styles.editFieldLabel}>Prefix</label>
                      <input className={styles.editInput} value={editPrefix}
                             onChange={(e) => setEditPrefix(e.target.value)} />
                    </div>
                    <div className={styles.editFieldRow}>
                      <div className={styles.editField}>
                        <label className={styles.editFieldLabel}>Lower</label>
                        <input className={styles.editInput} value={editLower}
                               onChange={(e) => setEditLower(e.target.value)} />
                      </div>
                      <div className={styles.editField}>
                        <label className={styles.editFieldLabel}>Upper</label>
                        <input className={styles.editInput} value={editUpper}
                               onChange={(e) => setEditUpper(e.target.value)} />
                      </div>
                    </div>
                    <div className={styles.editActions}>
                      <button className={styles.saveBtn} onClick={saveEdit}>Save</button>
                      <button className={styles.cancelBtn} onClick={cancelEdit}>Cancel</button>
                    </div>
                  </div>
                </div>
              ) : (
                <div key={r.owner} className={styles.tableRow}>
                  <span className={styles.owner}>{r.owner}</span>
                  <span className={styles.range}>{r.lower} - {r.upper}</span>
                  <span className={styles.prefix} title={r.prefix}>{r.prefix.split("/").pop()?.replace("#", "") || r.prefix}</span>
                  <span className={styles.used}>{r.used ?? 0}</span>
                  <span className={styles.rowActions}>
                    <button className={styles.editBtn} onClick={() => startEdit(r)} title="Edit range">&#9998;</button>
                    <button className={styles.deleteBtn} onClick={() => deleteRange(r.owner)} title="Delete range">&times;</button>
                  </span>
                </div>
              )
            ))}
          </>
        )}
      </div>
    </div>
  );
}
