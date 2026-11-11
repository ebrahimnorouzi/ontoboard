package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Whether a project once imported a library pattern, as a board saved by 1.135.0 is read (1.135.2
 * review): by its term list's {@code # Pattern:} line, whatever the module is called, and by a
 * module named after the pattern only when no list says it is another pattern's.
 *
 * <p>The check looked for {@code imports/<id>_import.owl} alone, and a pattern's module is named
 * after the IRI the pattern declares: 48 of the library's patterns have a module named otherwise,
 * so their frames were never recognised - and a module named after one pattern's id can be
 * another pattern's, so a frame titled after the first was taken for its frame.
 */
class APatternImportedOnceIsKnownByItsListTest {

    @TempDir
    File imports;

    private void write(String file, String text) throws IOException {
        Files.write(new File(imports, file).toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aModuleNamedAfterThePatternSaysItWasImported() throws IOException {
        write("objectrole_import.owl", "<rdf:RDF/>\n");

        assertTrue(ImportModules.patternImportedOnce(imports, "objectrole"));
        assertFalse(ImportModules.patternImportedOnce(imports, "price"));
    }

    @Test
    void aListMarkedForThePatternSaysItWhateverTheModuleIsCalled() throws IOException {
        write("dataset_terms.txt", "# Terms extracted into dataset_import.owl\n"
                + "# Pattern: dataset-description\nhttp://example.org/patterns/dataset/Dataset\n");

        assertTrue(ImportModules.patternImportedOnce(imports, "dataset-description"));
    }

    @Test
    void aModuleAnotherPatternsListClaimsIsThatPatterns() throws IOException {
        write("agentrole_import.owl", "<rdf:RDF/>\n");
        write("agentrole_terms.txt", "# Pattern: agent-role\nhttp://example.org/patterns/Role\n");

        assertFalse(ImportModules.patternImportedOnce(imports, "agentrole"),
                "the module is named after its IRI, and the pattern is agent-role");
        assertTrue(ImportModules.patternImportedOnce(imports, "agent-role"));
    }

    /**
     * A list written before 1.133.0 names no pattern: its source was the pattern's copy in mirror/,
     * which names it. The module beside it is that pattern's, whatever it is called.
     */
    @Test
    void aListFromBefore1330IsKnownByThePatternsCopy() throws IOException {
        write("agentrole_import.owl", "<rdf:RDF/>\n");
        write("agentrole_terms.txt", "# Terms extracted into agentrole_import.owl\n"
                + "# Source: file:/C:/Users/someone/project/src/ontology/mirror/agent-role.owl\n"
                + "http://example.org/patterns/Role\n");

        assertTrue(ImportModules.patternImportedOnce(imports, "agent-role"));
        assertFalse(ImportModules.patternImportedOnce(imports, "agentrole"),
                "its module is Agent Role's");
    }

    /** A plain import whose module is named like a pattern is not that pattern. */
    @Test
    void anImportNamedLikeAPatternIsNotThePattern() throws IOException {
        write("dataset_import.owl", "<rdf:RDF/>\n");
        write("dataset_terms.txt", "# Source: https://example.org/vocab/dataset.owl\n"
                + "https://example.org/vocab/dataset#Dataset\n");

        assertFalse(ImportModules.patternImportedOnce(imports, "dataset"));
    }

    @Test
    void withoutAnImportsDirectoryNothingWasImported() {
        assertFalse(ImportModules.patternImportedOnce(new File(imports, "missing"), "objectrole"));
        assertFalse(ImportModules.patternImportedOnce(null, "objectrole"));
        assertFalse(ImportModules.patternImportedOnce(imports, null));
    }
}
