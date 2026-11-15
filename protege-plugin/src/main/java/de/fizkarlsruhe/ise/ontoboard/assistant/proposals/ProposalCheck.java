package de.fizkarlsruhe.ise.ontoboard.assistant.proposals;

import de.fizkarlsruhe.ise.ontoboard.model.DisplayLabels;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.semanticweb.owlapi.reasoner.ReasonerInterruptedException;
import org.semanticweb.owlapi.reasoner.TimeOutException;

/**
 * What the reasoner says about a proposed change to the logic before the person accepts it: the
 * ontology is copied with its imports, once as it is and once with the change - an axiom
 * removed, added, or replaced - and each copy is reasoned over. The ontology itself is never
 * touched, and the copies belong to the thread that checks them, so the check runs in the
 * background while Protégé edits.
 *
 * <p>A removal can only take entailments away; an addition can make a class unsatisfiable or the
 * ontology inconsistent, and the card says so first.
 *
 * <p>The reasoner is created as Protégé creates it ({@code createReasoner(ontology)}), so HermiT
 * ignores datatypes outside the OWL 2 map as it does there (review R1, 1.138.0). Each reasoning
 * runs under a wall-clock watchdog that interrupts it after {@link #timeoutMillis()} - a
 * reasoner's own timeout bounds each tableau test, not the whole classification (review R2) -
 * and a {@link Run} lets Stop interrupt it.
 */
public final class ProposalCheck {

    private static volatile long timeoutMillis = 60_000L;

    /** How long past the timeout a reasoner that ignores interrupt is waited for. */
    private static volatile long graceMillis = 5_000L;

    /** How often a reasoning past its deadline, or stopped, is interrupted again. */
    private static final long TICK_MILLIS = 200L;

    private static final ScheduledThreadPoolExecutor WATCHDOG = watchdog();

    /**
     * Each reasoning runs on a thread of its own, so a reasoner that never answers its interrupt
     * is left there and the card still gets its verdict (review 1.139.0 I4).
     */
    private static final ExecutorService REASONING = Executors.newCachedThreadPool(work -> {
        Thread thread = new Thread(work, "OntoBoard assistant reasoning");
        thread.setDaemon(true);
        return thread;
    });

    private static ScheduledThreadPoolExecutor watchdog() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "OntoBoard assistant check watchdog");
            thread.setDaemon(true);
            return thread;
        });
        // A cancelled task holds its reasoner, and the reasoner its copy of the ontology: gone
        // from the queue at once, not when its delay would have passed (review 1.139.0 M1).
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /** How long one reasoning may take before it is interrupted and the check says so. */
    public static long timeoutMillis() {
        return timeoutMillis;
    }

    /** For tests. */
    static void setTimeoutMillis(long millis) {
        timeoutMillis = millis;
    }

    static long graceMillis() {
        return graceMillis;
    }

    /** For tests. */
    static void setGraceMillis(long millis) {
        graceMillis = millis;
    }

    /** For tests: watchdog tasks still queued. */
    static int queuedWatchdogTasks() {
        return WATCHDOG.getQueue().size();
    }

    /** One answer's checks: Stop interrupts the reasoning under way and skips the rest. */
    public static final class Run {
        private volatile OWLReasoner reasoning;
        private volatile boolean cancelled;

        public void cancel() {
            cancelled = true;
            OWLReasoner current = reasoning;
            if (current != null) {
                try {
                    current.interrupt();
                } catch (RuntimeException ignored) {
                    // A reasoner that cannot be interrupted finishes; its verdict is dropped.
                }
            }
        }

        public boolean isCancelled() {
            return cancelled;
        }
    }

    /** The verdict a card shows. */
    public static final class Check {
        private final String reasoner;
        private final boolean consistentBefore;
        private final boolean consistentAfter;
        private final List<String> unsatisfiableBefore;
        private final List<String> unsatisfiableAfter;
        private final String error;
        private final boolean adds;

        Check(String reasoner, boolean consistentBefore, boolean consistentAfter,
                List<String> unsatisfiableBefore, List<String> unsatisfiableAfter, String error,
                boolean adds) {
            this.reasoner = reasoner;
            this.consistentBefore = consistentBefore;
            this.consistentAfter = consistentAfter;
            this.unsatisfiableBefore = Collections.unmodifiableList(unsatisfiableBefore);
            this.unsatisfiableAfter = Collections.unmodifiableList(unsatisfiableAfter);
            this.error = error;
            this.adds = adds;
        }

        public boolean isConsistentBefore() {
            return consistentBefore;
        }

        public boolean isConsistentAfter() {
            return consistentAfter;
        }

        /** Labels; empty while the ontology is inconsistent, where nothing can be classified. */
        public List<String> getUnsatisfiableBefore() {
            return unsatisfiableBefore;
        }

        public List<String> getUnsatisfiableAfter() {
            return unsatisfiableAfter;
        }

        /** Classes unsatisfiable after the change and not before (an addition can cause them). */
        public List<String> getNewlyUnsatisfiable() {
            if (!consistentBefore || !consistentAfter) {
                return Collections.emptyList();
            }
            List<String> newly = new ArrayList<String>(unsatisfiableAfter);
            newly.removeAll(unsatisfiableBefore);
            return newly;
        }

        /** Why the reasoner could not check it; null when it could. */
        public String getError() {
            return error;
        }

        /** There was a problem, and after the change there is none: consistent, all satisfiable. */
        public boolean isSolved() {
            boolean problemBefore = !consistentBefore || !unsatisfiableBefore.isEmpty();
            return error == null && problemBefore && consistentAfter
                    && unsatisfiableAfter.isEmpty();
        }

        /** The change breaks nothing: not inconsistent where it was not, no class newly empty. */
        public boolean isSafe() {
            return error == null && !(consistentBefore && !consistentAfter)
                    && getNewlyUnsatisfiable().isEmpty();
        }

        /** "HermiT, on a copy with this change: the ontology becomes consistent." */
        public String describe() {
            if (error != null) {
                return reasoner + " could not check it: " + error;
            }
            String start = reasoner + ", on a copy with this change: ";
            if (consistentBefore && !consistentAfter) {
                return start + "the ontology becomes inconsistent.";
            }
            List<String> newly = getNewlyUnsatisfiable();
            if (!newly.isEmpty()) {
                return start + join(newly) + " " + (newly.size() == 1 ? "becomes" : "become")
                        + " unsatisfiable.";
            }
            if (!consistentBefore) {
                if (!consistentAfter) {
                    return start + "the ontology is still inconsistent.";
                }
                return start + "the ontology becomes consistent"
                        + (unsatisfiableAfter.isEmpty() ? "."
                                : ", but " + join(unsatisfiableAfter) + " "
                                        + (unsatisfiableAfter.size() == 1 ? "is" : "are")
                                        + " unsatisfiable.");
            }
            if (unsatisfiableBefore.isEmpty()) {
                return start + (adds ? "the ontology stays consistent, and no class becomes"
                        + " unsatisfiable." : "nothing changes; the ontology was consistent and"
                                + " every class satisfiable.");
            }
            List<String> fixed = new ArrayList<String>(unsatisfiableBefore);
            fixed.removeAll(unsatisfiableAfter);
            if (fixed.isEmpty()) {
                return start + "nothing changes; " + join(unsatisfiableAfter) + " "
                        + (unsatisfiableAfter.size() == 1 ? "stays" : "stay") + " unsatisfiable.";
            }
            return start + join(fixed) + " " + (fixed.size() == 1 ? "becomes" : "become")
                    + " satisfiable" + (unsatisfiableAfter.isEmpty() ? "."
                            : "; " + join(unsatisfiableAfter) + " "
                                    + (unsatisfiableAfter.size() == 1 ? "stays" : "stay")
                                    + " unsatisfiable.");
        }

        private static String join(List<String> labels) {
            int shown = Math.min(labels.size(), 5);
            String list = String.join(", ", labels.subList(0, shown));
            return labels.size() > shown ? list + " and " + (labels.size() - shown) + " more"
                    : list;
        }
    }

    private ProposalCheck() {
    }

    /** Every axiom the reasoner sees: the ontology's and its imports'. Read on the editor's thread. */
    public static Set<OWLAxiom> snapshot(OWLOntology ontology) {
        return new HashSet<OWLAxiom>(ontology.getAxioms(Imports.INCLUDED));
    }

    public static Check check(OWLOntology ontology, OWLReasonerFactory factory,
            Proposal proposal) {
        Set<OWLAxiom> axioms = snapshot(ontology);
        return after(axioms, before(axioms, factory, new Run()), factory, removed(proposal),
                proposal.getNewAxiom(), "The reasoner", new Run());
    }

    /** On copies of the snapshot; safe on any thread. */
    public static Check check(Set<OWLAxiom> axioms, OWLReasonerFactory factory, OWLAxiom removed,
            String reasonerName) {
        return after(axioms, before(axioms, factory, new Run()), factory, removed, null,
                reasonerName, new Run());
    }

    /** The axiom a proposal takes away: the matched one of a removal or replacement. */
    public static OWLAxiom removed(Proposal proposal) {
        return proposal.getAction() == Proposal.Action.REMOVE_AXIOM
                || proposal.getAction() == Proposal.Action.REPLACE_AXIOM
                ? proposal.getMatchedAxiom() : null;
    }

    /** The ontology as it is: reasoned once for every change an answer proposes. */
    public static Verdict before(Set<OWLAxiom> axioms, OWLReasonerFactory factory, Run run) {
        return reasonSafely(axioms, factory, run);
    }

    /** The ontology with one change - an axiom removed, added, or both - against the verdict before. */
    public static Check after(Set<OWLAxiom> axioms, Verdict before, OWLReasonerFactory factory,
            OWLAxiom removed, OWLAxiom added, String reasonerName, Run run) {
        String name = reasonerName == null || reasonerName.isEmpty() ? "The reasoner"
                : reasonerName;
        if (before.error != null) {
            return failed(name, before.error);
        }
        Set<OWLAxiom> changed = new HashSet<OWLAxiom>(axioms);
        if (removed != null) {
            changed.remove(removed);
        }
        if (added != null) {
            changed.add(added);
        }
        Verdict after = reasonSafely(changed, factory, run);
        if (after.error != null) {
            return failed(name, after.error);
        }
        return new Check(name, before.consistent, after.consistent, before.unsatisfiable,
                after.unsatisfiable, null, added != null);
    }

    /** A check that did not run to the end, and why: shown on the card instead of a verdict. */
    public static Check failed(String reasonerName, String why) {
        return new Check(reasonerName == null || reasonerName.isEmpty() ? "The reasoner"
                : reasonerName, false, false, Collections.<String>emptyList(),
                Collections.<String>emptyList(), why, false);
    }

    /** What one reasoning found, or why it could not finish. */
    public static final class Verdict {
        boolean consistent;
        List<String> unsatisfiable = new ArrayList<String>();
        String error;
    }

    /**
     * Never throws: a reasoner that runs out of time, is stopped, or fails with an Error (a
     * broken reasoner plugin, a stack overflow) is reported, so a card never waits forever.
     */
    private static Verdict reasonSafely(Set<OWLAxiom> axioms, OWLReasonerFactory factory,
            Run run) {
        Verdict verdict = new Verdict();
        if (run.isCancelled()) {
            verdict.error = "Stopped before it ran.";
            return verdict;
        }
        try {
            return reason(axioms, factory, run);
        } catch (ReasonerInterruptedException | TimeOutException interrupted) {
            verdict.error = interruptedWhy(run);
            return verdict;
        } catch (Throwable failed) {
            verdict.error = failed.getMessage() == null ? failed.getClass().getSimpleName()
                    : failed.getClass().getSimpleName() + ": " + failed.getMessage();
            return verdict;
        }
    }

    private static String interruptedWhy(Run run) {
        return run.isCancelled() ? "Stopped."
                : "it ran out of time (" + timeoutMillis / 1000.0 + " s)";
    }

    /**
     * Waits for the reasoning on its own thread: until it answers, Stop is pressed, or the
     * timeout and the grace after it have passed. In the last two cases the verdict says so at
     * once, and a reasoner that does not listen to its interrupt is left to finish alone.
     */
    private static Verdict reason(final Set<OWLAxiom> axioms, final OWLReasonerFactory factory,
            final Run run) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        Future<Verdict> reasoning = REASONING.submit(() -> reasonNow(axioms, factory, run,
                deadline));
        while (true) {
            try {
                return reasoning.get(TICK_MILLIS, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.TimeoutException notYet) {
                if (run.isCancelled()
                        || System.currentTimeMillis() > deadline + graceMillis) {
                    Verdict left = new Verdict();
                    left.error = interruptedWhy(run);
                    return left;
                }
            } catch (InterruptedException shutDown) {
                Thread.currentThread().interrupt();
                Verdict left = new Verdict();
                left.error = "Stopped.";
                return left;
            } catch (ExecutionException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Exception) {
                    throw (Exception) cause;
                }
                if (cause instanceof Error) {
                    throw (Error) cause;
                }
                throw failed;
            }
        }
    }

    private static Verdict reasonNow(Set<OWLAxiom> axioms, OWLReasonerFactory factory,
            final Run run, final long deadline) throws Exception {
        OWLOntology copy = OWLManager.createOWLOntologyManager().createOntology(axioms);
        final OWLReasoner reasoner = factory.createReasoner(copy);
        run.reasoning = reasoner;
        // Interrupted again on every tick once the deadline has passed or Stop was pressed:
        // HermiT clears its flag when each tableau test starts, so one interrupt that lands
        // between two tests is lost (review 1.139.0 I4).
        ScheduledFuture<?> watchdog = WATCHDOG.scheduleWithFixedDelay(() -> {
            if (run.isCancelled() || System.currentTimeMillis() >= deadline) {
                try {
                    reasoner.interrupt();
                } catch (RuntimeException ignored) {
                    // Nothing more can be done; the reasoner finishes on its own.
                }
            }
        }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        try {
            if (run.isCancelled()) {
                throw new ReasonerInterruptedException();
            }
            Verdict verdict = new Verdict();
            verdict.consistent = reasoner.isConsistent();
            if (run.isCancelled() || System.currentTimeMillis() >= deadline) {
                throw new ReasonerInterruptedException();
            }
            if (verdict.consistent) {
                for (OWLClass cls : reasoner.getUnsatisfiableClasses().getEntitiesMinusBottom()) {
                    verdict.unsatisfiable.add(DisplayLabels.forEntity(copy, cls));
                }
                Collections.sort(verdict.unsatisfiable);
            }
            return verdict;
        } finally {
            watchdog.cancel(false);
            run.reasoning = null;
            try {
                reasoner.dispose();
            } catch (RuntimeException ignored) {
                // A reasoner that fails to dispose has still given its verdict.
            }
        }
    }
}
