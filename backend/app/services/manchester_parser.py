"""Recursive-descent parser for OWL Manchester Syntax class expressions.

Converts textual Manchester Syntax (e.g. ``Animal and hasPart some Organ``)
into rdflib triples, and renders RDF class-expression BNodes back into
Manchester Syntax strings.

Operator precedence (tightest first):
    1. Parenthesised expressions, named classes, ObjectOneOf
    2. Restrictions: ``some``, ``only``, ``value``, ``min``, ``max``,
       ``exactly``, ``Self``
    3. Prefix ``not``
    4. Intersection ``and``
    5. Union ``or``

Grammar (EBNF)::

    union        ::= intersection ( 'or' intersection )*
    intersection ::= complement ( 'and' complement )*
    complement   ::= 'not' complement | primary
    primary      ::= '(' union ')' | '{' name_list '}' | restriction | named_class facets?
    restriction  ::= NAME restriction_kind | NAME 'Self'
    restriction_kind ::= 'some' complement
                       | 'only' complement
                       | 'value' NAME
                       | cardinality_kw INTEGER complement?
    cardinality_kw ::= 'min' | 'max' | 'exactly'
    facets       ::= '[' facet (',' facet)* ']'
    facet        ::= comp_op value | facet_name value
    name_list    ::= NAME ( ',' NAME )*
"""

from __future__ import annotations

import re
from typing import Union

from rdflib import Graph, URIRef, BNode, Literal, RDF, RDFS, OWL, XSD, Namespace
from rdflib.collection import Collection


# ---------------------------------------------------------------------------
# Well-known prefix map
# ---------------------------------------------------------------------------

_WELL_KNOWN: dict[str, Namespace | URIRef] = {
    "owl": OWL,
    "rdfs": RDFS,
    "rdf": RDF,
    "xsd": XSD,
}

_WELL_KNOWN_NAMES: dict[str, URIRef] = {
    "owl:Thing": OWL.Thing,
    "owl:Nothing": OWL.Nothing,
    "rdfs:Resource": RDFS.Resource,
    "rdfs:Literal": RDFS.Literal,
    "xsd:string": XSD.string,
    "xsd:integer": XSD.integer,
    "xsd:int": XSD.int,
    "xsd:float": XSD.float,
    "xsd:double": XSD.double,
    "xsd:boolean": XSD.boolean,
    "xsd:decimal": XSD.decimal,
    "xsd:dateTime": XSD.dateTime,
    "xsd:date": XSD.date,
    "xsd:nonNegativeInteger": XSD.nonNegativeInteger,
}

# Keywords that may NOT appear as class/property names.
_KEYWORDS = frozenset({
    "and", "or", "not", "some", "only", "value",
    "min", "max", "exactly", "self",
})

# ---------------------------------------------------------------------------
# Datatype facet support
# ---------------------------------------------------------------------------

XSD_NS = "http://www.w3.org/2001/XMLSchema#"

XSD_FACETS: dict[str, str] = {
    ">=": XSD_NS + "minInclusive",
    "<=": XSD_NS + "maxInclusive",
    ">": XSD_NS + "minExclusive",
    "<": XSD_NS + "maxExclusive",
    "minLength": XSD_NS + "minLength",
    "maxLength": XSD_NS + "maxLength",
    "pattern": XSD_NS + "pattern",
}

# Set of known XSD datatype local names for recognising datatype references.
_KNOWN_XSD_DATATYPES = frozenset({
    "string", "integer", "int", "float", "double", "boolean",
    "decimal", "dateTime", "date", "nonNegativeInteger",
    "long", "short", "byte", "unsignedInt", "unsignedLong",
    "unsignedShort", "unsignedByte", "positiveInteger",
    "negativeInteger", "nonPositiveInteger", "anyURI",
    "base64Binary", "hexBinary", "normalizedString", "token",
    "language", "duration", "gYear", "gMonth", "gDay",
    "gYearMonth", "gMonthDay", "time",
})

# ---------------------------------------------------------------------------
# Tokenizer
# ---------------------------------------------------------------------------

# Token types
_TT_NAME = "NAME"
_TT_INT = "INT"
_TT_LPAREN = "LPAREN"
_TT_RPAREN = "RPAREN"
_TT_LBRACE = "LBRACE"
_TT_RBRACE = "RBRACE"
_TT_LBRACKET = "LBRACKET"
_TT_RBRACKET = "RBRACKET"
_TT_COMMA = "COMMA"
_TT_GE = "GE"
_TT_LE = "LE"
_TT_GT = "GT"
_TT_LT = "LT"
_TT_AND = "AND"
_TT_OR = "OR"
_TT_NOT = "NOT"
_TT_SOME = "SOME"
_TT_ONLY = "ONLY"
_TT_VALUE = "VALUE"
_TT_MIN = "MIN"
_TT_MAX = "MAX"
_TT_EXACTLY = "EXACTLY"
_TT_SELF = "SELF"
_TT_EOF = "EOF"

# Maps keyword text to token type
_KW_MAP: dict[str, str] = {
    "and": _TT_AND,
    "or": _TT_OR,
    "not": _TT_NOT,
    "some": _TT_SOME,
    "only": _TT_ONLY,
    "value": _TT_VALUE,
    "min": _TT_MIN,
    "max": _TT_MAX,
    "exactly": _TT_EXACTLY,
    "self": _TT_SELF,
}


class _Token:
    """A single lexical token."""

    __slots__ = ("type", "value", "pos")

    def __init__(self, type_: str, value: str, pos: int) -> None:
        self.type = type_
        self.value = value
        self.pos = pos

    def __repr__(self) -> str:
        return f"Token({self.type}, {self.value!r}, pos={self.pos})"


# Regex that matches a single token.
_TOKEN_RE = re.compile(
    r"""
    (?P<ws>\s+)                         # whitespace (skip)
    | (?P<lparen>\()                    # left paren
    | (?P<rparen>\))                    # right paren
    | (?P<lbrace>\{)                    # left brace
    | (?P<rbrace>\})                    # right brace
    | (?P<lbracket>\[)                  # left bracket
    | (?P<rbracket>\])                  # right bracket
    | (?P<comma>,)                      # comma
    | (?P<ge>>=)                        # greater-or-equal
    | (?P<le><=)                        # less-or-equal
    | (?P<gt>>(?!=))                    # greater-than (not followed by =)
    | (?P<lt><(?!=)[^>]*)               # less-than (not an IRI, not <=)
    | (?P<iri><[^>]+>)                  # full IRI in angle brackets
    | (?P<integer>\d+)                  # integer literal
    | (?P<name>[A-Za-z_][\w.:-]*)       # name / compact IRI / keyword
    """,
    re.VERBOSE,
)

# Simpler approach: we tokenize < carefully to distinguish IRI vs operator
# by re-ordering alternatives and using a two-pass strategy for '<'.
# Actually, let's use a cleaner regex:
_TOKEN_RE = re.compile(
    r"""
    (?P<ws>\s+)                         # whitespace (skip)
    | (?P<lparen>\()                    # left paren
    | (?P<rparen>\))                    # right paren
    | (?P<lbrace>\{)                    # left brace
    | (?P<rbrace>\})                    # right brace
    | (?P<lbracket>\[)                  # left bracket
    | (?P<rbracket>\])                  # right bracket
    | (?P<comma>,)                      # comma
    | (?P<ge>>=)                        # greater-or-equal
    | (?P<le><=)                        # less-or-equal
    | (?P<iri><[^>]+>)                  # full IRI in angle brackets
    | (?P<gt>>)                         # greater-than
    | (?P<lt><)                         # less-than
    | (?P<integer>\d+)                  # integer literal
    | (?P<name>[A-Za-z_][\w.:-]*)       # name / compact IRI / keyword
    """,
    re.VERBOSE,
)


class _Tokenizer:
    """Lexer that converts a Manchester Syntax string into a stream of tokens."""

    def __init__(self, text: str) -> None:
        self._text = text
        self._tokens: list[_Token] = []
        self._pos = 0
        self._tokenize()

    # -- public interface ---------------------------------------------------

    def peek(self) -> _Token:
        """Return the current token without consuming it."""
        return self._tokens[self._pos]

    def advance(self) -> _Token:
        """Consume and return the current token."""
        tok = self._tokens[self._pos]
        if tok.type != _TT_EOF:
            self._pos += 1
        return tok

    def expect(self, ttype: str) -> _Token:
        """Consume the current token, raising if it is not *ttype*."""
        tok = self.advance()
        if tok.type != ttype:
            raise SyntaxError(
                f"Expected {ttype} at position {tok.pos}, "
                f"got {tok.type} ({tok.value!r})"
            )
        return tok

    def match(self, ttype: str) -> _Token | None:
        """Consume and return the current token if it matches *ttype*, else ``None``."""
        if self.peek().type == ttype:
            return self.advance()
        return None

    # -- private ------------------------------------------------------------

    def _tokenize(self) -> None:
        pos = 0
        text = self._text
        while pos < len(text):
            m = _TOKEN_RE.match(text, pos)
            if m is None:
                raise SyntaxError(
                    f"Unexpected character {text[pos]!r} at position {pos} "
                    f"in expression: {text!r}"
                )
            pos = m.end()

            if m.group("ws"):
                continue
            if m.group("lparen"):
                self._tokens.append(_Token(_TT_LPAREN, "(", m.start()))
            elif m.group("rparen"):
                self._tokens.append(_Token(_TT_RPAREN, ")", m.start()))
            elif m.group("lbrace"):
                self._tokens.append(_Token(_TT_LBRACE, "{", m.start()))
            elif m.group("rbrace"):
                self._tokens.append(_Token(_TT_RBRACE, "}", m.start()))
            elif m.group("lbracket"):
                self._tokens.append(_Token(_TT_LBRACKET, "[", m.start()))
            elif m.group("rbracket"):
                self._tokens.append(_Token(_TT_RBRACKET, "]", m.start()))
            elif m.group("comma"):
                self._tokens.append(_Token(_TT_COMMA, ",", m.start()))
            elif m.group("ge"):
                self._tokens.append(_Token(_TT_GE, ">=", m.start()))
            elif m.group("le"):
                self._tokens.append(_Token(_TT_LE, "<=", m.start()))
            elif m.group("gt"):
                self._tokens.append(_Token(_TT_GT, ">", m.start()))
            elif m.group("lt"):
                self._tokens.append(_Token(_TT_LT, "<", m.start()))
            elif m.group("iri"):
                # Strip angle brackets to get bare IRI string.
                iri = m.group("iri")[1:-1]
                self._tokens.append(_Token(_TT_NAME, iri, m.start()))
            elif m.group("integer") is not None and m.group("name") is None:
                self._tokens.append(
                    _Token(_TT_INT, m.group("integer"), m.start())
                )
            elif m.group("name"):
                word = m.group("name")
                lower = word.lower()
                if lower in _KW_MAP:
                    self._tokens.append(
                        _Token(_KW_MAP[lower], word, m.start())
                    )
                else:
                    self._tokens.append(_Token(_TT_NAME, word, m.start()))

        self._tokens.append(_Token(_TT_EOF, "", len(text)))


# ---------------------------------------------------------------------------
# Name resolution
# ---------------------------------------------------------------------------

def resolve_name(graph: Graph, name: str) -> URIRef | None:
    """Resolve a name to a :class:`URIRef` using multiple strategies.

    Resolution order:

    1. Full IRI (starts with ``http://`` or ``https://``).
    2. Well-known compact IRIs (``owl:Thing``, ``xsd:string``, ...).
    3. Compact IRIs using namespaces bound in *graph* (``ex:Foo``).
    4. Exact ``rdfs:label`` match in *graph*.
    5. Local-name match (fragment or last path segment) across all subjects
       in *graph*.

    Returns ``None`` when no match is found.
    """
    if not name:
        return None

    # 1. Full IRI
    if name.startswith("http://") or name.startswith("https://") or name.startswith("urn:"):
        return URIRef(name)

    # 2. Well-known compact IRIs
    if name in _WELL_KNOWN_NAMES:
        return _WELL_KNOWN_NAMES[name]

    # 3. Compact IRI via graph namespaces (and well-known prefixes)
    if ":" in name:
        prefix, local = name.split(":", 1)
        # Try well-known namespaces first
        ns = _WELL_KNOWN.get(prefix)
        if ns is not None:
            return URIRef(ns[local])
        # Try graph-bound namespaces
        for p, ns_uri in graph.namespaces():
            if p == prefix:
                return URIRef(ns_uri + local)

    # 4. Label match
    for s, _, o in graph.triples((None, RDFS.label, None)):
        if isinstance(s, URIRef) and str(o) == name:
            return s

    # 5. Local-name match (fragment or last path segment)
    for s in graph.subjects():
        if not isinstance(s, URIRef):
            continue
        iri = str(s)
        if iri.endswith(f"#{name}") or iri.endswith(f"/{name}"):
            return s

    return None


# ---------------------------------------------------------------------------
# RDF list helper
# ---------------------------------------------------------------------------

def _make_rdf_list(graph: Graph, items: list[Union[URIRef, BNode]]) -> BNode:
    """Create an RDF collection (linked list) in *graph* and return its head."""
    head = BNode()
    Collection(graph, head, items)
    return head


# ---------------------------------------------------------------------------
# Helper: detect datatype names
# ---------------------------------------------------------------------------

def _is_datatype_name(name: str) -> bool:
    """Return True if *name* looks like a known XSD datatype reference."""
    if name.startswith("xsd:"):
        return True
    if name in _WELL_KNOWN_NAMES:
        iri = str(_WELL_KNOWN_NAMES[name])
        return iri.startswith(XSD_NS)
    # Check local name against known XSD datatypes
    if name in _KNOWN_XSD_DATATYPES:
        return True
    return False


# ---------------------------------------------------------------------------
# Parser  (recursive descent)
# ---------------------------------------------------------------------------

class _Parser:
    """Recursive-descent parser that turns a token stream into rdflib triples.

    The parser writes triples directly into the supplied :class:`Graph` and
    returns a :class:`URIRef` (named class) or :class:`BNode` (anonymous
    class expression) for the top-level expression.
    """

    def __init__(self, tokenizer: _Tokenizer, graph: Graph) -> None:
        self._lex = tokenizer
        self._graph = graph

    # -- entry point --------------------------------------------------------

    def parse(self) -> URIRef | BNode:
        """Parse the full expression and return the root node."""
        node = self._parse_union()
        tok = self._lex.peek()
        if tok.type != _TT_EOF:
            raise SyntaxError(
                f"Unexpected token {tok.value!r} at position {tok.pos} "
                f"(expected end of expression)"
            )
        return node

    # -- grammar rules ------------------------------------------------------

    def _parse_union(self) -> URIRef | BNode:
        """union ::= intersection ( 'or' intersection )* """
        operands = [self._parse_intersection()]
        while self._lex.match(_TT_OR):
            operands.append(self._parse_intersection())
        if len(operands) == 1:
            return operands[0]
        return self._build_union(operands)

    def _parse_intersection(self) -> URIRef | BNode:
        """intersection ::= complement ( 'and' complement )* """
        operands = [self._parse_complement()]
        while self._lex.match(_TT_AND):
            operands.append(self._parse_complement())
        if len(operands) == 1:
            return operands[0]
        return self._build_intersection(operands)

    def _parse_complement(self) -> URIRef | BNode:
        """complement ::= 'not' complement | primary"""
        if self._lex.match(_TT_NOT):
            inner = self._parse_complement()
            return self._build_complement(inner)
        return self._parse_primary()

    def _parse_primary(self) -> URIRef | BNode:
        """primary ::= '(' union ')' | '{' name_list '}' | restriction | named_class facets?"""
        # Parenthesised sub-expression
        if self._lex.match(_TT_LPAREN):
            node = self._parse_union()
            self._lex.expect(_TT_RPAREN)
            return node

        # ObjectOneOf: {name, name, ...}
        if self._lex.match(_TT_LBRACE):
            individuals: list[URIRef] = []
            while True:
                name_tok = self._lex.expect(_TT_NAME)
                ind_uri = resolve_name(self._graph, name_tok.value)
                if ind_uri is None:
                    raise SyntaxError(
                        f"Cannot resolve individual name {name_tok.value!r} to an IRI"
                    )
                individuals.append(ind_uri)
                if not self._lex.match(_TT_COMMA):
                    break
            self._lex.expect(_TT_RBRACE)
            bnode = BNode()
            head = BNode()
            Collection(self._graph, head, individuals)
            self._graph.add((bnode, RDF.type, OWL.Class))
            self._graph.add((bnode, OWL.oneOf, head))
            return bnode

        # Must be a NAME (either a named class or beginning of a restriction)
        tok = self._lex.expect(_TT_NAME)
        name = tok.value

        # Look ahead: if the next token is a restriction keyword the NAME is
        # a property and we parse a restriction.
        next_tt = self._lex.peek().type
        if next_tt in (_TT_SOME, _TT_ONLY, _TT_VALUE, _TT_MIN, _TT_MAX, _TT_EXACTLY):
            return self._parse_restriction(name)

        # HasSelf: NAME Self
        if next_tt == _TT_SELF:
            self._lex.advance()  # consume 'Self'
            prop_uri = resolve_name(self._graph, name)
            if prop_uri is None:
                raise SyntaxError(
                    f"Cannot resolve property name {name!r} to an IRI"
                )
            bnode = BNode()
            self._graph.add((bnode, RDF.type, OWL.Restriction))
            self._graph.add((bnode, OWL.onProperty, prop_uri))
            self._graph.add((bnode, OWL.hasSelf, Literal(True)))
            return bnode

        # Otherwise it is a plain named class.
        uri = resolve_name(self._graph, name)
        if uri is None:
            raise SyntaxError(f"Cannot resolve name {name!r} to an IRI")

        # Check for datatype facet restrictions: xsd:integer[>= 0, <= 100]
        if self._lex.peek().type == _TT_LBRACKET and _is_datatype_name(name):
            return self._parse_datatype_restriction(uri)

        return uri

    def _parse_restriction(self, prop_name: str) -> BNode:
        """Parse the restriction part after the property name has been consumed."""
        prop_uri = resolve_name(self._graph, prop_name)
        if prop_uri is None:
            raise SyntaxError(f"Cannot resolve property name {prop_name!r} to an IRI")

        kw = self._lex.advance()  # consume the restriction keyword

        if kw.type == _TT_SOME:
            filler = self._parse_complement()
            return self._build_some(prop_uri, filler)

        if kw.type == _TT_ONLY:
            filler = self._parse_complement()
            return self._build_only(prop_uri, filler)

        if kw.type == _TT_VALUE:
            val_tok = self._lex.expect(_TT_NAME)
            val_uri = resolve_name(self._graph, val_tok.value)
            if val_uri is None:
                raise SyntaxError(
                    f"Cannot resolve value name {val_tok.value!r} to an IRI"
                )
            return self._build_value(prop_uri, val_uri)

        # Cardinality: min / max / exactly
        if kw.type in (_TT_MIN, _TT_MAX, _TT_EXACTLY):
            card_tok = self._lex.expect(_TT_INT)
            cardinality = int(card_tok.value)

            # Optional qualified class
            qual: URIRef | BNode | None = None
            next_tt = self._lex.peek().type
            if next_tt == _TT_NAME:
                qual = self._parse_primary()
            elif next_tt == _TT_LPAREN:
                qual = self._parse_primary()
            elif next_tt == _TT_NOT:
                qual = self._parse_complement()

            return self._build_cardinality(prop_uri, kw.type, cardinality, qual)

        raise SyntaxError(f"Unexpected restriction keyword {kw.value!r}")

    def _parse_datatype_restriction(self, datatype_uri: URIRef) -> BNode:
        """Parse facet constraints: ``[>= 0, <= 100]`` after a datatype name."""
        self._lex.expect(_TT_LBRACKET)

        facet_bnodes: list[BNode] = []
        while True:
            facet_bnode = BNode()

            # Determine facet type: comparison operator or named facet
            tok = self._lex.peek()
            if tok.type in (_TT_GE, _TT_LE, _TT_GT, _TT_LT):
                op_tok = self._lex.advance()
                facet_iri = URIRef(XSD_FACETS[op_tok.value])
            elif tok.type == _TT_NAME and tok.value in XSD_FACETS:
                name_tok = self._lex.advance()
                facet_iri = URIRef(XSD_FACETS[name_tok.value])
            else:
                raise SyntaxError(
                    f"Expected facet operator or name at position {tok.pos}, "
                    f"got {tok.type} ({tok.value!r})"
                )

            # Parse the facet value (integer or name/string)
            val_tok = self._lex.peek()
            if val_tok.type == _TT_INT:
                self._lex.advance()
                facet_value = Literal(int(val_tok.value))
            elif val_tok.type == _TT_NAME:
                self._lex.advance()
                facet_value = Literal(val_tok.value)
            else:
                raise SyntaxError(
                    f"Expected facet value at position {val_tok.pos}, "
                    f"got {val_tok.type} ({val_tok.value!r})"
                )

            self._graph.add((facet_bnode, facet_iri, facet_value))
            facet_bnodes.append(facet_bnode)

            if not self._lex.match(_TT_COMMA):
                break

        self._lex.expect(_TT_RBRACKET)

        # Build owl:DatatypeRestriction
        bnode = BNode()
        self._graph.add((bnode, RDF.type, RDFS.Datatype))
        self._graph.add((bnode, OWL.onDatatype, datatype_uri))
        restrictions_head = _make_rdf_list(self._graph, facet_bnodes)
        self._graph.add((bnode, OWL.withRestrictions, restrictions_head))
        return bnode

    # -- triple builders ----------------------------------------------------

    def _build_union(self, operands: list[URIRef | BNode]) -> BNode:
        bnode = BNode()
        head = _make_rdf_list(self._graph, operands)
        self._graph.add((bnode, OWL.unionOf, head))
        return bnode

    def _build_intersection(self, operands: list[URIRef | BNode]) -> BNode:
        bnode = BNode()
        head = _make_rdf_list(self._graph, operands)
        self._graph.add((bnode, OWL.intersectionOf, head))
        return bnode

    def _build_complement(self, inner: URIRef | BNode) -> BNode:
        bnode = BNode()
        self._graph.add((bnode, OWL.complementOf, inner))
        return bnode

    def _build_some(self, prop: URIRef, filler: URIRef | BNode) -> BNode:
        bnode = BNode()
        self._graph.add((bnode, RDF.type, OWL.Restriction))
        self._graph.add((bnode, OWL.onProperty, prop))
        self._graph.add((bnode, OWL.someValuesFrom, filler))
        return bnode

    def _build_only(self, prop: URIRef, filler: URIRef | BNode) -> BNode:
        bnode = BNode()
        self._graph.add((bnode, RDF.type, OWL.Restriction))
        self._graph.add((bnode, OWL.onProperty, prop))
        self._graph.add((bnode, OWL.allValuesFrom, filler))
        return bnode

    def _build_value(self, prop: URIRef, individual: URIRef) -> BNode:
        bnode = BNode()
        self._graph.add((bnode, RDF.type, OWL.Restriction))
        self._graph.add((bnode, OWL.onProperty, prop))
        self._graph.add((bnode, OWL.hasValue, individual))
        return bnode

    def _build_cardinality(
        self,
        prop: URIRef,
        kw_type: str,
        cardinality: int,
        qual: URIRef | BNode | None,
    ) -> BNode:
        bnode = BNode()
        self._graph.add((bnode, RDF.type, OWL.Restriction))
        self._graph.add((bnode, OWL.onProperty, prop))

        card_literal = Literal(cardinality, datatype=XSD.nonNegativeInteger)

        if qual is not None:
            # Qualified cardinality
            pred_map = {
                _TT_MIN: OWL.minQualifiedCardinality,
                _TT_MAX: OWL.maxQualifiedCardinality,
                _TT_EXACTLY: OWL.qualifiedCardinality,
            }
            self._graph.add((bnode, pred_map[kw_type], card_literal))
            self._graph.add((bnode, OWL.onClass, qual))
        else:
            # Unqualified cardinality
            pred_map = {
                _TT_MIN: OWL.minCardinality,
                _TT_MAX: OWL.maxCardinality,
                _TT_EXACTLY: OWL.cardinality,
            }
            self._graph.add((bnode, pred_map[kw_type], card_literal))

        return bnode


# ---------------------------------------------------------------------------
# Public API: parse
# ---------------------------------------------------------------------------

def parse_class_expression(expr: str, graph: Graph) -> URIRef | BNode:
    """Parse a Manchester Syntax class expression and add triples to *graph*.

    Returns the :class:`URIRef` or :class:`BNode` representing the root of
    the parsed expression.

    Examples::

        >>> from rdflib import Graph, Namespace
        >>> g = Graph()
        >>> EX = Namespace("http://example.org/")
        >>> g.bind("ex", EX)
        >>> for cls in ("Animal", "Plant", "Organ", "Tissue"):
        ...     g.add((EX[cls], RDF.type, OWL.Class))
        >>> g.add((EX.hasPart, RDF.type, OWL.ObjectProperty))
        >>> node = parse_class_expression("Animal and hasPart some (Organ or Tissue) and not Plant", g)

    Raises:
        SyntaxError: On malformed input or unresolvable names.
    """
    tokenizer = _Tokenizer(expr.strip())
    parser = _Parser(tokenizer, graph)
    return parser.parse()


# ---------------------------------------------------------------------------
# Renderer: RDF class expression -> Manchester Syntax string
# ---------------------------------------------------------------------------

def _get_label(graph: Graph, node) -> str | None:
    """Return the first ``rdfs:label`` for *node*, or ``None``."""
    if not isinstance(node, URIRef):
        return None
    for o in graph.objects(node, RDFS.label):
        if isinstance(o, Literal):
            return str(o)
    return None


def _local_name(iri: str) -> str:
    """Extract the local name (fragment or last path segment) from an IRI."""
    if "#" in iri:
        return iri.split("#")[-1]
    return iri.rsplit("/", 1)[-1]


def _display_name(graph: Graph, node) -> str:
    """Return a human-friendly name for a URI node."""
    if isinstance(node, URIRef):
        iri = str(node)
        # Well-known
        if node == OWL.Thing:
            return "owl:Thing"
        if node == OWL.Nothing:
            return "owl:Nothing"
        # XSD datatypes
        if iri.startswith(XSD_NS):
            local = iri[len(XSD_NS):]
            return f"xsd:{local}"
        label = _get_label(graph, node)
        if label:
            return label
        return _local_name(iri)
    return str(node)


def _single_obj(graph: Graph, subj, pred):
    """Return the first object for *(subj, pred, ?)* or ``None``."""
    for o in graph.objects(subj, pred):
        return o
    return None


def _rdf_list_items(graph: Graph, head) -> list:
    """Collect all items from an RDF list starting at *head*."""
    items: list = []
    current = head
    while current and current != RDF.nil:
        first = _single_obj(graph, current, RDF.first)
        if first is not None:
            items.append(first)
        current = _single_obj(graph, current, RDF.rest)
    return items


# Reverse map from facet IRIs to operator symbols for rendering.
_FACET_TO_OP: dict[str, str] = {
    XSD_NS + "minInclusive": ">=",
    XSD_NS + "maxInclusive": "<=",
    XSD_NS + "minExclusive": ">",
    XSD_NS + "maxExclusive": "<",
    XSD_NS + "minLength": "minLength",
    XSD_NS + "maxLength": "maxLength",
    XSD_NS + "pattern": "pattern",
}


def render_class_expression(graph: Graph, node) -> str:
    """Render an RDF class-expression node as a Manchester Syntax string.

    Handles named classes (:class:`URIRef`), anonymous class expressions
    (:class:`BNode`) including restrictions, Boolean connectives, and
    nested expressions.

    Returns a human-readable Manchester Syntax string.
    """
    return _render(graph, node, parent_op=None)


def _render(graph: Graph, node, parent_op: str | None) -> str:
    """Recursive renderer with *parent_op* for parenthesisation decisions."""
    if isinstance(node, URIRef):
        return _display_name(graph, node)

    if not isinstance(node, BNode):
        return str(node)

    # -- owl:Restriction ----------------------------------------------------
    if (node, RDF.type, OWL.Restriction) in graph:
        return _render_restriction(graph, node)

    # -- owl:oneOf (ObjectOneOf) --------------------------------------------
    list_head = _single_obj(graph, node, OWL.oneOf)
    if list_head is not None and (node, RDF.type, OWL.Class) in graph:
        items = _rdf_list_items(graph, list_head)
        names = [_display_name(graph, item) for item in items]
        return "{" + ", ".join(names) + "}"

    # -- owl:DatatypeRestriction (rdfs:Datatype with owl:onDatatype) --------
    on_datatype = _single_obj(graph, node, OWL.onDatatype)
    if on_datatype is not None and (node, RDF.type, RDFS.Datatype) in graph:
        return _render_datatype_restriction(graph, node, on_datatype)

    # -- owl:intersectionOf -------------------------------------------------
    list_head = _single_obj(graph, node, OWL.intersectionOf)
    if list_head is not None:
        items = _rdf_list_items(graph, list_head)
        parts = [_render(graph, item, parent_op="and") for item in items]
        text = " and ".join(parts)
        if parent_op == "and":
            # No extra parens needed when parent is also intersection
            return text
        if parent_op in ("not", "some", "only"):
            return f"({text})"
        return text

    # -- owl:unionOf --------------------------------------------------------
    list_head = _single_obj(graph, node, OWL.unionOf)
    if list_head is not None:
        items = _rdf_list_items(graph, list_head)
        parts = [_render(graph, item, parent_op="or") for item in items]
        text = " or ".join(parts)
        # Union needs parens when inside a tighter-binding context
        if parent_op in ("and", "not", "some", "only"):
            return f"({text})"
        return text

    # -- owl:complementOf ---------------------------------------------------
    comp = _single_obj(graph, node, OWL.complementOf)
    if comp is not None:
        inner = _render(graph, comp, parent_op="not")
        return f"not {inner}"

    # Fallback: unknown BNode
    return f"_:{node}"


def _render_restriction(graph: Graph, node: BNode) -> str:
    """Render an ``owl:Restriction`` BNode to Manchester Syntax."""
    prop = _single_obj(graph, node, OWL.onProperty)
    prop_name = _display_name(graph, prop) if prop else "?"

    # hasSelf
    has_self = _single_obj(graph, node, OWL.hasSelf)
    if has_self is not None:
        return f"{prop_name} Self"

    # someValuesFrom
    filler = _single_obj(graph, node, OWL.someValuesFrom)
    if filler is not None:
        filler_text = _render(graph, filler, parent_op="some")
        return f"{prop_name} some {filler_text}"

    # allValuesFrom
    filler = _single_obj(graph, node, OWL.allValuesFrom)
    if filler is not None:
        filler_text = _render(graph, filler, parent_op="only")
        return f"{prop_name} only {filler_text}"

    # hasValue
    val = _single_obj(graph, node, OWL.hasValue)
    if val is not None:
        val_text = _display_name(graph, val) if isinstance(val, URIRef) else str(val)
        return f"{prop_name} value {val_text}"

    # Cardinality (qualified and unqualified)
    for card_pred, qual_pred, keyword in [
        (OWL.minQualifiedCardinality, OWL.onClass, "min"),
        (OWL.maxQualifiedCardinality, OWL.onClass, "max"),
        (OWL.qualifiedCardinality, OWL.onClass, "exactly"),
        (OWL.minCardinality, None, "min"),
        (OWL.maxCardinality, None, "max"),
        (OWL.cardinality, None, "exactly"),
    ]:
        card = _single_obj(graph, node, card_pred)
        if card is not None:
            card_val = int(str(card))
            if qual_pred:
                qual_class = _single_obj(graph, node, qual_pred)
                if qual_class is not None:
                    qual_text = _render(graph, qual_class, parent_op=None)
                    return f"{prop_name} {keyword} {card_val} {qual_text}"
            return f"{prop_name} {keyword} {card_val}"

    return f"{prop_name} ?"


def _render_datatype_restriction(
    graph: Graph, node: BNode, on_datatype: URIRef
) -> str:
    """Render an ``owl:DatatypeRestriction`` BNode to Manchester Syntax."""
    dt_name = _display_name(graph, on_datatype)

    restrictions_head = _single_obj(graph, node, OWL.withRestrictions)
    if restrictions_head is None:
        return dt_name

    facet_bnodes = _rdf_list_items(graph, restrictions_head)
    facet_parts: list[str] = []
    for fb in facet_bnodes:
        # Each facet BNode has exactly one predicate-object pair (the facet)
        for p, o in graph.predicate_objects(fb):
            p_str = str(p)
            if p_str in _FACET_TO_OP:
                op = _FACET_TO_OP[p_str]
                val = str(o)
                facet_parts.append(f"{op} {val}")

    if not facet_parts:
        return dt_name

    return dt_name + "[" + ", ".join(facet_parts) + "]"
