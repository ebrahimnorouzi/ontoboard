package de.fizkarlsruhe.ise.ontoboard.odk;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

/**
 * How an ontology got to where it is: each release it kept, what each holds, and what changed from
 * one to the next.
 *
 * <p>Asked for: "go through the evolution of an ontology - all the available versions, open any of
 * them, and the statistics of what was changed: added, deleted, obsoleted". Project &gt; Compare
 * releases answered one pair at a time, chosen from two drop-downs; this reads every release in
 * {@code releases/} once, in order, so the whole path is one table.
 *
 * <p>Pure OWL API and files, so it is testable without Protege. One release is held in memory at a
 * time beside the one before it, because the question is what changed between neighbours, and an
 * ontology with forty releases would otherwise be forty ontologies.
 */
public final class ReleaseHistory {

    /** The name the edit file is listed under, newest of all. */
    public static final String WORKING_COPY = "Working copy";

    private ReleaseHistory() {
    }

    /** What one version holds. */
    public static final class Counts {
        private final int classes;
        private final int objectProperties;
        private final int dataProperties;
        private final int individuals;
        private final int logicalAxioms;
        private final int obsolete;

        Counts(int classes, int objectProperties, int dataProperties, int individuals,
                int logicalAxioms, int obsolete) {
            this.classes = classes;
            this.objectProperties = objectProperties;
            this.dataProperties = dataProperties;
            this.individuals = individuals;
            this.logicalAxioms = logicalAxioms;
            this.obsolete = obsolete;
        }

        public int getClasses() {
            return classes;
        }

        public int getObjectProperties() {
            return objectProperties;
        }

        public int getDataProperties() {
            return dataProperties;
        }

        /** Object and data properties together. */
        public int getProperties() {
            return objectProperties + dataProperties;
        }

        public int getIndividuals() {
            return individuals;
        }

        /** Every class, property and individual. */
        public int getTerms() {
            return classes + getProperties() + individuals;
        }

        /** Axioms that say something about the domain - not declarations or annotations. */
        public int getLogicalAxioms() {
            return logicalAxioms;
        }

        /** Terms marked {@code owl:deprecated}. */
        public int getObsolete() {
            return obsolete;
        }
    }

    /** One version: a kept release, or the working copy. */
    public static final class Version {
        private final String name;
        private final File file;
        private final String versionInfo;
        private final Counts counts;
        private final ReleaseDiff sincePrevious;
        private final List<String> propertiesAdded;
        private final List<String> propertiesRemoved;
        private final List<String> individualsAdded;
        private final List<String> individualsRemoved;

        Version(String name, File file, String versionInfo, Counts counts,
                ReleaseDiff sincePrevious, List<String> propertiesAdded,
                List<String> propertiesRemoved, List<String> individualsAdded,
                List<String> individualsRemoved) {
            this.name = name;
            this.file = file;
            this.versionInfo = versionInfo;
            this.counts = counts;
            this.sincePrevious = sincePrevious;
            this.propertiesAdded = propertiesAdded;
            this.propertiesRemoved = propertiesRemoved;
            this.individualsAdded = individualsAdded;
            this.individualsRemoved = individualsRemoved;
        }

        /** The release's version - {@code 1.2.0}, {@code 2026-10-31} - or {@link #WORKING_COPY}. */
        public String getName() {
            return name;
        }

        /** The file it was read from: the release's copy, or the edit file. */
        public File getFile() {
            return file;
        }

        /** Its {@code owl:versionInfo}, or "" when it has none. */
        public String getVersionInfo() {
            return versionInfo;
        }

        public Counts getCounts() {
            return counts;
        }

        /** The classes that changed since the version before, or null for the first. */
        public ReleaseDiff getSincePrevious() {
            return sincePrevious;
        }

        public List<String> getPropertiesAdded() {
            return propertiesAdded;
        }

        public List<String> getPropertiesRemoved() {
            return propertiesRemoved;
        }

        public List<String> getIndividualsAdded() {
            return individualsAdded;
        }

        public List<String> getIndividualsRemoved() {
            return individualsRemoved;
        }

        public boolean isWorkingCopy() {
            return WORKING_COPY.equals(name);
        }

        /**
         * What changed since the version before, in a line: "+12 classes, 1 obsoleted, 2 moved,
         * +3 properties". "First release" for the first, "No change" when nothing did.
         */
        public String describeChange() {
            if (sincePrevious == null) {
                return "First release";
            }
            List<String> parts = new ArrayList<String>();
            add(parts, sincePrevious.count(ReleaseDiff.Change.ADDED), "+", "class", "classes");
            add(parts, sincePrevious.count(ReleaseDiff.Change.REMOVED), "-", "class REMOVED",
                    "classes REMOVED");
            add(parts, sincePrevious.count(ReleaseDiff.Change.OBSOLETED), "", "obsoleted",
                    "obsoleted");
            add(parts, sincePrevious.count(ReleaseDiff.Change.RELABELLED), "", "relabelled",
                    "relabelled");
            add(parts, sincePrevious.count(ReleaseDiff.Change.REDEFINED), "", "redefined",
                    "redefined");
            add(parts, sincePrevious.count(ReleaseDiff.Change.MOVED), "", "moved", "moved");
            add(parts, propertiesAdded.size(), "+", "property", "properties");
            add(parts, propertiesRemoved.size(), "-", "property", "properties");
            add(parts, individualsAdded.size(), "+", "individual", "individuals");
            add(parts, individualsRemoved.size(), "-", "individual", "individuals");
            if (parts.isEmpty()) {
                return "No change to any term";
            }
            StringBuilder line = new StringBuilder();
            for (String part : parts) {
                line.append(line.length() == 0 ? "" : ", ").append(part);
            }
            return line.toString();
        }

        private static void add(List<String> parts, int count, String sign, String one,
                String many) {
            if (count > 0) {
                parts.add(sign + count + " " + (count == 1 ? one : many));
            }
        }
    }

    /** Told as each version is read, so a long history can say how far it has got. */
    public interface Progress {
        void reading(String version, int index, int of);
    }

    /**
     * Every kept release of the project, newest first, and the working copy above them.
     *
     * @param versionsNewestFirst the releases to read, as {@code releases/<version>/} names them
     * @param workingCopy the ontology being edited, or null to leave it out
     */
    public static List<Version> of(File projectRoot, String ontologyId,
            List<String> versionsNewestFirst, OWLOntology workingCopy, File workingCopyFile,
            Progress progress) throws OWLOntologyCreationException {
        List<String> oldestFirst = new ArrayList<String>(versionsNewestFirst);
        Collections.reverse(oldestFirst);
        int total = oldestFirst.size() + (workingCopy == null ? 0 : 1);

        List<Version> versions = new ArrayList<Version>();
        OWLOntology previous = null;
        for (int at = 0; at < oldestFirst.size(); at++) {
            String name = oldestFirst.get(at);
            if (progress != null) {
                progress.reading(name, at + 1, total);
            }
            File file = Release.releaseFile(projectRoot, ontologyId, name);
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology release = manager.loadOntologyFromOntologyDocument(file);
            versions.add(versionOf(name, file, release, previous));
            previous = release;
        }
        if (workingCopy != null) {
            if (progress != null) {
                progress.reading(WORKING_COPY, total, total);
            }
            versions.add(versionOf(WORKING_COPY, workingCopyFile, asReleased(workingCopy),
                    previous));
        }
        Collections.reverse(versions);
        return versions;
    }

    /**
     * {@code later} against {@code earlier}, which need not be neighbours: "what changed since
     * 0.2.0", from any version.
     */
    public static Version compare(Version earlier, Version later, OWLOntology workingCopy)
            throws OWLOntologyCreationException {
        return versionOf(later.getName(), later.getFile(), read(later, workingCopy),
                read(earlier, workingCopy));
    }

    private static OWLOntology read(Version version, OWLOntology workingCopy)
            throws OWLOntologyCreationException {
        if (version.isWorkingCopy()) {
            return asReleased(workingCopy);
        }
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(version.getFile());
    }

    private static Version versionOf(String name, File file, OWLOntology ontology,
            OWLOntology previous) {
        ReleaseDiff diff = previous == null ? null : ReleaseDiff.between(previous, ontology);
        Set<String> properties = propertiesOf(ontology);
        Set<String> individuals = individualsOf(ontology);
        Set<String> propertiesBefore = previous == null ? properties : propertiesOf(previous);
        Set<String> individualsBefore = previous == null ? individuals : individualsOf(previous);
        return new Version(name, file, versionInfoOf(ontology), countsOf(ontology), diff,
                minus(properties, propertiesBefore), minus(propertiesBefore, properties),
                minus(individuals, individualsBefore), minus(individualsBefore, individuals));
    }

    /** What an ontology holds, its imports included. */
    public static Counts countsOf(OWLOntology ontology) {
        int classes = 0;
        for (OWLEntity entity : ontology.getClassesInSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                classes++;
            }
        }
        int objectProperties = 0;
        for (OWLEntity entity : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                objectProperties++;
            }
        }
        int dataProperties = 0;
        for (OWLEntity entity : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                dataProperties++;
            }
        }
        int obsolete = 0;
        for (OWLAnnotationAssertionAxiom axiom
                : ontology.getAxioms(org.semanticweb.owlapi.model.AxiomType.ANNOTATION_ASSERTION,
                        Imports.INCLUDED)) {
            if (OWLRDFVocabulary.OWL_DEPRECATED.getIRI().equals(axiom.getProperty().getIRI())
                    && axiom.getValue() instanceof OWLLiteral
                    && ((OWLLiteral) axiom.getValue()).parseBoolean()) {
                obsolete++;
            }
        }
        return new Counts(classes, objectProperties, dataProperties,
                ontology.getIndividualsInSignature(Imports.INCLUDED).size(),
                ontology.getLogicalAxiomCount(Imports.INCLUDED), obsolete);
    }

    /**
     * The working copy as a release would hold it: its imports folded in, and reasoned the way
     * Release reasons, with ELK.
     *
     * <p>Because a release is both and the edit file is neither. Compared as they are, every term
     * an import brings in was reported as removed since the last release, and every parent the
     * reasoner had added to it as a term moved: the pizza walkthrough's working copy, identical to
     * its 1.0.0 release, came back "7 moved". Unreasoned, if the reasoner cannot finish - which
     * is still right about everything but inferred parents.
     */
    static OWLOntology asReleased(OWLOntology workingCopy) throws OWLOntologyCreationException {
        OWLOntology copy = merged(workingCopy);
        try {
            de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.Diff inferred =
                    de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.preview(copy,
                            de.fizkarlsruhe.ise.ontoboard.robot.RobotTransform.Kind.REASON,
                            de.fizkarlsruhe.ise.ontoboard.robot.Reasoners.DEFAULT.newFactory());
            copy.getOWLOntologyManager().applyChanges(inferred.getChanges());
        } catch (RuntimeException cannotReason) {
            // Compared unreasoned.
        }
        return copy;
    }

    /** A copy of the ontology with its imports folded in, as a release has them. */
    static OWLOntology merged(OWLOntology ontology) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology merged = manager.createOntology();
        for (OWLOntology part : ontology.getImportsClosure()) {
            manager.addAxioms(merged, part.getAxioms());
        }
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(merged,
                    annotation));
        }
        return merged;
    }

    private static String versionInfoOf(OWLOntology ontology) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            if (OWLRDFVocabulary.OWL_VERSION_INFO.getIRI().equals(annotation.getProperty().getIRI())
                    && annotation.getValue() instanceof OWLLiteral) {
                return ((OWLLiteral) annotation.getValue()).getLiteral();
            }
        }
        return "";
    }

    private static Set<String> propertiesOf(OWLOntology ontology) {
        Set<String> iris = new LinkedHashSet<String>();
        for (OWLEntity entity : ontology.getObjectPropertiesInSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                iris.add(entity.getIRI().toString());
            }
        }
        for (OWLEntity entity : ontology.getDataPropertiesInSignature(Imports.INCLUDED)) {
            if (!entity.isBuiltIn()) {
                iris.add(entity.getIRI().toString());
            }
        }
        return iris;
    }

    private static Set<String> individualsOf(OWLOntology ontology) {
        Set<String> iris = new LinkedHashSet<String>();
        for (OWLEntity entity : ontology.getIndividualsInSignature(Imports.INCLUDED)) {
            iris.add(entity.getIRI().toString());
        }
        return iris;
    }

    private static List<String> minus(Set<String> these, Set<String> those) {
        List<String> left = new ArrayList<String>();
        for (String iri : these) {
            if (!those.contains(iri)) {
                left.add(iri);
            }
        }
        return Collections.unmodifiableList(left);
    }

    /** A term's short form, for a list: its fragment or last path segment. */
    public static String shortForm(String iri) {
        return ReleaseDiff.shortForm(IRI.create(iri));
    }
}
