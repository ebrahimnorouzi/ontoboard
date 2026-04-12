/**
 * Zustand store — single source of truth for the ontology graph.
 * Syncs between React Flow canvas and Tree Browser.
 * Auto-saves to backend on any state change (debounced).
 */

import { create } from "zustand";
import { apiJson } from "../api";

interface OntClass {
  id: string; iri: string; label: string;
  x: number; y: number; w: number; h: number; color: string;
  created_by?: string; created_at?: string;
  modified_by?: string; modified_at?: string;
}
interface OntProperty {
  id: string; iri: string; label: string;
  source_id: string; target_id: string; property_type: string;
  created_by?: string; created_at?: string;
  modified_by?: string; modified_at?: string;
}
interface OntIndividual {
  id: string; iri: string; label: string;
  class_iri: string; x: number; y: number;
  created_by?: string; created_at?: string;
  modified_by?: string; modified_at?: string;
}
interface OntLiteral {
  id: string; value: string; datatype: string; language: string;
  x: number; y: number;
}
interface StickyNote {
  id: string; text: string;
  x: number; y: number; w: number; h: number;
  color: string; fontSize: number;
}

interface CanvasSnapshot {
  classes: OntClass[]; properties: OntProperty[];
  individuals: OntIndividual[]; literals: OntLiteral[]; stickyNotes: StickyNote[];
}

interface SelectedEntity {
  iri: string; type: string; label: string;
}

interface OntologyState {
  boardId: string | null;
  classes: OntClass[];
  properties: OntProperty[];
  individuals: OntIndividual[];
  literals: OntLiteral[];
  stickyNotes: StickyNote[];
  selectedEntity: SelectedEntity | null;
  dirty: boolean;
  saving: boolean;
  lastSaved: number;
  undoStack: CanvasSnapshot[];
  redoStack: CanvasSnapshot[];
  // Provenance settings
  currentUser: string;
  trackProvenance: boolean;
  provenanceTarget: "board" | "ontology" | "both";

  // Actions
  setBoardId: (id: string) => void;
  loadFromBackend: (boardId: string) => Promise<void>;
  saveToBackend: () => Promise<void>;
  selectEntity: (entity: SelectedEntity | null) => void;
  undo: () => void;
  redo: () => void;

  // Mutations (trigger auto-save)
  addClass: (cls: OntClass) => void;
  updateClass: (iri: string, updates: Partial<OntClass>) => void;
  removeClass: (iri: string) => void;
  addProperty: (prop: OntProperty) => void;
  removeProperty: (id: string) => void;
  addSubClassOf: (childIri: string, parentIri: string) => void;
  setClasses: (classes: OntClass[]) => void;
  setProperties: (properties: OntProperty[]) => void;
  setIndividuals: (individuals: OntIndividual[]) => void;
  updateIndividual: (iri: string, updates: Partial<OntIndividual>) => void;

  // Literals
  addLiteral: (lit: OntLiteral) => void;
  updateLiteral: (id: string, updates: Partial<OntLiteral>) => void;
  removeLiteral: (id: string) => void;

  // Sticky notes
  addStickyNote: (note: StickyNote) => void;
  updateStickyNote: (id: string, updates: Partial<StickyNote>) => void;
  removeStickyNote: (id: string) => void;
  setStickyNotes: (notes: StickyNote[]) => void;

  // Provenance
  setCurrentUser: (username: string) => void;
  setTrackProvenance: (enabled: boolean) => void;
  setProvenanceTarget: (target: "board" | "ontology" | "both") => void;
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;

function debouncedSave(get: () => OntologyState) {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    get().saveToBackend();
  }, 2000);
}

const MAX_UNDO = 50;

function pushUndo(get: () => OntologyState, set: (partial: Partial<OntologyState>) => void) {
  const { classes, properties, individuals, literals, stickyNotes, undoStack } = get();
  const snapshot: CanvasSnapshot = {
    classes: classes.map((c) => ({ ...c })),
    properties: properties.map((p) => ({ ...p })),
    individuals: individuals.map((i) => ({ ...i })),
    literals: literals.map((l) => ({ ...l })),
    stickyNotes: stickyNotes.map((s) => ({ ...s })),
  };
  set({ undoStack: [...undoStack.slice(-(MAX_UNDO - 1)), snapshot], redoStack: [] });
}

function provStamp(get: () => OntologyState): { created_by: string; created_at: string; modified_by: string; modified_at: string } {
  const now = new Date().toISOString();
  const user = get().currentUser || "anonymous";
  return { created_by: user, created_at: now, modified_by: user, modified_at: now };
}

function modStamp(get: () => OntologyState): { modified_by: string; modified_at: string } {
  return { modified_by: get().currentUser || "anonymous", modified_at: new Date().toISOString() };
}

export const useOntologyStore = create<OntologyState>((set, get) => ({
  boardId: null,
  classes: [],
  properties: [],
  individuals: [],
  literals: [],
  stickyNotes: [],
  selectedEntity: null,
  dirty: false,
  saving: false,
  lastSaved: 0,
  undoStack: [],
  redoStack: [],
  currentUser: "",
  trackProvenance: true,
  provenanceTarget: "both",

  setBoardId: (id) => set({ boardId: id }),

  loadFromBackend: async (boardId) => {
    try {
      const data = await apiJson<{ classes: OntClass[]; properties: OntProperty[]; individuals: OntIndividual[]; literals?: OntLiteral[]; sticky_notes?: StickyNote[] }>(
        `/api/owl/${boardId}/load`
      );
      set({
        boardId,
        classes: data.classes,
        properties: data.properties,
        individuals: data.individuals,
        literals: data.literals || [],
        stickyNotes: data.sticky_notes || [],
        dirty: false,
        undoStack: [],
        redoStack: [],
      });
    } catch {
      set({ boardId, classes: [], properties: [], individuals: [], literals: [], stickyNotes: [], dirty: false });
    }
  },

  saveToBackend: async () => {
    const { boardId, classes, properties, individuals, literals, stickyNotes, dirty, trackProvenance, provenanceTarget } = get();
    if (!boardId || !dirty) return;
    set({ saving: true });
    try {
      await apiJson(`/api/owl/${boardId}/save`, {
        method: "POST",
        body: JSON.stringify({
          classes, properties, individuals, literals, sticky_notes: stickyNotes,
          track_provenance: trackProvenance, provenance_target: provenanceTarget,
        }),
      });
      set({ dirty: false, saving: false, lastSaved: Date.now() });
    } catch {
      set({ saving: false });
    }
  },

  selectEntity: (entity) => set({ selectedEntity: entity }),

  undo: () => {
    const { undoStack, classes, properties, individuals, literals, stickyNotes } = get();
    if (undoStack.length === 0) return;
    const prev = undoStack[undoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      literals: literals.map((l) => ({ ...l })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
    };
    set({
      classes: prev.classes, properties: prev.properties,
      individuals: prev.individuals, literals: prev.literals, stickyNotes: prev.stickyNotes,
      undoStack: undoStack.slice(0, -1),
      redoStack: [...get().redoStack, current],
      dirty: true,
    });
    debouncedSave(get);
  },

  redo: () => {
    const { redoStack, classes, properties, individuals, literals, stickyNotes } = get();
    if (redoStack.length === 0) return;
    const next = redoStack[redoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      literals: literals.map((l) => ({ ...l })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
    };
    set({
      classes: next.classes, properties: next.properties,
      individuals: next.individuals, literals: next.literals, stickyNotes: next.stickyNotes,
      redoStack: redoStack.slice(0, -1),
      undoStack: [...get().undoStack, current],
      dirty: true,
    });
    debouncedSave(get);
  },

  addClass: (cls) => {
    pushUndo(get, set);
    const stamped = get().trackProvenance ? { ...cls, ...provStamp(get) } : cls;
    set((s) => ({ classes: [...s.classes, stamped], dirty: true }));
    debouncedSave(get);
  },

  updateClass: (iri, updates) => {
    const mod = get().trackProvenance ? modStamp(get) : {};
    set((s) => ({
      classes: s.classes.map((c) => (c.iri === iri ? { ...c, ...updates, ...mod } : c)),
      dirty: true,
    }));
    debouncedSave(get);
  },

  removeClass: (iri) => {
    pushUndo(get, set);
    set((s) => ({
      classes: s.classes.filter((c) => c.iri !== iri),
      properties: s.properties.filter((p) => p.source_id !== iri && p.target_id !== iri),
      dirty: true,
    }));
    debouncedSave(get);
  },

  addProperty: (prop) => {
    pushUndo(get, set);
    const stamped = get().trackProvenance ? { ...prop, ...provStamp(get) } : prop;
    set((s) => ({ properties: [...s.properties, stamped], dirty: true }));
    debouncedSave(get);
  },

  removeProperty: (id) => {
    pushUndo(get, set);
    set((s) => ({ properties: s.properties.filter((p) => p.id !== id), dirty: true }));
    debouncedSave(get);
  },

  addSubClassOf: (childIri, parentIri) => {
    pushUndo(get, set);
    const id = `subClassOf_${childIri}_${parentIri}`;
    const prov = get().trackProvenance ? provStamp(get) : {};
    set((s) => ({
      properties: [
        ...s.properties.filter((p) => p.id !== id),
        {
          id, iri: "rdfs:subClassOf", label: "rdfs:subClassOf",
          source_id: childIri, target_id: parentIri, property_type: "annotation",
          ...prov,
        },
      ],
      dirty: true,
    }));
    debouncedSave(get);
  },

  setClasses: (classes) => set({ classes, dirty: true }),
  setProperties: (properties) => set({ properties, dirty: true }),
  setIndividuals: (individuals) => set({ individuals, dirty: true }),
  updateIndividual: (iri, updates) => {
    set((s) => ({
      individuals: s.individuals.map((i) => (i.iri === iri ? { ...i, ...updates } : i)),
      dirty: true,
    }));
    debouncedSave(get);
  },

  addLiteral: (lit) => {
    pushUndo(get, set);
    set((s) => ({ literals: [...s.literals, lit], dirty: true }));
    debouncedSave(get);
  },
  updateLiteral: (id, updates) => {
    set((s) => ({
      literals: s.literals.map((l) => (l.id === id ? { ...l, ...updates } : l)),
      dirty: true,
    }));
    debouncedSave(get);
  },
  removeLiteral: (id) => {
    pushUndo(get, set);
    set((s) => ({
      literals: s.literals.filter((l) => l.id !== id),
      properties: s.properties.filter((p) => p.target_id !== id),
      dirty: true,
    }));
    debouncedSave(get);
  },

  addStickyNote: (note) => {
    pushUndo(get, set);
    set((s) => ({ stickyNotes: [...s.stickyNotes, note], dirty: true }));
    debouncedSave(get);
  },
  updateStickyNote: (id, updates) => {
    set((s) => ({
      stickyNotes: s.stickyNotes.map((n) => (n.id === id ? { ...n, ...updates } : n)),
      dirty: true,
    }));
    debouncedSave(get);
  },
  removeStickyNote: (id) => {
    pushUndo(get, set);
    set((s) => ({ stickyNotes: s.stickyNotes.filter((n) => n.id !== id), dirty: true }));
    debouncedSave(get);
  },
  setStickyNotes: (notes) => set({ stickyNotes: notes, dirty: true }),

  // Provenance settings
  setCurrentUser: (username) => set({ currentUser: username }),
  setTrackProvenance: (enabled) => set({ trackProvenance: enabled }),
  setProvenanceTarget: (target) => set({ provenanceTarget: target }),
}));

export type { OntClass, OntProperty, OntIndividual, OntLiteral, StickyNote, SelectedEntity };
