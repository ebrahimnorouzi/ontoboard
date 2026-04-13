/**
 * useYjsSync — Bridges Yjs shared types <-> Zustand ontology store.
 *
 * Uses Y.Map for each entity collection (classes, properties, individuals, etc.).
 * Each entity is stored as a JSON string keyed by its ID.
 *
 * Flow:
 * - Local change -> Zustand store -> push to Yjs map -> propagates to all users
 * - Remote Yjs change -> observe callback -> update Zustand store -> re-renders canvas
 *
 * Optimizations:
 * - Only updates changed arrays (compares JSON to avoid unnecessary re-renders)
 * - Uses _syncing flag + transaction.local to prevent infinite loops
 */

import { useEffect, useRef } from "react";
import * as Y from "yjs";
import { useOntologyStore } from "../store/ontologyStore";

type EntityMap = Y.Map<string>;

let _syncing = false;

export function useYjsSync(doc: Y.Doc | null, boardId: string | undefined) {
  const cleanupRef = useRef<(() => void) | null>(null);

  useEffect(() => {
    if (!doc || !boardId) return;

    const store = useOntologyStore;

    const yClasses: EntityMap = doc.getMap("classes");
    const yProperties: EntityMap = doc.getMap("properties");
    const yIndividuals: EntityMap = doc.getMap("individuals");
    const yLiterals: EntityMap = doc.getMap("literals");
    const yStickyNotes: EntityMap = doc.getMap("stickyNotes");
    const yFrames: EntityMap = doc.getMap("frames");

    // ── Seed Yjs from store (first user to load populates the shared doc) ──
    function seedYjsFromStore() {
      const state = store.getState();
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

    // ── Apply a single Yjs map change to the store (surgical update) ──
    function applyMapToStoreKey(yMap: EntityMap, stateKey: string, getKey: (item: any) => string) {
      const current: any[] = (store.getState() as any)[stateKey];
      const yEntries = new Map<string, any>();
      for (const [k, v] of yMap.entries()) {
        yEntries.set(k, JSON.parse(v));
      }

      // Check if anything actually changed
      if (current.length === yEntries.size) {
        let same = true;
        for (const item of current) {
          const yItem = yEntries.get(getKey(item));
          if (!yItem || JSON.stringify(item) !== JSON.stringify(yItem)) {
            same = false;
            break;
          }
        }
        if (same) return; // No changes — skip update to avoid re-render
      }

      store.setState({ [stateKey]: Array.from(yEntries.values()) } as any);
    }

    // ── Observe remote Yjs changes ──
    function makeObserver(yMap: EntityMap, stateKey: string, getKey: (item: any) => string) {
      return (_event: Y.YMapEvent<string>, transaction: Y.Transaction) => {
        if (transaction.local || _syncing) return;
        _syncing = true;
        try {
          applyMapToStoreKey(yMap, stateKey, getKey);
        } finally {
          _syncing = false;
        }
      };
    }

    const obsClasses = makeObserver(yClasses, "classes", (c) => c.iri);
    const obsProperties = makeObserver(yProperties, "properties", (p) => p.id);
    const obsIndividuals = makeObserver(yIndividuals, "individuals", (i) => i.iri);
    const obsLiterals = makeObserver(yLiterals, "literals", (l) => l.id);
    const obsStickyNotes = makeObserver(yStickyNotes, "stickyNotes", (n) => n.id);
    const obsFrames = makeObserver(yFrames, "frames", (f) => f.id);

    yClasses.observe(obsClasses);
    yProperties.observe(obsProperties);
    yIndividuals.observe(obsIndividuals);
    yLiterals.observe(obsLiterals);
    yStickyNotes.observe(obsStickyNotes);
    yFrames.observe(obsFrames);

    // ── Subscribe to Zustand store changes -> push to Yjs ──
    const unsubscribe = store.subscribe((state, prevState) => {
      if (_syncing) return;
      _syncing = true;
      try {
        doc!.transact(() => {
          if (state.classes !== prevState.classes) {
            syncArrayToMap(state.classes, yClasses, (c) => c.iri);
          }
          if (state.properties !== prevState.properties) {
            syncArrayToMap(state.properties, yProperties, (p) => p.id);
          }
          if (state.individuals !== prevState.individuals) {
            syncArrayToMap(state.individuals, yIndividuals, (i) => i.iri);
          }
          if (state.literals !== prevState.literals) {
            syncArrayToMap(state.literals, yLiterals, (l) => l.id);
          }
          if (state.stickyNotes !== prevState.stickyNotes) {
            syncArrayToMap(state.stickyNotes, yStickyNotes, (n) => n.id);
          }
          if (state.frames !== prevState.frames) {
            syncArrayToMap(state.frames, yFrames, (f) => f.id);
          }
        });
      } finally {
        _syncing = false;
      }
    });

    // If Yjs already has data, apply it; otherwise seed from store
    if (yClasses.size > 0) {
      _syncing = true;
      try {
        applyMapToStoreKey(yClasses, "classes", (c) => c.iri);
        applyMapToStoreKey(yProperties, "properties", (p) => p.id);
        applyMapToStoreKey(yIndividuals, "individuals", (i) => i.iri);
        applyMapToStoreKey(yLiterals, "literals", (l) => l.id);
        applyMapToStoreKey(yStickyNotes, "stickyNotes", (n) => n.id);
        applyMapToStoreKey(yFrames, "frames", (f) => f.id);
      } finally {
        _syncing = false;
      }
    } else {
      const unsub = store.subscribe((state, prev) => {
        if (state.classes.length > 0 && prev.classes.length === 0) {
          seedYjsFromStore();
          unsub();
        }
      });
      seedYjsFromStore();
    }

    cleanupRef.current = () => {
      yClasses.unobserve(obsClasses);
      yProperties.unobserve(obsProperties);
      yIndividuals.unobserve(obsIndividuals);
      yLiterals.unobserve(obsLiterals);
      yStickyNotes.unobserve(obsStickyNotes);
      yFrames.unobserve(obsFrames);
      unsubscribe();
    };

    return () => {
      cleanupRef.current?.();
      cleanupRef.current = null;
    };
  }, [doc, boardId]);
}


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
    if (yMap.get(key) !== json) {
      yMap.set(key, json);
    }
  }
  for (const key of yMap.keys()) {
    if (!localKeys.has(key)) {
      yMap.delete(key);
    }
  }
}
