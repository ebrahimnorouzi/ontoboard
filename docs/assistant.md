<!--
  Written for: domain experts and ontology experts using OntoBoard, who want to try the assistant
  and need to know exactly what it sends, where a key goes, and what it will not do.
-->

# The assistant

Since 1.136.0, the OntoBoard tab has an **Assistant** column to the right of the board. You can
ask it about the term you have selected or about your ontology, and it can explain a reasoner
problem in plain language. It works with a cloud model (you bring an API key) or with an
open-source model running on your own machine through [Ollama](https://ollama.com).

It can also **suggest changes**:
- a definition, or a better wording for a label or comment (since 1.137.0);
- the removal of the axiom behind a reasoner problem (since 1.137.0);
- OWL written from a sentence of yours, and a repair that changes an axiom instead of deleting it
  (since 1.139.0).

Each suggestion is a card in the conversation. **Nothing changes until you press Accept**, and an
accepted change is one *Edit → Undo* to take back. A hierarchy and terms from other ontologies
come in later releases.

## Setting up a model

Open the **Settings** tab beside the **Assistant** tab.

### Ollama on your own machine

With Ollama, nothing leaves your computer.

1. Install Ollama and download a model, for example `ollama pull llama3.1`. If Ollama is not
   already running, start it with `ollama serve`.
2. In Settings, choose **Ollama** as the kind. The address is already `http://localhost:11434`.
   Press **Fetch the list**, pick the model, and press **Save**.
3. **Context length** is how much text the model reads at once, 8,192 tokens by default. If a
   question with its context is bigger, OntoBoard tells you and sends nothing. It does not let
   Ollama cut the text silently. Raise the value if your machine has the memory for it.
4. **Without a graphics card, a model reads slowly.** It reads the whole request before it
   writes its first word, and a long one can take minutes. OntoBoard waits 5 minutes for that
   first word. If nothing arrives by then, it tells you so. Send less context (remove some
   chips), or use a smaller model.

### A cloud model

Choose a **Preset**. It fills in the kind, the address and the environment variable a key is read
from:

| Preset | Address | Key variable |
|---|---|---|
| Anthropic | `https://api.anthropic.com` | `ANTHROPIC_API_KEY` |
| OpenAI | `https://api.openai.com/v1` | `OPENAI_API_KEY` |
| Google Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `GEMINI_API_KEY` |
| OpenRouter | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` |
| Mistral | `https://api.mistral.ai/v1` | `MISTRAL_API_KEY` |
| LM Studio | `http://localhost:1234/v1` | (none) |
| Ollama | `http://localhost:11434` | (none) |

Any other server that speaks the OpenAI API (vLLM, a team's own gateway) works as
**OpenAI-compatible** with its address. The key variable can be changed, so a team can use its
own name for it.

Paste your key, fetch the model list, pick a model and save.

A preset names the connection after itself, numbered when a saved connection already has that
name ("Mistral 2" if "Mistral" is taken); a name you typed is kept. Chosen while a saved
connection is shown, it starts a **new** connection, and the saved one stays as it was, key
included. Choosing a preset empties the Key field and unticks Remember, and a Kind that puts its
own address in place of the one shown empties the Key field: a key goes only to the provider it
was typed for. Pick the preset first, then paste the key. A new connection cannot be saved under
a saved one's name, and a connection cannot be renamed onto one: delete the other connection
first.

**Test connection** asks the server for its list of models and nothing else. It spends no
tokens and sends nothing about your ontology. *OntoBoard → Check requirements…* runs the same
check and shows it as **Assistant model**.

### Where your key is kept

OntoBoard looks for a key in this order:

1. **The key you typed in this session.** It is kept in memory and forgotten when Protégé closes.
   This is the default.
2. **An environment variable**: the connection's key variable, `GEMINI_API_KEY` for example.
   Settings says when one is being used.
3. **A remembered key**, only if you tick **Remember the key on this computer**. It is then stored
   **as plain text**:
   - on Windows, in the registry under `HKEY_CURRENT_USER\SOFTWARE\JavaSoft\Prefs`;
   - on macOS, in a plist under `~/Library/Preferences`;
   - on Linux, in a file under `~/.java/.userPrefs`.

   Anything running as you can read it there. Unticking the box erases it.

A key you typed or remembered goes only to the host it was given for. **Test connection** and
**Fetch the list** do not send it to another address typed in the form, and saving a connection
with an address on another host, without a new key, forgets the old one. Renaming a
connection on the same host keeps its key. The environment variable is used for whatever
address the form shows, and Settings names it.

The key never appears in "What is sent", in a saved conversation, or in a log. **Nothing
OntoBoard writes into your project holds a key**: not the roles, not
`assistant/project.properties`, not a saved conversation. Before a conversation is written,
every key OntoBoard knows is replaced by `(your key)`. That includes a key you replaced, forgot
or deleted in Settings earlier in the same Protégé session. Anything shaped like a cloud,
GitHub or GitLab key (`sk-…`, `AIza…`, `ghp_…`, `glpat-…`) is replaced by `(a key)`, and a name
and password in an address by `(credentials)`, also when either is written straight after a word
with no space, as Chinese and Japanese are written. That covers a key someone pasted into the chat
by mistake. A password may hold any character but a space of any kind, / ? # " < > and
punctuation outside ASCII (a Chinese or Japanese comma, colon or bracket, a curly quote, a dash),
so semicolons, commas, brackets, apostrophes and letters such as ü, 々 or € are covered, and so is
a user name such as "jürgen" or "佐々木", a Windows domain user such as `CORP\alice`, or an e-mail
address, `o'brien@…` and `jürgen@…` too. Ordinary text stays: an address without a password ends
at a quote, comma, bracket, a space of any kind (a no-break or an ideographic space too) or
punctuation outside ASCII, and a port, or a range of ports, ends at a quote, comma, semicolon,
bracket, parenthesis, brace, bar, backtick or `^`, or at a letter of a language written without
spaces - Chinese, Japanese (with ・ and ー), Korean, Thai, Lao, Khmer or Burmese. So a CSV row such
as `kg,http://localhost:7200,alice@example.org`, single-quoted code, a Markdown code span, and
Chinese or Japanese text such as `地址：http://localhost:7200，联系人：alice@example.org` or
`部署在http://localhost:7200由alice@example.org负责`, a Turtle literal's `@en` and JSON-LD's
`"@type"` are kept. The price: these passwords are not recognised - one that begins with up to
five digits, or a range of them, and then one of those characters or such a letter
(`2026;Wint3r`, `1234密码Pw`); one that holds a space, punctuation outside ASCII or an invisible
character such as a zero-width space; one after a user name holding a comma, semicolon, bracket,
bar, curly apostrophe or no-break space; one longer than 4,096 characters, or after a user name
longer than that; and one after nine or more `x@` before the user name. A lower-case
hyphenated word such as `sk-learn-compatible-…` is no key, since a key's body holds a capital
letter or a digit.

## What is sent

By default, a question sends only the **selected term and its neighbourhood**:

- its labels, definition, comments and synonyms;
- its axioms, written out with labels;
- its parents, children and siblings by label;
- the terms its axioms mention, each with one line of its definition;
- the ontology's IRI and title.

The whole ontology is never sent unless you choose to send it.

The row of **context chips** above the input shows what goes with your next question. The first
chip follows your selection. To add more:

- **Add selected** keeps the selected term in the context when you move on.
- **Type a name** in the box to add a term by its name.
- **More ▾** adds the class hierarchy (as labels) or the whole ontology. Each shows its size
  first.
- On the board, right-click a term and choose **Add to assistant context**.

Each chip shows an estimate of its size in tokens: a quarter of its characters. Remove a chip
with ×, or all of them but the selected term with **Remove all**. After two rows, the chips
scroll, so the conversation keeps its room. A name too long for the column is shortened so its
× stays in reach; hover over it for the whole name.

**What is sent**, beside **Send**, shows the exact text of the next request before it goes:

- the destination and the model;
- which header carries the key, with `(your key)` in its place;
- the role's instructions and OntoBoard's fixed rules;
- the context, the conversation so far, and your message.

The first time in a session that you send to a cloud server, OntoBoard asks you to confirm. It
names the server and the size. If you switch to another cloud server, it asks again.

**Local models only.** The Settings tab has a switch for the project. When it is on, only models
on your machine or your private network can be used with this ontology. The switch is saved in
`assistant/project.properties` next to the ontology file. In an ODK repository that is
`src/ontology/assistant/`. It is committed with your repository, so everyone on the team gets it.

Values keep their type in what is sent: `"9.50"^^xsd:decimal`, not `9.50`. A justification that
puts a decimal against a `float` range therefore shows the clash the reasoner found. OntoBoard
also states the fact behind it under the justification, because a model may not know it: "Note
(OWL 2): xsd:decimal and xsd:float have disjoint value spaces".

Names are written as Protégé writes them in Manchester syntax: a label with a space in single
quotes, `'cheese topping'`. Then an axiom the model copies back reads as the axiom it saw.

## The conversation

Your messages are on the right and the assistant's on the left. Each answer names the model and,
once it is finished, how long it took and how many tokens it used. The line above the input says
what is happening now:

- "Sending ~1,200 tokens to localhost…";
- "Waiting for qwen2.5:7b…" (a local model can show this for a minute while it loads);
- "Writing… 40 words";
- "Checking 1 proposal with HermiT…";
- "Done in 3.2 s", "Stopped." or why it failed.

**Stop** sits beside it while an answer is coming. What has arrived stays.

The conversation is kept in memory, per ontology, until you press **Clear** or close Protégé.

### Saving conversations in the project

Settings → **This project** → **Save conversations in this project** writes each conversation to
`assistant/conversations/` next to the ontology, after every answer. It is off by default. The
switch is kept in `assistant/project.properties`, so it reaches the whole team through git. The
box can be ticked once the ontology is saved to a file.

What happens to the files in git is each person's own choice. When you tick the box, OntoBoard
asks you about your own conversations:

- **Keep private:** yours go to `assistant/conversations/`, which `assistant/.gitignore` keeps out
  of git, so they stay on this computer.
- **Release with the project:** yours go to `assistant/conversations/released/`, which git sees,
  so they go into the repository and its releases when you commit. Anyone who can read the
  repository can read them.
- **Cancel:** the box stays off, and nothing is written.

Your answer is kept in your own Protégé preferences, not in the project, under the project's real
folder: a project opened through a link or a junction is the same project. A colleague's choice
never decides yours, and yours never decides theirs. If saving reaches you switched on by someone
else, your conversations stay out of git until you choose. The first save of each conversation
tells you where it went.

OntoBoard commits nothing itself. Keys are removed from the files, but everything else stays as
written, including what you typed and what the model answered.

The line under the box shows your choice, and **Change…** asks again. A change applies to
conversations from then on:

- Conversations you released before stay in `conversations/released/`. One that is already
  committed stays in the repository until you remove it with `git rm --cached`.
- Conversations you kept private stay private.
- If you switch while a conversation is open, switching to private stops its released copy at
  once and the rest stays private. Switching to Release releases only what is said afterwards.
- The same holds when saving goes on, or back on, while a conversation is open: what was said
  while nothing was saved stays private, and the first save says so.
- What counts is how things stood when a question was asked: a question asked while nothing was
  saved, or before you chose Release, stays private even if you change the switch while its
  answer comes. So does a question that could not be saved because the rules below could not
  be put in place.
- The private file is always the whole conversation; a released file holds only what was said
  after the release. When a conversation that has a released part goes private, the notice names
  that part's file, because git still sees it.

OntoBoard also writes the same two rules into your own clone's `.git/info/exclude`, which is never
committed: into each clone it can find where git would list your conversations. Those are the
clones found from its real path (your project's own clone, tried first), from the folder as you
opened it, and through every junction on the way, followed to where it really is with the rest
as you opened it. Git does not go into a directory symbolic link (`mklink /D` on Windows, `ln -s`
elsewhere), though Git for Windows goes into a junction: so OntoBoard takes the folder as you
opened it with each such link replaced by its target as written in it - a relative target
counted, as Windows counts it, from the folder that holds the link as the path reaches it, a
junction kept by its own name - and a clone that holds one gets no rules from it, and no warning
about it. A junction on the way that target names is followed like any other, so the clone that
holds it gets the rules (`B\S` a symbolic link to `C\c\J`, where `J` is a junction in clone `C`
to the project). Past eight such links, or at a link that leads back to itself, OntoBoard follows
the link to where it really is instead; in a circle that fails, and it says so. So a project
reached through a link or a junction is
covered, and so are a junction inside your clone that
points to a submodule's top folder and every clone along a chain of junctions (`A\j1` leading to
`B\x`, and `B\x\j2` to `C\proj`, opened as `A\j1\j2`). A `.git` that is no repository, such as an
empty folder or a submodule's `.git` file seen through a junction, is passed over, as git passes
over it. A `[` or `*` in a folder's name counts as itself. Two kinds of clone get no rules: one
that holds a link to the project, which you did not open it through, and one passed through only
by a junction's target as written in it (a junction made to `B\x\j2\sub`, where `B\x\j2` is
itself a junction), because Java gives OntoBoard a junction's target only fully resolved; a
symbolic link's target it reads as written.
Nor is a project opened through a `subst` drive, or a network drive whose root lies inside a
clone, followed above that root: open it by its own path.
If a colleague deletes the shared `assistant/.gitignore`, your private conversations still stay
out of git, as long as OntoBoard can write your clone's `.git/info/exclude`. When it cannot - the
file is read-only, another program holds it locked, or it belongs to another account - OntoBoard
saves anyway and tells you every file that could not be written and why, your project's own
clone first, each once per conversation and by its real path, and Settings says the same when you
tick **Save conversations**: make a read-only one writable, and on Windows close the program that
holds a locked one open; for any other reason, such as a full disk, the reason is all it says.
When a link or a junction on the way cannot be followed, OntoBoard says that too, in the same
message as any file that could not be written, as a clone it leads into may lack the rules. If a `.gitignore` inside `assistant/conversations/` takes files back into git,
OntoBoard saves nothing and tells you why, once per conversation, naming that file. While it
does, git may also see the conversations saved there before.

Each file is named after when it started and the connection, for example
`2026-11-06-143012-ollama.md`, and starts with:

- when it started, the OntoBoard version, and the ontology's IRI and version IRI;
- the connection (kind, host, model) and the role.

Each turn records when it was asked, what context went with it, which model answered, how long it
took and the tokens. Each suggestion is listed with what you did with it: accepted, rejected,
revision asked, or why it could not be applied.

The **History** tab lists this project's saved conversations, newest first, and shows the one you
pick. **Save…** still writes the conversation you see to a file of your choice.

## Asking

- **The term's lines.** Every label, definition and axiom of the selected term is listed with a
  ✦ button.
  - **Ask about this line** asks what the line says and whether it is well written.
  - **Suggest a better wording** (on a label, definition or comment) asks for a new version as a
    card.
  - **Explain this axiom** asks what an axiom means, what follows from it, and what it does not
    say.
  - **Suggest a better axiom** asks for a replacement for it, as a card.
- **Suggest a definition** (**Write a definition** when there is none) asks for a
  genus-differentia definition, "A ⟨parent⟩ that …", with the term's asserted parent as the
  genus. It comes as a card.
- **Write as OWL…** asks you for a sentence about the term, for example "Every vegetarian pizza
  has at least one cheese topping". The assistant answers with the axiom as a card, with `some`
  where something must exist and `only` where it restricts; `min`, `max` or `exactly` where the
  sentence gives a number; and `value` where it names one individual or value. The other terms
  your sentence names, such as "cheese topping", are added as chips. That way the model sees
  what each one is, for example that "has topping" is a property, and uses it instead of making
  up a name.
  - You can remove these chips like any other.
  - Your next sentence's chips replace them; chips you added yourself stay.
  - If a term doesn't fit the model's context length, its chip is left out, and a notice names
    it. The model can still ask for it.
  - If nothing is sent, no chip is added, and the dialog offers your sentence again.
- **Explain the reasoner problem…** runs the reasoner Protégé is running. If Protégé has none
  selected, it runs HermiT and says so.
  - If the ontology is inconsistent, the reasoner's justification is added as a chip and the
    assistant explains it.
  - If classes are unsatisfiable, you pick one first.
  - When the selected class is unsatisfiable, **Why is this unsatisfiable?** does the same for it
    directly. The button follows Protégé's last classification, so the reasoner is asked first:
    a class that is satisfiable now is said to be, and nothing is sent; if the ontology is
    inconsistent now, the inconsistency is explained instead.
  - The assistant is asked which axiom is most likely the mistake, and for the repair as a card:
    removing the axiom, or replacing it when it is right in spirit but wrong in a detail.
  - The problem chip holds what the reasoner found when you asked. Once the ontology's logic has
    changed - you accepted the repair, or edited an axiom or an import - the reasoner is asked
    again: right after you accept a change, and before the chip goes with another question. If
    the problem is gone, the chip is removed and a notice says so, and the next question tells
    the model once what the reasoner found, as the conversation still holds the question about
    the problem. If it is still there, the chip
    is computed again. If the reasoner cannot run, the chip goes as it was, with a first line
    saying it was not checked again, and a notice says why. A label or a comment is no such
    change: it cannot change what the reasoner finds. **What is sent** shows the chip as it was,
    with a line saying the reasoner is asked again first. When **Explain the reasoner problem…**
    finds the ontology consistent, the chip about the inconsistency goes at once.
- **When the assistant needs more.** It is told not to guess. When it needs a term it has not
  been shown, it says so, and OntoBoard offers **Add … and ask again**.
  - The names are looked up exactly in your ontology.
  - Names that are not there are listed as "not in this ontology".
  - A name two terms share is listed as ambiguous. Neither is added.
  - Nothing is added unless you click.

A plain question works too. "Write a definition for Concept" gets its answer as a card, because
the fixed rules tell the model to write any change as a proposal.

## Suggestions: Accept, Reject, Revise

A suggestion is a card under the answer:

- **What would change.** The value it replaces is struck through, with the new one under it. For
  logic, a removed axiom is struck through and an added one shown under it.
- **Why**, in the model's words.
- **For logic, what the reasoner says.** Before you decide, OntoBoard runs the reasoner Protégé
  uses on a copy with the change. The card says what follows, first anything that breaks:
  - "HermiT, on a copy with this change: Margherita, cheese topping become unsatisfiable.";
  - "… the ontology becomes consistent.";
  - "… the ontology stays consistent, and no class becomes unsatisfiable."
- **The quantifier trap, said.** An added `only` on a property for which the class has no `some`
  gets a note: `only` alone says what a value may be, not that there is one.
- **Accept** applies it: one change, undone with *Edit → Undo*. It is checked once more against
  the ontology as it is now. If you changed the term meanwhile, the card says so instead of
  applying something you did not see.
- **Reject** leaves the ontology as it is.
- **Revise…** asks for a different version in your words. The new answer brings a new card.

Decided cards stay in the conversation, marked.

What a suggestion can change:

- **set** replaces a label, definition or comment in its language;
- **add** adds a synonym, an example or a comment;
- **remove-axiom** removes one axiom, which must match an axiom of the ontology exactly as the
  assistant was shown it;
- **add-axiom** adds one axiom, read with the OWL API's Manchester parser over your ontology's
  names. A name that is not in the ontology is reported, never declared. A SWRL rule cannot be
  written this way, but it can be removed by copying it exactly;
- **replace-axiom** removes one axiom, as shown, and adds another in one change.

The definition property is the one the term or the ontology already uses (`IAO:0000115` or
`skos:definition`).

A suggestion that cannot be applied as written is shown with the reason and no Accept. That
covers:

- a term that is not in the ontology, or is ambiguous;
- an empty value, or a label over two lines;
- an axiom that matches nothing;
- a value that lives in an imported ontology (change it there, not by adding a second one
  here);
- a term whose value changed since the suggestion was made, so Accept would replace something
  the card did not show.

The reasoner's check runs beside the conversation, so you can ask the next question while it
works. Each reasoning gives up after a minute and says so. **Stop** interrupts the one under way
and skips the rest. A reasoner that does not stop when asked is left to finish in the
background; its card is not kept waiting for it.

A card shows the axiom as OntoBoard will write it, using the ontology's names. If the model used
a synonym, or left out the quotes around a name with a space, the card shows the term that was
understood.

**Provenance.** What you accept is annotated `dcterms:contributor "OntoBoard assistant (ollama
qwen2.5:7b)"`, so the ontology records which changes a model suggested. Settings → **Name the
model on what I accept** switches it off.

## Roles

- **Ontology expert:** OWL, BFO and OBO conventions, and the quantifier traps.
- **Knowledge engineer:** asks you one question at a time before suggesting anything.
- **Domain expert:** brings knowledge of the field you name in **Your field**.

Each role is plain text you can read and edit in Settings. **Discard** throws away a text you
typed and have not saved, **Reset** brings back the original, and **New role…** adds your own. A
role is capped at 8,000 characters. **New role…** refuses, in any capitals, a name that a role
saved in this place already has - even when its file is named otherwise, such as `rev.md` headed
`# Reviewer`, or it is a built-in role whose heading you edited - and a name whose file is already
there. A role the list shows as not there yet is not checked.
Where file names differ only in capitals are one file - on Windows and macOS - a role of your own
whose file name differs only in capitals, such as a team's `Reviewer.md`, is the same role: a
text typed for reviewer is saved into `Reviewer.md`, with "Where edits go has changed" when it was
typed for another place. On Linux, and in Protégé's preferences, `reviewer` and `Reviewer` are two
roles, and Save adds `reviewer.md` beside the team's file.

Where an edited or new role is saved:

- next to the ontology, in `assistant/roles/`, so the team shares it;
- in your Protégé preferences if the ontology has no file yet.

The line under the role text always says which, naming the ontology's folder, so two ontologies
never read the same. Saving the ontology for the first time, or with Save As to another folder,
updates it even while Settings stays open; a role text you typed and have not saved is kept, and
the line starts "Where edits go has changed". If **Save** is pressed while the line still names
the old place, nothing is written: the line says where edits now go, and the next Save writes
there.

A role text you typed and have not saved is never thrown away by going elsewhere. Picking another
role, or **New role…**, puts it aside for its role, and the line says "The text you typed for X is
kept: pick it to go on or to Discard it." When another ontology becomes active, the role section
shows that
ontology's own role, and the line says the text you typed for the other ontology is kept until it
is active again. Each role in each project keeps its own text. Picking the role brings the text
back, and so does any later reading of the roles that shows that role - after Save, Reset, Delete
or New role, or when the ontology is read again: the line then says "The text you typed for X is
shown again: it is not saved yet." Clicking the role already shown, or typing its first letter in
the list, changes nothing. **Discard**, beside Save, is on while the text shown differs from the
role's saved text: it drops that text without keeping it, shows the saved text and writes no file;
a second text typed for that role then comes back, and the line says it is shown again; when that
text was typed for another place, the line starts "Where edits go has changed", so the next Save
is made knowing where it writes. When two roles in the list have the same name, the line names
each by its file, as in "Reviewer (reviewer-2.md)", or by its id while the ontology has no file.
The list does too, and puts each one's number among them first - "1 · Reviewer (reviewer.md)",
"2 · Reviewer (reviewer-2.md)" - so a narrow column that cuts off the file still tells them
apart. They are numbered in the order of their ids, which are the file names without `.md`
(reviewer before reviewer-2), and a role that is not there yet comes after them. The list's
tooltip names the selected role's file ("File: reviewer-2.md", or "Id: reviewer-2, in Protégé's
preferences"), and each row of the open list names its own. A heading is always shown as text:
one that starts with `<html>` is not rendered. A role of your own that
the folder does not hold yet says "X is not
there yet: Save adds it", and a text typed for another place says "Where edits go has changed".
Save As takes the texts put aside for the ontology to its new folder. While the roles cannot be
read, for example when another program has locked a role file, no role is shown and nothing can
be typed or saved; the line says the texts you typed are kept, and Settings reads the roles again
the next time it is shown. These texts are kept while Protégé runs.

OntoBoard adds a few fixed rules to every role. You see them in "What is sent":

- the assistant suggests and you decide;
- "I don't know" is a good answer;
- it never writes an IRI it was not shown;
- it asks for missing terms instead of guessing;
- it writes every change as a proposal, in a fixed format OntoBoard reads.

## Comparing models

The repository has a benchmark that runs any mix of models on the same test cases and writes a
table. It uses the assistant's own code. It checks what the model produces, not only that the
mechanics work:

- **explain-problem**: does the explanation name the clash?
- **context-request**: does the model ask for a term it was not shown?
- **definition**: is the definition a valid card for the right term, and does it name the
  genus? One card, as the request asks: an answer that offers a second card a person could
  accept does not pass; the same block repeated, word for word, with other spacing or with an
  invisible character such as a soft hyphen, counts as one card.
- **repair**: are its removals, replacements and additions applied to a copy and checked by the
  reasoner, round by round, until the problem is solved or three rounds are used? Did it change
  the fault, or something the ontology needed?
- **text-to-axiom**: given a sentence such as "Every C p at least one F", does the axiom the
  model writes say exactly that, according to the reasoner, without making a class empty? An
  axiom that says more does not pass, and neither does `only` where the sentence meant `some`,
  nor an answer that offers a second card a person could accept (the same axiom twice counts
  once), whichever comes first.
  The sentences also say "at least 2", "at most 1", "exactly 2", a value such as "iron", and a
  typed value such as "the decimal value 7.85".

The cases come from the pizza and pizza-bfo examples, from NFDIcore and MWO, and from five small
fixtures written for the benchmark. See
[benchmarks/assistant](https://github.com/ebrahimnorouzi/ontoboard/tree/main/benchmarks/assistant).

## Limits

- **What it can change is small on purpose:** annotations (label, definition, comment, synonym,
  example) and one axiom at a time (added, removed or replaced). It does not yet build a
  hierarchy or import terms.
- **The axioms it reads are the ones Manchester syntax writes for a single axiom:** SubClassOf,
  EquivalentTo, DisjointWith, Type, Domain, Range, SubPropertyOf, InverseOf, SameAs,
  DifferentFrom, property values, characteristics (`Transitive: p`) and the n-ary
  `DisjointClasses:` and `EquivalentClasses:`. Anything else is refused, not guessed.
- **Its answers come from a model and can be wrong.** Check them against the ontology and the
  reasoner. A good answer is allowed to say "I don't know" or "this needs a domain expert".
- **Protégé 5.5.0 on macOS cannot reach the cloud models.** Its bundled Java cannot check their
  certificates, and macOS offers no store to fall back on. The same is true for tool downloads.
  Ollama on the same machine works.
- **Plain http is refused except to two places:** your own machine, or a server you marked as
  "on my network". Settings then warns that requests to it are not encrypted.
- **Token counts are estimates** (a quarter of the characters). Each model counts its own way.
