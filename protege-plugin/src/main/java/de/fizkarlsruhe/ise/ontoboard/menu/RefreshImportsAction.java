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

    private volatile boolean rebuild;

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
        List<ImportModules.Module> modules = ImportModules.modulesIn(projectRoot());
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
                                        + "which downloads that source. It is the same job as "
                                        + "`make refresh-imports`.")
                                .build()));
        if (chosen == null) {
            return false;
        }
        rebuild = "true".equalsIgnoreCase(chosen.get(OPTION_REBUILD));
        return true;
    }

    @Override
    protected OperationResult run(OWLOntology ontology) {
        File root = projectRoot();
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
                rebuilt += rebuildOne(module, source, result) ? 1 : 0;
            }
        }

        if (notRebuildable > 0) {
            result.warn(notRebuildable + " of " + modules.size() + " imports cannot be rebuilt "
                    + "from what this repository contains. That is the property ODK's imports/ "
                    + "layout exists to provide, and it is what makes a clone buildable.");
        }
        if (!rebuild) {
            result.note("Nothing was changed - 'Rebuild the modules' was off.");
            return result.summary(modules.size() + " imports, "
                    + (modules.size() - notRebuildable) + " rebuildable.").build();
        }
        return result.summary("Rebuilt " + rebuilt + " of " + modules.size() + " imports.").build();
    }

    /** Re-extracts one module from its recorded source. */
    private boolean rebuildOne(ImportModules.Module module, String source,
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

            OWLOntology upstream = OntologySource.load(OWLManager.createOWLOntologyManager(),
                    IRI.create(source));
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

    private String sourceOf(ImportModules.Module module) {
        try {
            return ImportModules.sourceIn(module.getTermsFile());
        } catch (java.io.IOException cannotRead) {
            return null;
        }
    }

    /** The ODK project the open ontology belongs to, or null when it has never been saved. */
    private File projectRoot() {
        try {
            OWLOntology ontology = getOWLModelManager().getActiveOntology();
            if (ontology == null) {
                return null;
            }
            URI documentUri = getOWLModelManager().getOWLOntologyManager()
                    .getOntologyDocumentIRI(ontology).toURI();
            if (!"file".equalsIgnoreCase(documentUri.getScheme())) {
                return null;
            }
            return ReleaseAction.projectRootOf(new File(documentUri));
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
