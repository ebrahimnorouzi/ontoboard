/**
 * useOperationSync — Real-time operation-based CRDT sync.
 *
 * Phase 1 (operation log):
 *   Each ontology mutation is recorded as an OntologyOperation and appended to
 *   a shared Y.Array. Remote clients observe the array and apply incoming
 *   operations to their local Zustand store.
 *
 * Phase 2 (conflict detection):
 *   A sliding 2-second window tracks which entities each user has touched.
 *   When two different users edit the same entity within the window, a conflict
 *   is detected.
 *
 * Phase 3 (semantic merge):
 *   Detected conflicts are passed through the merge engine before surfacing.
 *   Auto-resolvable cases (different fields, additive ops, position-only) are
 *   handled silently. Only true conflicts (same semantic field) reach the UI.
 *   Resolution actions: "keep mine", "keep theirs", "keep both".
 *
 * Flow:
 *   Local mutation → original store action (local state + debounced save)
 *                  → push op to Y.Array → propagated by Hocuspocus
 *   Remote op arrives → setState directly (bypasses pushUndo / debouncedSave)
 *                     → conflict check → merge engine → surface or auto-resolve
 */

import { useEffect, useState, useCallback, useRef } from "react";
import * as Y from "yjs";
import { useOntologyStore } from "../store/ontologyStore";
import type {
  OntClass, OntProperty, OntIndividual, OntLiteral,
  StickyNote, CanvasFrame,
} from "../store/ontologyStore";
import { resolveMerge } from "./mergeEngine";
import type { MergeAction } from "./mergeEngine";

export type OntologyOpType =
  | "addClass" | "updateClass" | "removeClass"
  | "addProperty" | "removeProperty" | "addSubClassOf"
  | "addIndividual" | "updateIndividual"
  | "addLiteral" | "updateLiteral" | "removeLiteral"
  | "addStickyNote" | "updateStickyNote" | "removeStickyNote"
  | "addFrame" | "updateFrame" | "removeFrame";

export interface OntologyOperation {
  id: string;
  type: OntologyOpType;
  timestamp: number;
  userId: string;
  data: any;
}

/** A detected conflict: two users edited the same entity concurrently. */
export interface OpConflict {
  id: string;
  entityIri: string;
  localUser: string;
  remoteUser: string;
  localOp: OntologyOperation;
  remoteOp: OntologyOperation;
  detectedAt: number;
  /** Merge classification from the semantic merge engine. */
  mergeAction: MergeAction;
  /** Human-readable reason from the merge engine. */
  mergeReason: string;
}

/** Notification for auto-resolved merges (shown briefly, then fades). */
export interface AutoMergeNotice {
  id: string;
  entityIri: string;
  remoteUser: string;
  action: MergeAction;
  reason: string;
  detectedAt: number;
}

function genId(): string {
  const c: any = typeof crypto !== "undefined" ? crypto : null;
  if (c && typeof c.randomUUID === "function") return c.randomUUID();
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

/** Extract the entity IRIs that an operation touches. */
function getAffectedEntities(op: OntologyOperation): string[] {
  const d = op.data;
  switch (op.type) {
    case "addClass":       return [d.iri];
    case "updateClass":    return [d.iri];
    case "removeClass":    return [d.iri];
    case "addProperty":    return [d.id, d.source_id, d.target_id].filter(Boolean);
    case "removeProperty": return [d.id];
    case "addSubClassOf":  return [d.childIri, d.parentIri];
    case "addIndividual":  return [d.iri, d.class_iri].filter(Boolean);
    case "updateIndividual": return [d.iri];
    case "addLiteral":     return [d.id];
    case "updateLiteral":  return [d.id];
    case "removeLiteral":  return [d.id];
    case "addStickyNote":  return [d.id];
    case "updateStickyNote": return [d.id];
    case "removeStickyNote": return [d.id];
    case "addFrame":       return [d.id];
    case "updateFrame":    return [d.id];
    case "removeFrame":    return [d.id];
    default:               return [];
  }
}

const CONFLICT_WINDOW_MS = 2000;

export function useOperationSync(
  doc: Y.Doc | null,
  boardId: string | undefined,
  userName: string,
) {
  const [conflicts, setConflicts] = useState<OpConflict[]>([]);
  const [autoMerges, setAutoMerges] = useState<AutoMergeNotice[]>([]);

  const dismissConflict = useCallback((conflictId: string) => {
    setConflicts((prev) => prev.filter((c) => c.id !== conflictId));
  }, []);

  const dismissAll = useCallback(() => setConflicts([]), []);

  // Ref so resolution callbacks can access the latest applyRemoteOp without
  // needing the effect to re-run.
  const applyRemoteOpRef = useRef<(op: OntologyOperation) => void>(() => {});

  /**
   * Resolve a true conflict. "keepMine" reverts the remote op by re-applying
   * the local op. "keepTheirs" re-applies the remote op. "keepBoth" is a
   * no-op (both are already applied — just dismiss the banner).
   */
  const resolveConflict = useCallback(
    (conflictId: string, choice: "keepMine" | "keepTheirs" | "keepBoth") => {
      setConflicts((prev) => {
        const conflict = prev.find((c) => c.id === conflictId);
        if (conflict && choice === "keepMine") {
          // Re-apply local op to overwrite whatever the remote op did.
          applyRemoteOpRef.current(conflict.localOp);
        } else if (conflict && choice === "keepTheirs") {
          // Re-apply remote op to overwrite whatever the local op did.
          applyRemoteOpRef.current(conflict.remoteOp);
        }
        // In all cases, dismiss the conflict banner.
        return prev.filter((c) => c.id !== conflictId);
      });
    },
    [],
  );

  useEffect(() => {
    if (!doc || !boardId) return;

    const yOps = doc.getArray<string>("ops");
    const store = useOntologyStore;
    const appliedOps = new Set<string>();

    // Seed with any pre-existing ops so we don't re-apply history that is
    // already reflected in the freshly-loaded backend state.
    for (let i = 0; i < yOps.length; i++) {
      try {
        const raw = yOps.get(i);
        if (typeof raw !== "string") continue;
        const op = JSON.parse(raw) as OntologyOperation;
        if (op?.id) appliedOps.add(op.id);
      } catch { /* ignore malformed */ }
    }

    let isApplyingRemote = false;

    // ── Phase 2+3: conflict detection + semantic merge ─────────
    const recentOps: OntologyOperation[] = [];

    function pruneWindow() {
      const cutoff = Date.now() - CONFLICT_WINDOW_MS;
      while (recentOps.length > 0 && recentOps[0].timestamp < cutoff) {
        recentOps.shift();
      }
    }

    /**
     * Check if `newOp` conflicts with any op from a *different* user.
     * For each detected overlap, run the merge engine:
     *   - "both"            → silent (auto-merged)
     *   - "last-write-wins" → apply later op, notify briefly
     *   - "add-wins"        → re-apply the add op if a remove clobbered it
     *   - "conflict"        → surface to the user via ConflictBanner
     */
    function checkConflicts(newOp: OntologyOperation) {
      pruneWindow();
      const newEntities = new Set(getAffectedEntities(newOp));
      if (newEntities.size === 0) { recentOps.push(newOp); return; }

      const trueConflicts: OpConflict[] = [];
      const notices: AutoMergeNotice[] = [];

      for (const existing of recentOps) {
        if (existing.userId === newOp.userId) continue;
        for (const iri of getAffectedEntities(existing)) {
          if (!newEntities.has(iri)) continue;

          // Order by timestamp: earlier op first
          const [opA, opB] = existing.timestamp <= newOp.timestamp
            ? [existing, newOp] : [newOp, existing];
          const merge = resolveMerge(opA, opB);
          const remoteUser =
            existing.userId === userName ? newOp.userId : existing.userId;
          const localOp =
            existing.userId === userName ? existing : newOp;
          const remoteOp =
            existing.userId === userName ? newOp : existing;

          switch (merge.action) {
            case "both":
              // Nothing to do — both ops are already applied.
              notices.push({
                id: genId(), entityIri: iri, remoteUser,
                action: "both", reason: merge.reason, detectedAt: Date.now(),
              });
              break;

            case "last-write-wins":
              // Re-apply the later op to make sure it wins.
              applyRemoteOpRef.current(opB);
              notices.push({
                id: genId(), entityIri: iri, remoteUser,
                action: "last-write-wins", reason: merge.reason, detectedAt: Date.now(),
              });
              break;

            case "add-wins": {
              // Re-apply whichever op is the "add" (or "update") to undo
              // a concurrent remove.
              const addOp = opA.type.startsWith("remove") ? opB : opA;
              applyRemoteOpRef.current(addOp);
              notices.push({
                id: genId(), entityIri: iri, remoteUser,
                action: "add-wins", reason: merge.reason, detectedAt: Date.now(),
              });
              break;
            }

            case "conflict":
              trueConflicts.push({
                id: genId(), entityIri: iri,
                localUser: userName, remoteUser,
                localOp, remoteOp,
                detectedAt: Date.now(),
                mergeAction: merge.action,
                mergeReason: merge.reason,
              });
              break;
          }
        }
      }

      recentOps.push(newOp);

      if (trueConflicts.length > 0) {
        const seen = new Set<string>();
        const deduped = trueConflicts.filter((c) => {
          const key = `${c.entityIri}:${c.remoteUser}`;
          if (seen.has(key)) return false;
          seen.add(key);
          return true;
        });
        setConflicts((prev) => [...prev, ...deduped]);
      }

      if (notices.length > 0) {
        setAutoMerges((prev) => [...prev, ...notices]);
      }
    }

    // ── Phase 1: operation push ───────────────────────────────
    const pushOp = (type: OntologyOpType, data: any) => {
      if (isApplyingRemote) return;
      const op: OntologyOperation = {
        id: genId(),
        type,
        timestamp: Date.now(),
        userId: userName,
        data,
      };
      appliedOps.add(op.id);
      checkConflicts(op);
      yOps.push([JSON.stringify(op)]);
    };

    // Snapshot original mutations so we can restore them on unmount.
    const s0 = store.getState();
    const orig = {
      addClass: s0.addClass,
      updateClass: s0.updateClass,
      removeClass: s0.removeClass,
      addProperty: s0.addProperty,
      removeProperty: s0.removeProperty,
      addSubClassOf: s0.addSubClassOf,
      addIndividual: s0.addIndividual,
      updateIndividual: s0.updateIndividual,
      addLiteral: s0.addLiteral,
      updateLiteral: s0.updateLiteral,
      removeLiteral: s0.removeLiteral,
      addStickyNote: s0.addStickyNote,
      updateStickyNote: s0.updateStickyNote,
      removeStickyNote: s0.removeStickyNote,
      addFrame: s0.addFrame,
      updateFrame: s0.updateFrame,
      removeFrame: s0.removeFrame,
    };

    // Install wrappers: local mutation runs, then an op is appended to Y.Array.
    store.setState({
      addClass: (cls: OntClass) => { orig.addClass(cls); pushOp("addClass", cls); },
      updateClass: (iri: string, updates: Partial<OntClass>) => {
        orig.updateClass(iri, updates); pushOp("updateClass", { iri, updates });
      },
      removeClass: (iri: string) => { orig.removeClass(iri); pushOp("removeClass", { iri }); },
      addProperty: (prop: OntProperty) => { orig.addProperty(prop); pushOp("addProperty", prop); },
      removeProperty: (id: string) => { orig.removeProperty(id); pushOp("removeProperty", { id }); },
      addSubClassOf: (childIri: string, parentIri: string) => {
        orig.addSubClassOf(childIri, parentIri);
        pushOp("addSubClassOf", { childIri, parentIri });
      },
      addIndividual: (ind: OntIndividual) => { orig.addIndividual(ind); pushOp("addIndividual", ind); },
      updateIndividual: (iri: string, updates: Partial<OntIndividual>) => {
        orig.updateIndividual(iri, updates); pushOp("updateIndividual", { iri, updates });
      },
      addLiteral: (lit: OntLiteral) => { orig.addLiteral(lit); pushOp("addLiteral", lit); },
      updateLiteral: (id: string, updates: Partial<OntLiteral>) => {
        orig.updateLiteral(id, updates); pushOp("updateLiteral", { id, updates });
      },
      removeLiteral: (id: string) => { orig.removeLiteral(id); pushOp("removeLiteral", { id }); },
      addStickyNote: (note: StickyNote) => { orig.addStickyNote(note); pushOp("addStickyNote", note); },
      updateStickyNote: (id: string, updates: Partial<StickyNote>) => {
        orig.updateStickyNote(id, updates); pushOp("updateStickyNote", { id, updates });
      },
      removeStickyNote: (id: string) => { orig.removeStickyNote(id); pushOp("removeStickyNote", { id }); },
      addFrame: (frame: CanvasFrame) => { orig.addFrame(frame); pushOp("addFrame", frame); },
      updateFrame: (id: string, updates: Partial<CanvasFrame>) => {
        orig.updateFrame(id, updates); pushOp("updateFrame", { id, updates });
      },
      removeFrame: (id: string) => { orig.removeFrame(id); pushOp("removeFrame", { id }); },
    } as any);

    // Apply a remote op (or re-apply for merge resolution) directly to state.
    // Skips pushUndo/provenance/debouncedSave — the originator's save is
    // authoritative.
    const applyRemoteOp = (op: OntologyOperation) => {
      isApplyingRemote = true;
      try {
        const s = store.getState();
        switch (op.type) {
          case "addClass": {
            const cls = op.data as OntClass;
            store.setState({ classes: [...s.classes.filter((c) => c.iri !== cls.iri), cls] });
            break;
          }
          case "updateClass": {
            const { iri, updates } = op.data;
            store.setState({
              classes: s.classes.map((c) => (c.iri === iri ? { ...c, ...updates } : c)),
            });
            break;
          }
          case "removeClass": {
            const { iri } = op.data;
            store.setState({
              classes: s.classes.filter((c) => c.iri !== iri),
              properties: s.properties.filter((p) => p.source_id !== iri && p.target_id !== iri),
            });
            break;
          }
          case "addProperty": {
            const prop = op.data as OntProperty;
            store.setState({ properties: [...s.properties.filter((p) => p.id !== prop.id), prop] });
            break;
          }
          case "removeProperty": {
            const { id } = op.data;
            store.setState({ properties: s.properties.filter((p) => p.id !== id) });
            break;
          }
          case "addSubClassOf": {
            const { childIri, parentIri } = op.data;
            const id = `subClassOf_${childIri}_${parentIri}`;
            store.setState({
              properties: [
                ...s.properties.filter((p) => p.id !== id),
                {
                  id, iri: "rdfs:subClassOf", label: "rdfs:subClassOf",
                  source_id: childIri, target_id: parentIri, property_type: "annotation",
                },
              ],
            });
            break;
          }
          case "addIndividual": {
            const ind = op.data as OntIndividual;
            store.setState({
              individuals: [...s.individuals.filter((i) => i.iri !== ind.iri), ind],
            });
            break;
          }
          case "updateIndividual": {
            const { iri, updates } = op.data;
            store.setState({
              individuals: s.individuals.map((i) => (i.iri === iri ? { ...i, ...updates } : i)),
            });
            break;
          }
          case "addLiteral": {
            const lit = op.data as OntLiteral;
            store.setState({ literals: [...s.literals.filter((l) => l.id !== lit.id), lit] });
            break;
          }
          case "updateLiteral": {
            const { id, updates } = op.data;
            store.setState({
              literals: s.literals.map((l) => (l.id === id ? { ...l, ...updates } : l)),
            });
            break;
          }
          case "removeLiteral": {
            const { id } = op.data;
            store.setState({
              literals: s.literals.filter((l) => l.id !== id),
              properties: s.properties.filter((p) => p.target_id !== id),
            });
            break;
          }
          case "addStickyNote": {
            const note = op.data as StickyNote;
            store.setState({
              stickyNotes: [...s.stickyNotes.filter((n) => n.id !== note.id), note],
            });
            break;
          }
          case "updateStickyNote": {
            const { id, updates } = op.data;
            store.setState({
              stickyNotes: s.stickyNotes.map((n) => (n.id === id ? { ...n, ...updates } : n)),
            });
            break;
          }
          case "removeStickyNote": {
            const { id } = op.data;
            store.setState({ stickyNotes: s.stickyNotes.filter((n) => n.id !== id) });
            break;
          }
          case "addFrame": {
            const frame = op.data as CanvasFrame;
            store.setState({ frames: [...s.frames.filter((f) => f.id !== frame.id), frame] });
            break;
          }
          case "updateFrame": {
            const { id, updates } = op.data;
            store.setState({
              frames: s.frames.map((f) => (f.id === id ? { ...f, ...updates } : f)),
            });
            break;
          }
          case "removeFrame": {
            const { id } = op.data;
            store.setState({ frames: s.frames.filter((f) => f.id !== id) });
            break;
          }
        }
      } finally {
        isApplyingRemote = false;
      }
    };

    // Expose applyRemoteOp to the resolution callbacks via ref.
    applyRemoteOpRef.current = applyRemoteOp;

    const observer = (event: Y.YArrayEvent<string>) => {
      // Ignore our own pushes — we already applied them locally via the
      // original mutation before pushOp ran.
      if (event.transaction.local) return;
      for (const change of event.changes.delta) {
        if (!change.insert || !Array.isArray(change.insert)) continue;
        for (const raw of change.insert as string[]) {
          try {
            const op = JSON.parse(raw) as OntologyOperation;
            if (!op?.id || appliedOps.has(op.id)) continue;
            appliedOps.add(op.id);
            checkConflicts(op);
            applyRemoteOp(op);
          } catch { /* ignore malformed */ }
        }
      }
    };
    yOps.observe(observer);

    // Auto-expire stale conflicts (10s) and auto-merge notices (5s).
    const conflictGc = setInterval(() => {
      const conflictCutoff = Date.now() - 10_000;
      setConflicts((prev) => {
        const next = prev.filter((c) => c.detectedAt > conflictCutoff);
        return next.length === prev.length ? prev : next;
      });
      const noticeCutoff = Date.now() - 5_000;
      setAutoMerges((prev) => {
        const next = prev.filter((n) => n.detectedAt > noticeCutoff);
        return next.length === prev.length ? prev : next;
      });
    }, 2000);

    return () => {
      clearInterval(conflictGc);
      yOps.unobserve(observer);
      store.setState(orig as any);
    };
  }, [doc, boardId, userName]);

  return { conflicts, autoMerges, dismissConflict, dismissAll, resolveConflict };
}
