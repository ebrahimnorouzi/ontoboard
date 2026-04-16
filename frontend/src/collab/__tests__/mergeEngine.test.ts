/**
 * Tests for the semantic merge engine (Phase 3).
 *
 * Validates the merge rule table from the CRDT collaboration design:
 *
 *   Op A                          | Op B                          | Resolution
 *   ------------------------------|-------------------------------|--------------------
 *   addClass(X)                   | addClass(Y)                   | both (additive)
 *   updateClass(X, label="A")     | updateClass(X, label="B")     | conflict (same field)
 *   updateClass(X, x=100)         | updateClass(X, label="B")     | both (different fields)
 *   updateClass(X, x=1)           | updateClass(X, x=2)           | last-write-wins
 *   addClass(X)                   | removeClass(X)                | add-wins
 *   addProperty(SubClassOf X→Y)   | addProperty(SubClassOf X→Z)   | both (additive)
 *   updateClass(X, label)         | removeClass(X)                | add-wins (edit preserved)
 *   removeClass(X)                | removeClass(X)                | both (idempotent)
 */

import { describe, it, expect } from "vitest";
import { resolveMerge } from "../mergeEngine";
import type { OntologyOperation } from "../useOperationSync";

/** Helper to build a minimal OntologyOperation. */
function op(
  type: OntologyOperation["type"],
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

describe("mergeEngine.resolveMerge", () => {
  // ── Additive operations ──────────────────────────────────────

  describe("both adds → both applied", () => {
    it("addClass + addClass (different entities)", () => {
      const opA = op("addClass", { iri: "http://ex.org#A", label: "A" });
      const opB = op("addClass", { iri: "http://ex.org#B", label: "B" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("addProperty + addProperty (additive axioms)", () => {
      const opA = op("addProperty", {
        id: "subClassOf_A_B", iri: "rdfs:subClassOf",
        source_id: "http://ex.org#A", target_id: "http://ex.org#B",
      });
      const opB = op("addProperty", {
        id: "subClassOf_A_C", iri: "rdfs:subClassOf",
        source_id: "http://ex.org#A", target_id: "http://ex.org#C",
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("addSubClassOf + addSubClassOf", () => {
      const opA = op("addSubClassOf", { childIri: "X", parentIri: "Y" });
      const opB = op("addSubClassOf", { childIri: "X", parentIri: "Z" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("addStickyNote + addStickyNote", () => {
      const opA = op("addStickyNote", { id: "note1", text: "A" });
      const opB = op("addStickyNote", { id: "note2", text: "B" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("addIndividual + addIndividual", () => {
      const opA = op("addIndividual", { iri: "http://ex.org#i1" });
      const opB = op("addIndividual", { iri: "http://ex.org#i2" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });
  });

  // ── Update vs update (field-level merge) ─────────────────────

  describe("update + update → field-level analysis", () => {
    it("different semantic fields → both applied", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "NewLabel" },
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { color: "#ff0000" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
      expect(result.reason).toContain("Different fields");
    });

    it("same semantic field → conflict", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "LabelA" },
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "LabelB" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("conflict");
      expect(result.reason).toContain("label");
    });

    it("both position-only → last-write-wins", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { x: 100, y: 200 },
      }, { timestamp: 1000 });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { x: 300, y: 400 },
      }, { timestamp: 2000 });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("last-write-wins");
    });

    it("position + semantic → both applied", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { x: 100, y: 200 },
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "NewLabel" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
      expect(result.reason).toContain("Position update + semantic update");
    });

    it("w and h are position fields → last-write-wins", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { w: 150, h: 100 },
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { w: 200, h: 120 },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("last-write-wins");
    });

    it("updateIndividual same field → conflict", () => {
      const opA = op("updateIndividual", {
        iri: "http://ex.org#i1", updates: { label: "A" },
      });
      const opB = op("updateIndividual", {
        iri: "http://ex.org#i1", updates: { label: "B" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("conflict");
    });

    it("updateStickyNote different fields → both", () => {
      const opA = op("updateStickyNote", {
        id: "n1", updates: { text: "hello" },
      });
      const opB = op("updateStickyNote", {
        id: "n1", updates: { color: "#fff" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("updateFrame position only → last-write-wins", () => {
      const opA = op("updateFrame", { id: "f1", updates: { x: 10, y: 20 } });
      const opB = op("updateFrame", { id: "f1", updates: { x: 30, y: 40 } });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("last-write-wins");
    });
  });

  // ── Add vs remove ────────────────────────────────────────────

  describe("add vs remove → add-wins (conservative)", () => {
    it("addClass + removeClass → add-wins", () => {
      const opA = op("addClass", { iri: "http://ex.org#A" });
      const opB = op("removeClass", { iri: "http://ex.org#A" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });

    it("removeClass + addClass → add-wins", () => {
      const opA = op("removeClass", { iri: "http://ex.org#A" });
      const opB = op("addClass", { iri: "http://ex.org#A" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });

    it("addProperty + removeProperty → add-wins", () => {
      const opA = op("addProperty", { id: "p1" });
      const opB = op("removeProperty", { id: "p1" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });

    it("addFrame + removeFrame → add-wins", () => {
      const opA = op("addFrame", { id: "f1" });
      const opB = op("removeFrame", { id: "f1" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });
  });

  // ── Update vs remove ────────────────────────────────────────

  describe("update vs remove → add-wins (edit preserved)", () => {
    it("updateClass + removeClass → add-wins", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "New" },
      });
      const opB = op("removeClass", { iri: "http://ex.org#A" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });

    it("removeLiteral + updateLiteral → add-wins", () => {
      const opA = op("removeLiteral", { id: "lit1" });
      const opB = op("updateLiteral", { id: "lit1", updates: { value: "v2" } });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("add-wins");
    });
  });

  // ── Both removes ─────────────────────────────────────────────

  describe("both removes → both (idempotent)", () => {
    it("removeClass + removeClass", () => {
      const opA = op("removeClass", { iri: "http://ex.org#A" });
      const opB = op("removeClass", { iri: "http://ex.org#A" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
      expect(result.reason).toContain("idempotent");
    });

    it("removeStickyNote + removeStickyNote", () => {
      const opA = op("removeStickyNote", { id: "n1" });
      const opB = op("removeStickyNote", { id: "n1" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });
  });

  // ── Add + update ─────────────────────────────────────────────

  describe("add + update → both applied", () => {
    it("addClass + updateClass", () => {
      const opA = op("addClass", { iri: "http://ex.org#A" });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "New" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("updateClass + addClass", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "Old" },
      });
      const opB = op("addClass", { iri: "http://ex.org#A" });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });
  });

  // ── Edge cases ───────────────────────────────────────────────

  describe("edge cases", () => {
    it("empty updates object → both (no overlap)", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: {},
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "B" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("both");
    });

    it("multiple overlapping fields → conflict lists all fields", () => {
      const opA = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "A", color: "red" },
      });
      const opB = op("updateClass", {
        iri: "http://ex.org#A", updates: { label: "B", color: "blue" },
      });
      const result = resolveMerge(opA, opB);
      expect(result.action).toBe("conflict");
      expect(result.reason).toContain("label");
      expect(result.reason).toContain("color");
    });
  });
});
