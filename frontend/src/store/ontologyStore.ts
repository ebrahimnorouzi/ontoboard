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
interface SelectedEntity {
  iri: string; type: string; label: string;
}

interface OntologyState {
  boardId: string | null;
  classes: OntClass[];
  properties: OntProperty[];
  individuals: OntIndividual[];
  selectedEntity: SelectedEntity | null;
  dirty: boolean;
  saving: boolean;
  lastSaved: number;

  // Actions
  setBoardId: (id: string) => void;
  loadFromBackend: (boardId: string) => Promise<void>;
  saveToBackend: () => Promise<void>;
  selectEntity: (entity: SelectedEntity | null) => void;

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
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;

function debouncedSave(get: () => OntologyState) {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    get().saveToBackend();
  }, 2000);
}

export const useOntologyStore = create<OntologyState>((set, get) => ({
  boardId: null,
  classes: [],
  properties: [],
  individuals: [],
  selectedEntity: null,
  dirty: false,
  saving: false,
  lastSaved: 0,

  setBoardId: (id) => set({ boardId: id }),

  loadFromBackend: async (boardId) => {
    try {
      const data = await apiJson<{ classes: OntClass[]; properties: OntProperty[]; individuals: OntIndividual[] }>(
        `/api/owl/${boardId}/load`
      );
      set({
        boardId,
        classes: data.classes,
        properties: data.properties,
        individuals: data.individuals,
        dirty: false,
      });
    } catch {
      set({ boardId, classes: [], properties: [], individuals: [], dirty: false });
    }
  },

  saveToBackend: async () => {
    const { boardId, classes, properties, individuals, dirty } = get();
    if (!boardId || !dirty) return;
    set({ saving: true });
    try {
      await apiJson(`/api/owl/${boardId}/save`, {
        method: "POST",
        body: JSON.stringify({ classes, properties, individuals }),
      });
      set({ dirty: false, saving: false, lastSaved: Date.now() });
    } catch {
      set({ saving: false });
    }
  },

  selectEntity: (entity) => set({ selectedEntity: entity }),

  addClass: (cls) => {
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
    set((s) => ({
      classes: s.classes.filter((c) => c.iri !== iri),
      properties: s.properties.filter((p) => p.source_id !== iri && p.target_id !== iri),
      dirty: true,
    }));
    debouncedSave(get);
  },

  addProperty: (prop) => {
    set((s) => ({ properties: [...s.properties, prop], dirty: true }));
    debouncedSave(get);
  },

  removeProperty: (id) => {
    set((s) => ({ properties: s.properties.filter((p) => p.id !== id), dirty: true }));
    debouncedSave(get);
  },

  addSubClassOf: (childIri, parentIri) => {
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
}));

export type { OntClass, OntProperty, OntIndividual, SelectedEntity };
