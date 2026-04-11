/**
 * AxiomEditor — Monaco-based Manchester OWL Syntax editor.
 *
 * Shows axioms for the currently selected entity. Supports syntax
 * highlighting, validation markers, autocomplete, and save-to-backend.
 */

import { useEffect, useRef, useCallback } from "react";
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

export default function AxiomEditor({ boardId, entityIri, entityLabel, entityType }: Props) {
  const {
    manchester, setManchester, loading, saving,
    errors, saveResult, save, validate, entityNames,
  } = useAxiomEditor(boardId, entityIri);

  const editorRef = useRef<any>(null);
  const monacoRef = useRef<any>(null);
  const validateTimer = useRef<ReturnType<typeof setTimeout>>();

  const handleBeforeMount: BeforeMount = (monaco) => {
    monacoRef.current = monaco;

    // Register language if not already done
    if (!monaco.languages.getLanguages().some((l: any) => l.id === MANCHESTER_LANG_ID)) {
      monaco.languages.register({ id: MANCHESTER_LANG_ID });
      monaco.languages.setMonarchTokensProvider(MANCHESTER_LANG_ID, languageDef as any);

      // Custom dark theme
      monaco.editor.defineTheme("manchester-dark", {
        base: "vs-dark",
        inherit: true,
        rules: themeRules,
        colors: {
          "editor.background": "#12121a",
          "editor.foreground": "#dfe6e9",
          "editorLineNumber.foreground": "#636e72",
          "editor.selectionBackground": "#6c5ce733",
          "editor.lineHighlightBackground": "#1a1a2600",
        },
      });

      // Completion provider
      monaco.languages.registerCompletionItemProvider(MANCHESTER_LANG_ID, {
        provideCompletionItems: (_model: any, position: any) => {
          const range = {
            startLineNumber: position.lineNumber,
            startColumn: position.column - 1,
            endLineNumber: position.lineNumber,
            endColumn: position.column,
          };
          const suggestions = [
            ...completionItems.map((item) => ({
              label: item,
              kind: item.endsWith(":") ? monaco.languages.CompletionItemKind.Keyword : monaco.languages.CompletionItemKind.Text,
              insertText: item,
              range,
            })),
            ...entityNames.map((e) => ({
              label: e.label,
              kind: monaco.languages.CompletionItemKind.Class,
              insertText: e.label,
              detail: `${e.type} — ${e.iri}`,
              range,
            })),
          ];
          return { suggestions };
        },
      });
    }
  };

  const handleMount: OnMount = (editor) => {
    editorRef.current = editor;
  };

  // Update error markers when errors change
  useEffect(() => {
    if (!editorRef.current || !monacoRef.current) return;
    const model = editorRef.current.getModel();
    if (!model) return;

    const markers = errors.map((e) => ({
      severity: monacoRef.current.MarkerSeverity.Error,
      message: e.message,
      startLineNumber: e.line,
      startColumn: e.column,
      endLineNumber: e.line,
      endColumn: e.column + 20,
    }));
    monacoRef.current.editor.setModelMarkers(model, "manchester-validation", markers);
  }, [errors]);

  const handleChange = useCallback((value: string | undefined) => {
    const text = value || "";
    setManchester(text);
    // Debounced validation
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
      </div>

      {/* Editor */}
      <div className={styles.editorWrap}>
        {loading ? (
          <div className={styles.loading}>Loading axioms...</div>
        ) : (
          <Editor
            height="100%"
            language={MANCHESTER_LANG_ID}
            theme="manchester-dark"
            value={manchester}
            onChange={handleChange}
            beforeMount={handleBeforeMount}
            onMount={handleMount}
            options={{
              minimap: { enabled: false },
              fontSize: 13,
              lineNumbers: "on",
              scrollBeyondLastLine: false,
              wordWrap: "on",
              padding: { top: 8 },
              renderLineHighlight: "none",
              overviewRulerBorder: false,
              hideCursorInOverviewRuler: true,
              tabSize: 4,
              automaticLayout: true,
            }}
          />
        )}
      </div>

      {/* Footer: save button + status */}
      <div className={styles.footer}>
        <button
          className={styles.saveBtn}
          onClick={save}
          disabled={saving || !manchester.trim()}
        >
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
