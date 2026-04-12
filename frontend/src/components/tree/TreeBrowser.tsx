import { useState, useCallback } from "react";
import { useTreeData, useEntityDetail, TreeNode, TreeTab } from "../../hooks/useTreeData";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./TreeBrowser.module.css";

interface Props {
  boardId: string;
}

const TAB_LABELS: { id: TreeTab; label: string; fullLabel: string }[] = [
  { id: "classes", label: "C", fullLabel: "Classes" },
  { id: "object-properties", label: "OP", fullLabel: "Object Properties" },
  { id: "data-properties", label: "DP", fullLabel: "Data Properties" },
  { id: "annotation-properties", label: "AP", fullLabel: "Annotation Properties" },
  { id: "individuals", label: "Ind", fullLabel: "Individuals" },
];

export default function TreeBrowser({ boardId }: Props) {
  const { tree, activeTab, setActiveTab, loading, search, setSearch, refresh } = useTreeData(boardId);
  const selectedIri = useOntologyStore((s) => s.selectedEntity?.iri ?? null);
  const { detail, loading: detailLoading } = useEntityDetail(boardId, selectedIri || undefined);
  const [adding, setAdding] = useState(false);
  const [newLabel, setNewLabel] = useState("");
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const [renamingIri, setRenamingIri] = useState<string | null>(null);
  const [renameLabel, setRenameLabel] = useState("");
  const store = useOntologyStore();

  const handleSelect = (node: TreeNode) => {
    store.selectEntity({ iri: node.iri, type: node.entity_type, label: node.label });
  };

  // ── Add entity — if a class is selected, new entity is its child ──
  const handleAdd = useCallback(() => {
    if (!newLabel.trim()) return;
    const ts = Date.now();
    const label = newLabel.trim();

    if (activeTab === "classes") {
      const iri = `http://example.org/new#${label.replace(/\s+/g, "_")}_${ts}`;
      store.addClass({
        id: iri, iri, label,
        x: 200 + Math.random() * 400,
        y: 100 + Math.random() * 300,
        w: 160, h: 60, color: "#4f46e5",
      });
      // If a class is currently selected, make new class its subclass
      if (selectedIri && store.classes.some(c => c.iri === selectedIri)) {
        store.addSubClassOf(iri, selectedIri);
      }
      store.selectEntity({ iri, type: "class", label });
    } else if (activeTab === "individuals") {
      const iri = `http://example.org/new#${label.replace(/\s+/g, "_")}_${ts}`;
      // If a class is selected, assign the individual to that class
      const classIri = selectedIri && store.classes.some(c => c.iri === selectedIri) ? selectedIri : "";
      store.setIndividuals([...store.individuals, {
        id: iri, iri, label, class_iri: classIri,
        x: 300 + Math.random() * 200, y: 400 + Math.random() * 200,
      }]);
      store.selectEntity({ iri, type: "individual", label });
    } else if (activeTab === "object-properties" || activeTab === "data-properties" || activeTab === "annotation-properties") {
      const propType = activeTab === "object-properties" ? "object"
        : activeTab === "data-properties" ? "data" : "annotation";
      const iri = `http://example.org/new#${label.replace(/\s+/g, "_")}_${ts}`;
      store.addProperty({
        id: `prop_${ts}`, iri, label,
        source_id: "", target_id: "",
        property_type: propType,
      });
      store.selectEntity({ iri, type: propType + "-property", label });
    }
    setNewLabel("");
    setAdding(false);
    setTimeout(refresh, 500);
  }, [newLabel, activeTab, store, selectedIri, refresh]);

  // ── Delete entity ─────────────────────────────────────────
  const handleDelete = useCallback((iri: string) => {
    store.removeClass(iri);
    store.setIndividuals(store.individuals.filter(i => i.iri !== iri));
    if (selectedIri === iri) {
      store.selectEntity(null);
    }
    setConfirmDelete(null);
    setTimeout(refresh, 500);
  }, [store, selectedIri, refresh]);

  // ── Drag-drop: make child (SubClassOf) ────────────────────
  const handleMakeChild = useCallback((childIri: string, parentIri: string) => {
    if (childIri === parentIri) return;
    store.addSubClassOf(childIri, parentIri);
    setTimeout(refresh, 500);
  }, [store, refresh]);

  // ── Inline rename ─────────────────────────────────────────
  const handleRename = useCallback((iri: string, newLbl: string) => {
    if (!newLbl.trim()) { setRenamingIri(null); return; }
    store.updateClass(iri, { label: newLbl.trim() });
    // Also update individuals
    store.setIndividuals(store.individuals.map(i =>
      i.iri === iri ? { ...i, label: newLbl.trim() } : i
    ));
    setRenamingIri(null);
    if (selectedIri === iri) {
      store.selectEntity({ iri, type: "class", label: newLbl.trim() });
    }
    setTimeout(refresh, 500);
  }, [store, selectedIri, refresh]);

  const filtered = search ? filterTree(tree, search.toLowerCase()) : tree;

  return (
    <div className={styles.container}>
      {/* Tabs */}
      <div className={styles.tabs}>
        {TAB_LABELS.map((t) => (
          <button
            key={t.id}
            className={`${styles.tab} ${activeTab === t.id ? styles.tabActive : ""}`}
            onClick={() => setActiveTab(t.id)}
            title={t.fullLabel}
          >
            {t.label}
          </button>
        ))}
        <button className={styles.refreshBtn} onClick={refresh} title="Refresh">
          &#8635;
        </button>
      </div>

      {/* Search + Add */}
      <div className={styles.searchRow}>
        <input
          className={styles.searchInput}
          placeholder={`Search ${TAB_LABELS.find(t => t.id === activeTab)?.fullLabel?.toLowerCase() || ""}...`}
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <button
          className={styles.addBtn}
          onClick={() => setAdding(!adding)}
          title={selectedIri ? `Add under ${selectedIri.split(/[#/]/).pop()}` : `Add ${activeTab}`}
        >
          +
        </button>
      </div>

      {/* Context hint when selected */}
      {adding && selectedIri && activeTab === "classes" && store.classes.some(c => c.iri === selectedIri) && (
        <div className={styles.contextHint}>
          Adding subclass of <strong>{store.classes.find(c => c.iri === selectedIri)?.label || selectedIri.split(/[#/]/).pop()}</strong>
        </div>
      )}

      {/* Inline add form */}
      {adding && (
        <div className={styles.addForm}>
          <input
            className={styles.addInput}
            placeholder={`New ${TAB_LABELS.find(t => t.id === activeTab)?.fullLabel?.replace(/ies$/, "y").replace(/s$/, "") || "entity"} label...`}
            value={newLabel}
            onChange={(e) => setNewLabel(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") handleAdd(); if (e.key === "Escape") { setAdding(false); setNewLabel(""); } }}
            autoFocus
          />
          <button className={styles.addConfirm} onClick={handleAdd} disabled={!newLabel.trim()}>Add</button>
          <button className={styles.addCancel} onClick={() => { setAdding(false); setNewLabel(""); }}>&#10005;</button>
        </div>
      )}

      {/* Tree */}
      <div className={styles.treeArea}>
        {loading ? (
          <div className={styles.empty}>Loading...</div>
        ) : filtered.length === 0 ? (
          <div className={styles.emptyState}>
            <div className={styles.emptyIcon}>{ activeTab === "classes" ? "\u25CB" : activeTab === "individuals" ? "\u25C7" : "\u2194" }</div>
            <p>No {TAB_LABELS.find(t => t.id === activeTab)?.fullLabel?.toLowerCase() || "entities"} yet</p>
            <button className={styles.emptyAdd} onClick={() => setAdding(true)}>
              + Add first one
            </button>
          </div>
        ) : (
          <ul className={styles.treeList}>
            {filtered.map((node) => (
              <TreeNodeItem
                key={node.iri}
                node={node}
                selectedIri={selectedIri}
                renamingIri={renamingIri}
                renameLabel={renameLabel}
                onSelect={handleSelect}
                onDelete={(iri) => setConfirmDelete(iri)}
                onMakeChild={handleMakeChild}
                onStartRename={(iri, label) => { setRenamingIri(iri); setRenameLabel(label); }}
                onRenameChange={setRenameLabel}
                onRenameCommit={handleRename}
                depth={0}
              />
            ))}
          </ul>
        )}
      </div>

      {/* Delete confirmation */}
      {confirmDelete && (
        <div className={styles.confirmOverlay}>
          <div className={styles.confirmDialog}>
            <p>Delete this entity? This will remove it from the graph and all its relationships.</p>
            <div className={styles.confirmActions}>
              <button className={styles.confirmDeleteBtn} onClick={() => handleDelete(confirmDelete)}>Delete</button>
              <button className={styles.confirmCancelBtn} onClick={() => setConfirmDelete(null)}>Cancel</button>
            </div>
          </div>
        </div>
      )}

      {/* Detail panel — scrollable with max height */}
      {selectedIri && (
        <div className={styles.detailPanel}>
          {detailLoading ? (
            <div className={styles.empty}>Loading details...</div>
          ) : detail ? (
            <div className={styles.detail}>
              <div className={styles.detailHeader}>
                <span className={styles.detailBadge}>{detail.entity_type}</span>
                <span className={styles.detailLabel}>{detail.label}</span>
                <button className={styles.detailEditBtn} onClick={() => { setRenamingIri(detail.iri); setRenameLabel(detail.label); }}
                        title="Rename">&#9998;</button>
                <button className={styles.detailDelete} onClick={() => setConfirmDelete(selectedIri)}
                        title="Delete entity">&#128465;</button>
              </div>
              <div className={styles.detailIri}>{detail.iri}</div>

              {detail.annotations.length > 0 && (
                <div className={styles.detailSection}>
                  <h4 className={styles.detailSectionTitle}>Annotations ({detail.annotations.length})</h4>
                  <div className={styles.annotationList}>
                    {detail.annotations.map((a, i) => (
                      <div key={i} className={styles.annotationRow}>
                        <span className={styles.annotProp}>{a.property_label}</span>
                        <span className={styles.annotVal}>
                          {a.value}
                          {a.language && <span className={styles.langTag}>@{a.language}</span>}
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {detail.axiom_count > 0 && (
                <div className={styles.detailSection}>
                  <span className={styles.detailMeta}>{detail.axiom_count} axiom(s)</span>
                </div>
              )}

              {detail.usages.length > 0 && (
                <div className={styles.detailSection}>
                  <h4 className={styles.detailSectionTitle}>Used by ({detail.usages.length})</h4>
                  <div className={styles.usageList}>
                    {detail.usages.slice(0, 20).map((u, i) => (
                      <div key={i} className={styles.usageRow}>
                        <span
                          className={styles.usageLink}
                          onClick={() => handleSelect({ iri: u.subject_iri, label: u.subject_label, entity_type: "class", children: [], annotation_count: 0 })}
                        >
                          {u.subject_label}
                        </span>
                        <span className={styles.usagePred}>{u.predicate}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

function TreeNodeItem({
  node, selectedIri, renamingIri, renameLabel,
  onSelect, onDelete, onMakeChild, onStartRename, onRenameChange, onRenameCommit, depth,
}: {
  node: TreeNode; selectedIri: string | null;
  renamingIri: string | null; renameLabel: string;
  onSelect: (n: TreeNode) => void;
  onDelete: (iri: string) => void;
  onMakeChild: (child: string, parent: string) => void;
  onStartRename: (iri: string, label: string) => void;
  onRenameChange: (label: string) => void;
  onRenameCommit: (iri: string, label: string) => void;
  depth: number;
}) {
  const [expanded, setExpanded] = useState(depth < 2);
  const [dragOver, setDragOver] = useState(false);
  const hasChildren = node.children.length > 0;
  const isRenaming = renamingIri === node.iri;

  return (
    <li>
      <div
        className={`${styles.nodeRow} ${selectedIri === node.iri ? styles.nodeSelected : ""} ${dragOver ? styles.nodeDragOver : ""}`}
        style={{ paddingLeft: `${8 + depth * 14}px` }}
        onClick={() => !isRenaming && onSelect(node)}
        draggable={!isRenaming}
        onDragStart={(e) => {
          e.dataTransfer.setData("text/plain", node.iri);
          e.dataTransfer.effectAllowed = "move";
        }}
        onDragOver={(e) => { e.preventDefault(); e.dataTransfer.dropEffect = "move"; setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          const childIri = e.dataTransfer.getData("text/plain");
          if (childIri && childIri !== node.iri) {
            onMakeChild(childIri, node.iri);
          }
        }}
      >
        {hasChildren ? (
          <button
            className={styles.expandBtn}
            onClick={(e) => { e.stopPropagation(); setExpanded(!expanded); }}
          >
            {expanded ? "\u25BE" : "\u25B8"}
          </button>
        ) : (
          <span className={styles.expandSpacer} />
        )}

        {isRenaming ? (
          <input
            className={styles.renameInput}
            value={renameLabel}
            onChange={(e) => onRenameChange(e.target.value)}
            onBlur={() => onRenameCommit(node.iri, renameLabel)}
            onKeyDown={(e) => {
              if (e.key === "Enter") onRenameCommit(node.iri, renameLabel);
              if (e.key === "Escape") onRenameCommit(node.iri, node.label);
            }}
            onClick={(e) => e.stopPropagation()}
            autoFocus
          />
        ) : (
          <span
            className={styles.nodeLabel}
            onDoubleClick={(e) => { e.stopPropagation(); onStartRename(node.iri, node.label); }}
            title="Double-click to rename"
          >
            {node.label}
          </span>
        )}

        {!isRenaming && node.annotation_count > 0 && (
          <span className={styles.annotBadge}>{node.annotation_count}</span>
        )}
        {!isRenaming && (
          <button
            className={styles.nodeDeleteBtn}
            onClick={(e) => { e.stopPropagation(); onDelete(node.iri); }}
            title="Delete"
          >
            &#10005;
          </button>
        )}
      </div>
      {hasChildren && expanded && (
        <ul className={styles.treeList}>
          {node.children.map((child) => (
            <TreeNodeItem
              key={child.iri}
              node={child}
              selectedIri={selectedIri}
              renamingIri={renamingIri}
              renameLabel={renameLabel}
              onSelect={onSelect}
              onDelete={onDelete}
              onMakeChild={onMakeChild}
              onStartRename={onStartRename}
              onRenameChange={onRenameChange}
              onRenameCommit={onRenameCommit}
              depth={depth + 1}
            />
          ))}
        </ul>
      )}
    </li>
  );
}

function filterTree(nodes: TreeNode[], query: string): TreeNode[] {
  return nodes
    .map((n) => {
      const childMatches = filterTree(n.children, query);
      if (n.label.toLowerCase().includes(query) || childMatches.length > 0) {
        return { ...n, children: childMatches };
      }
      return null;
    })
    .filter(Boolean) as TreeNode[];
}
