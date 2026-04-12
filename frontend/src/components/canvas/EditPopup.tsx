/**
 * EditPopup — floating editor for nodes and edges on the canvas.
 * Positioned absolutely at the element's rendered position.
 */

import { useState, useEffect, useRef } from "react";
import styles from "./EditPopup.module.css";

const SHAPES = [
  { id: "roundrectangle", icon: "\u25AD", label: "Rectangle" },
  { id: "ellipse", icon: "\u25CB", label: "Ellipse" },
  { id: "diamond", icon: "\u25C7", label: "Diamond" },
  { id: "hexagon", icon: "\u2B21", label: "Hexagon" },
  { id: "barrel", icon: "\u2395", label: "Barrel" },
  { id: "star", icon: "\u2606", label: "Star" },
] as const;

export interface NodeEditData {
  type: "node";
  id: string;
  label: string;
  entityType: "class" | "individual";
  shape: string;
  color: string;
  fontSize: number;
  screenX: number;
  screenY: number;
}

export interface EdgeEditData {
  type: "edge";
  id: string;
  label: string;
  edgeType: string;
  color: string;
  lineStyle: "solid" | "dashed" | "dotted";
  screenX: number;
  screenY: number;
}

export type EditData = NodeEditData | EdgeEditData;

interface Props {
  data: EditData;
  onSave: (data: EditData) => void;
  onDelete: (id: string, type: "node" | "edge") => void;
  onCancel: () => void;
}

export default function EditPopup({ data, onSave, onDelete, onCancel }: Props) {
  const [local, setLocal] = useState<EditData>(data);
  const cardRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => { inputRef.current?.focus(); inputRef.current?.select(); }, []);

  // Close on Escape
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === "Escape") onCancel(); };
    document.addEventListener("keydown", handler);
    return () => document.removeEventListener("keydown", handler);
  }, [onCancel]);

  // Close on click outside
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (cardRef.current && !cardRef.current.contains(e.target as Node)) onCancel();
    };
    // delay to avoid closing immediately from the double-click that opened it
    const timer = setTimeout(() => document.addEventListener("mousedown", handler), 100);
    return () => { clearTimeout(timer); document.removeEventListener("mousedown", handler); };
  }, [onCancel]);

  const update = (patch: Partial<EditData>) => setLocal((prev) => ({ ...prev, ...patch } as EditData));

  const handleSave = () => onSave(local);

  return (
    <div className={styles.overlay} style={{ left: data.screenX, top: data.screenY }}>
      <div className={styles.card} ref={cardRef}>
        {/* Label */}
        <div className={styles.row}>
          <span className={styles.label}>Label</span>
          <input ref={inputRef} className={styles.input} value={local.label}
                 onChange={(e) => update({ label: e.target.value })}
                 onKeyDown={(e) => { if (e.key === "Enter") handleSave(); }} />
        </div>

        {local.type === "node" ? (
          <>
            {/* Entity type */}
            <div className={styles.row}>
              <span className={styles.label}>Type</span>
              <select className={styles.select} value={(local as NodeEditData).entityType}
                      onChange={(e) => update({ entityType: e.target.value as "class" | "individual" })}>
                <option value="class">Class</option>
                <option value="individual">Individual</option>
              </select>
            </div>

            {/* Color */}
            <div className={styles.row}>
              <span className={styles.label}>Color</span>
              <input type="color" className={styles.colorPick} value={(local as NodeEditData).color}
                     onChange={(e) => update({ color: e.target.value })} />
            </div>

            {/* Shape */}
            <div className={styles.row}>
              <span className={styles.label}>Shape</span>
              <div className={styles.shapeGrid}>
                {SHAPES.map((s) => (
                  <button key={s.id} title={s.label}
                          className={`${styles.shapeBtn} ${(local as NodeEditData).shape === s.id ? styles.shapeBtnActive : ""}`}
                          onClick={() => update({ shape: s.id })}>
                    {s.icon}
                  </button>
                ))}
              </div>
            </div>

            {/* Font size */}
            <div className={styles.row}>
              <span className={styles.label}>Font</span>
              <div className={styles.fontRow}>
                <input type="range" className={styles.fontSlider} min={8} max={24} step={1}
                       value={(local as NodeEditData).fontSize}
                       onChange={(e) => update({ fontSize: Number(e.target.value) })} />
                <span className={styles.fontValue}>{(local as NodeEditData).fontSize}px</span>
              </div>
            </div>
          </>
        ) : (
          <>
            {/* Edge type */}
            <div className={styles.row}>
              <span className={styles.label}>Type</span>
              <select className={styles.select} value={(local as EdgeEditData).edgeType}
                      onChange={(e) => update({ edgeType: e.target.value })}>
                <option value="objectProperty">Object Property</option>
                <option value="subClassOf">SubClassOf</option>
                <option value="dataProperty">Data Property</option>
                <option value="annotationProperty">Annotation</option>
              </select>
            </div>

            {/* Color */}
            <div className={styles.row}>
              <span className={styles.label}>Color</span>
              <input type="color" className={styles.colorPick} value={(local as EdgeEditData).color}
                     onChange={(e) => update({ color: e.target.value })} />
            </div>

            {/* Line style */}
            <div className={styles.row}>
              <span className={styles.label}>Style</span>
              <div className={styles.lineStyleGrid}>
                {(["solid", "dashed", "dotted"] as const).map((ls) => (
                  <button key={ls}
                          className={`${styles.lineBtn} ${(local as EdgeEditData).lineStyle === ls ? styles.lineBtnActive : ""}`}
                          onClick={() => update({ lineStyle: ls })}>
                    {ls}
                  </button>
                ))}
              </div>
            </div>
          </>
        )}

        {/* Actions */}
        <div className={styles.actions}>
          <button className={styles.deleteBtn} onClick={() => onDelete(local.id, local.type)}>Delete</button>
          <button className={styles.cancelBtn} onClick={onCancel}>Cancel</button>
          <button className={styles.saveBtn} onClick={handleSave}>Save</button>
        </div>
      </div>
    </div>
  );
}
