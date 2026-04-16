/**
 * mergeEngine — Semantic merge rules for concurrent ontology operations.
 *
 * Phase 3 of the CRDT collaboration system. Given two concurrent operations
 * that touch the same entity, determines whether they can be auto-merged or
 * require user intervention.
 *
 * Merge rule table:
 *   addClass(X)  + addClass(Y)          → both (different entities)
 *   updateClass(X, label) + updateClass(X, pos) → both (different fields)
 *   updateClass(X, label="A") + updateClass(X, label="B") → conflict
 *   addClass(X)  + removeClass(X)       → add-wins (conservative)
 *   addAxiom(SubClassOf X Y) + addAxiom(SubClassOf X Z) → both (additive)
 *   moveEntity(X, pos1) + moveEntity(X, pos2) → last-write-wins (ephemeral)
 */

import type { OntologyOperation, OntologyOpType } from "./useOperationSync";

export type MergeAction =
  | "both"            // both ops apply cleanly, no conflict
  | "conflict"        // real conflict — user must decide
  | "last-write-wins" // later timestamp wins (position-only edits)
  | "add-wins";       // addition beats deletion (conservative)

export interface MergeResult {
  action: MergeAction;
  reason: string;
}

/** Fields considered "positional" — last-write-wins, not worth conflicting. */
const POSITION_FIELDS = new Set(["x", "y", "w", "h"]);

/** True if the set of update keys contains only positional fields. */
function isPositionOnly(updates: Record<string, any>): boolean {
  return Object.keys(updates).every((k) => POSITION_FIELDS.has(k));
}

/** True if two sets of update keys overlap on any non-position field. */
function hasFieldOverlap(
  updatesA: Record<string, any>,
  updatesB: Record<string, any>,
): boolean {
  for (const key of Object.keys(updatesA)) {
    if (POSITION_FIELDS.has(key)) continue;
    if (key in updatesB) return true;
  }
  return false;
}

/** Is this op type an "add" (creation)? */
function isAdd(t: OntologyOpType): boolean {
  return t.startsWith("add") || t === "addSubClassOf";
}

/** Is this op type a "remove" (deletion)? */
function isRemove(t: OntologyOpType): boolean {
  return t.startsWith("remove");
}

/** Is this op type an "update" (mutation of existing entity)? */
function isUpdate(t: OntologyOpType): boolean {
  return t.startsWith("update");
}

/**
 * Classify two concurrent ops on the same entity.
 *
 * @param opA — the earlier op (by timestamp)
 * @param opB — the later op
 */
export function resolveMerge(
  opA: OntologyOperation,
  opB: OntologyOperation,
): MergeResult {
  const tA = opA.type;
  const tB = opB.type;

  // ── Both are adds (e.g. addProperty X→Y, addProperty X→Z) ──
  // Additive by nature — both apply.
  if (isAdd(tA) && isAdd(tB)) {
    return { action: "both", reason: "Additive operations — both applied" };
  }

  // ── Add vs remove on same entity ──
  // Conservative: keep the entity (add wins).
  if (isAdd(tA) && isRemove(tB)) {
    return { action: "add-wins", reason: "Add vs remove — addition preserved" };
  }
  if (isRemove(tA) && isAdd(tB)) {
    return { action: "add-wins", reason: "Remove vs add — addition preserved" };
  }

  // ── Both removes ──
  // Idempotent — both deletions converge to the same state.
  if (isRemove(tA) && isRemove(tB)) {
    return { action: "both", reason: "Both remove the same entity — idempotent" };
  }

  // ── Update vs update (same entity) ──
  if (isUpdate(tA) && isUpdate(tB)) {
    const updA: Record<string, any> = opA.data?.updates || {};
    const updB: Record<string, any> = opB.data?.updates || {};

    // Both position-only → last-write-wins (positions are ephemeral)
    if (isPositionOnly(updA) && isPositionOnly(updB)) {
      return {
        action: "last-write-wins",
        reason: "Both position changes — later wins",
      };
    }

    // One is position-only, the other touches semantic fields → both apply
    if (isPositionOnly(updA) || isPositionOnly(updB)) {
      return {
        action: "both",
        reason: "Position update + semantic update — both applied",
      };
    }

    // Different non-position fields → field-level merge, both apply
    if (!hasFieldOverlap(updA, updB)) {
      return {
        action: "both",
        reason: "Different fields updated — both applied",
      };
    }

    // Same non-position field edited by two users → real conflict
    const overlapping = Object.keys(updA).filter(
      (k) => !POSITION_FIELDS.has(k) && k in updB,
    );
    return {
      action: "conflict",
      reason: `Same field(s) edited: ${overlapping.join(", ")}`,
    };
  }

  // ── Update vs remove ──
  // Someone edited an entity the other user deleted — conflict.
  if (isUpdate(tA) && isRemove(tB)) {
    return { action: "add-wins", reason: "Edit vs remove — edit preserved" };
  }
  if (isRemove(tA) && isUpdate(tB)) {
    return { action: "add-wins", reason: "Remove vs edit — edit preserved" };
  }

  // ── Update vs add (on same entity IRI) ──
  // Add re-creates what update modifies — both apply (add brings fresh state).
  if ((isUpdate(tA) && isAdd(tB)) || (isAdd(tA) && isUpdate(tB))) {
    return { action: "both", reason: "Add + update — both applied" };
  }

  // ── Fallback: unknown combination — surface as conflict to be safe. ──
  return { action: "conflict", reason: "Unknown operation combination" };
}
