package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * The part of importing terms that is not Swing: what path goes into the catalog.
 *
 * <p>It matters more than it looks. The catalog is committed and read on somebody else's machine
 * and on CI, so an absolute path from the machine that wrote it resolves to nothing everywhere
 * else - and the person who wrote it has the file locally and never sees the failure.
 */
class ImportTermsActionTest {

    @Test
    void aModuleBesideTheOntologyGetsARelativePath() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/imports/iao_import.owl"));

        assertEquals("imports/iao_import.owl", path);
    }

    /**
     * The catalog is read on Linux CI. A backslash in it resolves nowhere there, and nothing on
     * the machine that wrote it will ever say so.
     */
    @Test
    void thePathIsWrittenWithForwardSlashesWhateverThePlatform() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/imports/iao_import.owl"));

        assertFalse(path.contains("\\"), path);
    }

    @Test
    void aModuleAboveTheOntologyStillGetsARelativePath() {
        String path = ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/imports/iao_import.owl"));

        assertEquals("../../imports/iao_import.owl", path);
        assertFalse(path.contains("\\"), path);
    }

    @Test
    void aModuleInTheSameDirectoryIsJustItsName() {
        assertEquals("iao_import.owl", ImportTermsAction.relativePath(
                new File("/project/src/ontology"),
                new File("/project/src/ontology/iao_import.owl")));
    }

    /**
     * Different drives on Windows have no relative path between them. An absolute one at least
     * works on this machine, which beats a path that resolves nowhere at all.
     */
    @Test
    void somewhereWithNoRelativePathFallsBackToAnAbsoluteOne() {
        String path = ImportTermsAction.relativePath(new File("C:\\project\\src\\ontology"),
                new File("D:\\elsewhere\\iao_import.owl"));

        assertTrue(path.contains("iao_import.owl"), path);
        assertFalse(path.contains("\\"), path);
    }
}
