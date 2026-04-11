import { useState } from "react";
import { useTreeData, useEntityDetail, TreeNode, TreeTab } from "../../hooks/useTreeData";
import styles from "./TreeBrowser.module.css";

interface Props {
  boardId: string;
  onSelectEntity?: (entity: { iri: string; type: string; label: string } | null) => void;
}

const TAB_LABELS: { id: TreeTab; label: string }[] = [
  { id: "classes", label: "C" },
  { id: "object-properties", label: "OP" },
  { id: "data-properties", label: "DP" },
  { id: "annotation-properties", label: "AP" },
  { id: "individuals", label: "Ind" },
];

export default function TreeBrowser({ boardId, onSelectEntity }: Props) {
  const { tree, activeTab, setActiveTab, loading, search, setSearch, refresh } = useTreeData(boardId);
  const [selectedIri, setSelectedIri] = useState<string | null>(null);
  const { detail, loading: detailLoading } = useEntityDetail(boardId, selectedIri || undefined);

  const handleSelect = (node: TreeNode) => {
    setSelectedIri(node.iri);
    onSelectEntity?.({ iri: node.iri, type: node.entity_type, label: node.label });
  };

  const filtered = search
    ? filterTree(tree, search.toLowerCase())
    : tree;

  return (
    <div className={styles.container}>
      {/* Tabs */}
      <div className={styles.tabs}>
        {TAB_LABELS.map((t) => (
          <button
            key={t.id}
            className={`${styles.tab} ${activeTab === t.id ? styles.tabActive : ""}`}
            onClick={() => setActiveTab(t.id)}
            title={t.id.replace("-", " ")}
          >
            {t.label}
          </button>
        ))}
        <button className={styles.refreshBtn} onClick={refresh} title="Refresh">
          &#8635;
        </button>
      </div>

      {/* Search */}
      <div className={styles.searchWrap}>
        <input
          className={styles.searchInput}
          placeholder="Filter..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </div>

      {/* Tree */}
      <div className={styles.treeArea}>
        {loading ? (
          <div className={styles.empty}>Loading...</div>
        ) : filtered.length === 0 ? (
          <div className={styles.empty}>No entities found</div>
        ) : (
          <ul className={styles.treeList}>
            {filtered.map((node) => (
              <TreeNodeItem
                key={node.iri}
                node={node}
                selectedIri={selectedIri}
                onSelect={handleSelect}
                depth={0}
              />
            ))}
          </ul>
        )}
      </div>

      {/* Detail panel */}
      {selectedIri && (
        <div className={styles.detailPanel}>
          {detailLoading ? (
            <div className={styles.empty}>Loading details...</div>
          ) : detail ? (
            <div className={styles.detail}>
              <div className={styles.detailHeader}>
                <span className={styles.detailBadge}>{detail.entity_type}</span>
                <span className={styles.detailLabel}>{detail.label}</span>
              </div>
              <div className={styles.detailIri}>{detail.iri}</div>

              {detail.annotations.length > 0 && (
                <div className={styles.detailSection}>
                  <h4 className={styles.detailSectionTitle}>Annotations</h4>
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
              )}

              {detail.axiom_count > 0 && (
                <div className={styles.detailSection}>
                  <span className={styles.detailMeta}>{detail.axiom_count} axiom(s)</span>
                </div>
              )}

              {detail.usages.length > 0 && (
                <div className={styles.detailSection}>
                  <h4 className={styles.detailSectionTitle}>Used by ({detail.usages.length})</h4>
                  {detail.usages.slice(0, 10).map((u, i) => (
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
              )}
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
}

function TreeNodeItem({
  node, selectedIri, onSelect, depth,
}: {
  node: TreeNode; selectedIri: string | null; onSelect: (n: TreeNode) => void; depth: number;
}) {
  const [expanded, setExpanded] = useState(depth < 2);
  const hasChildren = node.children.length > 0;

  return (
    <li>
      <div
        className={`${styles.nodeRow} ${selectedIri === node.iri ? styles.nodeSelected : ""}`}
        style={{ paddingLeft: `${8 + depth * 14}px` }}
        onClick={() => onSelect(node)}
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
        <span className={styles.nodeLabel}>{node.label}</span>
        {node.annotation_count > 0 && (
          <span className={styles.annotBadge}>{node.annotation_count}</span>
        )}
      </div>
      {hasChildren && expanded && (
        <ul className={styles.treeList}>
          {node.children.map((child) => (
            <TreeNodeItem
              key={child.iri}
              node={child}
              selectedIri={selectedIri}
              onSelect={onSelect}
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
