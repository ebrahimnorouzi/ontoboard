package de.fizkarlsruhe.ise.ontoboard.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every bundled pattern, and every example of one, put to a reasoner.
 *
 * <p>The library is published as a thing other people should build ontologies out of, and until
 * this existed nothing had ever asked a reasoner whether any of it holds together. It found four
 * real defects: two patterns no OWL API parser could read at all (so neither Protégé nor this
 * plugin could load them), one generated example asserting an individual into two classes its
 * pattern declares disjoint, and two patterns outside OWL 2 DL.
 *
 * <p><b>The verdicts are committed, in {@code patterns/soundness.tsv}.</b> Two reasons. The
 * pattern pages quote them - a reader deciding whether to adopt a pattern is told what a reasoner
 * says about it - and a generated page that quoted a number nothing checked would drift from the
 * truth at the first upstream change. And a committed file makes a regression visible as a diff
 * rather than as a sentence in a log. Regenerate with:
 *
 * <pre>mvn -o test -Dtest=PatternSoundnessTest -Dontoboard.writeSoundness=true</pre>
 *
 * <p>What this does NOT check is stated in {@link PatternSoundness}: imports are answered with
 * empty documents, so a contradiction that arises only against DUL or BFO is not caught here.
 * Resolving them would make the gate depend on five servers, one of which is already gone.
 */
class PatternSoundnessTest {

    /** The pattern directories, which sit beside the module rather than inside it. */
    private static File patternsDirectory() {
        return new File(new File(System.getProperty("user.dir")).getAbsoluteFile()
                .getParentFile(), "patterns");
    }

    private static File committedFile() {
        return new File(patternsDirectory(), "soundness.tsv");
    }

    /**
     * Judged once for the whole class.
     *
     * <p>Reasoning over 163 patterns twice each takes four times as long as doing it once, and
     * says the same thing. @TempDir is deliberately not used for the import stubs: the OWL API
     * holds those files open, so on Windows JUnit cannot delete the directory afterwards and
     * every test in the class ERRORS on cleanup while passing its own assertions. They go under
     * target/ instead, which `mvn clean` owns.
     */
    private static List<PatternSoundness.Verdict> VERDICTS;      // NOPMD - shared, judged once

    @BeforeAll
    static void judgeTheLibraryOnce() throws Exception {
        File stubs = new File("target", "pattern-soundness-stubs");
        assertTrue(stubs.isDirectory() || stubs.mkdirs(), "could not make " + stubs);
        VERDICTS = new PatternSoundness(stubs).judge(patternsDirectory());
    }

    /**
     * THE PATTERNS A REASONER OBJECTS TO, each with the reason it is still shipped.
     *
     * <p>An allowed exception is not a softened assertion: every one of these is a defect in the
     * pattern AS ITS AUTHORS PUBLISHED IT, which this project cannot fix without changing what
     * somebody else's pattern says. What it can do is refuse to let the list grow by accident,
     * and say so on the pattern's own page rather than leaving a user to find out by reasoning
     * over their own ontology.
     */
    private static final Map<String, String> ALLOWED = allowed();

    private static Map<String, String> allowed() {
        Map<String, String> known = new LinkedHashMap<String, String>();
        known.put("roledependentnames",
                "outside OWL 2 DL: assumesAgentRole takes part in a cyclic property hierarchy, "
                + "which breaks the regularity restriction OWL 2 DL needs for decidability. "
                + "HermiT refuses it rather than answering about it.");
        known.put("winstonpartwhole",
                "outside OWL 2 DL: the non-simple property po-feature appears in an asymmetric "
                + "object property axiom, which the simplicity restriction forbids.");
        known.put("spatial-graph-adapter-sga",
                "Attribute and ContextualizedRelation are unsatisfiable as published: hasType and "
                + "hasAttribute each declare BOTH as rdfs:domain, which OWL reads as their "
                + "intersection, and the pattern then declares the two disjoint in an "
                + "owl:AllDisjointClasses axiom. Its example was withdrawn because every example "
                + "of it is inconsistent.");
        return known;
    }

    /**
     * Every pattern is consistent, and so is every example of one - or it is a known exception.
     *
     * <p>The assertion is on the SET of unclean patterns rather than on a count, so a new defect
     * names itself and a fixed one has to be taken off the list deliberately.
     */
    @Test
    void everyPatternAndExampleHoldsTogether() throws Exception {
        List<PatternSoundness.Verdict> verdicts = VERDICTS;

        assertTrue(verdicts.size() > 150,
                "the whole library should have been read, not a corner of it: " + verdicts.size());

        List<String> unexpected = new ArrayList<String>();
        for (PatternSoundness.Verdict verdict : verdicts) {
            if (!verdict.isClean() && !ALLOWED.containsKey(verdict.getId())) {
                unexpected.add(verdict.getId() + " - " + verdict.describe());
            }
        }
        assertEquals(new ArrayList<String>(), unexpected,
                "a reasoner objects to " + unexpected.size() + " pattern(s) nothing has accounted "
                + "for. Fix the pattern, or add it to ALLOWED with the reason it cannot be fixed.");

        // And the other direction: an exception that has stopped being one is dead weight that
        // would quietly excuse a future defect in the same pattern.
        List<String> noLongerBroken = new ArrayList<String>();
        for (PatternSoundness.Verdict verdict : verdicts) {
            if (verdict.isClean() && ALLOWED.containsKey(verdict.getId())) {
                noLongerBroken.add(verdict.getId());
            }
        }
        assertEquals(new ArrayList<String>(), noLongerBroken,
                "these are clean now and should come off ALLOWED");
    }

    /**
     * The committed verdicts match what a reasoner says today.
     *
     * <p>Which is what lets the pattern pages quote them. A page saying "HermiT finds this
     * consistent" is a claim about the file as it is now, and a generated page cannot run a
     * reasoner.
     */
    @Test
    void theCommittedVerdictsAreWhatAReasonerSays() throws Exception {
        List<PatternSoundness.Verdict> verdicts = VERDICTS;

        StringBuilder text = new StringBuilder();
        text.append("# What HermiT says about every bundled pattern and its example.\n");
        text.append("#\n");
        text.append("# GENERATED by PatternSoundnessTest, which fails when this file and the\n");
        text.append("# patterns disagree. Regenerate with:\n");
        text.append("#   mvn -o test -Dtest=PatternSoundnessTest -Dontoboard.writeSoundness=true\n");
        text.append("#\n");
        text.append("# Imports are NOT resolved - 101 patterns declare them and five of those\n");
        text.append("# servers no longer answer - so a verdict is about what the pattern file\n");
        text.append("# itself asserts. The imports column says how much that leaves out.\n");
        text.append("#\n");
        text.append(PatternSoundness.HEADER).append('\n');
        for (PatternSoundness.Verdict verdict : verdicts) {
            text.append(verdict.asRow()).append('\n');
        }

        if (Boolean.getBoolean("ontoboard.writeSoundness")) {
            Files.write(committedFile().toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        assertTrue(committedFile().isFile(),
                "patterns/soundness.tsv is missing. Regenerate it: mvn -o test "
                + "-Dtest=PatternSoundnessTest -Dontoboard.writeSoundness=true");
        String committed = new String(Files.readAllBytes(committedFile().toPath()),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertEquals(text.toString(), committed,
                "patterns/soundness.tsv no longer matches what a reasoner says about the "
                + "patterns. Regenerate it in the same commit as the pattern change.");
    }

    /**
     * The exceptions are described in a curator's words, not as a reasoner's exception class.
     *
     * <p>"HermiT threw IllegalArgumentException" is a true statement that helps nobody. The
     * pattern pages quote these sentences, so they have to say what is wrong with the pattern.
     */
    @Test
    void eachExceptionSaysWhatIsWrongWithThePattern() throws Exception {
        Map<String, String> described = new LinkedHashMap<String, String>();
        for (PatternSoundness.Verdict verdict : VERDICTS) {
            if (ALLOWED.containsKey(verdict.getId())) {
                described.put(verdict.getId(), verdict.describe());
            }
        }

        assertEquals(new TreeSet<String>(ALLOWED.keySet()), new TreeSet<String>(described.keySet()),
                "every allowed exception should actually be found by the reasoner");
        for (Map.Entry<String, String> one : described.entrySet()) {
            assertFalse(one.getValue().contains("Exception"),
                    one.getKey() + " is described by a Java class name: " + one.getValue());
            assertFalse(one.getValue().contains("NOT_OWL_2_DL"),
                    one.getKey() + " is described by an internal token: " + one.getValue());
            assertTrue(one.getValue().length() > 40,
                    one.getKey() + " needs a sentence, not a word: " + one.getValue());
        }
    }

    /** The two patterns outside OWL 2 DL are named as that, not as inconsistent. */
    @Test
    void beingOutsideOwl2DlIsNotTheSameAsBeingInconsistent() throws Exception {
        Map<String, String> said = new LinkedHashMap<String, String>();
        for (PatternSoundness.Verdict verdict : VERDICTS) {
            said.put(verdict.getId(), verdict.describe());
        }

        for (String outside : Arrays.asList("roledependentnames", "winstonpartwhole")) {
            assertTrue(said.get(outside).contains("outside OWL 2 DL"),
                    outside + ": " + said.get(outside));
            assertFalse(said.get(outside).contains("inconsistent"),
                    "a question no DL reasoner can answer is not a contradiction: "
                            + said.get(outside));
        }
        assertTrue(said.get("spatial-graph-adapter-sga").contains("unsatisfiable"),
                said.get("spatial-graph-adapter-sga"));
    }
}
