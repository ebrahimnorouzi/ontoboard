/**
 * useYjsSync — Bridges Yjs shared types ↔ Zustand ontology store.
 *
 * Uses Y.Map for each entity collection (classes, properties, individuals, etc.).
 * Each entity is stored as a JSON string keyed by its ID.
 *
 * Flow:
 * - Local change → Zustand store → push to Yjs map → propagates to all users
 * - Remote Yjs change → observe callback → update Zustand store → re-renders canvas
 *
 * A `_syncing` flag prevents infinite loops (local→Yjs→observe→store→local...).
 */

import { useEffect, useRef } from "react";
import * as Y from "yjs";
import { useOntologyStore } from "../store/ontologyStore";

type EntityMap = Y.Map<string>; // key = entity id/iri, value = JSON string

let _syncing = false;

/**
 * Sync the Zustand ontology store with Yjs shared types for real-time collaboration.
 */
export function useYjsSync(doc: Y.Doc | null, boardId: string | undefined) {
  const cleanupRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (!doc || !boardId) return;

    const store = useOntologyStore;

    // Get or create shared types
    const yClasses: EntityMap = doc.getMap("classes");
    const yProperties: EntityMap = doc.getMap("properties");
    const yIndividuals: EntityMap = doc.getMap("individuals");
    const yLiterals: EntityMap = doc.getMap("literals");
    const yStickyNotes: EntityMap = doc.getMap("stickyNotes");
    const yFrames: EntityMap = doc.getMap("frames");

    // ── Push local store state to Yjs (initial seed if we have data) ──
    function seedYjsFromStore() {
      const state = store.getState();
      // Only seed if Yjs is empty and store has data (we loaded from backend)
      if (yClasses.size === 0 && state.classes.length > 0) {
        doc!.transact(() => {
          for (const c of state.classes) yClasses.set(c.iri, JSON.stringify(c));
          for (const p of state.properties) yProperties.set(p.id, JSON.stringify(p));
          for (const i of state.individuals) yIndividuals.set(i.iri, JSON.stringify(i));
          for (const l of state.literals) yLiterals.set(l.id, JSON.stringify(l));
          for (const n of state.stickyNotes) yStickyNotes.set(n.id, JSON.stringify(n));
          for (const f of state.frames) yFrames.set(f.id, JSON.stringify(f));
        });
      }
    }

    // ── Apply Yjs state to Zustand store ──
    function applyYjsToStore() {
      if (_syncing) return;
      _syncing = true;
      try {
        const classes = Array.from(yClasses.values()).map((v) => JSON.parse(v));
        const properties = Array.from(yProperties.values()).map((v) => JSON.parse(v));
        const individuals = Array.from(yIndividuals.values()).map((v) => JSON.parse(v));
        const literals = Array.from(yLiterals.values()).map((v) => JSON.parse(v));
        const stickyNotes = Array.from(yStickyNotes.values()).map((v) => JSON.parse(v));
        const frames = Array.from(yFrames.values()).map((v) => JSON.parse(v));

        store.setState({
          classes,
          properties,
          individuals,
          literals,
          stickyNotes,
          frames,
          // Don't mark as dirty — this is a remote update, not a local edit
        });
      } finally {
        _syncing = false;
      }
    }

    // ── Observe Yjs changes (from remote users) ──
    function onYjsChange(event: Y.YMapEvent<string>, transaction: Y.Transaction) {
      // Skip changes originated from this client's store sync
      if (transaction.local) return;
      applyYjsToStore();
    }

    yClasses.observe(onYjsChange);
    yProperties.observe(onYjsChange);
    yIndividuals.observe(onYjsChange);
    yLiterals.observe(onYjsChange);
    yStickyNotes.observe(onYjsChange);
    yFrames.observe(onYjsChange);

    // ── Subscribe to Zustand store changes → push to Yjs ──
    const unsubscribe = store.subscribe((state, prevState) => {
      if (_syncing) return;
      _syncing = true;

      try {
        doc!.transact(() => {
          // Sync classes
          if (state.classes !== prevState.classes) {
            syncArrayToMap(state.classes, yClasses, (c) => c.iri);
          }
          // Sync properties
          if (state.properties !== prevState.properties) {
            syncArrayToMap(state.properties, yProperties, (p) => p.id);
          }
          // Sync individuals
          if (state.individuals !== prevState.individuals) {
            syncArrayToMap(state.individuals, yIndividuals, (i) => i.iri);
          }
          // Sync literals
          if (state.literals !== prevState.literals) {
            syncArrayToMap(state.literals, yLiterals, (l) => l.id);
          }
          // Sync sticky notes
          if (state.stickyNotes !== prevState.stickyNotes) {
            syncArrayToMap(state.stickyNotes, yStickyNotes, (n) => n.id);
          }
          // Sync frames
          if (state.frames !== prevState.frames) {
            syncArrayToMap(state.frames, yFrames, (f) => f.id);
          }
        });
      } finally {
        _syncing = false;
      }
    });

    // If Yjs already has data from other users, apply it
    if (yClasses.size > 0) {
      applyYjsToStore();
    } else {
      // Seed Yjs once store loads from backend
      const unsub = store.subscribe((state, prev) => {
        if (state.classes.length > 0 && prev.classes.length === 0) {
          seedYjsFromStore();
          unsub();
        }
      });
      // Also try immediately in case store already has data
      seedYjsFromStore();
    }

    cleanupRef.current = () => {
      yClasses.unobserve(onYjsChange);
      yProperties.unobserve(onYjsChange);
      yIndividuals.unobserve(onYjsChange);
      yLiterals.unobserve(onYjsChange);
      yStickyNotes.unobserve(onYjsChange);
      yFrames.unobserve(onYjsChange);
      unsubscribe();
    };

    return () => {
      cleanupRef.current?.();
      cleanupRef.current = null;
    };
  }, [doc, boardId]);
}


/**
 * Sync a local array to a Yjs Map — adds new items, updates changed items, removes deleted items.
 */
function syncArrayToMap<T extends Record<string, any>>(
  items: T[],
  yMap: EntityMap,
  getKey: (item: T) => string,
) {
  const localKeys = new Set<string>();

  for (const item of items) {
    const key = getKey(item);
    localKeys.add(key);
    const json = JSON.stringify(item);
    // Only update if value actually changed (avoid unnecessary Yjs transactions)
    if (yMap.get(key) !== json) {
      yMap.set(key, json);
    }
  }

  // Remove items that no longer exist locally
  for (const key of yMap.keys()) {
    if (!localKeys.has(key)) {
      yMap.delete(key);
    }
  }
}
