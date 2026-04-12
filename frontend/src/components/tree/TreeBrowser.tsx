import { useState, useCallback } from "react";
import { useTreeData, useEntityDetail, TreeNode, TreeTab } from "../../hooks/useTreeData";
import { useOntologyStore } from "../../store/ontologyStore";
import styles from "./TreeBrowser.module.css";

interface Props {
  boardId: string;
}

const TAB_LABELS: { id: TreeTab | "ind-by-class"; label: string; fullLabel: string }[] = [
  { id: "classes", label: "C", fullLabel: "Classes" },
  { id: "object-properties", label: "OP", fullLabel: "Object Properties" },
  { id: "data-properties", label: "DP", fullLabel: "Data Properties" },
  { id: "annotation-properties", label: "AP", fullLabel: "Annotation Properties" },
  { id: "individuals", label: "Ind", fullLabel: "Individuals" },
  { id: "ind-by-class", label: "I/C", fullLabel: "Individuals by Class" },
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
  const [virtualTab, setVirtualTab] = useState<"ind-by-class" | null>(null);
  const store = useOntologyStore();

  // Build individuals-by-class grouping
  const indByClass = virtualTab === "ind-by-class" ? (() => {
    const groups: Record<string, { classLabel: string; individuals: { iri: string; label: string }[] }> = {};
    const ungrouped: { iri: string; label: string }[] = [];
    for (const ind of store.individuals) {
      if (ind.class_iri) {
        if (!groups[ind.class_iri]) {
          const cls = store.classes.find((c) => c.iri === ind.class_iri);
          groups[ind.class_iri] = { classLabel: cls?.label || ind.class_iri.split(/[#/]/).pop() || ind.class_iri, individuals: [] };
        }
        groups[ind.class_iri].individuals.push({ iri: ind.iri, label: ind.label });
      } else {
        ungrouped.push({ iri: ind.iri, label: ind.label });
      }
    }
    return { groups, ungrouped };
  })() : null;

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
  }, [newLabel, activeTab, store, selectedIri]);

  // ── Delete entity ─────────────────────────────────────────
  const handleDelete = useCallback((iri: string) => {
    store.removeClass(iri);
    store.setIndividuals(store.individuals.filter(i => i.iri !== iri));
    if (selectedIri === iri) {
      store.selectEntity(null);
    }
    setConfirmDelete(null);
  }, [store, selectedIri]);

  // ── Drag-drop: make child (SubClassOf) ────────────────────
  const handleMakeChild = useCallback((childIri: string, parentIri: string) => {
    if (childIri === parentIri) return;
    store.addSubClassOf(childIri, parentIri);
  }, [store]);

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
  }, [store, selectedIri]);

  const filtered = search ? filterTree(tree, search.toLowerCase()) : tree;

  return (
    <div className={styles.container}>
      {/* Tabs — scrollable */}
      <div className={styles.tabs}>
        {TAB_LABELS.map((t) => (
          <button
            key={t.id}
            className={`${styles.tab} ${(t.id === "ind-by-class" ? virtualTab === "ind-by-class" : activeTab === t.id && !virtualTab) ? styles.tabActive : ""}`}
            onClick={() => {
              if (t.id === "ind-by-class") {
                setVirtualTab("ind-by-class");
              } else {
                setVirtualTab(null);
                setActiveTab(t.id as TreeTab);
              }
            }}
            title={t.fullLabel}
          >
            {t.label}
          </button>
        ))}
        <button className={styles.refreshBtn} onClick={refresh} title="Refresh">
          &#8635;
        </button>
      </div>

      {/* Quick stats bar */}
      <div className={styles.statsBar}>
        <span title="Classes">{store.classes.length}C</span>
        <span title="Object Properties">{store.properties.filter(p => p.property_type === "object").length}OP</span>
        <span title="Individuals">{store.individuals.length}I</span>
        <span title="Literals">{store.literals.length}L</span>
      </div>

      {/* Show Inferences toggle */}
      <label className={styles.inferenceToggle}>
        <input
          type="checkbox"
          checked={store.showInferences}
          onChange={(e) => store.setShowInferences(e.target.checked)}
        />
        <span>Show Inferences</span>
        {store.inferences.length > 0 && (
          <span className={styles.inferenceCount}>{store.inferences.length}</span>
        )}
      </label>

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

      {/* Individuals by Class view */}
      {virtualTab === "ind-by-class" && indByClass && (
        <div className={styles.treeArea}>
          {store.individuals.length === 0 ? (
            <div className={styles.emptyState}>
              <div className={styles.emptyIcon}>{"\u25C7"}</div>
              <p>No individuals yet</p>
            </div>
          ) : (
            <ul className={styles.treeList}>
              {Object.entries(indByClass.groups).map(([classIri, group]) => (
                <li key={classIri} className={styles.groupNode}>
                  <div
                    className={styles.groupHeader}
                    onClick={() => store.selectEntity({ iri: classIri, type: "class", label: group.classLabel })}
                  >
                    <span className={styles.groupIcon}>{"\u25A0"}</span>
                    <span className={styles.groupLabel}>{group.classLabel}</span>
                    <span className={styles.groupCount}>{group.individuals.length}</span>
                  </div>
                  <ul className={styles.groupChildren}>
                    {group.individuals.map((ind) => (
                      <li
                        key={ind.iri}
                        className={`${styles.nodeRow} ${selectedIri === ind.iri ? styles.nodeSelected : ""}`}
                        onClick={() => store.selectEntity({ iri: ind.iri, type: "individual", label: ind.label })}
                      >
                        <span className={styles.indIcon}>{"\u25C7"}</span>
                        <span className={styles.nodeLabel}>{ind.label}</span>
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
              {indByClass.ungrouped.length > 0 && (
                <li className={styles.groupNode}>
                  <div className={styles.groupHeader}>
                    <span className={styles.groupIcon}>{"\u25CB"}</span>
                    <span className={styles.groupLabel}>Untyped</span>
                    <span className={styles.groupCount}>{indByClass.ungrouped.length}</span>
                  </div>
                  <ul className={styles.groupChildren}>
                    {indByClass.ungrouped.map((ind) => (
                      <li
                        key={ind.iri}
                        className={`${styles.nodeRow} ${selectedIri === ind.iri ? styles.nodeSelected : ""}`}
                        onClick={() => store.selectEntity({ iri: ind.iri, type: "individual", label: ind.label })}
                      >
                        <span className={styles.indIcon}>{"\u25C7"}</span>
                        <span className={styles.nodeLabel}>{ind.label}</span>
                      </li>
                    ))}
                  </ul>
                </li>
              )}
            </ul>
          )}
        </div>
      )}

      {/* Tree */}
      {!virtualTab && <div className={styles.treeArea}>
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
      </div>}

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
              <div
                className={styles.detailIri}
                title="Click to edit IRI"
                onClick={() => {
                  const newIri = prompt("Edit IRI:", detail.iri);
                  if (newIri && newIri !== detail.iri) {
                    const cls = store.classes.find((c) => c.iri === detail.iri);
                    if (cls) {
                      store.removeClass(detail.iri);
                      store.addClass({ ...cls, id: newIri, iri: newIri });
                      store.setProperties(store.properties.map((p) => ({
                        ...p,
                        source_id: p.source_id === detail.iri ? newIri : p.source_id,
                        target_id: p.target_id === detail.iri ? newIri : p.target_id,
                      })));
                      store.selectEntity({ iri: newIri, type: detail.entity_type, label: detail.label });
                    }
                  }
                }}
                style={{ cursor: "pointer" }}
              >{detail.iri}</div>

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

              {/* Provenance info */}
              {(() => {
                const cls = store.classes.find((c) => c.iri === detail.iri);
                const ind = store.individuals.find((i) => i.iri === detail.iri);
                const entity = cls || ind;
                if (!entity?.created_by && !entity?.modified_by) return null;
                return (
                  <div className={styles.detailSection}>
                    <h4 className={styles.detailSectionTitle}>Provenance</h4>
                    <div className={styles.annotationList}>
                      {entity.created_by && (
                        <div className={styles.annotationRow}>
                          <span className={styles.annotProp}>Created by</span>
                          <span className={styles.annotVal}>{entity.created_by}</span>
                        </div>
                      )}
                      {entity.created_at && (
                        <div className={styles.annotationRow}>
                          <span className={styles.annotProp}>Created</span>
                          <span className={styles.annotVal}>{new Date(entity.created_at).toLocaleString()}</span>
                        </div>
                      )}
                      {entity.modified_by && entity.modified_by !== entity.created_by && (
                        <div className={styles.annotationRow}>
                          <span className={styles.annotProp}>Modified by</span>
                          <span className={styles.annotVal}>{entity.modified_by}</span>
                        </div>
                      )}
                      {entity.modified_at && entity.modified_at !== entity.created_at && (
                        <div className={styles.annotationRow}>
                          <span className={styles.annotProp}>Modified</span>
                          <span className={styles.annotVal}>{new Date(entity.modified_at).toLocaleString()}</span>
                        </div>
                      )}
                    </div>
                  </div>
                );
              })()}

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
