/**
 * PropertyCharacteristics — checkboxes for OWL property characteristics
 * and a property chain editor.
 *
 * Only rendered when the selected entity is an object property.
 * Uses the /api/characteristics endpoints for GET/PUT characteristics
 * and POST property-chain.
 */

import { useState, useEffect, useCallback, useRef } from "react";
import { apiJson } from "../../api";
import styles from "./PropertyCharacteristics.module.css";

interface Props {
  boardId: string;
  entityIri: string;
  entityLabel?: string;
  /** List of all property names for autocomplete in chains */
  propertyNames?: { iri: string; label: string }[];
}

interface CharacteristicsState {
  functional: boolean;
  inverse_functional: boolean;
  transitive: boolean;
  symmetric: boolean;
  asymmetric: boolean;
  reflexive: boolean;
  irreflexive: boolean;
}

const CHAR_LABELS: { key: keyof CharacteristicsState; label: string }[] = [
  { key: "functional", label: "Functional" },
  { key: "inverse_functional", label: "InverseFunctional" },
  { key: "transitive", label: "Transitive" },
  { key: "symmetric", label: "Symmetric" },
  { key: "asymmetric", label: "Asymmetric" },
  { key: "reflexive", label: "Reflexive" },
  { key: "irreflexive", label: "Irreflexive" },
];

const DEFAULT_CHARS: CharacteristicsState = {
  functional: false,
  inverse_functional: false,
  transitive: false,
  symmetric: false,
  asymmetric: false,
  reflexive: false,
  irreflexive: false,
};

export default function PropertyCharacteristics({ boardId, entityIri, entityLabel, propertyNames }: Props) {
  const [chars, setChars] = useState<CharacteristicsState>(DEFAULT_CHARS);
  const [loading, setLoading] = useState(true);
  const [savingChar, setSavingChar] = useState(false);
  const [charMsg, setCharMsg] = useState<{ ok: boolean; text: string } | null>(null);

  // Property chain state
  const [chainMembers, setChainMembers] = useState<string[]>([]);
  const [chainInput, setChainInput] = useState("");
  const [chainSuggestions, setChainSuggestions] = useState<{ iri: string; label: string }[]>([]);
  const [savingChain, setSavingChain] = useState(false);
  const [chainMsg, setChainMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const chainInputRef = useRef<HTMLInputElement>(null);

  // Fetch characteristics on mount / entity change
  useEffect(() => {
    setLoading(true);
    setCharMsg(null);
    setChainMsg(null);
    const encoded = encodeURIComponent(entityIri);
    Promise.all([
      apiJson<CharacteristicsState>(`/api/characteristics/${boardId}/entity/${encoded}`)
        .then(setChars)
        .catch(() => setChars(DEFAULT_CHARS)),
      apiJson<{ chains: string[][] }>(`/api/characteristics/${boardId}/chains/${encoded}`)
        .then((data) => {
          // Use the first chain if available
          if (data.chains && data.chains.length > 0) {
            setChainMembers(data.chains[0]);
          } else {
            setChainMembers([]);
          }
        })
        .catch(() => setChainMembers([])),
    ]).finally(() => setLoading(false));
  }, [boardId, entityIri]);

  // Toggle a characteristic
  const toggleChar = useCallback(async (key: keyof CharacteristicsState) => {
    const newValue = !chars[key];
    // Optimistic update
    setChars((prev) => ({ ...prev, [key]: newValue }));
    setCharMsg(null);
    setSavingChar(true);
    try {
      const encoded = encodeURIComponent(entityIri);
      const result = await apiJson<CharacteristicsState>(
        `/api/characteristics/${boardId}/entity/${encoded}`,
        { method: "PUT", body: JSON.stringify({ [key]: newValue }) },
      );
      setChars(result);
      setCharMsg({ ok: true, text: `${CHAR_LABELS.find((c) => c.key === key)?.label} ${newValue ? "set" : "unset"}` });
    } catch (e: any) {
      // Revert optimistic update
      setChars((prev) => ({ ...prev, [key]: !newValue }));
      setCharMsg({ ok: false, text: e.message || "Failed to update" });
    } finally {
      setSavingChar(false);
    }
  }, [boardId, entityIri, chars]);

  // Chain autocomplete
  const updateChainSuggestions = useCallback((input: string) => {
    if (!input.trim() || !propertyNames) {
      setChainSuggestions([]);
      return;
    }
    const lower = input.toLowerCase();
    setChainSuggestions(
      propertyNames
        .filter((p) => p.label.toLowerCase().includes(lower) || p.iri.toLowerCase().includes(lower))
        .filter((p) => !chainMembers.includes(p.iri))
        .slice(0, 8)
    );
  }, [propertyNames, chainMembers]);

  const addToChain = useCallback((iri: string) => {
    setChainMembers((prev) => [...prev, iri]);
    setChainInput("");
    setChainSuggestions([]);
    chainInputRef.current?.focus();
  }, []);

  const removeFromChain = useCallback((index: number) => {
    setChainMembers((prev) => prev.filter((_, i) => i !== index));
  }, []);

  const moveChainMember = useCallback((index: number, direction: -1 | 1) => {
    setChainMembers((prev) => {
      const next = [...prev];
      const target = index + direction;
      if (target < 0 || target >= next.length) return prev;
      [next[index], next[target]] = [next[target], next[index]];
      return next;
    });
  }, []);

  const saveChain = useCallback(async () => {
    if (chainMembers.length < 2) {
      setChainMsg({ ok: false, text: "A chain needs at least 2 properties" });
      return;
    }
    setSavingChain(true);
    setChainMsg(null);
    try {
      await apiJson(`/api/characteristics/${boardId}/property-chain`, {
        method: "POST",
        body: JSON.stringify({
          super_property: entityIri,
          chain_properties: chainMembers,
        }),
      });
      setChainMsg({ ok: true, text: "Property chain saved" });
    } catch (e: any) {
      setChainMsg({ ok: false, text: e.message || "Failed to save chain" });
    } finally {
      setSavingChain(false);
    }
  }, [boardId, entityIri, chainMembers]);

  // Resolve IRI to label
  const iriToLabel = useCallback((iri: string) => {
    if (!propertyNames) {
      // Fallback: extract local name
      return iri.includes("#") ? iri.split("#").pop() : iri.split("/").pop();
    }
    const match = propertyNames.find((p) => p.iri === iri);
    return match ? match.label : (iri.includes("#") ? iri.split("#").pop() : iri.split("/").pop());
  }, [propertyNames]);

  if (loading) {
    return <div className={styles.loading}>Loading characteristics...</div>;
  }

  return (
    <div className={styles.container}>
      {/* Characteristics checkboxes */}
      <div className={styles.section}>
        <div className={styles.sectionTitle}>Property Characteristics</div>
        <div className={styles.checkboxGrid}>
          {CHAR_LABELS.map(({ key, label }) => (
            <label key={key} className={styles.checkboxLabel}>
              <input
                type="checkbox"
                checked={chars[key]}
                onChange={() => toggleChar(key)}
                disabled={savingChar}
              />
              <span className={styles.checkboxName}>{label}</span>
            </label>
          ))}
        </div>
        {charMsg && (
          <div className={charMsg.ok ? styles.successMsg : styles.errorMsg}>
            {charMsg.text}
          </div>
        )}
      </div>

      {/* Property chain editor */}
      <div className={styles.section}>
        <div className={styles.sectionTitle}>
          Property Chain ({entityLabel || iriToLabel(entityIri)})
        </div>

        {chainMembers.length === 0 ? (
          <div className={styles.emptyChain}>No chain defined. Add at least 2 properties.</div>
        ) : (
          <div className={styles.chainList}>
            {chainMembers.map((iri, i) => (
              <div key={`${iri}-${i}`}>
                <div className={styles.chainRow}>
                  <span className={styles.chainIndex}>{i + 1}</span>
                  <span className={styles.chainPropName} title={iri}>
                    {iriToLabel(iri)}
                  </span>
                  <div className={styles.chainBtnGroup}>
                    <button
                      className={styles.chainSmallBtn}
                      onClick={() => moveChainMember(i, -1)}
                      disabled={i === 0}
                      title="Move up"
                    >&#9650;</button>
                    <button
                      className={styles.chainSmallBtn}
                      onClick={() => moveChainMember(i, 1)}
                      disabled={i === chainMembers.length - 1}
                      title="Move down"
                    >&#9660;</button>
                    <button
                      className={styles.chainRemoveBtn}
                      onClick={() => removeFromChain(i)}
                      title="Remove"
                    >&times;</button>
                  </div>
                </div>
                {i < chainMembers.length - 1 && (
                  <div className={styles.chainCompose}>&#9675;</div>
                )}
              </div>
            ))}
          </div>
        )}

        <div className={styles.addChainRow}>
          <input
            ref={chainInputRef}
            className={styles.addChainInput}
            value={chainInput}
            onChange={(e) => {
              setChainInput(e.target.value);
              updateChainSuggestions(e.target.value);
            }}
            onKeyDown={(e) => {
              if (e.key === "Escape") {
                setChainInput("");
                setChainSuggestions([]);
              }
            }}
            placeholder="Search property to add..."
          />
          {chainSuggestions.length > 0 && (
            <div className={styles.autocomplete}>
              {chainSuggestions.map((s) => (
                <button
                  key={s.iri}
                  className={styles.autocompleteItem}
                  onClick={() => addToChain(s.iri)}
                >
                  {s.label}
                </button>
              ))}
            </div>
          )}
        </div>

        <button
          className={styles.saveChainBtn}
          onClick={saveChain}
          disabled={savingChain || chainMembers.length < 2}
        >
          {savingChain ? "Saving..." : "Save Chain"}
        </button>

        {chainMsg && (
          <div className={chainMsg.ok ? styles.successMsg : styles.errorMsg}>
            {chainMsg.text}
          </div>
        )}
      </div>
    </div>
  );
}
