package de.fizkarlsruhe.ise.ontoboard.odk;

import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.OWLOntologyCreationIOException;
import org.semanticweb.owlapi.io.StreamDocumentSource;
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
 * releases answered one pair at a time, chosen from two drop-downs; this reads every release once,
 * in order, so the whole path is one table.
 *
 * <p>And it says what the releases are ({@link #releasesOf}): the git tags {@code v<version>} that
 * ODK's release makes, and the copies under {@code releases/<version>/} that OntoBoard keeps -
 * History, Compare releases, the identifiers minting must not give out again and the guide all
 * ask here. Read from the folder alone, a project ODK released looked as if it had never been.
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

    /** Where a release was found. */
    public enum Source {
        /** A git tag {@code v<version>} whose commit holds the ontology: ODK's release. */
        TAG,
        /** A folder {@code releases/<version>/}: the copy OntoBoard keeps. */
        FOLDER,
        /** A tag and a folder for one version: one release. */
        BOTH
    }

    /**
     * One release of a project: its version, where it was found, and the ontology it released -
     * {@code <id>.owl} in its folder, or at the project's root in its tag's commit.
     */
    public static final class Entry {
        private final OntologyVersion version;
        private final String id;
        private final File projectRoot;
        private final File folder;
        private final String tag;
        private final String blob;
        /** What the tag holds the ontology as: {@code <id>.owl}, {@code <id>.ttl}, ... */
        private final String path;

        Entry(OntologyVersion version, String id, File projectRoot, File folder, String tag,
                String blob) {
            this(version, id, projectRoot, folder, tag, blob, id + ".owl");
        }

        Entry(OntologyVersion version, String id, File projectRoot, File folder, String tag,
                String blob, String path) {
            this.version = version;
            this.id = id;
            this.projectRoot = projectRoot;
            this.folder = folder;
            this.tag = tag;
            this.blob = blob;
            this.path = path == null ? id + ".owl" : path;
        }

        /** The version - {@code 1.2.0}, {@code 2026-10-31} - without a tag's {@code v}. */
        public String version() {
            return version.getText();
        }

        public Source source() {
            return tag == null ? Source.FOLDER : folder == null ? Source.TAG : Source.BOTH;
        }

        /** The tag, {@code v1.2.0}, or null when the release is a folder only. */
        public String tag() {
            return tag;
        }

        /** The folder under {@code releases/}, or null when the release is a tag only. */
        public File folder() {
            return folder;
        }

        /** The ontology's id: the released file is {@code <id>.owl}. */
        public String id() {
            return id;
        }

        /** The tag's {@code <id>.owl} as git names it - its blob's id - or null without a tag. */
        String blob() {
            return blob;
        }

        /**
         * The released file on disk - {@code <folder>/<id>.owl} - or null when there is none: a
         * release that is only a tag, or a folder without the file.
         */
        public File file() {
            File file = folder == null ? null : new File(folder, id + ".owl");
            return file != null && file.isFile() ? file : null;
        }

        /**
         * The released file, byte for byte: the folder's when it is there - on disk, and the copy
         * OntoBoard made of what it tagged - else what the tag holds ({@code git show}).
         */
        public byte[] bytes() throws IOException {
            File file = file();
            if (file != null) {
                return Files.readAllBytes(file.toPath());
            }
            if (tag != null) {
                byte[] held = GitTags.fileAt(projectRoot, tag, path);
                if (held != null) {
                    return held;
                }
                throw new IOException("Could not read " + path + " from the git tag " + tag
                        + ".");
            }
            throw new IOException("There is no " + id + ".owl in " + folder.getAbsolutePath()
                    + ".");
        }

        /** The released ontology, in a manager of its own: it declares the edit file's IRI. */
        public OWLOntology load() throws OWLOntologyCreationException {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            File file = file();
            if (file != null) {
                return manager.loadOntologyFromOntologyDocument(file);
            }
            try {
                return manager.loadOntologyFromOntologyDocument(
                        new StreamDocumentSource(new ByteArrayInputStream(bytes())));
            } catch (IOException unreadable) {
                throw new OWLOntologyCreationIOException(unreadable);
            }
        }

        /**
         * A file Protege can open this release from: the folder's own, or - for a release that is
         * only a tag - a copy of what the tag holds, written for the purpose.
         *
         * <p>The copy goes to the system's temporary folder, not into the project, so it is never
         * taken for part of it or committed with it. Its name says whose release it is a copy of
         * and where from - {@code ontoboard-release-copies/demo-1.0.0-copy-of-tag-v1.0.0.owl} -
         * and it is read-only, so that saving it in Protege fails instead of changing a file that
         * looks like the release. It is written again each time.
         */
        public File openable() throws IOException {
            File file = file();
            if (file != null) {
                return file;
            }
            byte[] held = bytes();
            File copies = new File(System.getProperty("java.io.tmpdir"), COPIES);
            Files.createDirectories(copies.toPath());
            File copy = new File(copies, id + "-" + version() + "-copy-of-tag-" + tag
                    + path.substring(path.lastIndexOf('.')));
            if (copy.exists() && !copy.setWritable(true)) {
                throw new IOException("Could not replace the earlier copy "
                        + copy.getAbsolutePath() + ".");
            }
            Files.write(copy.toPath(), held);
            copy.setReadOnly();
            return copy;
        }

        @Override
        public String toString() {
            return version();
        }
    }

    /** The folder, in the system's temporary folder, that copies of tagged releases go to. */
    static final String COPIES = "ontoboard-release-copies";

    /**
     * The releases of a project, newest first: the git tags {@code v<version>} whose commit holds
     * {@code <id>.owl} at the project's root - ODK's releases - and the folders
     * {@code releases/<version>/} OntoBoard keeps. A version with both is one release.
     *
     * <p>A tag without that file is no release of this ontology - a repository holds tags of
     * other things too - and is left out. A folder is a release by its name, as it always was
     * here, whether the file is in it or not. Where there is no repository, or no git, the folders
     * are all there is: no error and no message, which is an older project's normal case.
     *
     * <p>Newest first in {@link OntologyVersion}'s order, the one the tags and the folders were
     * each sorted by before: by number, so 1.10.0 is after 1.2.0, and dated releases after
     * numbered ones.
     *
     * <p>At most two git processes, however many releases there are: the tags, and one question
     * for all of them whether they hold the file - and none while the repository's tag refs are
     * as they were when git last answered, or where there is no repository ({@link ReleaseTags}).
     *
     * @param id the ontology's id: a release holds {@code <id>.owl}
     */
    public static List<Entry> releasesOf(File projectRoot, String id) {
        return releasesOf(projectRoot, id, ProcessRunner.real());
    }

    /** {@link #releasesOf(File, String)}, with the tags listed by {@code git}. */
    public static List<Entry> releasesOf(File projectRoot, String id,
            ProcessRunner.Runner git) {
        List<Entry> releases = new ArrayList<Entry>();
        if (projectRoot == null || id == null) {
            return releases;
        }
        Map<String, OntologyVersion> versions = new HashMap<String, OntologyVersion>();
        Map<String, File> folders = new HashMap<String, File>();
        File[] directories = new File(projectRoot, "releases").listFiles();
        if (directories != null) {
            // In name order, so which of releases/1.0.0 and releases/v1.0.0 is the release does
            // not depend on the disk's order: the one named as the version, else the first.
            Arrays.sort(directories);
            for (File directory : directories) {
                OntologyVersion version = directory.isDirectory()
                        ? OntologyVersion.parseOrNull(directory.getName()) : null;
                if (version != null && (!folders.containsKey(version.getText())
                        || directory.getName().equals(version.getText()))) {
                    folders.put(version.getText(), directory);
                    versions.put(version.getText(), version);
                }
            }
        }
        Map<String, String> tags = new HashMap<String, String>();
        Map<String, ReleaseTags.Held> held = new HashMap<String, ReleaseTags.Held>();
        for (Map.Entry<String, ReleaseTags.Held> one
                : ReleaseTags.holding(projectRoot, id, git).entrySet()) {
            OntologyVersion version = OntologyVersion.of(GitTags.versionOf(one.getKey()));
            if (!tags.containsKey(version.getText())
                    || one.getKey().equals(GitTags.tagFor(version.getText()))) {
                tags.put(version.getText(), one.getKey());
                held.put(version.getText(), one.getValue());
                versions.put(version.getText(), version);
            }
        }
        List<OntologyVersion> newestFirst = new ArrayList<OntologyVersion>(versions.values());
        Collections.sort(newestFirst, NEWEST_FIRST);
        for (OntologyVersion version : newestFirst) {
            String text = version.getText();
            ReleaseTags.Held tagged = held.get(text);
            releases.add(new Entry(version, id, projectRoot, folders.get(text), tags.get(text),
                    tagged == null ? null : tagged.blob, tagged == null ? null : tagged.path));
        }
        return releases;
    }

    /** By version, newest first; two that are one version written two ways (1.0, 1.0.0) by text. */
    private static final Comparator<OntologyVersion> NEWEST_FIRST =
            new Comparator<OntologyVersion>() {
                @Override
                public int compare(OntologyVersion one, OntologyVersion other) {
                    int byVersion = other.compareTo(one);
                    return byVersion != 0 ? byVersion : other.getText().compareTo(one.getText());
                }
            };

    /** One version: a kept release, or the working copy. */
    public static final class Version {
        private final String name;
        private final File file;
        private final Entry release;
        private final String versionInfo;
        private final Counts counts;
        private final ReleaseDiff sincePrevious;
        private final List<String> propertiesAdded;
        private final List<String> propertiesRemoved;
        private final List<String> individualsAdded;
        private final List<String> individualsRemoved;
        /** Why it could not be read, or null when it was. */
        private String whyUnreadable;

        /** A release that could not be read: shown, with why, and compared with nothing. */
        static Version unreadable(String name, File file, Entry release, String why) {
            List<String> none = Collections.<String>emptyList();
            Version version = new Version(name, file, release, "", new Counts(0, 0, 0, 0, 0, 0),
                    null, none, none, none, none);
            version.whyUnreadable = why;
            return version;
        }

        /** Whether it could be read; when not, {@link #describeChange} says why. */
        public boolean isReadable() {
            return whyUnreadable == null;
        }

        Version(String name, File file, Entry release, String versionInfo, Counts counts,
                ReleaseDiff sincePrevious, List<String> propertiesAdded,
                List<String> propertiesRemoved, List<String> individualsAdded,
                List<String> individualsRemoved) {
            this.name = name;
            this.file = file;
            this.release = release;
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

        /**
         * The file it was read from: the release's copy, or the edit file - null for a release
         * that is only a git tag, which {@link #getRelease} reads.
         */
        public File getFile() {
            return file;
        }

        /** The release it is, or null for the working copy. */
        public Entry getRelease() {
            return release;
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
            if (whyUnreadable != null) {
                return "Could not be read: " + whyUnreadable;
            }
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
        List<Entry> releases = new ArrayList<Entry>();
        for (String name : versionsNewestFirst) {
            releases.add(new Entry(OntologyVersion.of(name), ontologyId, projectRoot,
                    Release.releaseFile(projectRoot, ontologyId, name).getParentFile(), null,
                    null));
        }
        return of(releases, workingCopy, workingCopyFile, progress);
    }

    /**
     * Every release given, newest first, and the working copy above them.
     *
     * @param releasesNewestFirst the releases to read, as {@link #releasesOf} lists them
     * @param workingCopy the ontology being edited, or null to leave it out
     */
    public static List<Version> of(List<Entry> releasesNewestFirst, OWLOntology workingCopy,
            File workingCopyFile, Progress progress) throws OWLOntologyCreationException {
        List<Entry> oldestFirst = new ArrayList<Entry>(releasesNewestFirst);
        Collections.reverse(oldestFirst);
        int total = oldestFirst.size() + (workingCopy == null ? 0 : 1);

        List<Version> versions = new ArrayList<Version>();
        OWLOntology previous = null;
        for (int at = 0; at < oldestFirst.size(); at++) {
            Entry entry = oldestFirst.get(at);
            if (progress != null) {
                progress.reading(entry.version(), at + 1, total);
            }
            // One that cannot be read is shown as such, and the next is compared with the last
            // one that could (1.155.0: History stopped at the first it could not read).
            OWLOntology release;
            try {
                release = entry.load();
                versions.add(versionOf(entry.version(), entry.file(), entry, release, previous));
            } catch (OWLOntologyCreationException | RuntimeException unreadable) {
                versions.add(Version.unreadable(entry.version(), entry.file(), entry,
                        firstLineOf(unreadable)));
                continue;
            }
            previous = release;
        }
        if (workingCopy != null) {
            if (progress != null) {
                progress.reading(WORKING_COPY, total, total);
            }
            versions.add(versionOf(WORKING_COPY, workingCopyFile, null, asReleased(workingCopy),
                    previous));
        }
        Collections.reverse(versions);
        return versions;
    }

    /** What went wrong, in its first line - a parser's message runs on for a page. */
    private static String firstLineOf(Exception unreadable) {
        String said = unreadable.getMessage();
        if (said == null || said.trim().isEmpty()) {
            said = unreadable.getClass().getSimpleName();
        }
        said = said.trim();
        int end = said.indexOf('\n');
        said = end < 0 ? said : said.substring(0, end).trim();
        return said.length() > 240 ? said.substring(0, 240) + "..." : said;
    }

    /**
     * {@code later} against {@code earlier}, which need not be neighbours: "what changed since
     * 0.2.0", from any version.
     */
    public static Version compare(Version earlier, Version later, OWLOntology workingCopy)
            throws OWLOntologyCreationException {
        return versionOf(later.getName(), later.getFile(), later.getRelease(),
                read(later, workingCopy), read(earlier, workingCopy));
    }

    private static OWLOntology read(Version version, OWLOntology workingCopy)
            throws OWLOntologyCreationException {
        if (version.isWorkingCopy()) {
            return asReleased(workingCopy);
        }
        if (version.getRelease() != null) {
            return version.getRelease().load();
        }
        return OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(version.getFile());
    }

    private static Version versionOf(String name, File file, Entry release, OWLOntology ontology,
            OWLOntology previous) {
        // Classes only. Properties and individuals are compared below, and since 1.135.1 the
        // diff covers properties as well, which would count each new one twice.
        ReleaseDiff diff = previous == null ? null : ReleaseDiff.between(previous, ontology)
                .restrictedTo(org.semanticweb.owlapi.model.EntityType.CLASS);
        Set<String> properties = propertiesOf(ontology);
        Set<String> individuals = individualsOf(ontology);
        Set<String> propertiesBefore = previous == null ? properties : propertiesOf(previous);
        Set<String> individualsBefore = previous == null ? individuals : individualsOf(previous);
        return new Version(name, file, release, versionInfoOf(ontology), countsOf(ontology),
                diff,
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
                    && de.fizkarlsruhe.ise.ontoboard.robot.DeprecatedValue.isTrue(
                            (OWLLiteral) axiom.getValue())) {
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
