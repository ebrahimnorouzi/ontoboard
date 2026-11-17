package de.fizkarlsruhe.ise.ontoboard.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The testers of 1.132.0 asked for a button on each warning that applies its fix - "an
 * undeclared import, imports not merged". Imports not merged is Release's warning for an
 * ontology released with its import statements (One self-contained file unticked; on by
 * default since then): its button releases it again, merged.
 */
class ReleaseKeepsItsImportsTest {

    @Test
    void aReleaseThatKeepsItsImportsOffersToReleaseItAgainAsOneFile() {
        final OperationResult again = OperationResult.of("Release")
                .summary("Released as one self-contained file.").build();
        OperationResult.Builder result = OperationResult.of("Release");

        ReleaseAction.keepsItsImports(result, 2, () -> again);

        OperationResult built = result.build();
        assertEquals(OperationResult.Status.SUCCEEDED_WITH_WARNINGS, built.getStatus());
        assertEquals(1, built.getFixes().size());
        OperationResult.Fixable fixable = built.getFixes().get(0);
        assertTrue(fixable.warning().startsWith("This release keeps its 2 import statements"),
                fixable.warning());
        assertTrue(fixable.warning().contains("over the network"), fixable.warning());
        assertEquals("Release it again as one self-contained file", fixable.fix().label());
        assertSame(again, fixable.fix().apply());
    }

    @Test
    void oneImportIsSaidAsOne() {
        OperationResult.Builder result = OperationResult.of("Release");

        ReleaseAction.keepsItsImports(result, 1, () -> null);

        assertTrue(result.build().getFixes().get(0).warning()
                .startsWith("This release keeps its 1 import statement,"));
    }
}
