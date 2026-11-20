package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.UnloadableImportException;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import de.fizkarlsruhe.ise.ontoboard.robot.Explanations;

/**
 * What the reasoner a release uses says about the ontology, asked before anything is built.
 *
 * <p>D1 (the user, 2026-11-19): an inconsistent ontology, or a class that can have no members,
 * is not released - there is no way past. The user's egg project was released by 1.154.0 with
 * a contradiction only a DL reasoner sees, because ODK reasons with ELK unless told otherwise.
 *
 * <p>The edit file is read from disk with its imports as the project's catalog resolves them,
 * which is what ODK's release reads. A module that cannot be loaded is a verdict of its own,
 * {@link Kind#NOT_LOADED}: the reasoner was never asked, and "consistent" would be a guess. A
 * reasoner that does not finish within the limit has found nothing ({@link Kind#TIMED_OUT}); the
 * caller asks the user and the release record says it was not checked.
 *
 * <p>No Swing. The reasoner runs on a worker thread so that the limit and Stop can end it.
 */
public final class ReleaseVerdict {

    /** What came of asking. */
    public enum Kind {
        CLEAN, INCONSISTENT, UNSATISFIABLE, NOT_LOADED, TIMED_OUT, STOPPED, FAILED
    }

    /**
     * How long the check may take before the person is asked: 3 minutes - three times the
     * slowest HermiT measured (38 s on MWO with its imports, 1.155.0), with room for a busy
     * machine.
     */
    public static final long DEFAULT_LIMIT_MILLIS = 3L * 60 * 1000;

    /** How many unsatisfiable classes are named, at most. */
    static final int NAMED = 50;

    /** How long the waiting loop sleeps between looks at Stop. */
    private static final long LOOK_MILLIS = 100;

    private final Kind kind;
    private final String reasoner;
    private final long millis;
    private final List<String> unsatisfiable;
    private final String explanation;
    private final String said;

    private ReleaseVerdict(Kind kind, String reasoner, long millis, List<String> unsatisfiable,
            String explanation, String said) {
        this.kind = kind;
        this.reasoner = reasoner;
        this.millis = millis;
        this.unsatisfiable = Collections.unmodifiableList(new ArrayList<String>(unsatisfiable));
        this.explanation = explanation == null ? "" : explanation;
        this.said = said;
    }

    public Kind kind() {
        return kind;
    }

    /** The reasoner asked, as {@link ReleaseReasoners#names} spells it, or as given if unknown. */
    public String reasoner() {
        return reasoner;
    }

    public long millis() {
        return millis;
    }

    /** The unsatisfiable classes' IRIs, at most {@value #NAMED}. */
    public List<String> unsatisfiable() {
        return unsatisfiable;
    }

    /** The axioms that cause the problem, one justification each, in Manchester syntax; "" if none. */
    public String explanation() {
        return explanation;
    }

    /** One paragraph: what was found, for a dialog and the release record. */
    public String said() {
        return said;
    }

    /** Whether a release must stop on it (D1): anything but clean, or not finished in time. */
    public boolean stopsARelease() {
        return kind != Kind.CLEAN && kind != Kind.TIMED_OUT;
    }

    /**
     * Asks {@code reasoner} about {@code editFile} with its imports.
     *
     * @param limitMillis how long the reasoner may take before the verdict is TIMED_OUT
     * @param stopped     polled while waiting; true ends the check as STOPPED
     */
    public static ReleaseVerdict check(File editFile, String reasoner, long limitMillis,
            BooleanSupplier stopped) {
        String name = ReleaseReasoners.canonical(reasoner);
        if (name == null) {
            return new ReleaseVerdict(Kind.FAILED, reasoner, 0, Collections.<String>emptyList(),
                    "", "'" + reasoner + "' is not a reasoner a release can use. These can: "
                            + String.join(", ", ReleaseReasoners.names()) + ".");
        }
        return check(editFile, name, ReleaseReasoners.factory(name), limitMillis, stopped);
    }

    /** As {@link #check(File, String, long, BooleanSupplier)}, with the reasoner given. */
    static ReleaseVerdict check(File editFile, String name, final OWLReasonerFactory factory,
            long limitMillis, BooleanSupplier stopped) {
        long started = System.currentTimeMillis();
        final OWLOntology ontology;
        try {
            ontology = load(editFile);
        } catch (UnloadableImportException missing) {
            String iri = missing.getImportsDeclaration() == null ? "an import"
                    : missing.getImportsDeclaration().getIRI().toString();
            return new ReleaseVerdict(Kind.NOT_LOADED, name, elapsed(started),
                    Collections.<String>emptyList(), "", "The ontology could not be loaded "
                            + "with its imports, so " + name + " could not be asked: " + iri
                            + " could not be read (" + firstLine(missing) + "). Bring the "
                            + "import back - OntoBoard > Reuse > Refresh imports... - and "
                            + "release again.");
        } catch (OWLOntologyCreationException | RuntimeException unreadable) {
            return new ReleaseVerdict(Kind.NOT_LOADED, name, elapsed(started),
                    Collections.<String>emptyList(), "", editFile.getName() + " could not be "
                            + "read with its imports, so " + name + " could not be asked: "
                            + firstLine(unreadable));
        }
        return ask(ontology, name, factory, limitMillis, stopped, started);
    }

    /**
     * Asks {@code reasoner} about an ontology already loaded, with the imports it was loaded
     * with - the open ontology, for a release of an ontology in no project and for
     * <i>Check this ontology...</i>.
     */
    public static ReleaseVerdict check(OWLOntology ontology, String reasoner, long limitMillis,
            BooleanSupplier stopped) {
        String name = ReleaseReasoners.canonical(reasoner);
        if (name == null) {
            return new ReleaseVerdict(Kind.FAILED, reasoner, 0, Collections.<String>emptyList(),
                    "", "'" + reasoner + "' is not a reasoner a release can use. These can: "
                            + String.join(", ", ReleaseReasoners.names()) + ".");
        }
        // An import that did not load leaves the reasoner a part of the ontology: said, never
        // "with its imports, consistent" (the final review of 1.155.0).
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLImportsDeclaration declaration
                    : part.getImportsDeclarations()) {
                if (ontology.getOWLOntologyManager().getImportedOntology(declaration) == null) {
                    return new ReleaseVerdict(Kind.NOT_LOADED, name, 0,
                            Collections.<String>emptyList(), "", "The import "
                                    + declaration.getIRI() + " did not load when the ontology "
                                    + "was opened, so " + name + " could not be asked about the "
                                    + "ontology with its imports. OntoBoard > Reuse > Check "
                                    + "imports says why; open the ontology again once the "
                                    + "import loads.");
                }
            }
        }
        return ask(ontology, name, ReleaseReasoners.factory(name), limitMillis, stopped,
                System.currentTimeMillis());
    }

    /**
     * Asks {@code reasoner} about an ontology already loaded, as it loaded: an import that did
     * not is named in what is said, never "with its imports" - for <i>Check this ontology...</i>
     * on a loose file whose imports are elsewhere, where checking what is there is the point (the
     * final review of 1.155.0). A release asks {@link #check(OWLOntology, String, long,
     * BooleanSupplier)}, which stops there.
     */
    public static ReleaseVerdict checkWhatLoaded(OWLOntology ontology, String reasoner,
            long limitMillis, BooleanSupplier stopped) {
        String name = ReleaseReasoners.canonical(reasoner);
        if (name == null) {
            return check(ontology, reasoner, limitMillis, stopped);
        }
        List<String> missing = new java.util.ArrayList<String>();
        for (OWLOntology part : ontology.getImportsClosure()) {
            for (org.semanticweb.owlapi.model.OWLImportsDeclaration declaration
                    : part.getImportsDeclarations()) {
                String iri = declaration.getIRI().toString();
                if (ontology.getOWLOntologyManager().getImportedOntology(declaration) == null
                        && !missing.contains(iri)) {
                    missing.add(iri);
                }
            }
        }
        ReleaseVerdict verdict = ask(ontology, name, ReleaseReasoners.factory(name),
                limitMillis, stopped, System.currentTimeMillis());
        return missing.isEmpty() ? verdict : verdict.without(missing);
    }

    /** This verdict, said of the ontology as it loaded, without the imports that did not. */
    private ReleaseVerdict without(List<String> missing) {
        boolean one = missing.size() == 1;
        String named = String.join(", ", missing);
        String told = said.contains("the ontology, with its imports,")
                ? said.replace("the ontology, with its imports,", "the ontology as it loaded, "
                        + "without " + named + " (" + (one ? "an import" : "imports")
                        + " that did not load),")
                : said + " " + (one ? "The import " : "The imports ") + named + " did not "
                        + "load, so " + (one ? "it was" : "they were") + " not asked about.";
        return new ReleaseVerdict(kind, reasoner, millis, unsatisfiable, explanation, told);
    }

    private static ReleaseVerdict ask(final OWLOntology ontology, String name,
            final OWLReasonerFactory factory, long limitMillis, BooleanSupplier stopped,
            long started) {
        final AtomicReference<OWLReasoner> running = new AtomicReference<OWLReasoner>();
        final AtomicReference<Found> found = new AtomicReference<Found>();
        ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "OntoBoard release check (" + name + ")");
            thread.setDaemon(true);
            return thread;
        });
        Future<String> asked = worker.submit(() -> {
            OWLReasoner reasoner = factory.createReasoner(ontology);
            running.set(reasoner);
            Found answer = new Found();
            try {
                answer.consistent = reasoner.isConsistent();
                if (answer.consistent) {
                    for (OWLClass impossible
                            : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                        answer.unsatisfiable.add(impossible);
                    }
                }
            } finally {
                running.set(null);
                reasoner.dispose();
            }
            found.set(answer);
            if (answer.consistent && answer.unsatisfiable.isEmpty()) {
                return "";
            }
            return explained(Explanations.run(ontology, factory, 1));
        });
        worker.shutdown();
        try {
            while (true) {
                if (stopped != null && stopped.getAsBoolean()) {
                    stop(running, asked);
                    return new ReleaseVerdict(Kind.STOPPED, name, elapsed(started),
                            Collections.<String>emptyList(), "", "Stopped before " + name
                                    + " finished, so nothing was released.");
                }
                long left = limitMillis - elapsed(started);
                if (left <= 0) {
                    stop(running, asked);
                    Found partly = found.get();
                    if (partly != null) {
                        return verdict(partly, ontology, name, elapsed(started),
                                "The explanation did not finish within the time the check "
                                        + "may take; " + name + "'s verdict above stands.");
                    }
                    return new ReleaseVerdict(Kind.TIMED_OUT, name, elapsed(started),
                            Collections.<String>emptyList(), "", name + " did not finish "
                                    + "within " + seconds(limitMillis) + ", so whether the "
                                    + "ontology is consistent is not known.");
                }
                try {
                    String explanation = asked.get(Math.min(LOOK_MILLIS, left),
                            TimeUnit.MILLISECONDS);
                    return verdict(found.get(), ontology, name, elapsed(started), explanation);
                } catch (TimeoutException notYet) {
                    // Look at Stop and the limit again.
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop(running, asked);
            return new ReleaseVerdict(Kind.STOPPED, name, elapsed(started),
                    Collections.<String>emptyList(), "", "Stopped before " + name
                            + " finished, so nothing was released.");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause() == null ? failed : failed.getCause();
            Found partly = found.get();
            if (partly != null) {
                // The verdict is in; only the explanation failed.
                return verdict(partly, ontology, name, elapsed(started),
                        "The explanation could not be computed: " + firstLine(cause));
            }
            return new ReleaseVerdict(Kind.FAILED, name, elapsed(started),
                    Collections.<String>emptyList(), "", name + " could not reason over "
                            + "the ontology: " + firstLine(cause));
        }
    }

    /** What the worker found before the explanation. */
    private static final class Found {
        volatile boolean consistent;
        final List<OWLClass> unsatisfiable = Collections.synchronizedList(
                new ArrayList<OWLClass>());
    }

    private static ReleaseVerdict verdict(Found found, OWLOntology ontology, String name,
            long millis, String explanation) {
        if (!found.consistent) {
            return new ReleaseVerdict(Kind.INCONSISTENT, name, millis,
                    Collections.<String>emptyList(), explanation, name + " finds the ontology, "
                            + "with its imports, inconsistent: nothing sound can be concluded "
                            + "from it, and every ontology that imports it inherits the "
                            + "contradiction. The axioms that cause it are below; change one "
                            + "of them and release again.");
        }
        if (found.unsatisfiable.isEmpty()) {
            String misses = ReleaseReasoners.whatItMisses(name);
            return new ReleaseVerdict(Kind.CLEAN, name, millis, Collections.<String>emptyList(),
                    "", name + " finds the ontology, with its imports, consistent, and every "
                            + "class can have members (" + seconds(millis) + ")."
                            + (misses == null ? "" : " " + misses));
        }
        List<OWLClass> impossible = new ArrayList<OWLClass>(found.unsatisfiable);
        Collections.sort(impossible);
        List<String> iris = new ArrayList<String>();
        List<String> names = new ArrayList<String>();
        for (OWLClass one : impossible) {
            if (iris.size() == NAMED) {
                break;
            }
            iris.add(one.getIRI().toString());
            names.add(DisplayLabels.forEntity(ontology, one));
        }
        int count = impossible.size();
        return new ReleaseVerdict(Kind.UNSATISFIABLE, name, millis, iris, explanation,
                name + " finds " + count + (count == 1 ? " class" : " classes") + " that can "
                        + "have no members: " + String.join(", ", names)
                        + (count > names.size() ? " and " + (count - names.size()) + " more" : "")
                        + ". A class like that is a mistake in what the ontology says about it "
                        + "or above it; the axioms that cause it are below.");
    }

    private static String explained(Explanations.Result result) {
        StringBuilder text = new StringBuilder();
        for (Explanations.Justification justification : result.getJustifications()) {
            // In words: where it is shown - a dialog, the log - "**" is no emphasis (the hand
            // check of 1.155.0).
            text.append(justification.getEntailment()).append(" follows from these axioms:\n");
            for (String axiom : justification.getAxioms()) {
                text.append("- ").append(axiom).append('\n');
            }
            text.append('\n');
        }
        return text.toString().trim();
    }

    private static void stop(AtomicReference<OWLReasoner> running, Future<?> asked) {
        OWLReasoner reasoner = running.get();
        if (reasoner != null) {
            try {
                reasoner.interrupt();
            } catch (RuntimeException | LinkageError ignored) {
                // Not every reasoner can be interrupted; the thread is a daemon and is cancelled.
            }
        }
        asked.cancel(true);
    }

    /**
     * The edit file with its imports, as the project's catalog resolves them; strict - an import
     * that cannot be loaded throws, naming it.
     */
    public static OWLOntology load(File editFile) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        File catalog = new File(editFile.getAbsoluteFile().getParentFile(), "catalog-v001.xml");
        if (catalog.isFile()) {
            try {
                manager.getIRIMappers().add(
                        new org.obolibrary.robot.CatalogXmlIRIMapper(catalog));
            } catch (Exception unreadable) {
                throw new OWLOntologyCreationException("The project's catalog could not be "
                        + "read: " + unreadable.getMessage(), unreadable);
            }
        }
        return manager.loadOntologyFromOntologyDocument(editFile.getAbsoluteFile());
    }

    private static long elapsed(long started) {
        return Math.max(0, System.currentTimeMillis() - started);
    }

    static String seconds(long millis) {
        if (millis < 1000) {
            return "under a second";
        }
        long whole = Math.round(millis / 1000.0);
        return whole + (whole == 1 ? " second" : " seconds");
    }

    private static String firstLine(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return failure.getClass().getSimpleName();
        }
        String first = message.trim();
        int end = first.indexOf('\n');
        return end < 0 ? first : first.substring(0, end).trim();
    }
}
