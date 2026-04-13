/**
 * useYjsSync — Bridges Yjs shared types <-> Zustand ontology store.
 *
 * On connect:
 * - If Yjs already has data (other users connected) → apply Yjs to store
 * - If Yjs is empty and store has data (first user) → seed Yjs from store
 *
 * During session:
 * - Local store changes → push to Yjs (skips if change came from Yjs)
 * - Remote Yjs changes → update store (skips if change came from store)
 *
 * On reload (loadFromBackend):
 * - Store gets fresh data → Yjs sync pushes it → other users get it
 */

import { useEffect, useRef } from "react";
import * as Y from "yjs";
import { useOntologyStore } from "../store/ontologyStore";

type EntityMap = Y.Map<string>;

// Global flag prevents loops: store→Yjs→observe→store→...
let _syncing = false;

export function useYjsSync(doc: Y.Doc | null, boardId: string | undefined) {
  const setupDoneRef = useRef(false);

  useEffect(() => {
    if (!doc || !boardId) {
      setupDoneRef.current = false;
      return;
    }

    const store = useOntologyStore;
    const yClasses: EntityMap = doc.getMap("classes");
    const yProperties: EntityMap = doc.getMap("properties");
    const yIndividuals: EntityMap = doc.getMap("individuals");
    const yLiterals: EntityMap = doc.getMap("literals");
    const yStickyNotes: EntityMap = doc.getMap("stickyNotes");
    const yFrames: EntityMap = doc.getMap("frames");

    const allMaps = [
      { yMap: yClasses, key: "classes", getId: (x: any) => x.iri },
      { yMap: yProperties, key: "properties", getId: (x: any) => x.id },
      { yMap: yIndividuals, key: "individuals", getId: (x: any) => x.iri },
      { yMap: yLiterals, key: "literals", getId: (x: any) => x.id },
      { yMap: yStickyNotes, key: "stickyNotes", getId: (x: any) => x.id },
      { yMap: yFrames, key: "frames", getId: (x: any) => x.id },
    ];

    // ── Apply one Yjs map to store (only if actually different) ──
    function applyMapToStore(yMap: EntityMap, stateKey: string) {
      const current: any[] = (store.getState() as any)[stateKey];
      const items = Array.from(yMap.values()).map((v) => JSON.parse(v));
      // Quick check: same length and same content?
      if (current.length === items.length) {
        const currentJson = JSON.stringify(current);
        const newJson = JSON.stringify(items);
        if (currentJson === newJson) return;
      }
      store.setState({ [stateKey]: items } as any);
    }

    // ── Observe remote Yjs changes ──
    const observers: Array<[EntityMap, (e: Y.YMapEvent<string>, t: Y.Transaction) => void]> = [];

    for (const { yMap, key } of allMaps) {
      const handler = (_event: Y.YMapEvent<string>, transaction: Y.Transaction) => {
        if (transaction.local || _syncing) return;
        _syncing = true;
        try { applyMapToStore(yMap, key); }
        finally { _syncing = false; }
      };
      yMap.observe(handler);
      observers.push([yMap, handler]);
    }

    // ── Subscribe to store changes → push to Yjs ──
    const unsubscribe = store.subscribe((state, prevState) => {
      if (_syncing) return;
      _syncing = true;
      try {
        doc.transact(() => {
          for (const { yMap, key, getId } of allMaps) {
            const curr = (state as any)[key];
            const prev = (prevState as any)[key];
            if (curr !== prev) {
              syncArrayToMap(curr, yMap, getId);
            }
          }
        });
      } finally {
        _syncing = false;
      }
    });

    // ── Initial sync (after a short delay to let data load) ──
    function doInitialSync() {
      if (setupDoneRef.current) return;
      setupDoneRef.current = true;

      const state = store.getState();
      const yjsHasData = yClasses.size > 0 || yProperties.size > 0;
      const storeHasData = state.classes.length > 0 || state.properties.length > 0;

      if (yjsHasData && !storeHasData) {
        // Other users already have data → apply to our store
        _syncing = true;
        try {
          for (const { yMap, key } of allMaps) {
            applyMapToStore(yMap, key);
          }
        } finally { _syncing = false; }
      } else if (storeHasData) {
        // We have data (loaded from backend) → seed Yjs
        _syncing = true;
        try {
          doc.transact(() => {
            for (const { yMap, key, getId } of allMaps) {
              syncArrayToMap((state as any)[key], yMap, getId);
            }
          });
        } finally { _syncing = false; }
      }
    }

    // Wait for store to be populated (loadFromBackend is async)
    const timer = setTimeout(doInitialSync, 500);

    // Also listen for the first store load
    const unsub2 = store.subscribe((state, prev) => {
      if (!setupDoneRef.current && state.classes.length > 0 && prev.classes.length === 0) {
        doInitialSync();
      }
    });

    return () => {
      clearTimeout(timer);
      unsub2();
      for (const [yMap, handler] of observers) {
        yMap.unobserve(handler);
      }
      unsubscribe();
      setupDoneRef.current = false;
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
