<!--
  Written for: domain experts and ontology experts using OntoBoard, who want to try the assistant
  and need to know exactly what it sends, where a key goes, and what it will not do.
-->

# The assistant

The OntoBoard tab has an **Assistant** column to the right of the board. Ask it about the selected
term or your ontology, or have it explain a reasoner problem in plain language. It works with a
cloud model (you bring an API key) or with an open-source model on your own machine through
[Ollama](https://ollama.com).

It can also **suggest changes**: a definition, a better label or comment, OWL written from a
sentence of yours, and the repair of a reasoner problem. Each suggestion is a card in the
conversation. **Nothing changes until you press Accept**, and an accepted change is one
*Edit → Undo* to take back.

## Setting up a model

Open the **Settings** tab beside the **Assistant** tab.

### Ollama on your own machine

With Ollama, nothing leaves your computer.

1. Install Ollama and download a model, for example `ollama pull llama3.1`; start it with
   `ollama serve` if it is not running.
2. In Settings, choose **Ollama** as the kind (the address is `http://localhost:11434`), press
   **Fetch the list**, pick the model and press **Save**.
3. **Context length** is how much text the model reads at once, 8,192 tokens by default. A
   question bigger than that is not sent, and OntoBoard says so.
4. Without a graphics card a model reads slowly. OntoBoard waits 5 minutes for the first word; if
   nothing comes, send less context or use a smaller model.

### A cloud model

Choose a **Preset**; it fills in the kind, the address and the environment variable the key is
read from:

| Preset | Address | Key variable |
|---|---|---|
| Anthropic | `https://api.anthropic.com` | `ANTHROPIC_API_KEY` |
| OpenAI | `https://api.openai.com/v1` | `OPENAI_API_KEY` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `GEMINI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| Mistral | `https://api.mistral.ai/v1` | `MISTRAL_API_KEY` |
| LM Studio | `http://localhost:1234/v1` | (none) |
| Ollama | `http://localhost:11434` | (none) |

Any other server that speaks the OpenAI API works as **OpenAI-compatible** with its address. Pick
the preset first, then paste your key, fetch the model list, pick a model and save. A preset
starts a new connection; a saved one stays as it was.

**Test connection** asks the server for its list of models and nothing else: no tokens, nothing
about your ontology. *OntoBoard → Check requirements…* shows the same check as **Assistant model**.

### Where your key is kept

OntoBoard takes the first of:

1. **The key you typed in this session**, kept in memory and forgotten when Protégé closes - the
   default;
2. **the connection's environment variable**, `GEMINI_API_KEY` for example; Settings says when
   one is used;
3. **a remembered key**, if you tick **Remember the key on this computer**: stored **as plain
   text** in Java's preferences, where anything running as you can read it. Unticking the box
   erases it.

A key goes only to the host it was given for. It never appears in "What is sent", a saved
conversation or a log, and nothing OntoBoard writes into your project holds one: before a
conversation is written, every key OntoBoard knows is replaced by `(your key)`, anything shaped
like a cloud, GitHub or GitLab key by `(a key)`, and a name and password in an address by
`(credentials)`.

## What is sent

By default a question sends only the **selected term and its neighbourhood**: its labels,
definition, comments and synonyms; its axioms, written with labels; its parents, children and
siblings; the terms its axioms mention, with one line of their definitions; and the ontology's IRI
and title. The whole ontology is never sent unless you choose to send it.

The **context chips** above the input show what goes with your next question. The first follows
your selection. **Add selected** keeps a term; typing a name adds one; **More ▾** adds the class
hierarchy or the whole ontology, showing its size first; on the board, right-click a term and
choose **Add to assistant context**. Each chip shows its size in tokens; × removes it.

**What is sent**, beside **Send**, shows the exact request before it goes: the destination and
model, the role's instructions and OntoBoard's fixed rules, the context, the conversation so far
and your message, with `(your key)` where the key goes. The first time in a session you send to a
cloud server, OntoBoard names the server and the size and asks you to confirm.

**Local models only**, a switch in Settings, limits the ontology to models on your machine or your
private network. It is saved in `assistant/project.properties` beside the ontology
(`src/ontology/assistant/` in an ODK repository), so the whole team gets it through git.

Values keep their type (`"9.50"^^xsd:decimal`), and names are written as Protégé writes them in
Manchester syntax (`'cheese topping'`), so an axiom the model copies back is the axiom it saw.

## The conversation

Your messages are on the right, the assistant's on the left; each answer names the model, the time
and the tokens. The line above the input says what is happening ("Waiting for qwen2.5:7b…",
"Checking 1 proposal with HermiT…"), and **Stop** ends an answer, keeping what arrived. The
conversation is kept per ontology until **Clear** or until Protégé closes.

### Saving conversations in the project

Settings → **This project** → **Save conversations in this project** writes each conversation to
`assistant/conversations/` beside the ontology after every answer. It is off by default, and the
switch reaches the team through `assistant/project.properties`. Each person then chooses for their
own conversations:

- **Keep private:** in `assistant/conversations/`, which `assistant/.gitignore` and your clone's
  `.git/info/exclude` keep out of git;
- **Release with the project:** in `assistant/conversations/released/`, which git sees, so they go
  into the repository when you commit;
- **Cancel:** nothing is written.

The choice is kept in your own Protégé preferences, never decided by a colleague, and **Change…**
asks again; what was said before a change keeps its old place. OntoBoard commits nothing. If a
file that keeps conversations out of git cannot be written, or a `.gitignore` takes them back in,
OntoBoard says so.

A file is named after when it started and the connection (`2026-11-06-143012-ollama.md`). It
records the OntoBoard version, the ontology, the connection and role, each turn's context, model,
time and tokens, and what you did with each suggestion. The **History** tab lists the project's
saved conversations; **Save…** writes the one you see to a file of your choice.

## Asking

- **The term's lines.** Each label, definition and axiom of the selected term has a ✦ button:
  **Ask about this line**, **Suggest a better wording**, **Explain this axiom**, **Suggest a better
  axiom**.
- **Suggest a definition** (**Write a definition** when there is none) asks for "A ⟨parent⟩ that
  …", with the term's asserted parent as the genus.
- **Write as OWL…** turns a sentence such as "Every vegetarian pizza has at least one cheese
  topping" into an axiom card. The other terms the sentence names are added as chips, so the
  model uses them instead of inventing names.
- **Explain the reasoner problem…** runs the reasoner chosen in Protégé's Reasoner menu (HermiT
  when none is chosen) and explains the inconsistency, or the unsatisfiable class you pick. On an
  unsatisfiable class, **Why is this unsatisfiable?** does the same at once. The assistant names
  the axiom most likely wrong and offers the repair as a card. Once the logic changes, the reasoner
  is asked again before the problem goes with another question.
- **When the assistant needs more**, it says so instead of guessing, and OntoBoard offers
  **Add … and ask again** with the terms it found by exact name.

A plain question works too; any change the model writes comes as a card.

## Suggestions: Accept, Reject, Revise

A card shows what would change (the old value struck through, the new one under it), why, and for
logic what the reasoner says about a copy with the change: "HermiT, on a copy with this change:
Margherita, cheese topping become unsatisfiable." An `only` with no `some` for the same property
gets a note that `only` alone does not say there is a value.

- **Accept** applies it as one change, checked once more against the ontology as it is now.
- **Reject** leaves the ontology as it is.
- **Revise…** asks for another version in your words.

A suggestion can set a label, definition or comment; add a synonym, example or comment; or remove,
add or replace one axiom, read with the OWL API's Manchester parser over your ontology's names. A
name not in the ontology is reported, never declared. A suggestion that cannot be applied as
written - an unknown or ambiguous term, an axiom that matches nothing, a value from an import, a
value changed since - shows the reason and no Accept.

What you accept is annotated `dcterms:contributor "OntoBoard assistant (ollama qwen2.5:7b)"`;
Settings → **Name the model on what I accept** switches that off.

## Roles

- **Ontology expert:** OWL, BFO and OBO conventions, and the quantifier traps.
- **Knowledge engineer:** asks you one question at a time before suggesting anything.
- **Domain expert:** brings knowledge of the field you name in **Your field**.

Each role is plain text (up to 8,000 characters) you can edit in Settings: **Discard**, **Reset**
and **New role…** do what they say. An edited or new role is saved in `assistant/roles/` beside
the ontology, so the team shares it, or in your Protégé preferences while the ontology has no
file; the line under the text says which. Text you typed and have not saved is kept while Protégé
runs.

Every role gets OntoBoard's fixed rules, shown in "What is sent": the assistant suggests and you
decide; "I don't know" is a good answer; it never writes an IRI it was not shown; it asks for
missing terms; it writes every change as a proposal.

## Comparing models

A benchmark in the repository runs any mix of models on the same cases with the assistant's own
code, and checks what each model produces: whether an explanation names the clash, whether the
model asks for a term it was not shown, whether a definition is one valid card naming the genus,
whether a repair solves the problem without breaking what the ontology needed, and whether an
axiom written from a sentence says exactly that. See
[benchmarks/assistant](https://github.com/ebrahimnorouzi/ontoboard/tree/main/benchmarks/assistant).

## Limits

- It changes annotations and one axiom at a time; it does not build a hierarchy or import terms.
- It reads the axioms Manchester syntax writes for a single axiom; anything else is refused.
- Its answers come from a model and can be wrong; check them against the ontology and the
  reasoner.
- Protégé 5.5.0 on macOS cannot reach the cloud models: its Java cannot check their certificates.
  Ollama on the same machine works.
- Plain http is refused except to your own machine or a server you marked as on your network.
- Token counts are estimates, a quarter of the characters.
