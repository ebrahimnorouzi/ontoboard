import { useEffect, useState, useCallback } from "react";
import { apiJson } from "../api";

interface PrefixEntry { prefix: string; namespace: string }
interface OntologyMetadata {
  ontology_iri: string;
  version_iri: string | null;
  imports: string[];
  prefixes: PrefixEntry[];
  languages: string[];
}
interface OntologyStats {
  classes: number;
  object_properties: number;
  data_properties: number;
  annotation_properties: number;
  individuals: number;
  total_axioms: number;
  subclass_axioms: number;
  equivalent_axioms: number;
  disjoint_axioms: number;
  domain_axioms: number;
  range_axioms: number;
  total_triples: number;
}
interface ReportViolation {
  subject: string;
  property: string;
  severity: string;
  message: string;
  rule: string;
}
interface RobotReport {
  exit_code: number;
  violations: ReportViolation[];
  summary: string;
}
interface DashboardData {
  metadata: OntologyMetadata;
  statistics: OntologyStats;
  report: RobotReport | null;
}

export function useOntologyDashboard(boardId: string | undefined) {
  const [data, setData] = useState<DashboardData | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reportLoading, setReportLoading] = useState(false);

  useEffect(() => {
    if (!boardId) return;
    setLoading(true);
    apiJson<DashboardData>(`/api/ontology/${boardId}/dashboard`)
      .then(setData)
      .catch((e) => setError(e.message))
      .finally(() => setLoading(false));
  }, [boardId]);

  const runReport = useCallback(async () => {
    if (!boardId) return;
    setReportLoading(true);
    try {
      const report = await apiJson<RobotReport>(`/api/ontology/${boardId}/report`, { method: "POST" });
      setData((prev) => prev ? { ...prev, report } : prev);
    } catch (e: any) {
      setError(e.message);
    } finally {
      setReportLoading(false);
    }
  }, [boardId]);

  return { data, loading, error, runReport, reportLoading };
}

export type { OntologyMetadata, OntologyStats, RobotReport, ReportViolation, DashboardData, PrefixEntry };
