/**
 * Monaco language definition for Manchester OWL Syntax.
 */
import type { languages } from "monaco-editor";

export const MANCHESTER_LANG_ID = "manchester-owl";

export const languageDef: languages.IMonarchLanguage = {
  keywords: [
    "Class", "ObjectProperty", "DataProperty", "AnnotationProperty",
    "Individual", "Datatype",
    "SubClassOf", "EquivalentTo", "DisjointWith", "DisjointUnionOf",
    "Domain", "Range", "SubPropertyOf", "InverseOf", "EquivalentProperty",
    "Characteristics", "Types", "Facts", "SameAs", "DifferentFrom",
    "Annotations",
  ],
  quantifiers: [
    "some", "only", "value", "min", "max", "exactly", "Self",
    "and", "or", "not", "that",
  ],
  builtins: [
    "owl:Thing", "owl:Nothing", "owl:topObjectProperty",
    "owl:bottomObjectProperty", "owl:topDataProperty", "owl:bottomDataProperty",
    "rdfs:label", "rdfs:comment", "rdfs:subClassOf",
    "xsd:string", "xsd:integer", "xsd:float", "xsd:boolean",
    "xsd:dateTime", "xsd:decimal",
  ],

  tokenizer: {
    root: [
      // Comments
      [/#.*$/, "comment"],

      // String literals
      [/"[^"]*"/, "string"],

      // Language tags
      [/@[a-zA-Z-]+/, "string.escape"],

      // IRIs in angle brackets
      [/<[^>]+>/, "type.identifier"],

      // Prefixed names (prefix:localName)
      [/[a-zA-Z_][\w-]*:[a-zA-Z_][\w-]*/, "type.identifier"],

      // Keywords (followed by colon)
      [/[A-Z][a-zA-Z]*(?=\s*:)/, {
        cases: {
          "@keywords": "keyword",
          "@default": "identifier",
        },
      }],

      // Quantifiers
      [/\b(some|only|value|min|max|exactly|Self|and|or|not|that)\b/, "keyword.control"],

      // Numbers
      [/\d+/, "number"],

      // Identifiers (class/property names)
      [/[a-zA-Z_][\w-]*/, {
        cases: {
          "@builtins": "support.type",
          "@default": "identifier",
        },
      }],

      // Whitespace
      [/\s+/, "white"],
    ],
  },
};

export const themeRules: { token: string; foreground: string; fontStyle?: string }[] = [
  { token: "keyword", foreground: "a29bfe", fontStyle: "bold" },
  { token: "keyword.control", foreground: "6c5ce7", fontStyle: "bold" },
  { token: "type.identifier", foreground: "74b9ff" },
  { token: "support.type", foreground: "00cec9" },
  { token: "string", foreground: "55efc4" },
  { token: "string.escape", foreground: "81ecec" },
  { token: "comment", foreground: "636e72", fontStyle: "italic" },
  { token: "number", foreground: "fdcb6e" },
  { token: "identifier", foreground: "dfe6e9" },
];

export const completionItems = [
  // Keywords
  "Class:", "ObjectProperty:", "DataProperty:", "Individual:",
  "SubClassOf:", "EquivalentTo:", "DisjointWith:",
  "Domain:", "Range:", "SubPropertyOf:", "InverseOf:",
  "Annotations:", "Types:", "Facts:", "Characteristics:",
  // Quantifiers
  "some", "only", "value", "min", "max", "exactly", "Self",
  "and", "or", "not", "that",
  // Built-ins
  "owl:Thing", "owl:Nothing",
  "rdfs:label", "rdfs:comment",
  "xsd:string", "xsd:integer", "xsd:float", "xsd:boolean",
];
