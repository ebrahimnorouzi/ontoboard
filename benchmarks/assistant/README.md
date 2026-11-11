<!--
  Written for: anyone comparing models for the OntoBoard assistant, and a reviewer checking a
  number before believing it.
-->

# Assistant benchmark

These are test cases for the OntoBoard assistant, and a command that runs any mix of models on
them and writes a table. The command uses the plugin's own code: the roles, the fixed rules,
request assembly and the three model clients. A score is therefore what a person using the
plugin would get.

## What is here

| Path | What it is |
|---|---|
| `ontologies/pizza.owl` | Snapshot of the [pizza example](https://github.com/ebrahimnorouzi/pizza-ontology) release, CC BY 4.0. |
| `ontologies/pizzabfo.owl` | Snapshot of the [pizza-bfo example](https://github.com/ebrahimnorouzi/pizza-bfo) release, CC BY 4.0. |
| `ontologies/nfdicore.owl` | Snapshot of [NFDIcore](https://github.com/ISE-FIZKarlsruhe/nfdicore) 3.0.4, the merged release file, CC0. |
| `ontologies/mwo.owl` | Snapshot of the [MatWerk ontology (MWO)](https://github.com/ISE-FIZKarlsruhe/mwo) 3.0.0, the merged release file, CC0. |
| `ontologies/fixtures/*.ofn` | Five small hand-written materials ontologies, CC0: `consistent`, `unsatisfiable` (two classes, one through a property's domain), `inconsistent`, `datatype-clash` (a `decimal` value against a `float` range), and `restrictions` (one of each text-to-axiom shape beyond `some` and `only`). Each says in its comment what it is, and a test checks it. |
| `cases/pizza.jsonl`, `cases/pizzabfo.jsonl`, `cases/nfdicore.jsonl`, `cases/mwo.jsonl`, `cases/fixture-*.jsonl` | The cases, one JSON object per line, generated with seed 1 and the default counts. The `restrictions` fixture is generated with `--text-to-axiom 10`, so that all seven of its shapes are in. |
| `models.example.json` | A models file to copy and fill in. |

Each case records the SHA-256 of the ontology file it was made from, with line endings
normalised. The `run` command warns if the snapshot no longer matches.

## The tasks

Two tasks check what an answer says, and three check what it would do to the ontology.

### explain-problem

- **What the case is:** the reasoner's explanation of an unsatisfiable class or an inconsistent
  ontology, and the question OntoBoard asks when you press *Explain the reasoner problem…*.
- **Where the problem comes from:**
  - It is either injected into a consistent ontology (a fresh class made a subclass of two
    disjoint classes, or a fresh individual given both types), checked with HermiT;
  - or it is a problem the ontology already has. The pizza example is inconsistent on purpose:
    prices typed `decimal` against a `float` range.
- **Scores:**
  - `termRecall` is the share of the justification's terms the answer names.
  - `namesClash` is 1 when the answer names both sides of the clash. For a problem nobody
    designed, the clash is a heuristic: the terms that two or more of the first justification's
    axioms share.
  - A case **passes** when the answer names the clash and at least half the terms.

### context-request

- **What the case is:** a class T whose restriction names a class N that has subclasses, and the
  question "Which kinds of N are there in this ontology, and how does that affect T?".
- **What the model is sent:**
  - In an ordinary case, the context has T's block but not N's, so the model cannot know N's
    subclasses. The right answer asks for N in an `ontoboard-need` block.
  - In a control case, N's block is included. The right answer does not ask. There are never
    more controls than distinct (T, N) pairs, and the summary reports controls in a table of
    their own.
- **Scores:**
  - `asked` is 1 when the answer has an `ontoboard-need` block.
  - `askedRight` is 1 when that block names N.

### definition

- **What the case is:** a class with one definition and a named parent. The definition is held
  back. The model gets the class's block from a copy of the ontology without it, and exactly the
  request *Suggest a definition* sends: a genus-differentia definition, as a proposal.
- **Scores:**
  - `validProposal` is 1 when the answer has a proposal that OntoBoard would offer as a card for
    the right class and property. It is validated by the plugin's own code.
  - `namesGenus` is 1 when the proposed definition names an asserted parent.
  - `overlap` is the F1 of the proposal's content words against the held-back definition's.
  - `proposals` is the number of different cards the answer offers that OntoBoard would let a
    person accept, of any kind. Two blocks that would write the same definition, word for word,
    with other spacing (a no-break or zero-width space too) or with an invisible format character
    (a soft hyphen, a zero-width joiner or non-joiner, a direction mark), count once; a set and an
    add of it are two cards. A block OntoBoard shows as "Cannot be applied" is not counted. `namesGenus`
    and `overlap` describe the first definition.
  - A case **passes** with one card, a valid definition that names its genus. A second card -
    another definition, a definition of another term, a comment - fails the case whichever comes
    first: the request asks for one block. `overlap` is reported, not required: a good
    definition can use other words.

### repair

- **What the case is:** the same kinds of problem as explain-problem, injected or as the
  ontology has them. The request asks for the repair as `remove-axiom` or `replace-axiom`
  proposals.
- **The loop:**
  1. The model's proposals are validated as the plugin validates them and applied to a copy:
     removals, replacements (the old axiom counts as removed) and additions.
  2. HermiT runs again.
  3. If the problem is still there, the new justification goes back with the conversation so
     far. If nothing was removed, the next request says why: no proposal, or the reason each one
     was refused, and that an addition alone cannot remove the problem. An addition is no
     removal. A removal refused beside one that was applied is named with its reason too, and a
     block with an action OntoBoard does not know gets the list of the actions there are.
  4. At most 3 rounds.
- **Scores:**
  - `solved`: the problem is gone (consistent; for an unsatisfiable class, that class is
    satisfiable).
  - `rounds` used.
  - `removedFault` is 1 when what was removed is an injected axiom. For a problem the ontology
    already had, it is 1 when it is an axiom of a justification the loop showed, in the first
    round or a later one.
  - `collateral` counts the other axioms removed. Deleting a disjointness also "solves" an
    unsatisfiable class, but it removes what the ontology says, not what was wrong. For a problem
    the ontology already had, it counts the removals that were not needed: each removed axiom is
    put back in turn, in the order removed, and one whose return leaves the problem solved was
    not needed. This finds a minimal needed set, not always the smallest one. When such a run is
    not solved - the rounds ran out, a call failed or the reasoner could not check an answer - it
    counts the removals outside every justification the loop showed.
  - `validProposals` is, over all rounds, the share of the answers' blocks that changed the
    logic: removals and replacements that were applied, and additions. An annotation card, a
    block shown as Cannot be applied, a removal or a replacement of an axiom an earlier block of
    the same answer already removed, and an addition of an axiom already there count against it,
    so a removal with a comment card scores 0.5. A replacement dropped that way is named in the
    next request, with its reason.
    Additions are applied too, though on their own they cannot remove a clash.
  - A case **passes** when solved without collateral.
- `replies.jsonl` records every round, so `score` replays a repair without calling the model.

### text-to-axiom

- **What the case is:** an axiom about a class of the ontology's own namespace is held back,
  and a sentence is made from it. "At least one" stands for `some` and "nothing but" for
  `only`. The sentence avoids the word "only", so choosing the quantifier is left to the model.
  These shapes are used, with p and F named:
  - a superclass `p some F` or `p only F`, said as "Every ⟨C⟩ ⟨p⟩ at least one ⟨F⟩.";
  - since 1.144.0, more superclass restrictions, with numbers as digits:
    - `p min n F` is said as "⟨p⟩ at least n ⟨F⟩", and `min 1` as "at least one", like
      `some`;
    - `p max n F` is said as "⟨p⟩ at most n ⟨F⟩";
    - `p exactly n F` is said as "⟨p⟩ exactly n ⟨F⟩";
    - `p value i` is said as "⟨p⟩ ⟨i⟩", for example "Every steel has main element iron.";
    - `p some D`, for a datatype, is said as "⟨p⟩ at least one ⟨D⟩ value";
    - `p value "v"^^D` is said as "⟨p⟩ the ⟨D⟩ value v". The datatype is named, because
      `decimal` and `float` are not the same value.

    The snapshots have almost none of these. The `restrictions` fixture has each one once, and
    one definition with `exactly`. They are chosen after the cases of `some` and `only`, so
    adding them changed none of those cases;
  - a definition `C EquivalentTo G and (p some F)`, said as "Every ⟨C⟩ is a ⟨G⟩ that ⟨p⟩ at
    least one ⟨F⟩, and every ⟨G⟩ that ⟨p⟩ at least one ⟨F⟩ is a ⟨C⟩." Both directions are
    said, so an answer must give both. This is how NFDIcore and MWO define their classes. Their
    own superclass restrictions repeat parts of these definitions, so the rest of the ontology
    already entails them and they give no case.

  The model gets the class's block from a copy of the ontology without the axiom, and the blocks
  of the other terms the sentence names, which *Write as OWL…* adds as chips. It also gets
  exactly the request *Write as OWL…* sends with that sentence.
- **Which axioms are used:**
  - The names are written as the context writes them. An axiom whose terms have no readable name
    (only an ID such as `NFDI_0000001`) is skipped.
  - So is an axiom that the rest of the ontology already entails, since any answer would entail
    it too.
  - So is an axiom whose return makes a class unsatisfiable or the ontology inconsistent. Not
    even that axiom would pass `safe`, so a right answer would score as a fail. In the
    unsatisfiable fixture, the only candidate is such an axiom, so that fixture gives no case.
    A test checks that every shipped case passes when its own axiom is given as the answer.
  - An inconsistent ontology entails everything, so it gives no case. The pizza example is one.
- **Scores**, all by HermiT on the ontology as the model saw it:
  - `validProposal` is 1 when the answer has an `add-axiom` proposal that OntoBoard would offer.
  - `entails` is 1 when the ontology plus the proposed axiom entails the held-back one.
  - `entailed` is 1 when the ontology plus the held-back axiom entails the proposed one.
  - `equivalent` is 1 when both hold.
  - `safe` is 1 when the proposed axiom leaves the ontology consistent and makes no class empty.
  - `asked` is 1 when the answer asks for a term (an `ontoboard-need` block) instead of
    proposing.
  - `proposals` is the number of different cards the answer offers that OntoBoard would let a
    person accept, of any kind. The same axiom twice counts once, and a block OntoBoard shows as
    "Cannot be applied" is not counted. `entails`, `entailed`, `equivalent` and `safe`
    describe the first `add-axiom`.
  - A case **passes** with one card, a valid, safe proposal that is equivalent to the held-back
    axiom, given the rest of the ontology. A second card fails the case whichever comes first:
    the request asks for one block, and the person would get a card to accept for each. A
    sentence says one thing: an axiom that says more entails it but does not pass. Neither does
    `only` where `some` was held back, even where the rest of the ontology makes it entail
    `some`. An answer the reasoner cannot handle, such as an unsupported facet, counts as not
    entailed.
- **What it measures:** turning a controlled sentence into OWL, which means getting the names,
  the quantifier and the direction right. It does not measure reading free prose.

**All tasks:** `inventedIris` counts IRIs in the answer that are not in the ontology.

Names are compared as whole words, with case, punctuation and CamelCase ignored. So "vegetarian
topping", "vegetarian-topping" and "VegetarianTopping" are the same name, but "is it explained"
does not name "site".

## Running it

You need a clone of the repository, a JDK 8 or later, and Maven. Protégé is not needed. Build the
classpath once, as [docs/measurements.md](../../docs/measurements.md) explains:

```
cd protege-plugin
mvn -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt
mvn -q -DskipTests compile
```

Copy `models.example.json` to `my-models.json` at the top of the clone, fill in the models, and
set the key variables it names; git ignores `my-models.json` and `results/` there. Keys are read
only from the environment. A models file that holds one is refused before anything runs, and the
key is not repeated:

- anything shaped like a known key, anywhere in the file;
- a field that holds a key or a password, at any depth, by the words of its name:
  - under a password's name - a word that ends with `password`, `passwd`, `pwd`, `passwort`,
    `passphrase`, `secret` or `credential`, or their plural, or that joins one of them to `value`,
    `id`, `string`, `text`, `data`, `hash` or `file` (`password`, `db_passwds`, `clientSecret`,
    `credentials`, `passwordhash`, `secretvalue`) - any text is one, whatever its length or spaces
    (`hunter2`, `correct horse battery staple`), but a reference to a variable, a yes or no
    written as text, and a placeholder in brackets with no digit (`<YOUR_PASSWORD>`) unless what
    is inside looks like a key;
  - under a key's name - a word that ends with `key` or `token`, or their plural, or with
    `bearer` or `auth`; that begins with `auth`; or that joins `key` or `token` to one of those
    words (`apiKey`, `apikey`, `api_token`, `apiTokens`, `keyvalue`, `headers.Authorization`) - a
    text of 8 or more characters with no space is one, and a text with spaces holds one when a
    part of it, or two parts side by side, look like a key (`Key …`, `OAuth …`, two keys, a key
    and a comment, a key wrapped or broken in two);
  - under a setting's name a text holds one only when it looks like a key. A setting's name has a
    word such as `stop`, `eos`, `bos`, `pad`, `special`, `max`, `min`, `per`, `limit`, `count` or
    `type` (`stopTokens`, `eos_token`, `max_tokens`, `tokensPerMinute`, `tokenType`); or, after
    its last key's or password's word, a word such as `name`, `source`, `policy`, `required`,
    `url`, `endpoint`, `header` or `env`, which makes it a name about a key rather than of one
    (`secretName`, `credentialSource`, `passwordPolicy`, LiteLLM's `litellm_credential_name`,
    `tokenUrl`, `apiKeyHeader`). A word that only says which key, as in `apiKeyProd` or
    `api_key_2`, leaves it a key's name. `keyVariable` as written is checked by its own rule
    below; any other spelling of it (`keyvariable`, `key_variable`), and `maxOutputTokens`, are
    settings' names.

  A field inside such a field is read by its own name, or by the outer one when its own says
  nothing. `author`, `tokenizer`, `tokenization`, `keystore`, `secretary` or `keywords` is no such
  name. A number, `true`, `false`, a yes or no written as text (`"false"`, `"none"`, `"disabled"`,
  `"required"`), a reference to a variable, or a model's special token or a placeholder in
  brackets (`</s>`, `<|im_end|>`, `<YOUR_API_KEY>`) holds no key, unless what is in the brackets
  looks like a key; in `Bearer KEY`, KEY is what is checked. A text looks like a key as a
  `keyVariable` does below, or when it has a run of 16 or more letters and digits whose words are
  under three characters long on average (`QwErTy…`, `7f3a9c2e1b4d8a6f`). The message says "holds
  a key" for a password too;
- a field given twice, at any depth: only its last value would be read, so the file is refused.
  The message names the entry, and the field only when no key can be in its name, never either
  value;
- a query, or a name and password or a token, in `baseUrl`;
- a `keyVariable` that is not a variable's name, or that looks like a key: a run of 20 or more
  letters and digits, with no `_`, `.` or `-` in it, in which a digit is followed by a letter, as
  in Mistral, Cohere and Azure keys; a UUID; or 20 or more characters with three or more places
  where a digit is followed by a letter, as in base64url tokens split by `-` and `_`. Names such
  as `mistral_api_key_2024`, `MyTeamGatewayApiKey2` or `GPT4O_MINI_2024_07_18_KEY` are accepted.
  One with three such places, such as `MIXTRAL_8X22B_Q4K_KEY` or `MIXTRAL_8X7B_Q4K_M_KEY`, runs
  when its variable is set; when it is not set, it is refused as a key, and the message says to
  set the variable.

A `keyVariable` written as `$NAME`, `${NAME}`, `$env:NAME`, `$(NAME)`, `%NAME%`, `{{NAME}}` or
`env:NAME` is refused before anything runs, with a message to write the bare name; it is not
taken for a key. A default that looks like a key, as in `${NAME:-Qm7R…}`, is: the file holds a
key. A field under a key's name that refers to a variable - `"apiKey": "${MISTRAL_API_KEY}"`,
LiteLLM's `os.environ/MISTRAL_API_KEY`, `Bearer ${NAME}` - holds no key either: it is refused
before anything runs, with a message to name the variable in `keyVariable` and take the field
out. The variable is named only when it is a preset's, the entry's own `keyVariable`, or one set
in the environment; otherwise the message says "a variable". A field with a setting's name is not
checked for references, so a chat template's `{{user}}` in `stop_tokens` is not refused.

A file that is not valid JSON is reported by the line and column only, never its text, and every
line the bench prints on the error stream goes through the same redaction as the files. A key
with no known shape in a free-text field such as `_note` cannot be recognised. A model whose
variable is unset is skipped, and the command says so; it shows `$NAME` only for an upper-case
name. When an unset `keyVariable` has 20 or more characters and is not an upper-case name, the
line says instead that it names no variable that is set and, if it holds a key, to take it out of
the file and out of git. The value is never shown. Then run it (on Windows, use `;` instead of
`:`):

```
java -cp "target/classes:$(cat target/cp.txt)" \
     de.fizkarlsruhe.ise.ontoboard.assistant.bench.AssistantBench run \
     --models ../my-models.json --cases ../benchmarks/assistant/cases --out ../results --repeats 3
```

The command writes three files into `--out`. Whatever an earlier run left there moves, together,
into `previous-1/`, `previous-2/` and so on. Nothing is ever replaced. `score` keeps the results
it would overwrite the same way, never moves the replies, and skips a reply line that a stopped
run left half-written.

- `results.csv`: one row per case, model and repeat. Its last score column, `proposals`, is the
  number of cards an answer offers that a person could accept (1.146.0);
- `summary.md`: one table per task;
- `replies.jsonl`: every request and every answer, with any key the run knows shown as
  `(your key)`, anything shaped like a key as `(a key)`, and a name and password in an address as
  `(credentials)`. Each field is redacted whole before the line is written. For explain-problem,
  context-request, definition and text-to-axiom, `sent` is the whole request as "What is sent"
  shows it. For
  repair, it is each round's message; `rounds` holds each round's answer.

To re-score the recorded answers without calling any model, for example after changing a scorer:

```
... AssistantBench score --cases ../benchmarks/assistant/cases \
    --replies ../results/replies.jsonl --out ../rescored
```

To make cases from another ontology:

```
... AssistantBench generate --ontology my-ontology.owl --out my-cases.jsonl --seed 1 \
    --explain 10 --context 10 --controls 5 --definition 5 --repair 5 --text-to-axiom 5 \
    --reasoner HermiT
```

An ontology that is already inconsistent gives one explain-problem case and one repair case: its
own problem, since nothing can be injected into it. It gives no text-to-axiom case.

The same ontology and seed always give byte-identical cases.

## Read the numbers with care

- **Models have read the pizza ontology.** It is in many models' training data, so a good score
  on pizza can be recall rather than reasoning. The fixtures were written for this benchmark and
  are in no model's training data. NFDIcore and MWO are real ontologies in current use, so their
  terms are less likely to have been memorised.
- **Some axioms cannot be proposed.** `add-axiom` and `replace-axiom` take one axiom in
  Manchester syntax, so SWRL rules cannot be written with them. A rule can still be removed by
  copying it exactly. No case asks for a rule.
- **A repair is judged by the reasoner, not by intent.** Since 1.139.0 the loop applies
  `replace-axiom` too, so a value retyped from `decimal` to `float` counts as the repair it is.
  A replacement that makes the problem go away by weakening something unrelated is still solved;
  `collateral` is what catches it.
- **The scores are proxies.** They check what an answer names and asks for, not whether its
  explanation is right. Read some of `replies.jsonl` before quoting a number.
- **Answers vary between runs.** Run more than one repeat (`--repeats`, default 3).
