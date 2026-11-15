package de.fizkarlsruhe.ise.ontoboard.assistant.session;

import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Assembled;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NameResolver;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.NeedBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RenderedItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.RequestAssembler;
import de.fizkarlsruhe.ise.ontoboard.assistant.chat.Turn;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ChatRequest;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Connection;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ConnectionKind;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelCallException;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClient;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.ModelClients;
import de.fizkarlsruhe.ise.ontoboard.assistant.connect.Reply;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextBuilder;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.ContextItem;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Problems;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.TermLine;
import de.fizkarlsruhe.ise.ontoboard.assistant.context.Tokens;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.Proposal;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalBlock;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalChanges;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalCheck;
import de.fizkarlsruhe.ise.ontoboard.assistant.proposals.ProposalValidator;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.FixedRules;
import de.fizkarlsruhe.ise.ontoboard.assistant.roles.RoleLibrary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * One conversation about one ontology: its context chips, its turns, and every check made before
 * anything leaves the machine.
 *
 * <p>The ontology changes in one place only: {@link #accept}, which the person presses on a
 * proposal card. It validates the proposal again against the ontology as it is now, builds one
 * change list, and hands it to the {@link ProposalApplier}; nothing else here writes.
 *
 * <p>Before a request is sent, in this order: a connection must be set up; a project marked
 * "local models only" refuses a connection that is not local; an Ollama connection refuses a
 * request bigger than its context length, rather than letting Ollama cut it silently; and the
 * first request of the session to a cloud model is confirmed by the person. Each refusal is a
 * notice and nothing is sent.
 *
 * <p>Threads: every method is called on the UI executor, the context is rendered there (the OWL
 * API is not safe to read while Protégé edits), only the model call runs on the background
 * executor, and every delta comes back through the UI executor in order.
 */
public final class AssistantSession {

    private final OntologyAccess access;
    private final Executor background;
    private final Executor ui;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<SessionListener>();
    private final List<ContextItem> added = new ArrayList<ContextItem>();
    /** The chips the last sentence sent with "Write as OWL…" added; the next one replaces them. */
    private final List<ContextItem> sentenceChips = new ArrayList<ContextItem>();
    private final List<Turn> turns = new ArrayList<Turn>();

    private ClientFactory clientFactory = ModelClients::create;
    private Confirmer confirmer;
    private Connection connection;
    private String apiKey = "";
    private String roleName = "Ontology expert";
    private String roleText = RoleLibrary.shipped("ontology-expert");
    private boolean localModelsOnly;
    /** Consent is to a destination: a second cloud host is asked about again (review #4). */
    private final java.util.Set<String> confirmedHosts = new java.util.HashSet<String>();
    /**
     * Each chip's text, rendered once per version of the ontology (review #2): the column asks
     * for sizes and previews at every selection change, and a whole-ontology chip rendered on the
     * event thread each time froze the editor on a large ontology.
     */
    private final java.util.Map<ContextItem, String> rendered =
            new java.util.HashMap<ContextItem, String>();
    private int renders;
    private boolean busy;
    private ModelClient current;
    private String lastMessage;

    private ProposalApplier applier;
    private boolean provenance = true;
    private OWLReasonerFactory reasonerFactory;
    private String reasonerName = "";
    /** Which turn each proposal belongs to, by id: its status lives on the turn. */
    private final Map<Integer, Turn> proposalTurns = new HashMap<Integer, Turn>();
    private final Map<Integer, ProposalCheck.Check> checks =
            new HashMap<Integer, ProposalCheck.Check>();
    /** Where reasoner checks run; the model's executor until the host gives them their own. */
    private Executor checkExecutor;
    /** Checks under way, by answer; {@link #stop()} cancels them. Touched on the UI executor. */
    private final List<ProposalCheck.Run> runs = new ArrayList<ProposalCheck.Run>();
    /**
     * How many times the logic of the ontology has changed, and the count at which each problem
     * chip was explained. A chip is what the reasoner found then: every later question told the
     * model the ontology was inconsistent - after the repair was accepted too - without asking
     * the reasoner whether it still was (1.151.0, asked by the person). One from before the last
     * change is asked about again before it is sent.
     */
    private int version;
    private final Map<ContextItem, Integer> explainedAt = new HashMap<ContextItem, Integer>();
    /** The asking again under way, or null; Stop clears it, and what it finds is then dropped. */
    private Object asking;
    /** The question waiting for it, sent once the reasoner has answered. */
    private String waiting;
    /**
     * What the reasoner found when it was asked again and a problem was gone, said once in the
     * next request: the conversation still holds the question about it and its answer, and with
     * the chip gone and nothing said a model went on as if the problem were there. Dropped when
     * the logic changes again, as it may no longer hold.
     */
    private String foundAgain;
    /** For the status line: removals still being checked, and the answer's words so far. */
    private int pendingChecks;
    private int words;
    private boolean inWord;

    public AssistantSession(OntologyAccess access, Executor background, Executor ui) {
        this.access = access;
        this.background = background;
        this.ui = ui;
    }

    /** Without one, Accept changes nothing and says so. */
    public void setProposalApplier(ProposalApplier applier) {
        this.applier = applier;
    }

    /** Whether what Accept adds names the model, as {@code dcterms:contributor} (spec D5). */
    public void setProvenance(boolean provenance) {
        this.provenance = provenance;
    }

    /** The reasoner a removal is checked with; null for no checks. */
    public void setReasoner(OWLReasonerFactory factory, String name) {
        this.reasonerFactory = factory;
        this.reasonerName = name == null ? "" : name;
    }

    /**
     * Reasoner checks on an executor of their own (review I4), so the next question is not
     * queued behind a long classification.
     */
    public void setCheckExecutor(Executor executor) {
        this.checkExecutor = executor;
    }

    /** What the reasoner said about a removal, once checked; null until then. */
    public ProposalCheck.Check checkOf(Proposal proposal) {
        return proposal == null ? null : checks.get(proposal.getId());
    }

    /**
     * An answer in flight is stopped only when the connection itself changed: a Settings tick
     * that leaves it as it was must not cut an answer off (round-1 M4, 1.137.0 review).
     */
    public void setConnection(Connection connection, String apiKey) {
        boolean changed = !java.util.Objects.equals(this.connection, connection);
        this.connection = connection;
        this.apiKey = apiKey == null ? "" : apiKey;
        if (changed && busy) {
            stop();
        }
    }

    /** @param renderedText the role's text with its field filled in */
    public void setRole(String name, String renderedText) {
        this.roleName = name == null ? "" : name;
        this.roleText = renderedText == null ? "" : renderedText;
    }

    public void setLocalModelsOnly(boolean localModelsOnly) {
        this.localModelsOnly = localModelsOnly;
    }

    /** Without one, nothing is sent to a cloud model: there is nobody to ask. */
    public void setConfirmer(Confirmer confirmer) {
        this.confirmer = confirmer;
    }

    public void setClientFactory(ClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public Connection getConnection() {
        return connection;
    }

    public String getRoleName() {
        return roleName;
    }

    public boolean isBusy() {
        return busy;
    }

    public List<Turn> turns() {
        return Collections.unmodifiableList(new ArrayList<Turn>(turns));
    }

    /** The selected term first, following the selection; then what the person added. */
    public List<ContextItem> context() {
        List<ContextItem> items = new ArrayList<ContextItem>();
        IRI selected = access.selected();
        if (selected != null) {
            items.add(ContextItem.term(selected, true));
        }
        for (ContextItem item : added) {
            if (!items.contains(item)) {
                items.add(item);
            }
        }
        return items;
    }

    /**
     * Adds a chip. A reasoner problem with the same title replaces the one there (review #1):
     * explaining again after a fix must send the new justification, not keep the old one.
     */
    public void addContext(ContextItem item) {
        if (item != null && item.getKind() == ContextItem.Kind.PROBLEM) {
            added.remove(item);
            added.add(item);
            explainedAt.remove(item);
            explainedAt.put(item, version);
        } else if (item != null && !added.contains(item)) {
            added.add(item);
        }
        // A chip the person adds is theirs, even when a sentence had added it already: the next
        // sentence leaves it (1.145.0, review 1.143.0 M-6).
        sentenceChips.remove(item);
        fireContextChanged();
    }

    /** The ontology changed: every chip is rendered again the next time it is needed. */
    public void ontologyChanged() {
        ontologyChanged(true);
    }

    /**
     * As {@link #ontologyChanged()}; a change to the logic - an axiom that is not an annotation
     * or a declaration, an import - also leaves each problem chip to be asked about again before
     * it is sent. An annotation cannot make the ontology consistent or a class satisfiable.
     */
    public void ontologyChanged(boolean logic) {
        rendered.clear();
        if (logic) {
            version++;
            foundAgain = null;
        }
    }

    /** Estimated tokens a chip adds, from the cache. */
    public int sizeOf(ContextItem item) {
        return Tokens.estimate(render(item));
    }

    /** How many times a chip was actually rendered, for tests. */
    int renders() {
        return renders;
    }

    private String render(ContextItem item) {
        return render(item, false);
    }

    /**
     * @param preview for "What is sent", where a problem chip from before the last change says
     *     it is asked about again first: shown as it was, it told the person reading it that the
     *     ontology was inconsistent though it no longer was
     */
    private String render(ContextItem item, boolean preview) {
        if (item.getKind() == ContextItem.Kind.PROBLEM) {
            return preview && reasonerFactory != null && isStale(item)
                    ? "The ontology was edited after this was computed: when you send, "
                            + (reasonerName.isEmpty() ? "the reasoner" : reasonerName) + " is"
                            + " asked again first, and this is left out if the problem is gone.\n"
                            + item.getText()
                    : item.getText();
        }
        String text = rendered.get(item);
        if (text == null) {
            text = ContextBuilder.render(access.ontology(), item);
            rendered.put(item, text);
            renders++;
        }
        return text;
    }

    /** The chip that follows the selection stays; it changes when the selection does. */
    public void removeContext(ContextItem item) {
        if (item == null || item.isFollowingSelection()) {
            return;
        }
        added.remove(item);
        sentenceChips.remove(item);
        explainedAt.remove(item);
        fireContextChanged();
    }

    /** Every chip but the one that follows the selection (1.142.0, review 1.141.0 I1). */
    public void removeAllContext() {
        added.clear();
        sentenceChips.clear();
        explainedAt.clear();
        fireContextChanged();
    }

    /** What sending this draft now would send, for "What is sent". */
    public Assembled preview(String draft) {
        return fitted(draft, new ArrayList<String>(), true);
    }

    /** Whether a problem chip was explained before the logic last changed. */
    private boolean isStale(ContextItem item) {
        Integer at = explainedAt.get(item);
        return at == null || at != version;
    }

    /** The problem chips explained before the logic last changed, in the order shown. */
    private List<ContextItem> stale() {
        List<ContextItem> stale = new ArrayList<ContextItem>();
        for (ContextItem item : added) {
            if (item.getKind() == ContextItem.Kind.PROBLEM && isStale(item)) {
                stale.add(item);
            }
        }
        return stale;
    }

    /** The first line a problem chip gets when the reasoner could not be asked again. */
    private static final String NOT_CHECKED =
            "Not checked again since the ontology was last edited: ";

    /**
     * Asks the reasoner about each chip again, on a copy taken here, where the ontology may be
     * read: then {@link #askedAgain} keeps it as found now, or drops it, and sends the question
     * waiting, if any.
     */
    private void askAgain(final List<ContextItem> stale) {
        final OWLReasonerFactory factory = reasonerFactory;
        final String name = reasonerName.isEmpty() ? "The reasoner" : reasonerName;
        final OWLOntology ontology = access.ontology();
        final Set<OWLAxiom> snapshot = ProposalCheck.snapshot(ontology);
        final OWLOntologyID id = ontology.getOntologyID();
        final int at = version;
        final Object token = new Object();
        asking = token;
        if (waiting != null) {
            busy = true;
        }
        status("The ontology changed since the problem was explained: " + name + " checks it"
                + " again…", true);
        Runnable work = () -> {
            List<ContextItem> found = new ArrayList<ContextItem>();
            String failure = null;
            try {
                OWLOntology copy = copyOf(id, snapshot);
                for (ContextItem problem : stale) {
                    found.add(Problems.now(copy, factory, problem.getSubject(), name));
                }
            } catch (Throwable failed) {
                failure = failed.getMessage() == null ? failed.getClass().getSimpleName()
                        : failed.getMessage();
            }
            final List<ContextItem> now = found;
            final String why = failure;
            ui.execute(() -> askedAgain(token, stale, now, why, at, name));
        };
        try {
            (checkExecutor != null ? checkExecutor : background).execute(work);
        } catch (RuntimeException refused) {
            askedAgain(token, stale, new ArrayList<ContextItem>(), "it could not start: "
                    + refused, at, name);
        }
    }

    private static OWLOntology copyOf(OWLOntologyID id, Set<OWLAxiom> axioms)
            throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology copy = manager.createOntology(id);
        manager.addAxioms(copy, axioms);
        return copy;
    }

    /**
     * What the reasoner found: each chip as it finds the problem now, or gone; or, when it could
     * not run, each chip as it was, saying so. Then the question waiting is sent.
     */
    private void askedAgain(Object token, List<ContextItem> stale, List<ContextItem> found,
            String failure, int at, String name) {
        if (asking != token) {
            return;
        }
        asking = null;
        List<String> gone = new ArrayList<String>();
        List<String> solved = new ArrayList<String>();
        for (int i = 0; i < stale.size(); i++) {
            ContextItem old = stale.get(i);
            int index = added.indexOf(old);
            if (index < 0) {
                continue;
            }
            explainedAt.remove(old);
            ContextItem now;
            if (failure != null) {
                String text = old.getText().startsWith(NOT_CHECKED)
                        ? old.getText().substring(old.getText().indexOf('\n') + 1)
                        : old.getText();
                now = ContextItem.problem(old.getTitle(), NOT_CHECKED + name + " could not run ("
                        + failure + "). This is the problem as it was before.\n" + text,
                        old.getSubject());
            } else {
                now = found.get(i);
            }
            if (now == null) {
                added.remove(index);
                gone.add("\"" + old.getTitle() + "\"");
                solved.add(old.getSubject() == null ? "the ontology consistent"
                        : ContextBuilder.titleOf(access.ontology(),
                                ContextItem.term(old.getSubject(), false)) + " satisfiable");
            } else if (!now.equals(old) && added.contains(now)) {
                // A class's problem that gave way to the inconsistency, shown already.
                added.remove(index);
            } else {
                added.set(index, now);
                explainedAt.put(now, at);
            }
        }
        fireContextChanged();
        if (failure != null) {
            notice(name + " could not check the problem again (" + failure + "): it is sent as it"
                    + " was explained, saying so.");
        } else if (!gone.isEmpty()) {
            notice(name + " finds no such problem now, so " + String.join(" and ", gone)
                    + (gone.size() == 1 ? " is" : " are") + " no longer sent.");
            if (at == version) {
                foundAgain = name + ", asked again after the ontology changed, finds "
                        + String.join(" and ", solved) + " now.";
            }
        }
        String message = waiting;
        waiting = null;
        busy = false;
        if (message != null) {
            send(message, false);
        } else {
            status(name + " checked the problem again.", false);
        }
    }

    /**
     * The request, with older turns left out if an Ollama connection's context could not
     * otherwise hold it and an answer (review #6): num_ctx covers both, and a prompt that fills
     * it is cut by Ollama in the middle of the answer - the role and fixed rules first.
     */
    private Assembled fitted(String message, List<String> titles) {
        return fitted(message, titles, false);
    }

    /** @param preview for "What is sent": see {@link #render(ContextItem, boolean)} */
    private Assembled fitted(String message, List<String> titles, boolean preview) {
        List<String> first = new ArrayList<String>();
        Assembled assembled = assemble(message, first, RequestAssembler.HISTORY_BUDGET_TOKENS,
                preview);
        if (connection != null && connection.getKind() == ConnectionKind.OLLAMA) {
            int room = connection.getContextLength() - connection.getMaxOutputTokens();
            int tokens = assembled.getEstimatedTokens();
            if (tokens > room) {
                List<de.fizkarlsruhe.ise.ontoboard.assistant.connect.Message> messages =
                        assembled.getRequest().getMessages();
                int history = 0;
                for (int i = 0; i < messages.size() - 1; i++) {
                    history += Tokens.estimate(messages.get(i).getText());
                }
                int budget = Math.max(0, room - (tokens - history));
                first.clear();
                assembled = assemble(message, first, budget, preview);
            }
        }
        titles.addAll(first);
        return assembled;
    }

    public void send(String message) {
        send(message, true);
    }

    /**
     * @param askAgain whether a problem chip explained before the logic last changed is asked
     *     about again first: false once it was, for this question
     */
    private void send(String message, boolean askAgain) {
        if (busy || message == null || message.trim().isEmpty()) {
            return;
        }
        if (connection == null) {
            notice("Set up a model in the Settings tab first.");
            return;
        }
        if (localModelsOnly && !connection.isLocal()) {
            notice("This project allows local models only (assistant/project.properties), and "
                    + connection.getName() + " sends to " + connection.getHost() + ".");
            return;
        }
        if (askAgain && asking != null) {
            // Asked already, since a change was accepted: the question waits for the answer.
            waiting = message;
            busy = true;
            return;
        }
        if (askAgain && reasonerFactory != null) {
            List<ContextItem> stale = stale();
            if (!stale.isEmpty()) {
                waiting = message;
                askAgain(stale);
                return;
            }
        }
        List<String> titles = new ArrayList<String>();
        Assembled assembled = fitted(message, titles);
        int tokens = assembled.getEstimatedTokens();
        if (connection.getKind() == ConnectionKind.OLLAMA
                && tokens + connection.getMaxOutputTokens() > connection.getContextLength()) {
            notice(String.format(Locale.ROOT, "This request is about %,d tokens and %s has a"
                    + " context length of %,d, which must also hold an answer of up to %,d"
                    + " tokens. Remove some chips (× on a chip, or Remove all), clear the"
                    + " conversation, or raise the context length in Settings.", tokens,
                    connection.getName(),
                    connection.getContextLength(), connection.getMaxOutputTokens()));
            return;
        }
        if (!connection.isLocal() && !confirmedHosts.contains(connection.getHost())) {
            if (confirmer == null
                    || !confirmer.confirmCloudSend(connection.getHost(), tokens, subject())) {
                notice("Not sent.");
                return;
            }
            confirmedHosts.add(connection.getHost());
        }
        final Turn turn = new Turn(message, titles, connection.getName(),
                connection.getKind().getId() + " " + connection.getModel());
        turns.add(turn);
        // Said once: this request carries it.
        foundAgain = null;
        lastMessage = message;
        busy = true;
        final ModelClient client = clientFactory.create(connection, apiKey);
        current = client;
        for (SessionListener listener : listeners) {
            listener.onTurnStarted(turn);
        }
        final ChatRequest request = assembled.getRequest();
        words = 0;
        inWord = false;
        status(String.format(Locale.ROOT, "Sending ~%,d tokens to %s…", tokens,
                connection.getHost()), true);
        final String waiting = "Waiting for " + (connection.getModel().isEmpty()
                ? connection.getName() : connection.getModel()) + "…";
        Runnable call = () -> {
            try {
                ui.execute(() -> status(waiting, true));
                final Reply reply = client.chat(request, delta -> ui.execute(() -> {
                    turn.append(delta);
                    countWords(delta);
                    for (SessionListener listener : listeners) {
                        listener.onText(turn, delta);
                    }
                    status("Writing… " + words + (words == 1 ? " word" : " words"), true);
                }));
                ui.execute(() -> finish(turn, reply));
            } catch (final ModelCallException failure) {
                ui.execute(() -> fail(turn, failure));
            } catch (final RuntimeException unexpected) {
                ui.execute(() -> fail(turn, new ModelCallException(
                        ModelCallException.Kind.BAD_REPLY, "The answer could not be read: "
                                + unexpected, unexpected)));
            } catch (final Throwable broken) {
                // An Error (a stack overflow, a class missing from the bundle) must end the
                // turn too, or the session stays busy and every later question is ignored
                // (round-1 M7).
                ui.execute(() -> fail(turn, new ModelCallException(
                        ModelCallException.Kind.BAD_REPLY, "The assistant failed: "
                                + broken, broken)));
            }
        };
        try {
            background.execute(call);
        } catch (RuntimeException refused) {
            fail(turn, new ModelCallException(ModelCallException.Kind.BAD_REPLY,
                    "The assistant could not start the call: " + refused, refused));
        }
    }

    /** Asks about one line of the selected term. */
    public void askAboutLine(TermLine line) {
        send("About this line of " + subject() + ": " + line.getRendered()
                + "\nWhat does it say, and is it well written?");
    }

    /** Asks for one axiom in plain language. */
    public void explainLine(TermLine line) {
        send("Explain this axiom in plain language: what it says, what follows from it, and what"
                + " it does not say.\n" + line.getRendered());
    }

    /**
     * "Suggest a definition" for the selected term: genus-differentia from its asserted parent,
     * asked for as a proposal (spec, round 2).
     */
    public void suggestDefinition() {
        IRI selected = access.selected();
        if (selected == null) {
            notice("Select a term first: the definition is written for the selected term.");
            return;
        }
        OWLOntology ontology = access.ontology();
        // The imports closure, as the column's lines read it (review I2).
        boolean hasDefinition = false;
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom axiom
                    : part.getAnnotationAssertionAxioms(selected)) {
                if (Proposal.Property.DEFINITION.getIris().contains(
                        axiom.getProperty().getIRI())) {
                    hasDefinition = true;
                }
            }
        }
        send(Questions.suggestDefinition(subject(), Questions.parentsOf(ontology, selected),
                hasDefinition));
    }

    /** "Suggest a better wording" for a label, definition or comment line, as a proposal. */
    public void suggestWording(TermLine line) {
        // The value and its language as the literal has them: the line shows " (en)" after a
        // tagged value, and an untagged one can end in "(SEM)" (1.138.0 R3).
        send(Questions.betterWording(subject(), line.getKey(), line.getValue(),
                line.getLanguage()));
    }

    /**
     * "Write as OWL…": the person's sentence, asked for as one add-axiom proposal (1.139.0), with
     * the terms it names as chips (1.141.0). Since 1.142.0 (review 1.141.0 I1) they are one
     * sentence's chips at a time: the next sentence's replace them, chips the person added stay,
     * nothing is added when nothing is sent, and terms that would not fit an Ollama context are
     * left out, last first - the model can still ask for them.
     */
    public void writeAsOwl(String sentence) {
        if (sentence == null || sentence.trim().isEmpty()) {
            return;
        }
        String message = Questions.sentenceToAxiom(subject(), sentence.trim());
        // Not while an answer is coming: send() refuses then, and chips without the sentence
        // would only puzzle.
        if (busy) {
            send(message);
            return;
        }
        List<ContextItem> addedBefore = new ArrayList<ContextItem>(added);
        List<ContextItem> sentenceBefore = new ArrayList<ContextItem>(sentenceChips);
        added.removeAll(sentenceChips);
        sentenceChips.clear();
        List<ContextItem> fresh = new ArrayList<ContextItem>();
        for (IRI term : Questions.sentenceTerms(access.ontology(), access.selected(), sentence)) {
            ContextItem item = ContextItem.term(term, false);
            if (!added.contains(item)) {
                added.add(item);
                fresh.add(item);
            }
        }
        List<ContextItem> leftOut = new ArrayList<ContextItem>();
        while (!fresh.isEmpty() && !fits(message)) {
            ContextItem dropped = fresh.remove(fresh.size() - 1);
            added.remove(dropped);
            leftOut.add(0, dropped);
        }
        int turnsBefore = turns.size();
        send(message);
        if (turns.size() == turnsBefore) {
            added.clear();
            added.addAll(addedBefore);
            sentenceChips.addAll(sentenceBefore);
        } else {
            sentenceChips.addAll(fresh);
            if (!leftOut.isEmpty()) {
                // Said, not dropped silently (1.145.0, review 1.143.0 M-5).
                List<String> titles = new ArrayList<String>();
                for (ContextItem item : leftOut) {
                    titles.add(ContextBuilder.titleOf(access.ontology(), item));
                }
                notice("Left out to fit " + connection.getName() + "'s context length: "
                        + String.join(", ", titles) + ". The model can ask for them, or a larger"
                        + " context length in Settings sends them too.");
            }
        }
        fireContextChanged();
    }

    /** Whether this message, with the chips as they are, fits the connection's context. */
    private boolean fits(String message) {
        if (connection == null || connection.getKind() != ConnectionKind.OLLAMA) {
            return true;
        }
        return fitted(message, new ArrayList<String>()).getEstimatedTokens()
                + connection.getMaxOutputTokens() <= connection.getContextLength();
    }

    /** ✦ "Suggest a better axiom" on a semantics line (1.139.0). */
    public void suggestBetterAxiom(TermLine line) {
        send(Questions.betterAxiom(subject(), line.getRendered()));
    }

    /**
     * Accepts a proposal: validated again against the ontology as it is now, then applied as one
     * change list. Once only; a proposal that no longer fits becomes INVALID with the reason.
     */
    public void accept(Proposal proposal) {
        Proposal now = current(proposal);
        if (now == null || now.getStatus() != Proposal.Status.OFFERED) {
            return;
        }
        Turn turn = proposalTurns.get(now.getId());
        OWLOntology ontology = access.ontology();
        Proposal ready;
        if (now.getAction() != null && now.getAction().isLogic()) {
            // The axioms the card showed, checked as they are: reading the text again by name
            // failed once another card of the answer renamed a term it uses (review 1.139.0 I2).
            String why = ProposalValidator.stillFits(ontology, now);
            if (why != null) {
                update(turn, now.invalid(why));
                return;
            }
            ready = now;
        } else {
            ready = ProposalValidator.validate(ontology, now);
            if (ready.getStatus() != Proposal.Status.OFFERED) {
                update(turn, ready);
                return;
            }
            // Review I1: applied only as the card showed it. If the term changed meanwhile,
            // what Accept would replace is not what was struck through.
            if (!sameChange(now, ready)) {
                String what = now.getProperty() == null ? "value"
                        : now.getProperty().getWord();
                update(turn, now.invalid("The term's " + what + " changed since this was"
                        + " suggested; ask again to see a suggestion against it as it is now."));
                return;
            }
        }
        if (applier == null) {
            notice("This view cannot change the ontology; nothing was applied.");
            return;
        }
        String by = provenance ? "OntoBoard assistant (" + turn.getModel() + ")" : null;
        applier.apply(ProposalChanges.build(ontology, ready, by));
        update(turn, ready.withStatus(Proposal.Status.ACCEPTED));
        // The session's own change, heard here whatever the host's listener does. A repair
        // accepted, the chip still told the model the ontology was inconsistent: the reasoner is
        // asked at once (1.151.0, asked by the person).
        boolean logic = ready.getAction() != null && ready.getAction().isLogic();
        ontologyChanged(logic);
        if (logic && reasonerFactory != null && asking == null && !busy) {
            List<ContextItem> stale = stale();
            if (!stale.isEmpty()) {
                askAgain(stale);
            }
        }
    }

    /** The person declined it; it stays on the card, marked. */
    public void reject(Proposal proposal) {
        Proposal now = current(proposal);
        if (now != null && (now.getStatus() == Proposal.Status.OFFERED
                || now.getStatus() == Proposal.Status.INVALID)) {
            update(proposalTurns.get(now.getId()), now.withStatus(Proposal.Status.REJECTED));
        }
    }

    /**
     * "Revise your proposal for <subject> <property>: <what the person typed>".
     *
     * @return whether it was sent; not while an answer is coming, so the card keeps the text
     */
    public boolean revise(Proposal proposal, String request) {
        Proposal now = current(proposal);
        if (busy || now == null || request == null || request.trim().isEmpty()
                || (now.getStatus() != Proposal.Status.OFFERED
                        && now.getStatus() != Proposal.Status.INVALID)) {
            return false;
        }
        String what;
        if (now.getAction() == Proposal.Action.REMOVE_AXIOM) {
            what = "removing " + now.getShownAxiom();
        } else if (now.getAction() == Proposal.Action.ADD_AXIOM) {
            what = "adding " + now.getShownAxiom();
        } else if (now.getAction() == Proposal.Action.REPLACE_AXIOM) {
            what = "replacing " + now.getShownAxiom() + " with " + now.getShownWith();
        } else {
            what = now.getSubject() + " " + (now.getProperty() == null ? now.getPropertyText()
                    : now.getProperty().getWord());
        }
        int before = turns.size();
        send("Revise your proposal for " + what + ": " + request.trim());
        if (turns.size() > before) {
            update(proposalTurns.get(now.getId()), now.withStatus(Proposal.Status.REVISED));
            return true;
        }
        return false;
    }

    /** The same term, property, language and replaced values as the card showed. */
    private static boolean sameChange(Proposal shown, Proposal now) {
        return java.util.Objects.equals(shown.getSubjectIri(), now.getSubjectIri())
                && java.util.Objects.equals(shown.getMatchedAxiom(), now.getMatchedAxiom())
                && java.util.Objects.equals(shown.getNewAxiom(), now.getNewAxiom())
                && java.util.Objects.equals(shown.getPropertyIri(), now.getPropertyIri())
                && java.util.Objects.equals(shown.getLanguage(), now.getLanguage())
                // What Accept would replace now must be among what the card struck through:
                // fewer is fine (one was taken away by another card), others are not (1.138.0 R4).
                && new java.util.HashSet<String>(shown.getReplaced())
                        .containsAll(now.getReplaced());
    }

    private Proposal current(Proposal proposal) {
        Turn turn = proposal == null ? null : proposalTurns.get(proposal.getId());
        return turn == null ? null : turn.proposal(proposal.getId());
    }

    private void update(Turn turn, Proposal next) {
        if (turn != null) {
            turn.replaceProposal(next);
        }
        for (SessionListener listener : listeners) {
            listener.onProposalChanged(next);
        }
    }

    /**
     * Each offered change to the logic - a removal, an addition, a replacement - checked on
     * copies in the background. The snapshot is taken here, on the editor's thread, where the
     * ontology may be read; the ontology as it is is reasoned once for all of them.
     */
    private int check(List<Proposal> proposals, final Turn turn) {
        if (reasonerFactory == null) {
            return 0;
        }
        List<Proposal> logic = new ArrayList<Proposal>();
        for (Proposal proposal : proposals) {
            if (proposal.getAction() != null && proposal.getAction().isLogic()
                    && proposal.getStatus() == Proposal.Status.OFFERED) {
                logic.add(proposal);
            }
        }
        if (logic.isEmpty()) {
            return 0;
        }
        final OWLReasonerFactory factory = reasonerFactory;
        final String name = reasonerName.isEmpty() ? "the reasoner" : reasonerName;
        final Set<OWLAxiom> snapshot = ProposalCheck.snapshot(access.ontology());
        final ProposalCheck.Run run = new ProposalCheck.Run();
        runs.add(run);
        pendingChecks += logic.size();
        status("Checking " + logic.size() + (logic.size() == 1 ? " proposal" : " proposals")
                + " with " + name + "…", true);
        final List<Proposal> toCheck = new ArrayList<Proposal>(logic);
        Executor executor = checkExecutor != null ? checkExecutor : background;
        try {
            executor.execute(() -> {
                ProposalCheck.Verdict before = null;
                for (final Proposal proposal : toCheck) {
                    ProposalCheck.Check check;
                    // Each proposal's check ends in a verdict or a reason, whatever is thrown
                    // (review R8): a card must not say "Checking…" forever.
                    try {
                        if (run.isCancelled()) {
                            check = ProposalCheck.failed(name, "Stopped before it ran.");
                        } else {
                            if (before == null) {
                                before = ProposalCheck.before(snapshot, factory, run);
                            }
                            check = ProposalCheck.after(snapshot, before, factory,
                                    ProposalCheck.removed(proposal), proposal.getNewAxiom(),
                                    name, run);
                        }
                    } catch (Throwable failed) {
                        check = ProposalCheck.failed(name, String.valueOf(failed));
                    }
                    final ProposalCheck.Check verdict = check;
                    ui.execute(() -> checked(proposal, verdict, turn));
                }
                ui.execute(() -> runs.remove(run));
            });
        } catch (RuntimeException refused) {
            runs.remove(run);
            for (Proposal proposal : toCheck) {
                checked(proposal, ProposalCheck.failed(name, "The check could not start: "
                        + refused), turn);
            }
        }
        return logic.size();
    }

    private void checked(Proposal proposal, ProposalCheck.Check check, Turn turn) {
        checks.put(proposal.getId(), check);
        Proposal now = current(proposal);
        for (SessionListener listener : listeners) {
            listener.onProposalChecked(now == null ? proposal : now, check);
        }
        pendingChecks--;
        if (pendingChecks == 0 && !busy) {
            status(done(turn), false);
        }
    }

    private void countWords(String delta) {
        for (int i = 0; i < delta.length(); i++) {
            boolean space = Character.isWhitespace(delta.charAt(i));
            if (!space && !inWord) {
                words++;
            }
            inWord = !space;
        }
    }

    /** "Done in 3.2 s": from the question to the last check. */
    private static String done(Turn turn) {
        double seconds = (System.currentTimeMillis() - turn.getStarted()) / 1000.0;
        return seconds < 10 ? String.format(Locale.ROOT, "Done in %.1f s", seconds)
                : String.format(Locale.ROOT, "Done in %d s", Math.round(seconds));
    }

    private void status(String text, boolean working) {
        for (SessionListener listener : listeners) {
            listener.onStatus(text, working);
        }
    }

    /** The terms the model asked for, added as chips; then the same question again. */
    public void addAndAskAgain(List<NameResolver.Resolution> resolutions) {
        if (busy) {
            return;
        }
        for (NameResolver.Resolution resolution : resolutions) {
            if (resolution.isFound() && !added.contains(ContextItem.term(resolution.getIri(),
                    false))) {
                added.add(ContextItem.term(resolution.getIri(), false));
            }
        }
        fireContextChanged();
        if (lastMessage != null) {
            send(lastMessage);
        }
    }

    /**
     * Stops the answer coming, the reasoner check under way, and the checks not yet started; and
     * the asking again about a problem, with the question waiting for it, which is not sent.
     */
    public void stop() {
        for (ProposalCheck.Run run : new ArrayList<ProposalCheck.Run>(runs)) {
            run.cancel();
        }
        ModelClient client = current;
        if (client != null) {
            client.stop();
        }
        if (asking != null) {
            asking = null;
            waiting = null;
            busy = false;
            status("Stopped.", false);
        }
    }

    /** Forgets the conversation; the session's cloud confirmation stands. */
    public void clear() {
        if (!busy) {
            turns.clear();
            proposalTurns.clear();
            checks.clear();
            lastMessage = null;
        }
    }

    private Assembled assemble(String message, List<String> titles, int historyBudget,
            boolean preview) {
        OWLOntology ontology = access.ontology();
        List<RenderedItem> items = new ArrayList<RenderedItem>();
        for (ContextItem item : context()) {
            String title = ContextBuilder.titleOf(ontology, item);
            titles.add(title);
            items.add(new RenderedItem(title, render(item, preview)));
        }
        if (foundAgain != null) {
            titles.add("The reasoner, asked again");
            items.add(new RenderedItem("The reasoner, asked again", foundAgain));
        }
        int maxOutput = connection == null ? Connection.DEFAULT_MAX_OUTPUT_TOKENS
                : connection.getMaxOutputTokens();
        return RequestAssembler.assemble(roleText, FixedRules.text(),
                ContextBuilder.header(ontology), items, turns, message, maxOutput, historyBudget);
    }

    /** What a question is about, in words: the selected term's label, or "the ontology". */
    private String subject() {
        IRI selected = access.selected();
        return selected == null ? "the ontology"
                : ContextBuilder.titleOf(access.ontology(), ContextItem.term(selected, true));
    }

    private void finish(Turn turn, Reply reply) {
        turn.finish(reply);
        busy = false;
        current = null;
        List<Proposal> proposals = new ProposalValidator(access.ontology())
                .validateAll(ProposalBlock.parse(turn.getReply()));
        turn.setProposals(proposals);
        for (Proposal proposal : proposals) {
            proposalTurns.put(proposal.getId(), turn);
        }
        for (SessionListener listener : listeners) {
            listener.onTurnFinished(turn);
        }
        if (!proposals.isEmpty()) {
            for (SessionListener listener : listeners) {
                listener.onProposals(turn, proposals);
            }
        }
        if (check(proposals, turn) == 0) {
            status(done(turn), false);
        }
        List<String> names = NeedBlock.parse(reply.getText());
        if (!names.isEmpty()) {
            NameResolver resolver = new NameResolver(access.ontology());
            List<NameResolver.Resolution> resolutions = new ArrayList<NameResolver.Resolution>();
            for (String name : names) {
                resolutions.add(resolver.resolve(name));
            }
            for (SessionListener listener : listeners) {
                listener.onNeeds(resolutions);
            }
        }
    }

    private void fail(Turn turn, ModelCallException failure) {
        boolean stopped = failure.getKind() == ModelCallException.Kind.STOPPED;
        turn.fail(stopped ? "Stopped." : failure.getMessage());
        busy = false;
        current = null;
        for (SessionListener listener : listeners) {
            listener.onTurnFinished(turn);
        }
        status(stopped ? "Stopped." : "Failed: " + failure.getMessage(), false);
        if (!stopped) {
            notice(failure.getMessage());
        }
    }

    private void notice(String message) {
        for (SessionListener listener : listeners) {
            listener.onNotice(message);
        }
    }

    private void fireContextChanged() {
        List<ContextItem> items = context();
        for (SessionListener listener : listeners) {
            listener.onContextChanged(items);
        }
    }
}
