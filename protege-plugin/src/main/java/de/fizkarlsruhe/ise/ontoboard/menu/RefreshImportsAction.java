package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.ImportModules;
import de.fizkarlsruhe.ise.ontoboard.odk.ImportProducts;
import de.fizkarlsruhe.ise.ontoboard.robot.OntologySource;
import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>This answers both halves. By default it only reports - which imports the project declares,
 * which have a term list, which have a module, where each comes from - because that audit is what
 * tells somebody whether the repository they just cloned is reproducible, and it needs no network.
 * Turning on the rebuild re-extracts each module from its declared source, which does need the
 * network, and is the in-Protege equivalent of {@code make refresh-imports}.
 *
 * <p><b>The list comes from the project, not from the directory.</b> This scanned
 * {@code imports/} and read each module's upstream from a {@code # Source:} comment at the top of
 * the term list - a comment that only {@link ImportModules#writeTerms} ever writes. Across
 * NFDIcore, MWO, PMDCO and ECTO there are 39 term lists and not one carries it, so the rebuild
 * worked on projects OntoBoard had made and on no real one. {@link ImportProducts} reads
 * {@code import_group.products} instead, which is where ODK itself gets the answer, and that gives
 * all 35 products those four projects declare a source.
 *
 * <p>Two things only the declaration can tell you, and both turned up in real repositories. An
 * import can be <b>declared and absent</b> - exactly the state adding one to the list leaves you
 * in. And an import can be <b>on disk and undeclared</b>, which means ODK's {@code IMPORTS} list
 * does not contain it, {@code make refresh-imports} never touches it, and nothing regenerates it;
 * three of the four projects have one.
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
        OWLOntology active = getOWLModelManager() == null ? null
                : getOWLModelManager().getActiveOntology();
        File editFile = active == null ? null : fileOf(active);
        List<ImportProducts.Product> declared = editFile == null
                ? java.util.Collections.<ImportProducts.Product>emptyList()
                : ImportProducts.declaredIn(editFile.getAbsoluteFile().getParentFile());
        int canRebuild = 0;
        for (ImportProducts.Product product : declared) {
            if (product.isRebuildable()) {
                canRebuild++;
            }
        }
        // Counted from the declaration, not from the directory, because that is what decides
        // whether a rebuild is possible - and because "6 imports found" followed by six refusals
        // is a worse thing to read before pressing the button than the honest number.
        String found = declared.isEmpty()
                ? "This project declares no imports in import_group.products."
                : declared.size() + " import" + (declared.size() == 1 ? "" : "s") + " declared, "
                        + canRebuild + " of them a kind this can rebuild.";

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
                .columns("Import", "Declared as", "Module", "Term list", "Source");

        if (root == null) {
            return result.failed("This ontology has not been saved, so there is no project to look "
                    + "in.").build();
        }

        // What the project says, which is the thing this used to not read. An import's upstream
        // source lives in import_group.products - mirror_from, or the OBO PURL by default - and
        // not in the term list, where only OntoBoard ever wrote one.
        File editFile = fileOf(ontology);
        List<ImportProducts.Product> declared = editFile == null
                ? java.util.Collections.<ImportProducts.Product>emptyList()
                : ImportProducts.declaredIn(editFile.getAbsoluteFile().getParentFile());
        List<ImportModules.Module> onDisk = ImportModules.modulesIn(root);

        if (declared.isEmpty() && onDisk.isEmpty()) {
            return result.summary("No imports. This project declares none in "
                    + "import_group.products, and there is nothing under "
                    + ImportModules.IMPORTS_DIRECTORY + " in " + root.getAbsolutePath()
                    + ".").build();
        }

        // The merge of the two is ImportProducts.audit: pure, and tested, because that is where
        // the judgement lives. A declared import can be absent and an import on disk can be
        // undeclared, and only one of the two sources of truth knows about each.
        List<ImportProducts.Row> rows = ImportProducts.audit(declared, onDisk, root);

        int rebuilt = 0;
        int rebuildable = 0;
        int noTermList = 0;
        int undeclared = 0;
        for (ImportProducts.Row row : rows) {
            ImportModules.Module module = row.getModule();
            result.row(row.getName(),
                    row.describeDeclaration(),
                    module.hasModule() ? "present" : "not built",
                    module.isRebuildable() ? "present"
                            : (row.termListIsRead() ? "MISSING" : "not used"),
                    row.getSource() == null ? "unrecorded" : row.getSource());

            switch (row.getVerdict()) {
                case NO_TERM_LIST:
                    noTermList++;
                    result.warn(row.getName() + " is declared as " + row.describeDeclaration()
                            + " but has no term list, so neither this nor "
                            + "`make refresh-imports` can build it. Run ROBOT > Import terms... "
                            + "for it to write one.");
                    break;
                case REFUSED:
                    // Named rather than rebuilt approximately. Each reason is ODK's own rule for
                    // that module_type said in words, and the worst outcome here would be a module
                    // that looks right, differs from the repository's, and says nothing about it.
                    result.note(row.getName() + ": " + row.getProduct().getRefusal());
                    break;
                case NO_SOURCE:
                    result.warn(row.getName() + " declares mirror_type: no_mirror, so there is no "
                            + "upstream to extract from. Its module is produced some other way.");
                    break;
                case UNDECLARED:
                    undeclared++;
                    result.warn(row.getName() + " is in " + ImportModules.IMPORTS_DIRECTORY
                            + " but is not in this project's import_group.products. ODK builds "
                            + "its IMPORTS list from that key, so `make refresh-imports` leaves "
                            + "this file alone and nothing regenerates it. Add it as a product - "
                            + "Project > Project configuration..., \"Add to a list...\" - or "
                            + "delete the file.");
                    break;
                default:
                    break;
            }
            if (row.willRebuild()) {
                rebuildable++;
                if (rebuild) {
                    rebuilt += rebuildOne(module, row.getSource(), row.getMethod(), root, result)
                            ? 1 : 0;
                }
            }
        }

        if (noTermList > 0) {
            result.warn(noTermList + " of " + declared.size() + " declared imports cannot be "
                    + "rebuilt from what this repository contains. That is the property ODK's "
                    + "imports/ layout exists to provide, and it is what makes a clone "
                    + "buildable.");
        }
        if (declared.isEmpty() && !onDisk.isEmpty()) {
            // Said plainly because OntoBoard causes it. Its scaffold writes `products: []` into
            // every project it generates, and ROBOT > Import terms... writes the module, the term
            // list and the catalog entry without adding the product - so a repository built
            // entirely with this plugin has imports that ODK's own build does not know about.
            result.warn("This project declares no imports in import_group.products, so ODK's "
                    + "IMPORTS list is empty: `make refresh-imports` and `make all_imports` "
                    + "build nothing, whatever is in " + ImportModules.IMPORTS_DIRECTORY + ". If "
                    + "OntoBoard made this project, that is OntoBoard's doing - its scaffold "
                    + "writes an empty products list and Import terms... does not add to it. "
                    + "Adding each import as a product is what makes the repository build for "
                    + "anyone else.");
        }

        String counted = declared.size() + " declared" + (undeclared == 0 ? ""
                : ", " + undeclared + " on disk and undeclared");
        if (!rebuild) {
            result.note(ImportModules.hasMirror(root)
                    ? "This project has a mirror, so a rebuild would work offline."
                    : "This project has no mirror, so a rebuild would download each source. "
                            + "Turn on the mirror option to keep local copies.");
            result.note("Nothing was changed - 'Rebuild the modules' was off.");
            return result.summary(counted + ", " + rebuildable + " rebuildable here.").build();
        }
        result.note("OntoBoard runs the extraction ODK runs - same term list, same method, same "
                + "handling of individuals. It does not run the normalisation and annotation "
                + "stripping ODK wraps around it, so a module rebuilt here is not byte-identical "
                + "to one `make refresh-imports` produces.");
        return result.summary("Rebuilt " + rebuilt + " of " + rebuildable + " rebuildable, out of "
                + counted + ".").build();
    }

    /**
     * Re-extracts one module from its declared source, by its declared method.
     *
     * <p>The method is the project's, not this plugin's default. A project declaring
     * {@code module_type_slme: STAR} and getting BOT back would have its module quietly replaced
     * by a larger one that no longer matches what its own CI builds.
     */
    private boolean rebuildOne(ImportModules.Module module, String source,
            TermExtract.Method method, File root, OperationResult.Builder result) {
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
            TermExtract.Result extracted = TermExtract.run(upstream, terms, method, moduleIri);

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

}
