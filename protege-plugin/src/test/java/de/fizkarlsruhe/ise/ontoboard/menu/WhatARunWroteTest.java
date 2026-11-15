package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * After a run of ODK's make - Build, Release, Refresh imports - Protege still holds what it had
 * read of the files the run rewrote. Each of those is read again; one that has changes of the
 * person's that are not saved is not, so that they are not lost, and that is said.
 */
class WhatARunWroteTest {

    private static final File EDIT = new File("/p/src/ontology/obim-edit.owl");
    private static final File RO = new File("/p/src/ontology/imports/ro_import.owl");
    private static final File IAO = new File("/p/src/ontology/imports/iao_import.owl");
    private static final File OTHER = new File("/q/other.owl");

    /** What Protege has open, in its order, by a name for each, with its file. */
    private static Map<String, File> open() {
        Map<String, File> open = new LinkedHashMap<String, File>();
        open.put("obim", EDIT);
        open.put("ro_import", RO);
        open.put("iao_import", IAO);
        open.put("other", OTHER);
        return open;
    }

    @Test
    void whatTheRunWroteIsReadAgainInTheOrderProtegeHasThem() {
        WhatARunWrote.Plan<String> plan = WhatARunWrote.plan(open(),
                Collections.<String>emptySet(), Arrays.asList(IAO, RO));

        assertEquals(Arrays.asList("ro_import", "iao_import"), plan.toRead());
        assertEquals(Collections.<String>emptyList(), plan.notRead());
    }

    /** An ontology as the OWL API has it: equal by its ID, and the ID can change. */
    private static final class Changing {
        private String id;

        Changing(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Changing && ((Changing) other).id.equals(id);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }
    }

    /**
     * Reading a module again can give it another ID - ODK's build of a module has a
     * version IRI with the day in it, OntoBoard's own extraction has none - and with it
     * another place in a hash map. The file to say it by is still its file. Found live:
     * Build's full test built a module again and ended in a NullPointerException.
     */
    @Test
    void anOntologyThatIsAnotherAfterReadingItAgainStillHasItsFile() {
        Changing edit = new Changing("obim");
        Changing module = new Changing("ro_import, no version");
        Map<Changing, File> open = new LinkedHashMap<Changing, File>();
        open.put(edit, EDIT);
        open.put(module, RO);
        WhatARunWrote.Plan<Changing> plan = WhatARunWrote.plan(open,
                Collections.<Changing>emptySet(), Arrays.asList(RO, EDIT));

        module.id = "ro_import, version 2026-11-13";

        assertNull(open.get(module), "the map no longer finds it");
        assertEquals(RO, plan.fileOf(module));
        assertEquals(EDIT, plan.fileOf(edit));
        assertNull(plan.fileOf(new Changing("obim")), "by what it is, not by its name");
    }

    @Test
    void whatTheRunDidNotWriteIsNotReadAgain() {
        WhatARunWrote.Plan<String> plan = WhatARunWrote.plan(open(),
                Collections.<String>emptySet(), Collections.singletonList(new File(
                        "/p/src/ontology/mirror/ro.owl")));

        assertEquals(Collections.<String>emptyList(), plan.toRead());
        assertEquals(Collections.<String>emptyList(), plan.notRead());
    }

    /** Unsaved changes are the person's: such an ontology is left as it is, and that is said. */
    @Test
    void oneWithChangesThatAreNotSavedIsNotReadAgainAndThatIsSaid() {
        WhatARunWrote.Plan<String> plan = WhatARunWrote.plan(open(),
                Collections.singleton("ro_import"), Arrays.asList(RO, IAO));

        assertEquals(Collections.singletonList("iao_import"), plan.toRead());
        assertEquals(1, plan.notRead().size(), plan.notRead().toString());
        String said = plan.notRead().get(0);
        assertTrue(said.startsWith("ro_import.owl was rewritten"), said);
        assertTrue(said.contains("not saved"), said);
        assertTrue(said.contains("Protege will ask"), said);
    }

    /** A file is the same file however its path is written. */
    @Test
    void theSameFileWrittenAnotherWay() {
        WhatARunWrote.Plan<String> plan = WhatARunWrote.plan(open(),
                Collections.<String>emptySet(), Collections.singletonList(
                        new File("/p/src/ontology/imports/../imports/ro_import.owl")));

        assertEquals(Collections.singletonList("ro_import"), plan.toRead());
    }

    @Test
    void whatWasReadAgainIsSaidInOneSentence() {
        assertNull(WhatARunWrote.saidOf(Collections.<File>emptyList()));
        assertEquals("Protege has read again what the run rewrote: ro_import.owl.",
                WhatARunWrote.saidOf(Collections.singletonList(RO)));
        assertEquals("Protege has read again what the run rewrote: ro_import.owl, "
                + "iao_import.owl.", WhatARunWrote.saidOf(Arrays.asList(RO, IAO)));
    }

    /** What a reading again did: said in a sentence, and each file not read in its own. */
    @Test
    void whatWasDoneIsSaid() {
        WhatARunWrote.Done nothing = WhatARunWrote.Done.nothing();
        WhatARunWrote.Done done = new WhatARunWrote.Done(Arrays.asList(RO, IAO),
                Collections.singletonList("pato_import.owl could not be read again."));

        assertNull(nothing.said());
        assertEquals(Collections.<String>emptyList(), nothing.notRead());
        assertEquals(Arrays.asList(RO, IAO), done.read());
        assertEquals("Protege has read again what the run rewrote: ro_import.owl, "
                + "iao_import.owl.", done.said());
        assertEquals(Collections.singletonList("pato_import.owl could not be read again."),
                done.notRead());
    }

    @Test
    void whatCouldNotBeReadAgainIsSaidWithWhatToDo() {
        String said = WhatARunWrote.notReadAgain(RO, "the file is not an ontology");

        assertTrue(said.startsWith("ro_import.owl could not be read again: the file is not an "
                + "ontology."), said);
        assertTrue(said.contains("open it again"), said);
    }
}
