/**
 * Tests for the operation sync system (Phases 1-3).
 *
 * Since useOperationSync is a React hook that depends on Yjs and Zustand,
 * these tests focus on the pure logic extracted from the hook:
 *   - Entity extraction from operations
 *   - Conflict detection window mechanics
 *   - Operation serialization / deserialization
 *   - Integration with the merge engine
 *
 * The hook's React lifecycle behaviour (mount/unmount, mutation wrapping)
 * is validated via manual/integration testing.
 */

import { describe, it, expect } from "vitest";
import type { OntologyOperation, OntologyOpType, OpConflict, AutoMergeNotice } from "../useOperationSync";
import { resolveMerge } from "../mergeEngine";

// ── Re-implement getAffectedEntities for testing (module-private) ──

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

function op(
  type: OntologyOpType,
  data: any,
  overrides?: Partial<OntologyOperation>,
): OntologyOperation {
  return {
    id: `test-${Math.random().toString(36).slice(2, 8)}`,
    type,
    timestamp: Date.now(),
    userId: "user-a",
    data,
    ...overrides,
  };
}

// ── Entity extraction tests ──────────────────────────────────

describe("getAffectedEntities", () => {
  it("addClass extracts IRI", () => {
    const o = op("addClass", { iri: "http://ex.org#A", label: "A", x: 0, y: 0 });
    expect(getAffectedEntities(o)).toEqual(["http://ex.org#A"]);
  });

  it("updateClass extracts IRI from data.iri", () => {
    const o = op("updateClass", { iri: "http://ex.org#B", updates: { label: "New" } });
    expect(getAffectedEntities(o)).toEqual(["http://ex.org#B"]);
  });

  it("addProperty extracts id, source_id, target_id", () => {
    const o = op("addProperty", {
      id: "p1", source_id: "http://ex.org#A", target_id: "http://ex.org#B",
    });
    expect(getAffectedEntities(o)).toEqual(["p1", "http://ex.org#A", "http://ex.org#B"]);
  });

  it("addProperty handles missing source/target gracefully", () => {
    const o = op("addProperty", { id: "p1" });
    expect(getAffectedEntities(o)).toEqual(["p1"]);
  });

  it("addSubClassOf extracts both IRIs", () => {
    const o = op("addSubClassOf", { childIri: "http://ex.org#C", parentIri: "http://ex.org#P" });
    expect(getAffectedEntities(o)).toEqual(["http://ex.org#C", "http://ex.org#P"]);
  });

  it("addIndividual extracts IRI and class_iri", () => {
    const o = op("addIndividual", { iri: "http://ex.org#i1", class_iri: "http://ex.org#A" });
    expect(getAffectedEntities(o)).toEqual(["http://ex.org#i1", "http://ex.org#A"]);
  });

  it("removeLiteral extracts id", () => {
    const o = op("removeLiteral", { id: "lit-123" });
    expect(getAffectedEntities(o)).toEqual(["lit-123"]);
  });

  it("updateFrame extracts id", () => {
    const o = op("updateFrame", { id: "frame-1", updates: { x: 10 } });
    expect(getAffectedEntities(o)).toEqual(["frame-1"]);
  });
});

// ── Conflict window simulation ───────────────────────────────

describe("conflict detection logic", () => {
  const CONFLICT_WINDOW_MS = 2000;

  /**
   * Simulates the conflict detection window from useOperationSync.
   * Returns { conflicts, notices } for a sequence of operations.
   */
  function simulate(ops: OntologyOperation[], localUser: string) {
    const recentOps: OntologyOperation[] = [];
    const conflicts: OpConflict[] = [];
    const notices: AutoMergeNotice[] = [];

    for (const newOp of ops) {
      // Prune window
      const cutoff = newOp.timestamp - CONFLICT_WINDOW_MS;
      while (recentOps.length > 0 && recentOps[0].timestamp < cutoff) {
        recentOps.shift();
      }

      const newEntities = new Set(getAffectedEntities(newOp));
      for (const existing of recentOps) {
        if (existing.userId === newOp.userId) continue;
        for (const iri of getAffectedEntities(existing)) {
          if (!newEntities.has(iri)) continue;

          const [opA, opB] = existing.timestamp <= newOp.timestamp
            ? [existing, newOp] : [newOp, existing];
          const merge = resolveMerge(opA, opB);
          const remoteUser =
            existing.userId === localUser ? newOp.userId : existing.userId;

          if (merge.action === "conflict") {
            conflicts.push({
              id: `c-${conflicts.length}`,
              entityIri: iri,
              localUser,
              remoteUser,
              localOp: existing.userId === localUser ? existing : newOp,
              remoteOp: existing.userId === localUser ? newOp : existing,
              detectedAt: newOp.timestamp,
              mergeAction: merge.action,
              mergeReason: merge.reason,
            });
          } else {
            notices.push({
              id: `n-${notices.length}`,
              entityIri: iri,
              remoteUser,
              action: merge.action,
              reason: merge.reason,
              detectedAt: newOp.timestamp,
            });
          }
        }
      }
      recentOps.push(newOp);
    }

    return { conflicts, notices };
  }

  it("no conflict when same user edits twice", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { label: "v2" } }, { userId: "alice", timestamp: now + 500 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
    expect(result.notices).toHaveLength(0);
  });

  it("conflict when two users edit same field within window", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { label: "v2" } }, { userId: "bob", timestamp: now + 500 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(1);
    expect(result.conflicts[0].entityIri).toBe("A");
    expect(result.conflicts[0].mergeAction).toBe("conflict");
  });

  it("no conflict when ops are outside window", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { label: "v2" } }, { userId: "bob", timestamp: now + 3000 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
  });

  it("auto-merge notice for different fields on same entity", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { color: "red" } }, { userId: "bob", timestamp: now + 500 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
    expect(result.notices).toHaveLength(1);
    expect(result.notices[0].action).toBe("both");
  });

  it("position conflict → last-write-wins notice", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { x: 10, y: 20 } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { x: 30, y: 40 } }, { userId: "bob", timestamp: now + 100 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
    expect(result.notices).toHaveLength(1);
    expect(result.notices[0].action).toBe("last-write-wins");
  });

  it("add vs remove → add-wins notice", () => {
    const now = Date.now();
    const result = simulate([
      op("addClass", { iri: "A" }, { userId: "alice", timestamp: now }),
      op("removeClass", { iri: "A" }, { userId: "bob", timestamp: now + 500 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
    expect(result.notices).toHaveLength(1);
    expect(result.notices[0].action).toBe("add-wins");
  });

  it("different entities → no overlap", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "B", updates: { label: "v2" } }, { userId: "bob", timestamp: now + 500 }),
    ], "alice");
    expect(result.conflicts).toHaveLength(0);
    expect(result.notices).toHaveLength(0);
  });

  it("three-user scenario: multiple conflicts detected", () => {
    const now = Date.now();
    const result = simulate([
      op("updateClass", { iri: "A", updates: { label: "v1" } }, { userId: "alice", timestamp: now }),
      op("updateClass", { iri: "A", updates: { label: "v2" } }, { userId: "bob", timestamp: now + 200 }),
      op("updateClass", { iri: "A", updates: { label: "v3" } }, { userId: "carol", timestamp: now + 400 }),
    ], "alice");
    // carol conflicts with both alice and bob
    expect(result.conflicts.length).toBeGreaterThanOrEqual(2);
  });
});

// ── Operation serialization ──────────────────────────────────

describe("operation serialization", () => {
  it("round-trips through JSON.stringify / JSON.parse", () => {
    const original = op("addClass", {
      iri: "http://ex.org#A", label: "ClassA", x: 100, y: 200, w: 150, h: 80, color: "#6366f1",
    });
    const json = JSON.stringify(original);
    const parsed = JSON.parse(json) as OntologyOperation;
    expect(parsed.id).toBe(original.id);
    expect(parsed.type).toBe("addClass");
    expect(parsed.data.iri).toBe("http://ex.org#A");
    expect(parsed.userId).toBe("user-a");
  });

  it("handles special characters in labels", () => {
    const original = op("updateClass", {
      iri: "http://ex.org#A",
      updates: { label: 'Class "with" <special> & chars' },
    });
    const parsed = JSON.parse(JSON.stringify(original));
    expect(parsed.data.updates.label).toBe('Class "with" <special> & chars');
  });

  it("handles unicode in IRIs", () => {
    const original = op("addClass", { iri: "http://ex.org#Klasse_\u00FC\u00E4\u00F6" });
    const parsed = JSON.parse(JSON.stringify(original));
    expect(parsed.data.iri).toBe("http://ex.org#Klasse_\u00FC\u00E4\u00F6");
  });
});
