package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class OdkScaffoldTest {

    /** A real licence: ROBOT reports a missing ontology licence as an ERROR, and
     * the generated CI fails on errors, so a project without one fails its own build. */
    private static final String CC0 =
            "https://creativecommons.org/publicdomain/zero/1.0/";

    private static OdkProjectConfig config(File into) {
        return new OdkProjectConfig("mwo", "Materials Workflow Ontology",
                "Workflows for materials science", "http://purl.obolibrary.org/obo/mwo.owl",
                "https://creativecommons.org/licenses/by/4.0/", into);
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8"));
    }

    @Test
    void generatesTheOdkWorkspaceLayout(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        File root = config.getProjectRoot();
        File ontology = new File(new File(root, "src"), "ontology");
        for (String expected : new String[] {
            "mwo-edit.owl", "mwo-odk.yaml", "Makefile", "mwo.Makefile",
            "mwo-idranges.owl", "catalog-v001.xml", "profile.txt"}) {
            assertTrue(new File(ontology, expected).isFile(), "missing " + expected);
        }
        // mwo.owl is NOT seeded: it is what `make reason` writes and `make clean` deletes, so a
        // committed hand-made copy meant every build dirtied the tree.
        assertFalse(new File(ontology, "mwo.owl").isFile(),
                "the reasoned product should not be seeded as a file to commit");
        assertTrue(new File(ontology, "imports").isDirectory());
        assertTrue(new File(new File(root, "src"), "sparql").isDirectory());
        assertTrue(new File(root, "README.md").isFile());
        assertTrue(new File(root, ".gitignore").isFile());
        assertTrue(new File(new File(new File(root, ".github"), "workflows"), "qc.yml").isFile());
    }

    /**
     * The whole point is that Protege can open the result immediately. If the generated OWL
     * does not parse, the user's first action after the wizard fails.
     */
    @Test
    void theGeneratedEditFileIsAnOntologyProtegeCanOpen(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        OWLOntology ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(config.getEditFile());

        assertEquals("http://purl.obolibrary.org/obo/mwo.owl",
                ontology.getOntologyID().getOntologyIRI().get().toString());
    }

    @Test
    void theUsersBaseIriIsUsedRatherThanAPlaceholder(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "https://example.institute/abc", CC0, dir.toFile());
        OdkScaffold.create(config);

        String owl = read(config.getEditFile());
        assertTrue(owl.contains("https://example.institute/abc"), owl.substring(0, 400));
        assertTrue(!owl.contains("example.org"), "a placeholder IRI leaked into the output");
    }

    @Test
    void anOmittedIriFallsBackToTheOboConvention(@TempDir Path dir) {
        OdkProjectConfig config =
                new OdkProjectConfig("abc", "ABC", "", "", CC0, dir.toFile());
        assertEquals("http://purl.obolibrary.org/obo/abc.owl", config.getBaseIri());
    }

    @Test
    void trailingDelimitersInTheIriAreNormalisedAway(@TempDir Path dir) {
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc#", CC0, dir.toFile()).getBaseIri());
        assertEquals("http://x.org/abc", new OdkProjectConfig(
                "abc", "ABC", "", "http://x.org/abc/", CC0, dir.toFile()).getBaseIri());
    }

    /** The id becomes file names, Makefile variables and IRIs; a bad one breaks the build. */
    @Test
    void invalidOntologyIdsAreRejectedWithAnActionableMessage(@TempDir Path dir) {
        for (String bad : new String[] {"", "  ", "MWO", "my ont", "9lives", "my-ont"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig(bad, "T", "", "", CC0, dir.toFile()).validate(),
                    "should have rejected id '" + bad + "'");
            assertTrue(thrown.getMessage().length() > 20,
                    "message must tell the user what to do: " + thrown.getMessage());
        }
    }

    /**
     * The most damaging value this dialog can accept, and the one that looks most harmless.
     *
     * <p>Found in a real generated project: the user typed "o" for the base IRI, and Protege
     * resolved it against the file location on save. The ontology became
     * {@code file:/C:/Users/.../src/ontology/o} and every term
     * {@code file:/C:/Users/.../o#apple} - the author's own folder path baked into the identity
     * of every concept. Nothing failed. It would simply never have meant the same thing to
     * anyone else, and two people collaborating would mint different IRIs for one concept and
     * never converge.
     */
    @Test
    void aRelativeBaseIriIsRejectedBecauseItWouldBakeInALocalFilePath(@TempDir Path dir) {
        for (String relative : new String[] {"o", "mmo", "obo/mmo.owl", "/absolute/path",
            "./here", "ftp://x.org/a", "www.example.org/mmo"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", relative, CC0, dir.toFile())
                            .validate(),
                    "should have rejected base IRI '" + relative + "'");
            assertTrue(thrown.getMessage().contains("absolute http"),
                    thrown.getMessage());
            assertTrue(thrown.getMessage().contains(relative),
                    "the message must quote what was entered: " + thrown.getMessage());
        }
    }

    @Test
    void anAbsoluteBaseIriIsAccepted(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "http://purl.obolibrary.org/obo/abc.owl", CC0,
                dir.toFile()).validate();
        new OdkProjectConfig("abc", "ABC", "", "https://w3id.org/abc", CC0, dir.toFile())
                .validate();
    }

    /** The fallback has to survive its own validation, or an omitted IRI would be unusable. */
    @Test
    void theFallbackIriPassesValidation(@TempDir Path dir) {
        new OdkProjectConfig("abc", "ABC", "", "", CC0, dir.toFile()).validate();
    }

    /**
     * The generated ID ranges file must be one ODK recognises and one the plugin can mint from.
     *
     * <p>The previous version wrote an invented has_id_policy property and no ranges, so a fresh
     * project could not allocate an identifier to anybody - which makes two collaborators minting
     * the same identifier a certainty rather than a risk.
     */
    @Test
    void theGeneratedIdRangesFileCanActuallyMint(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String written = new String(java.nio.file.Files.readAllBytes(
                new java.io.File(config.getProjectRoot(),
                        "src/ontology/mwo-idranges.owl").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        assertFalse(written.contains("has_id_policy"),
                "has_id_policy is not an OBO property and ODK does not read it");
        IdRanges ranges = IdRanges.parse(written);
        assertEquals("MWO", ranges.getPolicyName());
        assertEquals(7, ranges.getIdDigits());
        assertEquals(1, ranges.getRanges().size(),
                "a project with no ranges cannot mint at all");

        String owner = ranges.getRanges().get(0).getAllocatedTo();
        String minted = ranges.mint(owner, java.util.Collections.<String>emptySet());
        assertTrue(minted.startsWith("http://purl.obolibrary.org/obo/mwo.owl#MWO_")
                        || minted.contains("MWO_"),
                "minted IRI should carry the policy prefix: " + minted);
        assertTrue(minted.endsWith("0001000"), "first identifier in the range, padded: " + minted);
    }

    /**
     * The generated project must not tell a user to run a file it does not write.
     *
     * <p>The YAML header used to say "sh run.sh make update_repo" - ODK's own instruction, naming
     * a 150-line Docker wrapper this scaffold deliberately does not produce, because running the
     * pipeline without Docker is the reason the plugin embeds robot-core at all.
     */
    @Test
    void theGeneratedProjectDoesNotNameFilesItNeverWrites(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", CC0, dir.toFile());
        List<File> written = OdkScaffold.create(config);

        java.util.Set<String> names = new java.util.HashSet<String>();
        for (File file : written) {
            names.add(file.getName());
        }
        String yaml = read(config, "mwo-odk.yaml");
        if (yaml.contains("run.sh")) {
            assertTrue(names.contains("run.sh"),
                    "the YAML names run.sh but the scaffold never writes it");
        }
        assertTrue(yaml.contains("robot on") || yaml.contains("robot directly"),
                "it should say what is actually needed: " + yaml);
    }

    /**
     * A release with no owl:versionIRI cannot be cited or pinned by anyone downstream, and an
     * undated copy means each release destroys the last - which a single cp did.
     */
    @Test
    void prepareReleaseStampsAVersionAndKeepsDatedCopies(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("mwo", "MWO", "",
                "http://purl.obolibrary.org/obo/mwo.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String makefile = read(config, "Makefile");
        assertTrue(makefile.contains("--version-iri"), makefile);
        assertTrue(makefile.contains("owl:versionInfo"), makefile);
        assertTrue(makefile.contains("releases/$(TODAY)"),
                "an undated release directory means each release overwrites the last");
        assertTrue(makefile.contains("TODAY :="), "TODAY has to be defined to be used");
    }

    private static String read(OdkProjectConfig config, String name) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(
                new File(new File(new File(config.getProjectRoot(), "src"), "ontology"),
                        name).toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void aMissingTitleIsRejected(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "", "", "", CC0, dir.toFile()).validate());
    }

    /** Writing into an existing folder could clobber someone's work. */
    @Test
    void refusesToWriteIntoAnExistingProjectFolder(@TempDir Path dir) {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);

        assertThrows(IllegalArgumentException.class, () -> OdkScaffold.create(config(dir.toFile())));
    }

    @Test
    void theMakefilePairSeparatesGeneratedFromCustom(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = config(dir.toFile());
        OdkScaffold.create(config);
        File ontology = new File(new File(config.getProjectRoot(), "src"), "ontology");

        String generated = read(new File(ontology, "Makefile"));
        assertTrue(generated.contains("Do NOT edit"), "generated Makefile must warn");
        assertTrue(generated.contains("-include $(ONT).Makefile"),
                "the generated Makefile must include the custom one, or custom targets are lost");
        assertTrue(read(new File(ontology, "mwo.Makefile")).contains("never overwritten"));
    }

    @Test
    void reportsEveryFileItWrote(@TempDir Path dir) {
        List<File> written = OdkScaffold.create(config(dir.toFile()));
        assertTrue(written.size() >= 11, "expected the full workspace, got " + written.size());
        for (File file : written) {
            assertTrue(file.isFile(), "reported but not written: " + file);
        }
    }
    /**
     * The generated project has to do what its own configuration says. The YAML declared
     * fail_on: ERROR and use_labels: TRUE and the Makefile passed neither, so CI did whatever
     * ROBOT defaults to - and a build that announces a policy it does not apply is worse than one
     * that announces nothing, because somebody relies on it.
     */
    @Test
    void theReportTargetPassesWhatTheYamlDeclares(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String makefile = read(config, "Makefile");
        String yaml = read(config, "abc-odk.yaml");

        assertTrue(yaml.contains("fail_on: ERROR"), yaml);
        assertTrue(makefile.contains("--fail-on ERROR"),
                "the Makefile does not pass the fail_on the YAML declares:\n" + makefile);
        assertTrue(yaml.contains("use_labels: TRUE"), yaml);
        assertTrue(makefile.contains("--labels true"),
                "the Makefile does not pass the labels setting the YAML declares:\n" + makefile);
    }

    /**
     * It listed base, full, obo and json and built one .owl, so anybody following the generated
     * configuration went looking for four artefacts and found one, with no way to tell whether
     * the build was broken or the configuration decorative.
     */
    @Test
    void theYamlOnlyPromisesArtefactsTheMakefileBuilds(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String yaml = read(config, "abc-odk.yaml");
        String makefile = read(config, "Makefile");

        for (String promised : new String[] {"  - obo", "  - json", "  - base"}) {
            assertFalse(yaml.contains(promised),
                    "the YAML promises an artefact nothing builds (" + promised.trim()
                            + "):\n" + yaml);
        }
        assertTrue(yaml.contains("  - owl"), yaml);
        assertTrue(makefile.contains("$(ONT).owl"), makefile);
    }

    // ---------- the report profile is not quietly emptier than it looks ----------

    /**
     * The generated profile listed seven rules, and that was not a smaller profile - it was a
     * silently emptier one. ROBOT's {@code ReportOperation.getProfile(path)} builds a fresh map
     * from the file and does not merge it with the defaults, so seven lines meant seven of
     * thirty-two checks ran. This plugin's own quality report and the generated CI read the same
     * file, so both were equally blind, and nothing said which rules were off.
     *
     * <p>Read from robot-core's own bundled profile rather than a list copied into this test, so
     * upgrading the dependency fails the build instead of silently adding a rule nobody runs.
     */
    @Test
    void theGeneratedProfileNamesEveryRuleRobotKnows(@TempDir Path dir) throws Exception {
        Set<String> robotsOwn = rulesInRobotsBundledProfile();
        assertFalse(robotsOwn.isEmpty(),
                "could not read robot-core's report_profile.txt, so this test proves nothing");

        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);
        Set<String> ours = rulesIn(read(config, "profile.txt"));

        Set<String> missing = new java.util.TreeSet<String>(robotsOwn);
        missing.removeAll(ours);
        assertTrue(missing.isEmpty(),
                "these ROBOT rules would never run in a generated project, and nothing would "
                        + "say so: " + missing);
    }

    /** A rule this plugin invented would be ignored by ROBOT and look like a check that passed. */
    @Test
    void theGeneratedProfileInventsNoRules(@TempDir Path dir) throws Exception {
        Set<String> robotsOwn = rulesInRobotsBundledProfile();
        org.junit.jupiter.api.Assumptions.assumeFalse(robotsOwn.isEmpty());

        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        Set<String> invented = new java.util.TreeSet<String>(rulesIn(read(config, "profile.txt")));
        invented.removeAll(robotsOwn);
        assertTrue(invented.isEmpty(), "ROBOT knows no such rules, so they check nothing: "
                + invented);
    }

    /**
     * The checks that catch what several editors do to one ontology were the ones switched off,
     * and they are the reason this project exists. They must be errors, not warnings.
     */
    @Test
    void theRulesThatCatchMultiEditorDamageAreErrors(@TempDir Path dir) throws Exception {
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);
        String profile = read(config, "profile.txt");

        for (String rule : new String[] {"deprecated_class_reference", "misused_obsolete_label",
                "misused_replaced_by", "duplicate_label", "duplicate_definition",
                "illegal_use_of_built_in_vocabulary", "multiple_equivalent_class_definitions",
                "missing_label", "multiple_labels"}) {
            assertTrue(profile.contains("ERROR\t" + rule),
                    rule + " is not an error in the generated profile:\n" + profile);
        }
    }

    /**
     * The profile must contain nothing but rules.
     *
     * <p>This test used to assert the opposite - that an explanatory header was present - and that
     * header broke {@code make report} in every project this scaffold produced. ROBOT's profile
     * parser reads every line as {@code <LEVEL> <rule>} and has no comment syntax, so a leading
     * {@code #} is taken for a reporting level and the run dies with "REPORT LEVEL ERROR '# ...'
     * is not a valid reporting level".
     *
     * <p>Neither ROBOT's documentation nor its error message says comments are unsupported; the
     * only way this surfaced was running the real ODK container against a generated project. The
     * explanation now lives in the Makefile, where {@code #} is a comment.
     */
    @Test
    void theProfileContainsNothingButRules() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ontoboard-profile");
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String profile = read(config, "profile.txt");

        for (String line : profile.split("\r?\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            assertFalse(line.startsWith("#"),
                    "ROBOT parses this as a reporting level and refuses to run: " + line);
            String[] parts = line.split("\\s+");
            assertEquals(2, parts.length, "not a '<LEVEL> <rule>' line: " + line);
            assertTrue(parts[0].equals("ERROR") || parts[0].equals("WARN")
                            || parts[0].equals("INFO"),
                    "not a reporting level ROBOT accepts: " + parts[0]);
        }
    }

    /**
     * The warning a deleted line is a disabled check still has to be somewhere a person meets it -
     * it just cannot be in profile.txt. The Makefile is where the report is actually run from.
     */
    @Test
    void theMakefileExplainsThatADeletedRuleStopsRunning() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ontoboard-makefile");
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        String makefile = read(config, "Makefile");

        assertTrue(makefile.contains("does NOT"), makefile);
        assertTrue(makefile.contains("stops running silently"), makefile);
        assertTrue(makefile.contains("Do not put comments in profile.txt"),
                "the trap that broke every generated project has to be named: " + makefile);
    }

    /** Rule names from a ROBOT profile file, ignoring comments and blank lines. */
    private static Set<String> rulesIn(String profile) {
        Set<String> rules = new java.util.TreeSet<String>();
        for (String line : profile.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length >= 2) {
                rules.add(parts[1]);
            }
        }
        return rules;
    }

    /** ROBOT's own list, from the jar, so this test cannot drift from the dependency. */
    private static Set<String> rulesInRobotsBundledProfile() throws Exception {
        java.io.InputStream in = org.obolibrary.robot.ReportOperation.class
                .getResourceAsStream("/report_profile.txt");
        if (in == null) {
            return new java.util.TreeSet<String>();
        }
        try {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                bytes.write(buffer, 0, read);
            }
            return rulesIn(new String(bytes.toByteArray(), "UTF-8"));
        } finally {
            in.close();
        }
    }


    /**
     * An import module must be committed, not ignored.
     *
     * <p>The generated .gitignore used to hold {@code src/ontology/imports/*.owl}. Combined with
     * the catalog entry and the import declaration that {@code ImportTermsAction} writes - both
     * committed - that produces a repository whose imports resolve for their author and for
     * nobody else: clone it and the catalog maps an IRI to a file that is not there. The
     * generated CI would not catch it either, because {@code make test} reports on the edit file
     * without loading its imports.
     */
    @Test
    void importModulesAreCommittedNotIgnored() throws Exception {
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("ontoboard-gitignore");
        OdkProjectConfig config = new OdkProjectConfig("abc", "ABC", "",
                "http://x.org/abc.owl", CC0, dir.toFile());
        OdkScaffold.create(config);

        // .gitignore is at the project root, not under src/ontology like the rest.
        String ignored = read(new File(config.getProjectRoot(), ".gitignore"));

        for (String line : ignored.split("\\r?\\n")) {
            if (line.trim().startsWith("#") || line.trim().isEmpty()) {
                // Comments explain why imports are NOT ignored, so they mention the word.
                continue;
            }
            assertFalse(line.contains("imports"),
                    "an ignored import module is an import that resolves only for its author: "
                            + line);
        }
        // The reasoned product is generated, so it IS ignored - that is the other half.
        assertTrue(ignored.contains("src/ontology/abc.owl"), ignored);
        // Generated output should still be ignored - the point is which things are generated.
        assertTrue(ignored.contains("report.tsv"), ignored);
        assertTrue(ignored.contains("tmp_"), ignored);
    }

    /**
     * The ontology id and the IRI's own name must be the same word.
     *
     * <p>ODK names the built artefact after the id ({@code $(ONT).owl}) and the release version
     * IRI after the ontology IRI. Let them differ and one release is published as
     * {@code .../pizza/releases/<date>/mwo.owl} - a namespace naming one thing, a file naming
     * another - with nothing failing. Found by a test comparing the two generators directly
     * rather than asserting a literal.
     */
    @Test
    void aBaseIriThatDisagreesWithTheIdIsRejected(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("mwo", "MWO", "", "http://example.org/pizza", CC0,
                        dir.toFile()).validate());

        assertTrue(thrown.getMessage().contains("pizza"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("mwo"), thrown.getMessage());
        assertTrue(thrown.getMessage().length() > 80,
                "the message must say what to do about it: " + thrown.getMessage());
    }

    @Test
    void aBaseIriThatAgreesWithTheIdIsAccepted(@TempDir Path dir) {
        new OdkProjectConfig("mwo", "MWO", "", "http://purl.obolibrary.org/obo/mwo.owl", CC0,
                dir.toFile()).validate();
        new OdkProjectConfig("pizza", "Pizza", "", "http://example.org/pizza", CC0,
                dir.toFile()).validate();
    }

    /**
     * A project must not fail its own CI on its first push.
     *
     * <p>ROBOT's default profile - which this scaffold writes out in full - treats
     * missing_ontology_license as an ERROR, and the generated report recipe passes
     * --fail-on ERROR. So a licenceless project went red immediately, over something the wizard
     * never asked about.
     */
    @Test
    void aProjectWithoutALicenceIsRejectedBecauseItsOwnCiWouldFail(@TempDir Path dir) {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new OdkProjectConfig("abc", "ABC", "", "http://x.org/abc.owl", "",
                        dir.toFile()).validate());

        assertTrue(thrown.getMessage().contains("ERROR"),
                "the message must say why, or it reads as bureaucracy: " + thrown.getMessage());
    }

    /**
     * An SPDX identifier is not a licence IRI. It goes straight into rdf:resource, where a
     * relative value is resolved against the file's own location on save - so the ontology's
     * licence would name a folder on one machine. This project's own pizza test used "CC0-1.0".
     */
    @Test
    void anSpdxIdentifierIsNotAcceptedAsALicence(@TempDir Path dir) {
        for (String notAnIri : new String[] {"CC0-1.0", "CC-BY-4.0", "MIT", "Apache-2.0"}) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> new OdkProjectConfig("abc", "ABC", "", "http://x.org/abc.owl", notAnIri,
                            dir.toFile()).validate(),
                    "should have rejected licence '" + notAnIri + "'");
            assertTrue(thrown.getMessage().contains(notAnIri), thrown.getMessage());
        }
    }

    /**
     * The generated CI installs the same ROBOT the plugin embeds, or the quality report a user
     * sees in Protege and the one their CI produces come from different rules. ROBOT_VERSION's
     * javadoc promised this test; it did not exist until the audit asked for it.
     */
    @Test
    void theCiInstallsTheRobotThePluginEmbeds() throws Exception {
        String pom = new String(Files.readAllBytes(new File("pom.xml").toPath()),
                Charset.forName("UTF-8"));
        int robotCore = pom.indexOf("<artifactId>robot-core</artifactId>");
        assertTrue(robotCore > 0, "robot-core is not a dependency any more");
        int versionStart = pom.indexOf("<version>", robotCore) + "<version>".length();
        String embedded = pom.substring(versionStart, pom.indexOf("</version>", versionStart));

        assertEquals(embedded, OdkScaffold.ROBOT_VERSION,
                "the generated CI installs ROBOT " + OdkScaffold.ROBOT_VERSION + " while the "
                        + "plugin embeds robot-core " + embedded + ", so a user's CI and their "
                        + "Protege would report against different rules");
    }
}
