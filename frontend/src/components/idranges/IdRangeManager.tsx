/**
 * IdRangeManager — Protege-style ID range allocation and reservation.
 * Shows allocated ranges per user, lets user allocate new range and reserve IDs.
 */

import { useState, useEffect, useCallback } from "react";
import { apiJson } from "../../api";
import styles from "./IdRangeManager.module.css";

interface IdRange {
  owner: string;
  lower: number;
  upper: number;
  prefix: string;
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
  const [prefix, setPrefix] = useState("");
  const [lastReserved, setLastReserved] = useState<string | null>(null);

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
      await apiJson(`/api/idranges/${boardId}/allocate`, {
        method: "POST",
        body: JSON.stringify({ prefix }),
      });
      setPrefix("");
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

      {/* Allocate new range */}
      <div className={styles.allocateRow}>
        <input
          className={styles.input}
          placeholder="Prefix (optional, e.g. EX_)"
          value={prefix}
          onChange={(e) => setPrefix(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && handleAllocate()}
        />
        <button className={styles.allocateBtn} onClick={handleAllocate} disabled={allocating}>
          {allocating ? "..." : "Allocate Range"}
        </button>
      </div>

      {/* Ranges table */}
      <div className={styles.table}>
        {loading ? (
          <div className={styles.empty}>Loading...</div>
        ) : ranges.length === 0 ? (
          <div className={styles.empty}>
            <p>No ID ranges allocated yet.</p>
            <p className={styles.emptyHint}>Click "Allocate Range" to claim your ID range.</p>
          </div>
        ) : (
          <>
            <div className={styles.tableHeader}>
              <span>Owner</span>
              <span>Range</span>
              <span>Prefix</span>
              <span>Used</span>
            </div>
            {ranges.map((r, i) => (
              <div key={i} className={styles.tableRow}>
                <span className={styles.owner}>{r.owner}</span>
                <span className={styles.range}>{r.lower.toString().padStart(7, "0")} - {r.upper.toString().padStart(7, "0")}</span>
                <span className={styles.prefix}>{r.prefix || "—"}</span>
                <span className={styles.used}>{r.used}</span>
              </div>
            ))}
          </>
        )}
      </div>
    </div>
  );
}
