import { useState } from "react";
import { useRestrictions } from "../../hooks/useRestrictions";
import styles from "./RestrictionBuilder.module.css";

interface Props {
  boardId: string;
  entityIri: string | undefined;
  entityLabel?: string;
}

const RESTRICTION_TYPES = [
  { value: "someValuesFrom", label: "some (existential)" },
  { value: "allValuesFrom", label: "only (universal)" },
  { value: "hasValue", label: "value (exact)" },
  { value: "minCardinality", label: "min cardinality" },
  { value: "maxCardinality", label: "max cardinality" },
  { value: "exactCardinality", label: "exactly cardinality" },
];

export default function RestrictionBuilder({ boardId, entityIri, entityLabel }: Props) {
  const { restrictions, loading, addRestriction, removeRestriction } = useRestrictions(boardId, entityIri);
  const [showForm, setShowForm] = useState(false);
  const [rType, setRType] = useState("someValuesFrom");
  const [onProp, setOnProp] = useState("");
  const [filler, setFiller] = useState("");
  const [cardinality, setCardinality] = useState(1);

  if (!entityIri) {
    return <div className={styles.empty}>Select an entity to manage its restrictions.</div>;
  }

  const handleAdd = async () => {
    const body: any = { restriction_type: rType, on_property: onProp };
    if (rType === "someValuesFrom" || rType === "allValuesFrom" || rType === "hasValue") {
      body.filler = filler;
    }
    if (rType.includes("Cardinality")) {
      body.cardinality = cardinality;
      if (filler) body.qualified_class = filler;
    }
    await addRestriction(body);
    setShowForm(false);
    setOnProp("");
    setFiller("");
  };

  return (
    <div className={styles.container}>
      <div className={styles.header}>
        <span className={styles.title}>Restrictions on {entityLabel || entityIri}</span>
        <button className={styles.addBtn} onClick={() => setShowForm(!showForm)}>
          {showForm ? "Cancel" : "+ Add"}
        </button>
      </div>

      {showForm && (
        <div className={styles.form}>
          <select className={styles.select} value={rType} onChange={(e) => setRType(e.target.value)}>
            {RESTRICTION_TYPES.map((t) => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
          <input className={styles.input} placeholder="Property IRI" value={onProp}
                 onChange={(e) => setOnProp(e.target.value)} />
          <input className={styles.input}
                 placeholder={rType.includes("Cardinality") ? "Qualified class IRI (optional)" : "Filler class/value IRI"}
                 value={filler} onChange={(e) => setFiller(e.target.value)} />
          {rType.includes("Cardinality") && (
            <input className={styles.input} type="number" min="0" value={cardinality}
                   onChange={(e) => setCardinality(parseInt(e.target.value) || 0)} />
          )}
          <button className={styles.submitBtn} onClick={handleAdd}>Add Restriction</button>
        </div>
      )}

      {loading ? (
        <div className={styles.empty}>Loading...</div>
      ) : restrictions.length === 0 ? (
        <div className={styles.empty}>No restrictions defined.</div>
      ) : (
        <div className={styles.list}>
          {restrictions.map((r, i) => (
            <div key={i} className={styles.row}>
              <span className={styles.badge}>{r.restriction_type}</span>
              <span className={styles.manchester}>{r.manchester}</span>
              <button className={styles.removeBtn}
                      onClick={() => removeRestriction({
                        restriction_type: r.restriction_type,
                        on_property: r.on_property,
                        filler: r.filler,
                        cardinality: r.cardinality,
                      })}
                      title="Remove">&times;</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
