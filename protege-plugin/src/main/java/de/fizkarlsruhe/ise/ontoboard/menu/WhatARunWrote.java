package de.fizkarlsruhe.ise.ontoboard.menu;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What Protege reads again after a run of ODK's make - Build, Release, Refresh imports: each
 * ontology it has open from a file the run wrote. The run wrote behind Protege's back: what
 * Protege holds is what the file held before, and the next time its window comes to the front
 * it asks about an ontology that "has changed outside of Protege".
 *
 * <p>An ontology with changes of the person's that are not saved is not read again: reading
 * it would throw them away. That is said, and Protege's own question then lets the person
 * choose.
 *
 * <p>No Protege types: an ontology is whatever the caller holds for one ({@code T}).
 */
final class WhatARunWrote {

    private WhatARunWrote() {
    }

    /** Which open ontologies to read again, and what is said of those that are not. */
    static final class Plan<T> {
        private final List<T> toRead = new ArrayList<T>();
        private final List<String> notRead = new ArrayList<String>();

        /** In the order Protege has them. */
        List<T> toRead() {
            return Collections.unmodifiableList(toRead);
        }

        /** A sentence for each written file that is not read again, and why. */
        List<String> notRead() {
            return Collections.unmodifiableList(notRead);
        }
    }

    /**
     * @param open    what Protege has open, in its order, each with its file
     * @param unsaved those of them with changes that are not saved
     * @param written the files the run wrote
     */
    static <T> Plan<T> plan(Map<T, File> open, Set<T> unsaved, Collection<File> written) {
        Set<File> wrote = new HashSet<File>();
        for (File file : written) {
            wrote.add(known(file));
        }
        Plan<T> plan = new Plan<T>();
        for (Map.Entry<T, File> one : open.entrySet()) {
            if (one.getValue() == null || !wrote.contains(known(one.getValue()))) {
                continue;
            }
            if (unsaved.contains(one.getKey())) {
                plan.notRead.add(one.getValue().getName() + " was rewritten by the run while it "
                        + "had changes that are not saved. It was not read again, so that they "
                        + "are not lost; Protege will ask about it, and reloading it there "
                        + "replaces them with what the run wrote.");
            } else {
                plan.toRead.add(one.getKey());
            }
        }
        return plan;
    }

    /** What a reading again did: the files read again, and a sentence for each that was not. */
    static final class Done {
        private final List<File> read;
        private final List<String> notRead;

        Done(List<File> read, List<String> notRead) {
            this.read = Collections.unmodifiableList(new ArrayList<File>(read));
            this.notRead = Collections.unmodifiableList(new ArrayList<String>(notRead));
        }

        /** Nothing to read again: nothing was written. */
        static Done nothing() {
            return new Done(Collections.<File>emptyList(), Collections.<String>emptyList());
        }

        List<File> read() {
            return read;
        }

        List<String> notRead() {
            return notRead;
        }

        /** What was read again, in one sentence; null when nothing was. */
        String said() {
            return saidOf(read);
        }
    }

    /** What was read again, in one sentence; null when nothing was. */
    static String saidOf(List<File> read) {
        if (read.isEmpty()) {
            return null;
        }
        List<String> names = new ArrayList<String>();
        for (File file : read) {
            names.add(file.getName());
        }
        return "Protege has read again what the run rewrote: " + String.join(", ", names) + ".";
    }

    /** What could not be read again, and what to do. */
    static String notReadAgain(File file, String why) {
        String reason = why == null || why.trim().isEmpty() ? "no reason was given" : why.trim();
        return file.getName() + " could not be read again: " + reason
                + (reason.endsWith(".") ? "" : ".") + " Protege shows it as it was before the "
                + "run; close the ontology and open it again to see it as it is now.";
    }

    private static File known(File file) {
        return file.getAbsoluteFile().toPath().normalize().toFile();
    }
}
