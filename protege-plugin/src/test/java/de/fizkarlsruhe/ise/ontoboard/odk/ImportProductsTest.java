package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.robot.TermExtract;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading {@code import_group.products} - where each import comes from, and whether it can be
 * rebuilt here.
 *
 * <p>Every fixture below is copied from a real project's own configuration file, because the
 * defect was that <i>Refresh imports</i> worked on projects OntoBoard had created and on none of
 * the four real ones measured.
 */
class ImportProductsTest {

    /**
     * NFDIcore's own import_group, abridged to one product of each type it uses.
     *
     * <p>Comments and all - NFDIcore comments out alternative {@code mirror_from} lines above the
     * live one, and a parser that read the commented line would download the wrong artefact.
     */
    private static final String NFDICORE = String.join("\n",
            "id: nfdicore",
            "import_group:",
            "   annotation_properties: ['rdfs:label', 'IAO:0000115']",
            "   products:",
            "    - id: bfo",
            "      #mirror_from: https://raw.githubusercontent.com/BFO-ontology/BFO-2020/"
                    + "release-2024-01-29/src/owl/bfo-core.ttl",
            "      mirror_from: http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl",
            "      module_type: mirror",
            "    - id: iao",
            "      module_type: custom",
            "    - id: obi",
            "      module_type: slme",
            "      module_type_slme: BOT",
            "      slme_individuals: exclude",
            "      use_base: true",
            "    - id: skos",
            "      mirror_from: http://www.w3.org/TR/skos-reference/skos.rdf",
            "      module_type: slme",
            "      module_type_slme: SUBSET",
            "");

    // ---------- the source, which is the whole point ----------

    /**
     * A product with no {@code mirror_from} resolves to ODK's OBO PURL.
     *
     * <p>Verified against a real ODK-generated Makefile: a bare {@code - id: iao} became
     * {@code curl -L $(OBOBASE)/iao.owl}, with {@code OBOBASE=http://purl.obolibrary.org/obo}.
     */
    @Test
    void aProductThatNamesNoSourceGetsOdksDefault() {
        ImportProducts.Product iao = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "iao");
        assertNotNull(iao);
        assertEquals("http://purl.obolibrary.org/obo/iao.owl", iao.getSource());
        assertFalse(iao.isSourceDeclared());
    }

    /** And one that names a source gets exactly that, not the PURL. */
    @Test
    void aDeclaredMirrorFromIsTheSource() {
        ImportProducts.Product bfo = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "bfo");
        assertNotNull(bfo);
        assertEquals("http://purl.obolibrary.org/obo/bfo/2020/notime/bfo.owl", bfo.getSource());
        assertTrue(bfo.isSourceDeclared());
    }

    /**
     * A commented-out {@code mirror_from} above the live one is not read.
     *
     * <p>NFDIcore writes exactly this, and the commented line points at BFO's Turtle on GitHub
     * while the live one points at the OBO PURL - two different artefacts.
     */
    @Test
    void aCommentedOutSourceIsIgnored() {
        ImportProducts.Product bfo = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "bfo");
        assertNotNull(bfo);
        assertFalse(bfo.getSource().contains("raw.githubusercontent.com"),
                "the commented line would download a different file: " + bfo.getSource());
    }

    /** ECTO's gzipped NCBITaxon, which changes the URL rather than only the download. */
    @Test
    void useGzippedAppendsToTheDefaultUrl() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  products:",
                "    - id: ncbitaxon",
                "      use_gzipped: TRUE",
                "      is_large: TRUE",
                ""));

        assertEquals(1, products.size());
        assertEquals("http://purl.obolibrary.org/obo/ncbitaxon.owl.gz",
                products.get(0).getSource());
        assertTrue(products.get(0).isLarge(), "ODK's own refresh target can skip a large import");
    }

    /** {@code use_variant} puts the artefact under the product's own directory. */
    @Test
    void aVariantIsFetchedFromTheProductsOwnDirectory() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(
                "import_group:\n  products:\n    - id: go\n      use_variant: base\n");

        assertEquals("http://purl.obolibrary.org/obo/go/go-base.owl",
                products.get(0).getSource());
    }

    /** {@code mirror_type: no_mirror} has no upstream at all, so no source to refresh from. */
    @Test
    void aProductWithNoMirrorHasNoSource() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(
                "import_group:\n  products:\n    - id: local\n      mirror_type: no_mirror\n");

        assertFalse(products.get(0).isMirrored());
        assertNull(products.get(0).getSource());
    }

    // ---------- the inherited defaults ----------

    /**
     * A product that declares nothing gets BOT with individuals included.
     *
     * <p>From {@code ImportGroup}'s field initialisers in odkcore - {@code module_type = "slme"},
     * {@code module_type_slme = "BOT"}, {@code slme_individuals = "include"} - and confirmed by
     * the generated Makefile, whose rule for a bare {@code iao} is
     * {@code extract … --individuals include --method BOT}.
     */
    @Test
    void aBareProductInheritsSlmeBotAndIncludedIndividuals() {
        ImportProducts.Product bare = ImportProducts.declaredIn(
                "import_group:\n  products:\n    - id: iao\n").get(0);

        assertEquals("slme", bare.getModuleType());
        assertEquals("BOT", bare.getSlmeMethod());
        assertEquals("include", bare.getSlmeIndividuals());
        assertEquals(TermExtract.Method.BOT, bare.getMethod());
        assertTrue(bare.isRebuildable());
        assertNull(bare.getRefusal());
    }

    /** The group's own settings override the built-in defaults for every product under it. */
    @Test
    void theGroupsSettingsAreInherited() {
        ImportProducts.Product inherited = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  module_type: slme",
                "  module_type_slme: STAR",
                "  slme_individuals: exclude",
                "  products:",
                "    - id: ro",
                "")).get(0);

        assertEquals("STAR", inherited.getSlmeMethod());
        assertEquals("exclude", inherited.getSlmeIndividuals());
        assertEquals(TermExtract.Method.STAR, inherited.getMethod());
    }

    /** And the product's own settings override the group's. */
    @Test
    void aProductOverridesItsGroup() {
        ImportProducts.Product own = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  module_type_slme: STAR",
                "  products:",
                "    - id: ro",
                "      module_type_slme: TOP",
                "")).get(0);

        assertEquals(TermExtract.Method.TOP, own.getMethod());
    }

    /**
     * {@code fast_slme} is an alias for {@code slme}, so an older project is not all-unknown.
     *
     * <p>odkcore normalises it in {@code derive_fields} for backwards compatibility; without the
     * same normalisation here, every import of a project using it would report an unknown type.
     */
    @Test
    void fastSlmeIsSlme() {
        ImportProducts.Product fast = ImportProducts.declaredIn(
                "import_group:\n  products:\n    - id: go\n      module_type: fast_slme\n").get(0);

        assertEquals("slme", fast.getModuleType());
        assertTrue(fast.isRebuildable());
    }

    // ---------- what it will not rebuild, and why ----------

    /**
     * A custom module is refused, because ODK refuses it too.
     *
     * <p>ODK's generated rule for a custom product is three lines: two echoes saying "This rule
     * needs to be overwritten in {@code <project>.Makefile}" and {@code @false}. Fourteen of the
     * 35 products across the four measured projects are custom, so this is the common case, and
     * rebuilding one generically would overwrite a hand-written pipeline.
     */
    @Test
    void aCustomModuleIsRefusedBecauseOdkRefusesIt() {
        ImportProducts.Product custom = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "iao");

        assertFalse(custom.isRebuildable());
        assertNull(custom.getMethod());
        String why = custom.getRefusal();
        assertNotNull(why);
        assertTrue(why.contains("Makefile"), "it has to say where the real command lives: " + why);
    }

    /** A mirror module has no term list in ODK's rule, so there is nothing here to extract. */
    @Test
    void aMirrorModuleIsRefusedWithItsOwnReason() {
        ImportProducts.Product mirror = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "bfo");

        assertFalse(mirror.isRebuildable());
        assertTrue(mirror.getRefusal().contains("term list"),
                "the reason differs from a custom module's: " + mirror.getRefusal());
        assertNotNull(mirror.getSource(), "it still has a source - the mirror is refreshable");
    }

    /**
     * SUBSET is named, not quietly replaced with BOT.
     *
     * <p>NFDIcore and PMDCO between them declare three SUBSET products. ROBOT reaches that
     * extraction by a different route than the module extractor this uses, so falling back to BOT
     * would write a module that differs from the repository's and say nothing about it.
     */
    @Test
    void anUnrunnableSlmeMethodIsNamedRatherThanSubstituted() {
        ImportProducts.Product subset = ImportProducts.named(
                ImportProducts.declaredIn(NFDICORE), "skos");

        assertEquals("SUBSET", subset.getSlmeMethod());
        assertNull(subset.getMethod(), "silently extracting BOT here is the defect");
        assertTrue(subset.getRefusal().contains("SUBSET"), subset.getRefusal());
    }

    /** minimal and filter each get their own reason, because each differs from ODK differently. */
    @Test
    void minimalAndFilterAreRefusedForWhatTheyAddAfterTheExtraction() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  products:",
                "    - id: maxo",
                "      module_type: filter",
                "    - id: cl",
                "      module_type: minimal",
                ""));

        assertEquals(2, products.size());
        for (ImportProducts.Product product : products) {
            assertFalse(product.isRebuildable(), product.getId());
            assertTrue(product.getRefusal().contains("larger"),
                    "the module would come out larger: " + product.getRefusal());
        }
        assertFalse(products.get(0).getRefusal().equals(products.get(1).getRefusal()),
                "filter and minimal differ, and the reason should too");
    }

    /** And a type ODK has never had is reported as unknown rather than assumed. */
    @Test
    void anUnknownModuleTypeIsReported() {
        ImportProducts.Product odd = ImportProducts.declaredIn(
                "import_group:\n  products:\n    - id: go\n      module_type: handwave\n").get(0);

        assertFalse(odd.isRebuildable());
        assertTrue(odd.getRefusal().contains("handwave"), odd.getRefusal());
    }

    // ---------- the whole of a real file ----------

    /** NFDIcore's four products, in its order, each with the right verdict. */
    @Test
    void aRealProjectReadsEndToEnd() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(NFDICORE);

        assertEquals(4, products.size(), products.toString());
        assertEquals("bfo", products.get(0).getId());
        assertEquals("iao", products.get(1).getId());
        assertEquals("obi", products.get(2).getId());
        assertEquals("skos", products.get(3).getId());

        int rebuildable = 0;
        for (ImportProducts.Product product : products) {
            assertNotNull(product.getSource(), product.getId() + " has a source");
            rebuildable += product.isRebuildable() ? 1 : 0;
        }
        assertEquals(1, rebuildable, "only obi - mirror, custom and SUBSET are all refused");
        assertEquals(TermExtract.Method.BOT, products.get(2).getMethod());
        assertEquals("exclude", products.get(2).getSlmeIndividuals());
    }

    /** MWO's import_group, whose indentation is two spaces where NFDIcore's is three. */
    @Test
    void indentationDoesNotMatter() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  annotation_properties: ['rdfs:label']",
                "  products:",
                "   - id: iao",
                "     module_type: custom",
                "   - id: nfdicore",
                "     #mirror_from: https://raw.githubusercontent.com/ISE-FIZKarlsruhe/nfdicore/"
                        + "refs/heads/main/nfdicore.ttl",
                "     mirror_from: https://nfdi.fiz-karlsruhe.de/ontology/3.0.4",
                "     module_type: mirror",
                ""));

        assertEquals(2, products.size(), products.toString());
        assertEquals("https://nfdi.fiz-karlsruhe.de/ontology/3.0.4",
                products.get(1).getSource());
    }

    // ---------- reading it from a project ----------

    /** From a project directory, which is how the menu action reaches it. */
    @Test
    void itReadsFromAProjectDirectory(@TempDir File root) throws Exception {
        File ontology = new File(new File(root, "src"), "ontology");
        assertTrue(ontology.mkdirs());
        Files.write(new File(ontology, "x-odk.yaml").toPath(),
                NFDICORE.getBytes(StandardCharsets.UTF_8));

        assertEquals(4, ImportProducts.declaredIn(ontology).size());
    }

    /** Nothing at all, on anything at all - the caller then lists the directory as before. */
    @Test
    void anythingUnreadableDeclaresNothing(@TempDir File root) {
        assertTrue(ImportProducts.declaredIn((File) null).isEmpty());
        assertTrue(ImportProducts.declaredIn(new File(root, "nothing")).isEmpty());
        assertTrue(ImportProducts.declaredIn("this: is: not: yaml\n").isEmpty());
        assertTrue(ImportProducts.declaredIn("id: x\ntitle: no imports\n").isEmpty());
        assertTrue(ImportProducts.declaredIn("import_group:\n  products: []\n").isEmpty());
        assertTrue(ImportProducts.declaredIn("").isEmpty());
    }

    /** A product with no id names no module and no term list, so there is nothing to report. */
    @Test
    void aProductWithoutAnIdIsSkipped() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(String.join("\n",
                "import_group:",
                "  products:",
                "    - module_type: custom",
                "    - id: go",
                ""));

        assertEquals(1, products.size());
        assertEquals("go", products.get(0).getId());
    }

    /** named() finds one by id, and null rather than throwing when there is none. */
    @Test
    void namedFindsOneOrNothing() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(NFDICORE);

        assertEquals("obi", ImportProducts.named(products, "obi").getId());
        assertNull(ImportProducts.named(products, "uberon"));
    }

    // ---------- the audit: the declaration merged with the directory ----------

    /** A project root with these imports already built: a module and a term list each. */
    private static File projectHolding(File root, String... names) throws Exception {
        File imports = new File(root, ImportModules.IMPORTS_DIRECTORY);
        assertTrue(imports.mkdirs() || imports.isDirectory());
        for (String name : names) {
            Files.write(new File(imports, name + "_import.owl").toPath(),
                    "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
            Files.write(new File(imports, name + "_terms.txt").toPath(),
                    "http://example.org/A\n".getBytes(StandardCharsets.UTF_8));
        }
        return root;
    }

    /**
     * An import on disk that no product declares.
     *
     * <p>ODK builds its {@code IMPORTS} list from {@code import_group.products}, so nothing
     * regenerates this file. Three of the four projects measured for this release have one -
     * NFDIcore's {@code schema}, MWO's {@code foaf}, ECTO's {@code iao} and {@code ons} - and a
     * directory scan, which is all this did before, cannot tell the difference.
     */
    @Test
    void anImportOnDiskAndInNoProductIsFound(@TempDir File root) throws Exception {
        projectHolding(root, "iao", "schema");

        List<ImportProducts.Row> rows = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products:\n    - id: iao\n"),
                ImportModules.modulesIn(root), root);

        assertEquals(2, rows.size(), rows.toString());
        assertEquals("iao", rows.get(0).getName());
        assertEquals(ImportProducts.Verdict.REBUILD, rows.get(0).getVerdict());
        assertEquals("schema", rows.get(1).getName());
        assertEquals(ImportProducts.Verdict.UNDECLARED, rows.get(1).getVerdict());
        assertNull(rows.get(1).getProduct());
        assertEquals("NOT DECLARED", rows.get(1).describeDeclaration());
    }

    /**
     * An undeclared import is still rebuilt when its term list records a source.
     *
     * <p>That is what a refresh did before it read the declaration, and taking it away would be a
     * regression - it is the path for every project OntoBoard itself made, because its scaffold
     * writes an empty products list and <i>Import terms…</i> does not add to it.
     */
    @Test
    void anUndeclaredImportWithARecordedSourceIsStillRebuilt(@TempDir File root) throws Exception {
        File imports = new File(root, ImportModules.IMPORTS_DIRECTORY);
        assertTrue(imports.mkdirs());
        Files.write(new File(imports, "ro_import.owl").toPath(),
                "<rdf:RDF/>".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(imports, "ro_terms.txt").toPath(),
                "# Source: http://purl.obolibrary.org/obo/ro.owl\nhttp://example.org/A\n"
                        .getBytes(StandardCharsets.UTF_8));

        ImportProducts.Row row = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products: []\n"),
                ImportModules.modulesIn(root), root).get(0);

        assertEquals(ImportProducts.Verdict.UNDECLARED, row.getVerdict());
        assertTrue(row.willRebuild(), "this worked before the declaration was read");
        assertEquals("http://purl.obolibrary.org/obo/ro.owl", row.getSource());
        assertEquals(TermExtract.Method.BOT, row.getMethod(),
                "no module_type to read, so the ODK default - as before");
    }

    /** And not rebuilt when nothing records one, which is every real project's term lists. */
    @Test
    void anUndeclaredImportWithNoRecordedSourceIsNotRebuilt(@TempDir File root) throws Exception {
        projectHolding(root, "ro");

        ImportProducts.Row row = ImportProducts.audit(
                java.util.Collections.<ImportProducts.Product>emptyList(),
                ImportModules.modulesIn(root), root).get(0);

        assertEquals(ImportProducts.Verdict.UNDECLARED, row.getVerdict());
        assertNull(row.getSource(), "no '# Source:' line - which no real project's lists have");
        assertFalse(row.willRebuild());
        assertNull(row.getMethod());
    }

    /**
     * A declared import with nothing built for it yet.
     *
     * <p>Exactly the state adding a product to the list leaves you in, and a state a directory
     * scan cannot see at all.
     */
    @Test
    void aDeclaredImportWithNothingOnDiskIsReported(@TempDir File root) {
        ImportProducts.Row row = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products:\n    - id: ro\n"),
                java.util.Collections.<ImportModules.Module>emptyList(), root).get(0);

        assertEquals(ImportProducts.Verdict.NO_TERM_LIST, row.getVerdict());
        assertFalse(row.getModule().hasModule());
        assertFalse(row.willRebuild());
        assertTrue(row.termListIsRead(), "an slme module's rule reads the term list");
    }

    /**
     * A mirror module with no term list is not missing one.
     *
     * <p>ODK's rule for a mirror module has no term-list prerequisite at all, so NFDIcore's
     * {@code bfo_terms.txt} is a file its own build never opens. Reporting it MISSING would send
     * somebody to write a list nothing reads.
     */
    @Test
    void aMirrorModuleDoesNotWantATermList(@TempDir File root) {
        ImportProducts.Row row = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products:\n    - id: bfo\n"
                        + "      module_type: mirror\n"),
                java.util.Collections.<ImportModules.Module>emptyList(), root).get(0);

        assertEquals(ImportProducts.Verdict.REFUSED, row.getVerdict(),
                "refused for being a mirror, not for a term list it does not want");
        assertFalse(row.termListIsRead());
    }

    /** A no_mirror product has nowhere to extract from, which is its own verdict. */
    @Test
    void aProductWithNoUpstreamGetsItsOwnVerdict(@TempDir File root) throws Exception {
        projectHolding(root, "local");

        ImportProducts.Row row = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products:\n    - id: local\n"
                        + "      mirror_type: no_mirror\n"),
                ImportModules.modulesIn(root), root).get(0);

        assertEquals(ImportProducts.Verdict.NO_SOURCE, row.getVerdict());
        assertFalse(row.willRebuild());
    }

    /** The declaration's order is kept, and the undeclared ones come after it. */
    @Test
    void declaredComeFirstInTheProjectsOrder(@TempDir File root) throws Exception {
        projectHolding(root, "aaa", "ro", "zzz");

        List<ImportProducts.Row> rows = ImportProducts.audit(
                ImportProducts.declaredIn("import_group:\n  products:\n    - id: zzz\n"
                        + "    - id: ro\n"),
                ImportModules.modulesIn(root), root);

        assertEquals(3, rows.size());
        assertEquals("zzz", rows.get(0).getName());
        assertEquals("ro", rows.get(1).getName());
        assertEquals("aaa", rows.get(2).getName(), "the undeclared one, last");
        assertEquals(ImportProducts.Verdict.UNDECLARED, rows.get(2).getVerdict());
    }

    /** Nothing declared and nothing on disk is no rows, not a crash. */
    @Test
    void anEmptyProjectAuditsToNothing(@TempDir File root) {
        assertTrue(ImportProducts.audit(
                java.util.Collections.<ImportProducts.Product>emptyList(),
                java.util.Collections.<ImportModules.Module>emptyList(), root).isEmpty());
    }

    // ---------- declaring what Import terms... just extracted ----------

    /**
     * A BOT extraction declares its method explicitly, even though BOT is ODK's default.
     *
     * <p>The tempting economy is to leave {@code module_type_slme} out when it matches the
     * default. It would be wrong: a project whose {@code import_group} sets
     * {@code module_type_slme: STAR} at group level would rebuild this module as STAR, which is
     * not what was extracted. {@link #aGroupDefaultCannotSilentlyChangeADeclaredModule} is the
     * test for that.
     */
    @Test
    void aBotExtractionIsDeclaredAsSlmeBot() {
        ImportProducts.Declaration declaration = ImportProducts.declarationFor(
                "ro", "http://purl.obolibrary.org/obo/ro.owl", TermExtract.Method.BOT);

        assertEquals("[id, module_type, module_type_slme]",
                declaration.getFields().keySet().toString());
        assertEquals("ro", declaration.getFields().get("id"));
        assertEquals("slme", declaration.getFields().get("module_type"));
        assertEquals("BOT", declaration.getFields().get("module_type_slme"));
        assertNull(declaration.getCaveat());
        assertNull(declaration.getFields().get("slme_individuals"),
                "ODK's default and robot-core's are both 'include', so saying it adds nothing");
    }

    /** The OBO PURL is left out, because it is what ODK downloads anyway. */
    @Test
    void theDefaultSourceIsNotWrittenOut() {
        assertNull(ImportProducts.declarationFor("iao",
                        "http://purl.obolibrary.org/obo/iao.owl", TermExtract.Method.BOT)
                .getFields().get("mirror_from"),
                "NFDIcore's iao product is one line for exactly this reason");
    }

    /** Any other source is written, because nothing else would find it. */
    @Test
    void anUnusualSourceIsWritten() {
        assertEquals("https://edamontology.org/EDAM_1.25.owl",
                ImportProducts.declarationFor("edam", "https://edamontology.org/EDAM_1.25.owl",
                        TermExtract.Method.STAR).getFields().get("mirror_from"));
    }

    /** STAR and TOP travel through as themselves. */
    @Test
    void everyRunnableMethodIsDeclarable() {
        for (TermExtract.Method method : new TermExtract.Method[] {
                TermExtract.Method.BOT, TermExtract.Method.TOP, TermExtract.Method.STAR}) {
            ImportProducts.Declaration declaration =
                    ImportProducts.declarationFor("x", null, method);
            assertEquals("slme", declaration.getFields().get("module_type"), method.getLabel());
            assertEquals(method.getLabel(), declaration.getFields().get("module_type_slme"));
            assertNull(declaration.getCaveat(), method.getLabel());
        }
    }

    /**
     * MIREOT is declared as custom, with the reason, because ODK has no module type for it.
     *
     * <p>Inventing {@code module_type_slme: MIREOT} would be outside ODK's documented set, and
     * {@code custom} is ODK's own word for a rule written by hand - which is what NFDIcore does
     * for its one MIREOT import.
     */
    @Test
    void aMireotExtractionIsDeclaredCustomAndSaysWhy() {
        ImportProducts.Declaration declaration = ImportProducts.declarationFor(
                "swo", "https://example.org/swo.owl", TermExtract.Method.MIREOT);

        assertEquals("custom", declaration.getFields().get("module_type"));
        assertNull(declaration.getFields().get("module_type_slme"));
        assertNotNull(declaration.getCaveat());
        assertTrue(declaration.getCaveat().contains("Makefile"), declaration.getCaveat());
    }

    /** A declaration this writes is one this reads back the same way. */
    @Test
    void whatItDeclaresItReadsBack() {
        ImportProducts.Declaration declaration = ImportProducts.declarationFor(
                "chebi", "https://example.org/chebi_slim.owl", TermExtract.Method.STAR);

        String yaml = OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                "import_group.products", declaration.getFields());
        ImportProducts.Product read = ImportProducts.declaredIn(yaml).get(0);

        assertEquals("chebi", read.getId());
        assertEquals("https://example.org/chebi_slim.owl", read.getSource());
        assertEquals(TermExtract.Method.STAR, read.getMethod(),
                "the method that comes back is the one that was extracted");
        assertTrue(read.isRebuildable());
        assertNull(read.getRefusal());
    }

    /**
     * And a group default cannot silently change it, which is why the method is written out.
     *
     * <p>Remove {@code module_type_slme} from the declaration and this test fails: the product
     * inherits STAR from the group and a refresh rebuilds a module that is not the one extracted.
     */
    @Test
    void aGroupDefaultCannotSilentlyChangeADeclaredModule() {
        ImportProducts.Declaration declaration =
                ImportProducts.declarationFor("ro", null, TermExtract.Method.BOT);

        String yaml = OdkYaml.appendBlockTo(
                "import_group:\n  module_type_slme: STAR\n  products: []\n",
                "import_group.products", declaration.getFields());

        assertEquals(TermExtract.Method.BOT,
                ImportProducts.declaredIn(yaml).get(0).getMethod(),
                "BOT was extracted, so BOT is what a rebuild must use - not the group's STAR");
    }

    /** A MIREOT declaration reads back as refused, which is honest about what make will do. */
    @Test
    void aMireotDeclarationReadsBackAsRefused() {
        String yaml = OdkYaml.appendBlockTo("import_group:\n  products: []\n",
                "import_group.products",
                ImportProducts.declarationFor("swo", null, TermExtract.Method.MIREOT).getFields());

        ImportProducts.Product read = ImportProducts.declaredIn(yaml).get(0);
        assertFalse(read.isRebuildable());
        assertTrue(read.getRefusal().contains("custom"), read.getRefusal());
    }

    /** The module type reads as one line, with the SLME parameters only when they apply. */
    @Test
    void theModuleTypeDescribesItself() {
        List<ImportProducts.Product> products = ImportProducts.declaredIn(NFDICORE);

        assertEquals("mirror", products.get(0).describeModuleType());
        assertEquals("custom", products.get(1).describeModuleType());
        assertTrue(products.get(2).describeModuleType().contains("BOT"),
                products.get(2).describeModuleType());
        assertTrue(products.get(2).describeModuleType().contains("exclude"),
                products.get(2).describeModuleType());
    }
}
