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
interface CanvasFrame {
  id: string;
  label: string;
  x: number;
  y: number;
  w: number;
  h: number;
  color: string; // background color with transparency
  borderColor: string;
}

/** Inference from reasoning (inferred triple). */
interface Inference {
  inference_type: string;
  subject: string;
  subject_label: string;
  predicate: string;
  object: string;
  object_label: string;
}

/** Maps prefix name → color for per-prefix class coloring */
interface PrefixColor {
  prefix: string;
  namespace: string;
  color: string;
}

interface CanvasSnapshot {
  classes: OntClass[]; properties: OntProperty[];
  individuals: OntIndividual[]; literals: OntLiteral[]; stickyNotes: StickyNote[];
  frames: CanvasFrame[];
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
  frames: CanvasFrame[];
  selectedEntity: SelectedEntity | null;
  dirty: boolean;
  saving: boolean;
  lastSaved: number;
  undoStack: CanvasSnapshot[];
  redoStack: CanvasSnapshot[];
  // Prefix colors for per-prefix class coloring
  prefixColors: PrefixColor[];
  // Pattern tracking: maps class IRI → pattern ID for coloring
  patternMap: Record<string, string>;
  // Inferences from reasoning
  showInferences: boolean;
  inferences: Inference[];
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
  addIndividual: (ind: OntIndividual) => void;
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

  // Frames
  addFrame: (frame: CanvasFrame) => void;
  updateFrame: (id: string, updates: Partial<CanvasFrame>) => void;
  removeFrame: (id: string) => void;
  setFrames: (frames: CanvasFrame[]) => void;

  // Prefix colors
  setPrefixColors: (colors: PrefixColor[]) => void;
  setPrefixColor: (prefix: string, namespace: string, color: string) => void;
  removePrefixColor: (prefix: string) => void;

  // Pattern map
  setPatternMap: (map: Record<string, string>) => void;
  assignPattern: (classIri: string, patternId: string) => void;

  // Inferences
  setShowInferences: (show: boolean) => void;
  setInferences: (infs: Inference[]) => void;

  // Provenance
  setCurrentUser: (username: string) => void;
  setTrackProvenance: (enabled: boolean) => void;
  setProvenanceTarget: (target: "board" | "ontology" | "both") => void;
}

// Distinct colors for auto-assigning to prefixes (namespace-based)
const PREFIX_AUTO_COLORS = [
  "#6366f1", "#10b981", "#f59e0b", "#ef4444", "#8b5cf6",
  "#0ea5e9", "#ec4899", "#14b8a6", "#f97316", "#84cc16",
  "#a855f7", "#06b6d4", "#d946ef", "#22c55e", "#e11d48",
  "#0891b2", "#7c3aed", "#059669", "#dc2626", "#2563eb",
];

function _autoAssignPrefixColors(classes: OntClass[]): PrefixColor[] {
  // Extract unique namespaces from class IRIs
  const namespaces = new Map<string, string>(); // namespace -> short prefix
  for (const c of classes) {
    const iri = c.iri;
    let ns: string;
    if (iri.includes("#")) {
      ns = iri.substring(0, iri.lastIndexOf("#") + 1);
    } else {
      ns = iri.substring(0, iri.lastIndexOf("/") + 1);
    }
    if (ns && !namespaces.has(ns)) {
      // Derive a short prefix name from the namespace
      const parts = ns.replace(/#$/, "").split("/").filter(Boolean);
      const shortName = parts[parts.length - 1] || parts[parts.length - 2] || "ns";
      namespaces.set(ns, shortName);
    }
  }

  // Assign colors
  const colors: PrefixColor[] = [];
  let idx = 0;
  for (const [ns, prefix] of namespaces) {
    colors.push({
      prefix,
      namespace: ns,
      color: PREFIX_AUTO_COLORS[idx % PREFIX_AUTO_COLORS.length],
    });
    idx++;
  }
  return colors;
}

let saveTimer: ReturnType<typeof setTimeout> | null = null;

/** Callback invoked after each successful save (set by BoardPage to broadcast via Yjs). */
let _onSaveCallback: (() => void) | null = null;
export function setOnSaveCallback(cb: (() => void) | null) { _onSaveCallback = cb; }

function debouncedSave(get: () => OntologyState) {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    get().saveToBackend();
  }, 800); // Fast saves — 800ms debounce for responsive collaboration
}

/** Schedule an auto-save (for use after batched setState calls). */
export function scheduleAutoSave() {
  debouncedSave(() => useOntologyStore.getState());
}

const MAX_UNDO = 50;

function pushUndo(get: () => OntologyState, set: (partial: Partial<OntologyState>) => void) {
  const { classes, properties, individuals, literals, stickyNotes, frames, undoStack } = get();
  const snapshot: CanvasSnapshot = {
    classes: classes.map((c) => ({ ...c })),
    properties: properties.map((p) => ({ ...p })),
    individuals: individuals.map((i) => ({ ...i })),
    literals: literals.map((l) => ({ ...l })),
    stickyNotes: stickyNotes.map((s) => ({ ...s })),
    frames: frames.map((f) => ({ ...f })),
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
  frames: [],
  selectedEntity: null,
  dirty: false,
  saving: false,
  lastSaved: 0,
  undoStack: [],
  redoStack: [],
  prefixColors: [],
  patternMap: {},
  showInferences: false,
  inferences: [],
  currentUser: "",
  trackProvenance: true,
  provenanceTarget: "both",

  setBoardId: (id) => set({ boardId: id }),

  loadFromBackend: async (boardId) => {
    try {
      const data = await apiJson<{ classes: OntClass[]; properties: OntProperty[]; individuals: OntIndividual[]; literals?: OntLiteral[]; sticky_notes?: StickyNote[]; frames?: CanvasFrame[] }>(
        `/api/owl/${boardId}/load`
      );

      // Auto-assign colors to prefixes if none set yet
      const currentColors = get().prefixColors;
      let newColors = currentColors;
      if (currentColors.length === 0 && data.classes.length > 0) {
        newColors = _autoAssignPrefixColors(data.classes);
      }

      set({
        boardId,
        classes: data.classes,
        properties: data.properties,
        individuals: data.individuals,
        literals: data.literals || [],
        stickyNotes: data.sticky_notes || [],
        frames: data.frames || [],
        prefixColors: newColors,
        dirty: false,
        undoStack: [],
        redoStack: [],
      });
    } catch {
      set({ boardId, classes: [], properties: [], individuals: [], literals: [], stickyNotes: [], frames: [], dirty: false });
    }
  },

  saveToBackend: async () => {
    const { boardId, classes, properties, individuals, literals, stickyNotes, frames, dirty, trackProvenance, provenanceTarget } = get();
    if (!boardId || !dirty) return;
    set({ saving: true });
    try {
      await apiJson(`/api/owl/${boardId}/save`, {
        method: "POST",
        body: JSON.stringify({
          classes, properties, individuals, literals, sticky_notes: stickyNotes, frames,
          track_provenance: trackProvenance, provenance_target: provenanceTarget,
        }),
      });
      set({ dirty: false, saving: false, lastSaved: Date.now() });
      _onSaveCallback?.();
    } catch {
      set({ saving: false });
    }
  },

  selectEntity: (entity) => set({ selectedEntity: entity }),

  undo: () => {
    const { undoStack, classes, properties, individuals, literals, stickyNotes, frames } = get();
    if (undoStack.length === 0) return;
    const prev = undoStack[undoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      literals: literals.map((l) => ({ ...l })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
      frames: frames.map((f) => ({ ...f })),
    };
    set({
      classes: prev.classes, properties: prev.properties,
      individuals: prev.individuals, literals: prev.literals, stickyNotes: prev.stickyNotes,
      frames: prev.frames || [],
      undoStack: undoStack.slice(0, -1),
      redoStack: [...get().redoStack, current],
      dirty: true,
    });
    debouncedSave(get);
  },

  redo: () => {
    const { redoStack, classes, properties, individuals, literals, stickyNotes, frames } = get();
    if (redoStack.length === 0) return;
    const next = redoStack[redoStack.length - 1];
    const current: CanvasSnapshot = {
      classes: classes.map((c) => ({ ...c })),
      properties: properties.map((p) => ({ ...p })),
      individuals: individuals.map((i) => ({ ...i })),
      literals: literals.map((l) => ({ ...l })),
      stickyNotes: stickyNotes.map((s) => ({ ...s })),
      frames: frames.map((f) => ({ ...f })),
    };
    set({
      classes: next.classes, properties: next.properties,
      individuals: next.individuals, literals: next.literals, stickyNotes: next.stickyNotes,
      frames: next.frames || [],
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
  addIndividual: (ind) => {
    pushUndo(get, set);
    const stamped = get().trackProvenance ? { ...ind, ...provStamp(get) } : ind;
    set((s) => ({ individuals: [...s.individuals, stamped], dirty: true }));
    debouncedSave(get);
  },
  updateIndividual: (iri, updates) => {
    const mod = get().trackProvenance ? modStamp(get) : {};
    set((s) => ({
      individuals: s.individuals.map((i) => (i.iri === iri ? { ...i, ...updates, ...mod } : i)),
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

  // Frames
  addFrame: (frame) => {
    pushUndo(get, set);
    set((s) => ({ frames: [...s.frames, frame], dirty: true }));
    debouncedSave(get);
  },
  updateFrame: (id, updates) => {
    set((s) => ({
      frames: s.frames.map((f) => (f.id === id ? { ...f, ...updates } : f)),
      dirty: true,
    }));
    debouncedSave(get);
  },
  removeFrame: (id) => {
    pushUndo(get, set);
    set((s) => ({ frames: s.frames.filter((f) => f.id !== id), dirty: true }));
    debouncedSave(get);
  },
  setFrames: (frames) => set({ frames, dirty: true }),

  // Prefix colors
  setPrefixColors: (colors) => set({ prefixColors: colors }),
  setPrefixColor: (prefix, namespace, color) => set((s) => {
    const existing = s.prefixColors.findIndex((pc) => pc.prefix === prefix);
    if (existing >= 0) {
      const updated = [...s.prefixColors];
      updated[existing] = { prefix, namespace, color };
      return { prefixColors: updated };
    }
    return { prefixColors: [...s.prefixColors, { prefix, namespace, color }] };
  }),
  removePrefixColor: (prefix) => set((s) => ({
    prefixColors: s.prefixColors.filter((pc) => pc.prefix !== prefix),
  })),

  // Pattern map
  setPatternMap: (map) => set({ patternMap: map }),
  assignPattern: (classIri, patternId) => set((s) => ({
    patternMap: { ...s.patternMap, [classIri]: patternId },
  })),

  // Inferences
  setShowInferences: (show) => set({ showInferences: show }),
  setInferences: (infs) => set({ inferences: infs }),

  // Provenance settings
  setCurrentUser: (username) => set({ currentUser: username }),
  setTrackProvenance: (enabled) => set({ trackProvenance: enabled }),
  setProvenanceTarget: (target) => set({ provenanceTarget: target }),
}));

export type { OntClass, OntProperty, OntIndividual, OntLiteral, StickyNote, CanvasFrame, SelectedEntity, PrefixColor, Inference };
