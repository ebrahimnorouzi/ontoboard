import { useState, useCallback, useEffect } from "react";
import { apiJson } from "../api";

interface SparqlResult {
  columns: string[]; rows: Record<string, string>[];
  total: number; execution_time_ms: number; query_type: string;
}
interface GraphNode { id: string; label: string; type: string; color: string }
interface GraphEdge { source: string; target: string; label: string; iri: string }
interface GraphViz { nodes: GraphNode[]; edges: GraphEdge[] }

const EXAMPLE_QUERIES = [
  { label: "All classes", query: "PREFIX owl: <http://www.w3.org/2002/07/owl#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nSELECT ?class ?label WHERE {\n  ?class a owl:Class .\n  OPTIONAL { ?class rdfs:label ?label }\n}" },
  { label: "All individuals", query: "PREFIX owl: <http://www.w3.org/2002/07/owl#>\nPREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>\nSELECT ?ind ?type ?label WHERE {\n  ?ind a owl:NamedIndividual .\n  ?ind a ?type .\n  FILTER(?type != owl:NamedIndividual)\n  OPTIONAL { ?ind rdfs:label ?label }\n}" },
  { label: "Graph (CONSTRUCT)", query: "PREFIX owl: <http://www.w3.org/2002/07/owl#>\nCONSTRUCT { ?s ?p ?o }\nWHERE { ?s a owl:Class . ?s ?p ?o }" },
  { label: "Count triples", query: "SELECT (COUNT(*) as ?count) WHERE { ?s ?p ?o }" },
];

export function useSparql(boardId: string | undefined) {
  const [result, setResult] = useState<SparqlResult | null>(null);
  const [graphViz, setGraphViz] = useState<GraphViz | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState("");
  const [prefixes, setPrefixes] = useState<{ prefix: string; namespace: string }[]>([]);

  useEffect(() => {
    if (!boardId) return;
    apiJson<{ prefix: string; namespace: string }[]>(`/api/sparql/${boardId}/prefixes`)
      .then(setPrefixes).catch(() => {});
  }, [boardId]);

  const runQuery = useCallback(async (query: string) => {
    if (!boardId) return;
    setRunning(true); setError(""); setGraphViz(null);
    try {
      const r = await apiJson<SparqlResult>(`/api/sparql/${boardId}/query`, {
        method: "POST", body: JSON.stringify({ query }),
      });
      setResult(r);
    } catch (e: any) { setError(e.message); }
    finally { setRunning(false); }
  }, [boardId]);

  const visualize = useCallback(async (query: string) => {
    if (!boardId) return;
    setRunning(true); setError("");
    try {
      const g = await apiJson<GraphViz>(`/api/sparql/${boardId}/visualize`, {
        method: "POST", body: JSON.stringify({ query }),
      });
      setGraphViz(g);
    } catch (e: any) { setError(e.message); }
    finally { setRunning(false); }
  }, [boardId]);

  return { result, graphViz, running, error, runQuery, visualize, prefixes, EXAMPLE_QUERIES };
}

export type { SparqlResult, GraphNode, GraphEdge, GraphViz };
