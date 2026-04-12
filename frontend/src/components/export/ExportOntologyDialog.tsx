/**
 * ExportOntologyDialog — unified dialog for exporting the ontology
 * in various OWL serialisation formats and as an ODK repository ZIP.
 */
import { useState } from "react";
import { api } from "../../api";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./ExportOntologyDialog.module.css";

interface Props {
  boardId: string;
  onClose: () => void;
  onError: (msg: string) => void;
}

const FORMATS: { value: string; label: string }[] = [
  { value: "owx", label: "OWL/XML (.owx)" },
  { value: "ttl", label: "Turtle (.ttl)" },
  { value: "obo", label: "OBO (.obo)" },
  { value: "jsonld", label: "JSON-LD (.jsonld)" },
  { value: "ofn", label: "OWL Functional (.ofn)" },
  { value: "nt", label: "N-Triples (.nt)" },
];

/** Trigger a browser download from a Blob */
function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export default function ExportOntologyDialog({ boardId, onClose, onError }: Props) {
  const [selectedFormat, setSelectedFormat] = useState("ttl");
  const [includeImports, setIncludeImports] = useState(true);
  const [includeAnnotations, setIncludeAnnotations] = useState(true);
  const [includeIndividuals, setIncludeIndividuals] = useState(true);
  const [busy, setBusy] = useState(false);
  const [statusMsg, setStatusMsg] = useState<{ type: "error" | "success"; text: string } | null>(null);

  const handleDownload = async () => {
    setBusy(true);
    setStatusMsg(null);
    try {
      // Save current state to backend first
      await useOntologyStore.getState().saveToBackend();

      // Request conversion
      const res = await api(`/api/robot/${boardId}/convert`, {
        method: "POST",
        body: JSON.stringify({ output_format: selectedFormat }),
      });

      if (!res.ok) {
        const data = await res.json().catch(() => ({ detail: "Conversion failed" }));
        setStatusMsg({ type: "error", text: data.detail || "Conversion failed" });
        setBusy(false);
        return;
      }

      const data = await res.json();
      if (!data.output_file) {
        setStatusMsg({ type: "error", text: "No output file returned from conversion" });
        setBusy(false);
        return;
      }

      const fileName = data.output_file.split("/").pop() || `${boardId}.${selectedFormat}`;

      // Try primary download endpoint
      const dlRes = await api(`/api/odk-mediator/${boardId}/release/download/${fileName}`);
      if (dlRes.ok) {
        const blob = await dlRes.blob();
        downloadBlob(blob, fileName);
        setStatusMsg({ type: "success", text: `Downloaded ${fileName}` });
      } else {
        // Fallback endpoint
        const fb = await api(`/api/odk-setup/${boardId}/file/${data.output_file}`);
        if (fb.ok) {
          const blob = await fb.blob();
          downloadBlob(blob, fileName);
          setStatusMsg({ type: "success", text: `Downloaded ${fileName}` });
        } else {
          setStatusMsg({ type: "error", text: "Download failed - file not found after conversion" });
        }
      }
    } catch (e: any) {
      setStatusMsg({ type: "error", text: e.message || "Export failed" });
    } finally {
      setBusy(false);
    }
  };

  const handleZipExport = async () => {
    setBusy(true);
    setStatusMsg(null);
    try {
      const res = await api(`/api/export/${boardId}/zip`, { method: "POST" });
      if (res.ok) {
        const blob = await res.blob();
        downloadBlob(blob, `${boardId}-odp-repo.zip`);
        setStatusMsg({ type: "success", text: "Downloaded ODK repository ZIP" });
      } else {
        setStatusMsg({ type: "error", text: "ZIP export failed" });
      }
    } catch (e: any) {
      setStatusMsg({ type: "error", text: e.message || "ZIP export failed" });
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className={styles.overlay} onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className={styles.dialog}>
        <div className={styles.header}>
          <h3 className={styles.title}>Export Ontology</h3>
          <button className={styles.closeBtn} onClick={onClose}>&times;</button>
        </div>

        <div className={styles.body}>
          {/* Format selector */}
          <div>
            <div className={styles.sectionLabel}>Format</div>
            <div className={styles.formatGrid}>
              {FORMATS.map((fmt) => (
                <label key={fmt.value} className={styles.formatOption}>
                  <input
                    type="radio"
                    name="ontology-format"
                    checked={selectedFormat === fmt.value}
                    onChange={() => setSelectedFormat(fmt.value)}
                  />
                  {fmt.label}
                </label>
              ))}
            </div>
          </div>

          {/* Options */}
          <div>
            <div className={styles.sectionLabel}>Options</div>
            <div className={styles.optionsSection}>
              <label>
                <input type="checkbox" checked={includeImports} onChange={(e) => setIncludeImports(e.target.checked)} />
                Include imports
              </label>
              <label>
                <input type="checkbox" checked={includeAnnotations} onChange={(e) => setIncludeAnnotations(e.target.checked)} />
                Include annotations
              </label>
              <label>
                <input type="checkbox" checked={includeIndividuals} onChange={(e) => setIncludeIndividuals(e.target.checked)} />
                Include individuals
              </label>
            </div>
          </div>

          {/* ODK Repository */}
          <div className={styles.odkSection}>
            <div className={styles.sectionLabel}>ODK Repository</div>
            <button className={styles.odkBtn} onClick={handleZipExport} disabled={busy}>
              Export Full ODK Repository (ZIP)
            </button>
          </div>

          {/* Status message */}
          {statusMsg && (
            <div className={styles.statusMsg} data-type={statusMsg.type}>
              {statusMsg.text}
            </div>
          )}
        </div>

        <div className={styles.actions}>
          <button className={styles.cancelBtn} onClick={onClose}>Cancel</button>
          <button className={styles.downloadBtn} onClick={handleDownload} disabled={busy}>
            {busy ? "Exporting..." : "Download"}
          </button>
        </div>
      </div>
    </div>
  );
}
