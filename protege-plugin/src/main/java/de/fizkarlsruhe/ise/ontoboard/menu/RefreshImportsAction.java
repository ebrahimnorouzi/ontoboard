package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Project &gt; Refresh imports - are this project's import modules rebuildable, and rebuild them.
 *
 * <p>ODK's {@code all_imports} and {@code refresh-imports} targets exist because an import module is
 * not a thing you keep - it is a thing you regenerate from a committed list of terms whenever the
 * upstream ontology moves. A repository whose {@code imports/} holds modules and no term lists is
 * one nobody else can build: the modules cannot be extended, cannot be refreshed, and cannot be
 * checked for staleness against their source.
 *
 * <p>This answers both halves. By default it only reports - which imports have a term list, which
 * have a module, which record where they came from - because that audit is what tells somebody
 * whether the repository they just cloned is reproducible, and it needs no network. Turning on the
 * rebuild re-extracts each module from its recorded source, which does need the network, and is the
 * in-Protege equivalent of {@code make refresh-imports}.
 *
 * <p>This plugin wrote modules without term lists until 1.38.0, so a project built with an earlier
 * version will report imports that cannot be rebuilt. Re-running <em>ROBOT &gt; Import terms...</em>
 * for those writes the missing list.
 */
public class RefreshImportsAction extends OntoBoardAction {

    private static final long serialVersionUID = 1L;

    private static final String OPTION_REBUILD = "rebuild";
    private static final String OPTION_MIRROR = "mirror";

    private volatile boolean rebuild;
    private volatile boolean updateMirror;

    @Override
    protected String operationName() {
        return "Refresh imports";
    }

    @Override
    protected boolean runsInBackground() {
        return true;
    }

    @Override
    protected boolean configure() {
        List<ImportModules.Module> modules = ImportModules.modulesIn(
                projectRootOf(getOWLModelManager() == null ? null
                        : getOWLModelManager().getActiveOntology()));
        int rebuildable = 0;
        for (ImportModules.Module module : modules) {
            if (module.isRebuildable()) {
                rebuildable++;
            }
        }
        String found = modules.isEmpty()
                ? "This project has no imports/ directory yet, or nothing in it."
                : modules.size() + " import" + (modules.size() == 1 ? "" : "s") + " found, "
                        + rebuildable + " with a term list.";

        Map<String, String> chosen = ParameterDialog.show(getOWLWorkspace(), "Refresh imports",
                "An import module is meant to be regenerated from a committed list of terms, not "
                        + "kept as a file. " + found,
                Arrays.asList(
                        Parameter.of(OPTION_REBUILD, "Rebuild the modules", Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("Off by default, so this reports without touching anything "
                                        + "and without needing the network - which is the question "
                                        + "worth asking about a repository you have just cloned: "
                                        + "can it be rebuilt at all?\n\nTurning it on re-extracts "
                                        + "each module from the source recorded in its term list, "
                                        + "which downloads that source unless it is mirrored. It "
                                        + "is the same job as `make refresh-imports`.")
                                .build(),
                        Parameter.of(OPTION_MIRROR, "Download fresh copies first (update the "
                                + "mirror)", Parameter.Kind.FLAG)
                                .defaultValue("false")
                                .help("ODK keeps a downloaded copy of each upstream ontology under "
                                        + "src/ontology/mirror/, and extracts modules from that "
                                        + "rather than from the network. Two things follow: a "
                                        + "rebuild works offline once the mirror exists, and a "
                                        + "module records what it was actually built from rather "
                                        + "than whatever the upstream was that afternoon.\n\n"
                                        + "With this off, a rebuild uses the mirror when there is "
                                        + "one and downloads when there is not. With it on, every "
                                        + "source is downloaded again and the mirror is replaced - "
                                        + "which is what you want when upstream has released.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        rebuild = "true".equalsIgnoreCase(chosen.get(OPTION_REBUILD));
        updateMirror = "true".equalsIgnoreCase(chosen.get(OPTION_MIRROR));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File root = projectRootOf(ontology);
        OperationResult.Builder result = OperationResult.of(operationName())
                .columns("Import", "Module", "Term list", "Source");

        if (root == null) {
            return result.failed("This ontology has not been saved, so there is no project to look "
                    + "in.").build();
        }
        List<ImportModules.Module> modules = ImportModules.modulesIn(root);
        if (modules.isEmpty()) {
            return result.summary("No imports. Nothing under "
                    + ImportModules.IMPORTS_DIRECTORY + " in " + root.getAbsolutePath()
                    + ".").build();
        }

        int notRebuildable = 0;
        int rebuilt = 0;
        for (ImportModules.Module module : modules) {
            String source = sourceOf(module);
            result.row(module.getName(),
                    module.hasModule() ? "present" : "not built",
                    module.isRebuildable() ? "present" : "MISSING",
                    source == null ? "unrecorded" : source);

            if (!module.isRebuildable()) {
                notRebuildable++;
                result.warn(module.getName() + " has no term list, so nobody can rebuild, extend "
                        + "or refresh it. Re-run ROBOT > Import terms... for it to write one.");
                continue;
            }
            if (source == null) {
                result.warn(module.getName() + " has a term list but does not record where its "
                        + "terms came from, so a refresh cannot know what to extract from.");
                continue;
            }
            if (rebuild) {
                rebuilt += rebuildOne(module, source, root, result) ? 1 : 0;
            }
        }

        if (notRebuildable > 0) {
            result.warn(notRebuildable + " of " + modules.size() + " imports cannot be rebuilt "
                    + "from what this repository contains. That is the property ODK's imports/ "
                    + "layout exists to provide, and it is what makes a clone buildable.");
        }
        if (!rebuild) {
            result.note(ImportModules.hasMirror(root)
                    ? "This project has a mirror, so a rebuild would work offline."
                    : "This project has no mirror, so a rebuild would download each source. "
                            + "Turn on the mirror option to keep local copies.");
            result.note("Nothing was changed - 'Rebuild the modules' was off.");
            return result.summary(modules.size() + " imports, "
                    + (modules.size() - notRebuildable) + " rebuildable.").build();
        }
        return result.summary("Rebuilt " + rebuilt + " of " + modules.size() + " imports.").build();
    }

    /** Re-extracts one module from its recorded source. */
    private boolean rebuildOne(ImportModules.Module module, String source, File root,
            OperationResult.Builder result) {
        try {
            List<IRI> terms = ImportModules.readTerms(module.getTermsFile());
            for (String malformed : ImportModules.malformedIn(module.getTermsFile())) {
                result.warn(module.getName() + ": '" + malformed + "' is not an IRI, so it was "
                        + "left out of the rebuild.");
            }
            if (terms.isEmpty()) {
                result.warn(module.getName() + " has an empty term list, so rebuilding it would "
                        + "produce an empty module. Left alone.");
                return false;
            }

            OWLOntology upstream = upstreamFor(module, source, root, result);
            IRI moduleIri = IRI.create(module.getModuleFile().toURI());
            TermExtract.Result extracted = TermExtract.run(upstream, terms,
                    TermExtract.DEFAULT_METHOD, moduleIri);

            extracted.getModule().getOWLOntologyManager().saveOntology(extracted.getModule(),
                    IRI.create(module.getModuleFile().toURI()));
            result.wrote(module.getModuleFile());
            for (IRI missing : extracted.getMissing()) {
                result.warn(module.getName() + ": " + missing + " is in the term list but not in "
                        + source + " any more. The upstream ontology may have obsoleted it.");
            }
            return true;
        } catch (Exception cannotRebuild) {
            result.warn("Could not rebuild " + module.getName() + " from " + source + ": "
                    + cannotRebuild.getMessage());
            return false;
        }
    }

    /**
     * The upstream ontology, from the mirror where possible.
     *
     * <p>Reading the mirror is what makes a refresh repeatable and offline. Downloading replaces
     * it, which is what "upstream has released" calls for - and writing the mirror is
     * {@code MirrorOperation}'s job, so it brings the whole imports closure and a catalog with it
     * rather than one file that then cannot resolve its own imports.
     */
    private OWLOntology upstreamFor(ImportModules.Module module, String source, File root,
            OperationResult.Builder result) throws Exception {
        File mirrorDirectory = ImportModules.mirrorDirectoryIn(root);
        boolean mirrored = ImportModules.hasMirror(root);

        if (!updateMirror && mirrored) {
            try {
                // AutoIRIMapper scans the directory and maps each ontology's own IRI to its file,
                // so loading the source IRI resolves locally with no network at all.
                OWLOntologyManager offline = OWLManager.createOWLOntologyManager();
                offline.getIRIMappers().add(
                        new org.semanticweb.owlapi.util.AutoIRIMapper(mirrorDirectory, true));
                OWLOntology fromMirror = offline.loadOntology(IRI.create(source));
                result.note(module.getName() + ": read " + source + " from the mirror.");
                return fromMirror;
            } catch (Exception notInMirror) {
                result.note(module.getName() + ": not in the mirror (" + notInMirror.getMessage()
                        + "), downloading instead.");
            }
        }

        OWLOntology downloaded = OntologySource.load(OWLManager.createOWLOntologyManager(),
                IRI.create(source));
        try {
            if (!mirrorDirectory.isDirectory() && !mirrorDirectory.mkdirs()) {
                throw new java.io.IOException("could not create " + mirrorDirectory);
            }
            org.obolibrary.robot.MirrorOperation.mirror(downloaded, mirrorDirectory,
                    new File(mirrorDirectory, "catalog-v001.xml"));
            result.note(module.getName() + ": mirrored " + source + " into "
                    + ImportModules.MIRROR_DIRECTORY + ".");
        } catch (Exception cannotMirror) {
            // The extraction can still go ahead from the copy in memory; only the repeatability is
            // lost, and saying so is better than failing a refresh that otherwise worked.
            result.warn(module.getName() + ": downloaded " + source + " but could not mirror it ("
                    + cannotMirror.getMessage() + "), so the next refresh will download again.");
        }
        return downloaded;
    }

    private String sourceOf(ImportModules.Module module) {
        try {
            return ImportModules.sourceIn(module.getTermsFile());
        } catch (java.io.IOException cannotRead) {
            return null;
        }
    }

}
