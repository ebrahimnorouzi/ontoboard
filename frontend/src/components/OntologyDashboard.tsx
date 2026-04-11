import { useOntologyDashboard } from "../hooks/useOntology";
import styles from "./OntologyDashboard.module.css";

interface Props {
  boardId: string;
}

export default function OntologyDashboard({ boardId }: Props) {
  const { data, loading, error, runReport, reportLoading } = useOntologyDashboard(boardId);

  if (loading) return <div className={styles.loading}>Loading ontology info...</div>;
  if (error) return <div className={styles.error}>{error}</div>;
  if (!data) return null;

  const { metadata, statistics, report } = data;

  return (
    <div className={styles.dashboard}>
      {/* ── Metadata ─────────────────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Ontology</h3>
        <div className={styles.metaGrid}>
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>IRI</span>
            <span className={styles.metaValue}>{metadata.ontology_iri || "—"}</span>
          </div>
          {metadata.version_iri && (
            <div className={styles.metaItem}>
              <span className={styles.metaLabel}>Version</span>
              <span className={styles.metaValue}>{metadata.version_iri}</span>
            </div>
          )}
          <div className={styles.metaItem}>
            <span className={styles.metaLabel}>Languages</span>
            <span className={styles.metaValue}>{metadata.languages.join(", ") || "—"}</span>
          </div>
        </div>

        {metadata.imports.length > 0 && (
          <div className={styles.imports}>
            <span className={styles.metaLabel}>Imports</span>
            <ul className={styles.importList}>
              {metadata.imports.map((imp) => (
                <li key={imp} className={styles.importItem}>{imp}</li>
              ))}
            </ul>
          </div>
        )}
      </section>

      {/* ── Statistics ────────────────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Statistics</h3>
        <div className={styles.statsGrid}>
          <StatCell label="Classes" value={statistics.classes} color="var(--accent)" />
          <StatCell label="Object Props" value={statistics.object_properties} color="var(--success)" />
          <StatCell label="Data Props" value={statistics.data_properties} color="var(--warning)" />
          <StatCell label="Annot Props" value={statistics.annotation_properties} color="var(--text-muted)" />
          <StatCell label="Individuals" value={statistics.individuals} color="var(--danger)" />
          <StatCell label="Axioms" value={statistics.total_axioms} color="var(--accent-light)" />
          <StatCell label="Triples" value={statistics.total_triples} color="var(--text-secondary)" />
        </div>
        <div className={styles.axiomBreakdown}>
          <small>SubClassOf: {statistics.subclass_axioms} | Equivalent: {statistics.equivalent_axioms} | Disjoint: {statistics.disjoint_axioms} | Domain: {statistics.domain_axioms} | Range: {statistics.range_axioms}</small>
        </div>
      </section>

      {/* ── Prefixes ──────────────────────────────────── */}
      <section className={styles.section}>
        <h3 className={styles.sectionTitle}>Prefixes ({metadata.prefixes.length})</h3>
        <div className={styles.prefixTable}>
          {metadata.prefixes.slice(0, 12).map((p) => (
            <div key={p.prefix} className={styles.prefixRow}>
              <span className={styles.prefixName}>{p.prefix}</span>
              <span className={styles.prefixNs}>{p.namespace}</span>
            </div>
          ))}
          {metadata.prefixes.length > 12 && (
            <div className={styles.prefixMore}>+{metadata.prefixes.length - 12} more</div>
          )}
        </div>
      </section>

      {/* ── ROBOT Report ──────────────────────────────── */}
      <section className={styles.section}>
        <div className={styles.sectionHeader}>
          <h3 className={styles.sectionTitle}>ROBOT Report</h3>
          <button
            className={styles.reportBtn}
            onClick={runReport}
            disabled={reportLoading}
          >
            {reportLoading ? "Running..." : "Run Report"}
          </button>
        </div>

        {report ? (
          <>
            <div className={styles.reportSummary}>{report.summary}</div>
            {report.violations.length > 0 && (
              <div className={styles.violationList}>
                {report.violations.slice(0, 20).map((v, i) => (
                  <div
                    key={i}
                    className={`${styles.violation} ${
                      v.severity.toUpperCase() === "ERROR"
                        ? styles.violationError
                        : v.severity.toUpperCase().startsWith("WARN")
                        ? styles.violationWarn
                        : styles.violationInfo
                    }`}
                  >
                    <span className={styles.violationSeverity}>{v.severity}</span>
                    <span className={styles.violationMsg}>{v.message}</span>
                    {v.subject && (
                      <span className={styles.violationSubject}>{v.subject}</span>
                    )}
                  </div>
                ))}
                {report.violations.length > 20 && (
                  <div className={styles.prefixMore}>+{report.violations.length - 20} more</div>
                )}
              </div>
            )}
          </>
        ) : (
          <p className={styles.reportHint}>
            Click "Run Report" to check ontology quality with ROBOT (requires odkfull image).
          </p>
        )}
      </section>
    </div>
  );
}

function StatCell({ label, value, color }: { label: string; value: number; color: string }) {
  return (
    <div className={styles.statCell}>
      <div className={styles.statValue} style={{ color }}>{value}</div>
      <div className={styles.statLabel}>{label}</div>
    </div>
  );
}
