package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;

/**
 * The reasoners a release may reason with, and which one the Release form offers first.
 *
 * <p>The user chooses (2026-11-19: "for release the user define the reasoner"). The choice does
 * two things: OntoBoard asks it, before anything is built, whether the ontology with its imports
 * is consistent and every class can have members ({@link ReleaseVerdict}), and ODK reasons with it
 * for the release ({@code REASONER=} on make's command line, on every route).
 *
 * <p>Offered first: the project's own {@code reasoner:} when its configuration says one, else
 * HermiT. Not ODK's default ELK, although ODK writes {@code REASONER= ELK} into every Makefile:
 * ELK reasons in OWL 2 EL and ignores what lies outside it, and a project built on BFO-style
 * patterns released an inconsistent ontology that way - its contradiction ran through an inverse
 * property, which ELK does not read (the user's egg project, 1.154.0).
 */
public final class ReleaseReasoners {

    private static final List<String> NAMES = Collections.unmodifiableList(
            Arrays.asList("ELK", "HermiT", "JFact", "Whelk"));

    /** The reasoner offered first when a project chose none. */
    public static final String COMPLETE_DEFAULT = "HermiT";

    private ReleaseReasoners() {
    }

    /** The names, as ROBOT's {@code reason --reasoner} takes them - what REASONER= hands ODK. */
    public static List<String> names() {
        return NAMES;
    }

    /** The name as {@link #names} spells it, matched without case; null when ROBOT knows none. */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim();
        for (String known : NAMES) {
            if (known.equalsIgnoreCase(wanted)) {
                return known;
            }
        }
        return null;
    }

    /**
     * The {@code reasoner:} of the project's configuration, as written; null when it says none,
     * or the file is in no project.
     *
     * <p>The configuration rather than the Makefile: ODK writes its default into every Makefile,
     * so only the configuration says what somebody chose.
     */
    public static String ofProject(File editFile) {
        if (editFile == null) {
            return null;
        }
        File yaml = OdkBuildSettings.yamlIn(editFile.getAbsoluteFile().getParentFile());
        if (yaml == null) {
            return null;
        }
        String text;
        try {
            text = new String(Files.readAllBytes(yaml.toPath()), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return null;
        }
        try {
            for (OdkYaml.Entry entry : OdkYaml.entriesIn(text)) {
                if ("reasoner".equals(entry.getPath())) {
                    String value = entry.getValue();
                    return value == null || value.trim().isEmpty() ? null : value.trim();
                }
            }
        } catch (OdkYaml.UnreadableException notYaml) {
            return null;
        }
        return null;
    }

    /**
     * What the Release form offers first: the project's own reasoner when ROBOT knows it, else
     * {@link #COMPLETE_DEFAULT}.
     */
    public static String preselected(File editFile) {
        String own = canonical(ofProject(editFile));
        return own != null ? own : COMPLETE_DEFAULT;
    }

    /** True for a reasoner that answers for all of OWL 2 DL, false for the EL ones. */
    public static boolean isComplete(String name) {
        String known = canonical(name);
        return "HermiT".equals(known) || "JFact".equals(known);
    }

    /** What the reasoner does not see, for the form and the record; null for a complete one. */
    public static String whatItMisses(String name) {
        String known = canonical(name);
        if (known == null || isComplete(known)) {
            return null;
        }
        return known + " reasons in OWL 2 EL: it ignores inverse properties, universal "
                + "restrictions, cardinalities, unions and negation, so a contradiction that "
                + "needs one of them goes unseen and the release goes out with it. HermiT reads "
                + "all of OWL 2 DL; on a large ontology it takes longer.";
    }

    /** The reasoner, ready to be asked; IllegalArgumentException for a name ROBOT does not know. */
    public static OWLReasonerFactory factory(String name) {
        String known = canonical(name);
        if (known == null) {
            throw new IllegalArgumentException("'" + name + "' is not a reasoner a release can "
                    + "use. These can: " + String.join(", ", NAMES) + ".");
        }
        switch (known.toLowerCase(Locale.ROOT)) {
            case "elk":
                return new org.semanticweb.elk.owlapi.ElkReasonerFactory();
            case "hermit":
                return new org.semanticweb.HermiT.ReasonerFactory();
            case "jfact":
                return new uk.ac.manchester.cs.jfact.JFactFactory();
            default:
                return new org.geneontology.whelk.owlapi.WhelkOWLReasonerFactory();
        }
    }
}
