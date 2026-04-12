/**
 * AxiomEditor — Protege-style axiom editor with structured rows + Monaco editor.
 *
 * Shows axioms for the selected entity in two views:
 * 1. Structured view: axioms grouped by type (SubClassOf, EquivalentTo, etc.)
 *    with inline edit/delete per row
 * 2. Manchester editor: full Manchester OWL Syntax with syntax highlighting,
 *    autocomplete from ontology entities, and real-time validation
 */

import { useState, useEffect, useRef, useCallback, useMemo } from "react";
import Editor, { OnMount, BeforeMount } from "@monaco-editor/react";
import { useAxiomEditor } from "../../hooks/useAxiomEditor";
import {
  MANCHESTER_LANG_ID,
  languageDef,
  themeRules,
  completionItems,
} from "./manchesterLanguage";
import styles from "./AxiomEditor.module.css";

interface Props {
  boardId: string;
  entityIri: string | undefined;
  entityLabel?: string;
  entityType?: string;
}

/** Parse Manchester text into structured axiom rows */
function parseAxiomRows(text: string): { type: string; value: string }[] {
  const rows: { type: string; value: string }[] = [];
  let currentType = "";
  for (const line of text.split("\n")) {
    const trimmed = line.trim();
    if (!trimmed || trimmed.startsWith("#") || trimmed.startsWith("//")) continue;
    // Check if line starts with a Manchester keyword
    const kwMatch = trimmed.match(/^(SubClassOf|EquivalentTo|DisjointWith|DisjointUnionOf|Domain|Range|SubPropertyOf|InverseOf|Characteristics|Types|Facts|SameAs|DifferentFrom|Annotations):\s*(.*)/);
    if (kwMatch) {
      currentType = kwMatch[1];
      const val = kwMatch[2].trim();
      if (val) rows.push({ type: currentType, value: val });
    } else if (currentType && trimmed.startsWith(",")) {
      // Continuation line (comma-separated)
      rows.push({ type: currentType, value: trimmed.slice(1).trim() });
    } else if (currentType) {
      rows.push({ type: currentType, value: trimmed });
    }
  }
  return rows;
}

/** Convert structured rows back to Manchester text */
function rowsToManchester(entityType: string, entityLabel: string, rows: { type: string; value: string }[]): string {
  const header = entityType === "class" ? "Class:" : entityType === "individual" ? "Individual:" : "ObjectProperty:";
  const grouped = new Map<string, string[]>();
  for (const r of rows) {
    const arr = grouped.get(r.type) || [];
    arr.push(r.value);
    grouped.set(r.type, arr);
  }
  let text = `${header} ${entityLabel}\n`;
  for (const [type, values] of grouped) {
    text += `    ${type}:\n`;
    for (const v of values) {
      text += `        ${v}\n`;
    }
  }
  return text;
}

const AXIOM_TYPES = [
  "SubClassOf", "EquivalentTo", "DisjointWith", "DisjointUnionOf",
  "Domain", "Range", "SubPropertyOf", "InverseOf", "Characteristics",
  "Types", "Facts", "SameAs", "DifferentFrom", "Annotations",
];

const TYPE_COLORS: Record<string, string> = {
  SubClassOf: "#6366f1", EquivalentTo: "#10b981", DisjointWith: "#ef4444",
  Domain: "#f59e0b", Range: "#f59e0b", SubPropertyOf: "#8b5cf6",
  InverseOf: "#ec4899", Characteristics: "#64748b", Types: "#0ea5e9",
  Facts: "#14b8a6", Annotations: "#a855f7", DisjointUnionOf: "#ef4444",
  SameAs: "#10b981", DifferentFrom: "#ef4444",
};

export default function AxiomEditor({ boardId, entityIri, entityLabel, entityType }: Props) {
  const {
    manchester, setManchester, loading, saving,
    errors, saveResult, save, validate, entityNames,
  } = useAxiomEditor(boardId, entityIri);

  const [view, setView] = useState<"structured" | "editor">("structured");
  const [addingType, setAddingType] = useState<string | null>(null);
  const [addValue, setAddValue] = useState("");
  const [editingIdx, setEditingIdx] = useState<number | null>(null);
  const [editValue, setEditValue] = useState("");
  const [suggestions, setSuggestions] = useState<string[]>([]);
  const addInputRef = useRef<HTMLInputElement>(null);

  const editorRef = useRef<any>(null);
  const monacoRef = useRef<any>(null);
  const validateTimer = useRef<ReturnType<typeof setTimeout>>();

  // Parse current Manchester into structured rows
  const axiomRows = useMemo(() => parseAxiomRows(manchester), [manchester]);

  // Group rows by type for display
  const groupedAxioms = useMemo(() => {
    const groups = new Map<string, { value: string; idx: number }[]>();
    axiomRows.forEach((r, i) => {
      const arr = groups.get(r.type) || [];
      arr.push({ value: r.value, idx: i });
      groups.set(r.type, arr);
    });
    return groups;
  }, [axiomRows]);

  // Autocomplete suggestions from entity names
  const updateSuggestions = useCallback((input: string) => {
    if (!input.trim()) { setSuggestions([]); return; }
    const lower = input.toLowerCase();
    const matches = entityNames
      .filter((e) => e.label.toLowerCase().includes(lower) || e.iri.toLowerCase().includes(lower))
      .slice(0, 8)
      .map((e) => e.label);
    // Also add Manchester keywords
    const kwMatches = ["some", "only", "value", "min", "max", "exactly", "and", "or", "not", "that",
      "owl:Thing", "owl:Nothing", ...AXIOM_TYPES.map((t) => t + ":")]
      .filter((kw) => kw.toLowerCase().startsWith(lower))
      .slice(0, 4);
    setSuggestions([...kwMatches, ...matches]);
  }, [entityNames]);

  // ── Structured row operations ──
  const addAxiomRow = useCallback(() => {
    if (!addingType || !addValue.trim()) return;
    const newRows = [...axiomRows, { type: addingType, value: addValue.trim() }];
    const newText = rowsToManchester(entityType || "class", entityLabel || entityIri || "", newRows);
    setManchester(newText);
    setAddValue("");
    setAddingType(null);
    setSuggestions([]);
  }, [addingType, addValue, axiomRows, entityType, entityLabel, entityIri, setManchester]);

  const deleteAxiomRow = useCallback((idx: number) => {
    const newRows = axiomRows.filter((_, i) => i !== idx);
    const newText = rowsToManchester(entityType || "class", entityLabel || entityIri || "", newRows);
    setManchester(newText);
  }, [axiomRows, entityType, entityLabel, entityIri, setManchester]);

  const updateAxiomRow = useCallback((idx: number, newValue: string) => {
    const newRows = axiomRows.map((r, i) => i === idx ? { ...r, value: newValue } : r);
    const newText = rowsToManchester(entityType || "class", entityLabel || entityIri || "", newRows);
    setManchester(newText);
    setEditingIdx(null);
    setEditValue("");
  }, [axiomRows, entityType, entityLabel, entityIri, setManchester]);

  // ── Monaco setup ──
  const handleBeforeMount: BeforeMount = (monaco) => {
    monacoRef.current = monaco;
    if (!monaco.languages.getLanguages().some((l: any) => l.id === MANCHESTER_LANG_ID)) {
      monaco.languages.register({ id: MANCHESTER_LANG_ID });
      monaco.languages.setMonarchTokensProvider(MANCHESTER_LANG_ID, languageDef as any);
      monaco.editor.defineTheme("manchester-dark", {
        base: "vs-dark", inherit: true, rules: themeRules,
        colors: {
          "editor.background": "#12121a", "editor.foreground": "#dfe6e9",
          "editorLineNumber.foreground": "#636e72",
          "editor.selectionBackground": "#6c5ce733",
          "editor.lineHighlightBackground": "#1a1a2600",
        },
      });
      monaco.languages.registerCompletionItemProvider(MANCHESTER_LANG_ID, {
        provideCompletionItems: (_model: any, position: any) => {
          const range = {
            startLineNumber: position.lineNumber, startColumn: position.column - 1,
            endLineNumber: position.lineNumber, endColumn: position.column,
          };
          return {
            suggestions: [
              ...completionItems.map((item) => ({
                label: item,
                kind: item.endsWith(":") ? monaco.languages.CompletionItemKind.Keyword : monaco.languages.CompletionItemKind.Text,
                insertText: item, range,
              })),
              ...entityNames.map((e) => ({
                label: e.label, kind: monaco.languages.CompletionItemKind.Class,
                insertText: e.label, detail: `${e.type} — ${e.iri}`, range,
              })),
            ],
          };
        },
      });
    }
  };

  const handleMount: OnMount = (editor) => { editorRef.current = editor; };

  useEffect(() => {
    if (!editorRef.current || !monacoRef.current) return;
    const model = editorRef.current.getModel();
    if (!model) return;
    const markers = errors.map((e) => ({
      severity: monacoRef.current.MarkerSeverity.Error,
      message: e.message, startLineNumber: e.line, startColumn: e.column,
      endLineNumber: e.line, endColumn: e.column + 20,
    }));
    monacoRef.current.editor.setModelMarkers(model, "manchester-validation", markers);
  }, [errors]);

  const handleChange = useCallback((value: string | undefined) => {
    const text = value || "";
    setManchester(text);
    if (validateTimer.current) clearTimeout(validateTimer.current);
    validateTimer.current = setTimeout(() => validate(text), 600);
  }, [setManchester, validate]);

  if (!entityIri) {
    return (
      <div className={styles.empty}>
        <p>Select a class or property on the canvas to edit its axioms.</p>
      </div>
    );
  }

  return (
    <div className={styles.container}>
      {/* Entity header */}
      <div className={styles.header}>
        <span className={styles.entityBadge}>{entityType || "entity"}</span>
        <span className={styles.entityLabel}>{entityLabel || entityIri}</span>
        <div className={styles.viewToggle}>
          <button className={`${styles.viewBtn} ${view === "structured" ? styles.viewActive : ""}`}
                  onClick={() => setView("structured")}>Structured</button>
          <button className={`${styles.viewBtn} ${view === "editor" ? styles.viewActive : ""}`}
                  onClick={() => setView("editor")}>Manchester</button>
        </div>
      </div>

      {loading ? (
        <div className={styles.loading}>Loading axioms...</div>
      ) : view === "structured" ? (
        /* ── Structured Axiom View ── */
        <div className={styles.structuredView}>
          {groupedAxioms.size === 0 && !addingType && (
            <div className={styles.emptyAxioms}>No axioms defined yet. Click + to add one.</div>
          )}

          {Array.from(groupedAxioms.entries()).map(([type, items]) => (
            <div key={type} className={styles.axiomGroup}>
              <div className={styles.axiomGroupHeader}>
                <span className={styles.axiomTypeBadge} style={{ background: (TYPE_COLORS[type] || "#64748b") + "18", color: TYPE_COLORS[type] || "#64748b" }}>
                  {type}
                </span>
                <span className={styles.axiomCount}>{items.length}</span>
              </div>
              {items.map(({ value, idx }) => (
                <div key={idx} className={styles.axiomRow}>
                  {editingIdx === idx ? (
                    <div className={styles.axiomEditRow}>
                      <input
                        className={styles.axiomInput}
                        value={editValue}
                        onChange={(e) => { setEditValue(e.target.value); updateSuggestions(e.target.value); }}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") updateAxiomRow(idx, editValue);
                          if (e.key === "Escape") { setEditingIdx(null); setSuggestions([]); }
                        }}
                        autoFocus
                      />
                      <button className={styles.axiomSaveBtn} onClick={() => updateAxiomRow(idx, editValue)}>Save</button>
                      <button className={styles.axiomCancelBtn} onClick={() => { setEditingIdx(null); setSuggestions([]); }}>Cancel</button>
                    </div>
                  ) : (
                    <>
                      <span className={styles.axiomValue} onDoubleClick={() => { setEditingIdx(idx); setEditValue(value); }}
                            title="Double-click to edit">{value}</span>
                      <button className={styles.axiomEditBtn} onClick={() => { setEditingIdx(idx); setEditValue(value); }}
                              title="Edit">&#9998;</button>
                      <button className={styles.axiomDeleteBtn} onClick={() => deleteAxiomRow(idx)}
                              title="Remove">&times;</button>
                    </>
                  )}
                </div>
              ))}
            </div>
          ))}

          {/* Add axiom row */}
          {addingType ? (
            <div className={styles.addRow}>
              <span className={styles.addLabel}>{addingType}:</span>
              <div className={styles.addInputWrap}>
                <input
                  ref={addInputRef}
                  className={styles.axiomInput}
                  value={addValue}
                  onChange={(e) => { setAddValue(e.target.value); updateSuggestions(e.target.value); }}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") addAxiomRow();
                    if (e.key === "Escape") { setAddingType(null); setAddValue(""); setSuggestions([]); }
                  }}
                  placeholder="e.g. Animal, hasPart some Organ, ..."
                  autoFocus
                />
                {suggestions.length > 0 && (
                  <div className={styles.suggestions}>
                    {suggestions.map((s) => (
                      <button key={s} className={styles.suggestion}
                              onClick={() => { setAddValue((prev) => { const parts = prev.split(/\s+/); parts[parts.length - 1] = s; return parts.join(" "); }); setSuggestions([]); addInputRef.current?.focus(); }}>
                        {s}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <button className={styles.axiomSaveBtn} onClick={addAxiomRow} disabled={!addValue.trim()}>Add</button>
              <button className={styles.axiomCancelBtn} onClick={() => { setAddingType(null); setAddValue(""); setSuggestions([]); }}>Cancel</button>
            </div>
          ) : (
            <div className={styles.addButtons}>
              {AXIOM_TYPES.filter((t) => {
                if (entityType === "class") return ["SubClassOf", "EquivalentTo", "DisjointWith", "Annotations"].includes(t);
                if (entityType === "individual") return ["Types", "Facts", "SameAs", "DifferentFrom", "Annotations"].includes(t);
                return ["Domain", "Range", "SubPropertyOf", "InverseOf", "Characteristics", "Annotations"].includes(t);
              }).map((t) => (
                <button key={t} className={styles.addTypeBtn}
                        style={{ borderColor: TYPE_COLORS[t] || "#64748b", color: TYPE_COLORS[t] || "#64748b" }}
                        onClick={() => { setAddingType(t); setTimeout(() => addInputRef.current?.focus(), 50); }}>
                  + {t}
                </button>
              ))}
            </div>
          )}
        </div>
      ) : (
        /* ── Manchester Editor View ── */
        <div className={styles.editorWrap}>
          <Editor
            height="100%"
            language={MANCHESTER_LANG_ID}
            theme="manchester-dark"
            value={manchester}
            onChange={handleChange}
            beforeMount={handleBeforeMount}
            onMount={handleMount}
            options={{
              minimap: { enabled: false }, fontSize: 13, lineNumbers: "on",
              scrollBeyondLastLine: false, wordWrap: "on", padding: { top: 8 },
              renderLineHighlight: "none", overviewRulerBorder: false,
              hideCursorInOverviewRuler: true, tabSize: 4, automaticLayout: true,
            }}
          />
        </div>
      )}

      {/* Footer: save button + status */}
      <div className={styles.footer}>
        <button className={styles.saveBtn} onClick={save} disabled={saving || !manchester.trim()}>
          {saving ? "Saving..." : "Save Axioms"}
        </button>
        {saveResult && (
          <span className={saveResult.success ? styles.successMsg : styles.errorMsg}>
            {saveResult.message}
          </span>
        )}
        {errors.length > 0 && !saveResult && (
          <span className={styles.errorMsg}>{errors.length} syntax issue(s)</span>
        )}
      </div>
    </div>
  );
}
