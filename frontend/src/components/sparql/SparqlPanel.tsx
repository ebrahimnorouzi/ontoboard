import { useState, useRef } from "react";
import Editor from "@monaco-editor/react";
import { useSparql } from "../../hooks/useSparql";
import styles from "./SparqlPanel.module.css";

interface Props { boardId: string }

export default function SparqlPanel({ boardId }: Props) {
  const { result, graphViz, running, error, runQuery, visualize, EXAMPLE_QUERIES } = useSparql(boardId);
  const [query, setQuery] = useState(EXAMPLE_QUERIES[0].query);
  const [showViz, setShowViz] = useState(false);

  const handleRun = () => runQuery(query);
  const handleVisualize = () => { visualize(query); setShowViz(true); };

  return (
    <div className={styles.container}>
      {/* Example queries */}
      <div className={styles.examples}>
        {EXAMPLE_QUERIES.map((eq, i) => (
          <button key={i} className={styles.exBtn} onClick={() => setQuery(eq.query)}>
            {eq.label}
          </button>
        ))}
      </div>

      {/* Editor */}
      <div className={styles.editorWrap}>
        <Editor
          height="160px"
          language="sparql"
          theme="vs-dark"
          value={query}
          onChange={(v) => setQuery(v || "")}
          options={{
            minimap: { enabled: false }, fontSize: 12, lineNumbers: "on",
            scrollBeyondLastLine: false, wordWrap: "on", padding: { top: 4 },
            automaticLayout: true,
          }}
        />
      </div>

      {/* Action buttons */}
      <div className={styles.actions}>
        <button className={styles.runBtn} onClick={handleRun} disabled={running}>
          {running ? "Running..." : "Run Query"}
        </button>
        <button className={styles.vizBtn} onClick={handleVisualize} disabled={running}>
          Visualize
        </button>
        {result && (
          <span className={styles.meta}>
            {result.total} results &middot; {result.execution_time_ms.toFixed(0)}ms &middot; {result.query_type}
          </span>
        )}
      </div>

      {error && <div className={styles.error}>{error}</div>}

      {/* Results table */}
      {result && !showViz && result.query_type === "SELECT" && (
        <div className={styles.resultTable}>
          <table>
            <thead>
              <tr>{result.columns.map((c) => <th key={c}>{c}</th>)}</tr>
            </thead>
            <tbody>
              {result.rows.slice(0, 100).map((row, i) => (
                <tr key={i}>
                  {result.columns.map((c) => (
                    <td key={c} title={row[c]}>{_shorten(row[c])}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          {result.total > 100 && (
            <div className={styles.more}>Showing 100 of {result.total} results</div>
          )}
        </div>
      )}

      {/* ASK result */}
      {result && result.query_type === "ASK" && (
        <div className={styles.askResult}>
          Result: <strong>{result.rows[0]?.result}</strong>
        </div>
      )}

      {/* CONSTRUCT result as triples */}
      {result && !showViz && (result.query_type === "CONSTRUCT" || result.query_type === "DESCRIBE") && (
        <div className={styles.resultTable}>
          <table>
            <thead><tr><th>Subject</th><th>Predicate</th><th>Object</th></tr></thead>
            <tbody>
              {result.rows.slice(0, 100).map((row, i) => (
                <tr key={i}>
                  <td title={row.subject}>{_shorten(row.subject)}</td>
                  <td title={row.predicate}>{_shorten(row.predicate)}</td>
                  <td title={row.object}>{_shorten(row.object)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Graph visualization */}
      {showViz && graphViz && (
        <div className={styles.vizSection}>
          <div className={styles.vizHeader}>
            <span>{graphViz.nodes.length} nodes, {graphViz.edges.length} edges</span>
            <button className={styles.exBtn} onClick={() => setShowViz(false)}>Table View</button>
          </div>
          <div className={styles.graphCanvas}>
            <svg width="100%" height="100%" viewBox="0 0 600 400">
              {/* Simple force-layout approximation */}
              {graphViz.edges.map((e, i) => {
                const si = graphViz.nodes.findIndex((n) => n.id === e.source);
                const ti = graphViz.nodes.findIndex((n) => n.id === e.target);
                if (si < 0 || ti < 0) return null;
                const sx = 80 + (si % 6) * 90, sy = 40 + Math.floor(si / 6) * 80;
                const tx = 80 + (ti % 6) * 90, ty = 40 + Math.floor(ti / 6) * 80;
                return (
                  <g key={i}>
                    <line x1={sx} y1={sy} x2={tx} y2={ty} stroke="#555" strokeWidth="1" markerEnd="url(#ah)" />
                    <text x={(sx + tx) / 2} y={(sy + ty) / 2 - 4} fill="#888" fontSize="8" textAnchor="middle">{e.label}</text>
                  </g>
                );
              })}
              <defs>
                <marker id="ah" markerWidth="6" markerHeight="4" refX="6" refY="2" orient="auto">
                  <polygon points="0 0, 6 2, 0 4" fill="#888" />
                </marker>
              </defs>
              {graphViz.nodes.map((n, i) => {
                const x = 80 + (i % 6) * 90, y = 40 + Math.floor(i / 6) * 80;
                return (
                  <g key={n.id}>
                    <circle cx={x} cy={y} r="16" fill={n.color || "#a29bfe"} opacity="0.8" />
                    <text x={x} y={y + 28} fill="#ddd" fontSize="9" textAnchor="middle">{n.label}</text>
                  </g>
                );
              })}
            </svg>
          </div>
        </div>
      )}
    </div>
  );
}

function _shorten(value: string): string {
  if (!value) return "";
  if (value.length > 50) {
    if (value.includes("#")) return value.split("#").pop() || value;
    if (value.includes("/")) return "..." + value.split("/").pop();
  }
  return value;
}
