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
}
interface OntProperty {
  id: string; iri: string; label: string;
  source_id: string; target_id: string; property_type: string;
}
interface OntIndividual {
  id: string; iri: string; label: string;
  class_iri: string; x: number; y: number;
}
interface StickyNote {
  id: string; text: string;
  x: number; y: number; w: number; h: number;
  color: string; fontSize: number;
}

interface CanvasSnapshot {
  classes: OntClass[]; properties: OntProperty[];
  individuals: OntIndividual[]; stickyNotes: StickyNote[];
}

interface SelectedEntity {
  iri: string; type: string; label: string;
}

interface OntologyState {
  boardId: string | null;
  classes: OntClass[];
  properties: OntProperty[];
  individuals: OntIndividual[];
  stickyNotes: StickyNote[];
  selectedEntity: SelectedEntity | null;
  dirty: boolean;
  saving: boolean;
  lastSaved: number;
  undoStack: CanvasSnapshot[];
  redoStack: CanvasSnapshot[];

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

  // Sticky notes
  addStickyNote: (note: StickyNote) => void;
  updateStickyNote: (id: string, updates: Partial<StickyNote>) => void;
  removeStickyNote: (id: string) => void;
  setStickyNotes: (notes: StickyNote[]) => void;
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
  const { classes, properties, individuals, stickyNotes, undoStack } = get();
  const snapshot: CanvasSnapshot = {
    classes: classes.map((c) => ({ ...c })),
    properties: properties.map((p) => ({ ...p })),
    individuals: individuals.map((i) => ({ ...i })),
    stickyNotes: stickyNotes.map((s) => ({ ...s })),
  };
  set({ undoStack: [...undoStack.slice(-(MAX_UNDO - 1)), snapshot], redoStack: [] });
}

export const useOntologyStore = create<OntologyState>((set, get) => ({
  boardId: null,
  classes: [],
  properties: [],
  individuals: [],
  stickyNotes: [],
  selectedEntity: null,
  dirty: false,
  saving: false,
  lastSaved: 0,
  undoStack: [],
  redoStack: [],

  setBoardId: (id) => set({ boardId: id }),

  loadFromBackend: async (boardId) => {
    try {
      const data = await apiJson<{ classes: OntClass[]; properties: OntProperty[]; individuals: OntIndividual[]; sticky_notes?: StickyNote[] }>(
        `/api/owl/${boardId}/load`
      );
      set({
        boardId,
        classes: data.classes,
        properties: data.properties,
        individuals: data.individuals,
        stickyNotes: data.sticky_notes || [],
        dirty: false,
        undoStack: [],
        redoStack: [],
      });
    } catch {
      set({ boardId, classes: [], properties: [], individuals: [], stickyNotes: [], dirty: false });
    }
  },

  saveToBackend: async () => {
    const { boardId, classes, properties, individuals, stickyNotes, dirty } = get();
    if (!boardId || !dirty) return;
    set({ saving: true });
    try {
      await apiJson(`/api/owl/${boardId}/save`, {
        method: "POST",
        body: JSON.stringify({ classes, properties, individuals, sticky_notes: stickyNotes }),
      });
      set({ dirty: false, saving: false, lastSaved: Date.now() });
    } catch {
      set({ saving: false });
    }
  },

  selectEntity: (entity) => set({ selectedEntity: entity }),

  undo: () => {
    const { undoStack, classes, properties, individuals, stickyNotes } = get();
    if (undoStack.length === 0) return;
    const prev = undoStack[undoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
    };
    set({
      classes: prev.classes, properties: prev.properties,
      individuals: prev.individuals, stickyNotes: prev.stickyNotes,
      undoStack: undoStack.slice(0, -1),
      redoStack: [...get().redoStack, current],
      dirty: true,
    });
    debouncedSave(get);
  },

  redo: () => {
    const { redoStack, classes, properties, individuals, stickyNotes } = get();
    if (redoStack.length === 0) return;
    const next = redoStack[redoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
    };
    set({
      classes: next.classes, properties: next.properties,
      individuals: next.individuals, stickyNotes: next.stickyNotes,
      redoStack: redoStack.slice(0, -1),
      undoStack: [...get().undoStack, current],
      dirty: true,
    });
    debouncedSave(get);
  },

  addClass: (cls) => {
    pushUndo(get, set);
    set((s) => ({ classes: [...s.classes, cls], dirty: true }));
    debouncedSave(get);
  },

  updateClass: (iri, updates) => {
    set((s) => ({
      classes: s.classes.map((c) => (c.iri === iri ? { ...c, ...updates } : c)),
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
    set((s) => ({ properties: [...s.properties, prop], dirty: true }));
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
    set((s) => ({
      properties: [
        ...s.properties.filter((p) => p.id !== id),
        {
          id, iri: "rdfs:subClassOf", label: "subClassOf",
          source_id: childIri, target_id: parentIri, property_type: "annotation",
        },
      ],
      dirty: true,
    }));
    debouncedSave(get);
  },

  setClasses: (classes) => set({ classes, dirty: true }),
  setProperties: (properties) => set({ properties, dirty: true }),
  setIndividuals: (individuals) => set({ individuals, dirty: true }),

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
}));

export type { OntClass, OntProperty, OntIndividual, StickyNote, SelectedEntity };
