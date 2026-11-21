package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.model.PropertyOutline;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.OWLOntology;

/**
 * The board's properties panel (1.155.0): the ontology's properties in their hierarchy, each with
 * its characteristics and domain and range, opened from the toolbar; a click selects the property
 * in Protégé. It never takes the keyboard from the board.
 */
class PropertiesPanelTest {

    private static final String U = "http://example.org/u#";

    private static PropertyOutline outline() throws Exception {
        OWLOntology ontology = OWLManager.createOWLOntologyManager().loadOntologyFromOntologyDocument(
                new StringDocumentSource("Prefix(:=<" + U + ">)\nPrefix(xsd:=<http://www.w3.org/2001/XMLSchema#>)\n"
                        + "Ontology(<http://example.org/u>\n"
                        + "Declaration(Class(:Pizza)) Declaration(Class(:Topping)) Declaration(Class(:Person))\n"
                        + "Declaration(ObjectProperty(:hasPart)) Declaration(ObjectProperty(:hasTopping))\n"
                        + "Declaration(DataProperty(:age))\n"
                        + "SubObjectPropertyOf(:hasTopping :hasPart) TransitiveObjectProperty(:hasPart)\n"
                        + "ObjectPropertyDomain(:hasTopping :Pizza) ObjectPropertyRange(:hasTopping :Topping)\n"
                        + "FunctionalDataProperty(:age) DataPropertyDomain(:age :Person)\n"
                        + "DataPropertyRange(:age xsd:integer)\n)"));
        return PropertyOutline.of(ontology);
    }

    @Test
    void thePropertiesAreInTwoFoldersInTheirHierarchy() throws Exception {
        PropertiesPanel panel = new PropertiesPanel(iri -> { }, () -> { });
        panel.show(outline());

        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        assertEquals(2, root.getChildCount());
        assertEquals("Object properties (2)", root.getChildAt(0).toString());
        assertEquals("Data properties (1)", root.getChildAt(1).toString());
        DefaultMutableTreeNode hasPart = (DefaultMutableTreeNode) root.getChildAt(0).getChildAt(0);
        assertEquals("hasPart", ((PropertyOutline.Entry) hasPart.getUserObject()).getLabel());
        assertEquals("hasTopping",
                ((PropertyOutline.Entry) ((DefaultMutableTreeNode) hasPart.getChildAt(0)).getUserObject())
                        .getLabel());
        assertTrue(panel.tree().isVisible(new TreePath(((DefaultMutableTreeNode) hasPart.getChildAt(0)).getPath())),
                "opened down to the sub-properties");
    }

    @Test
    void anEntrySaysItsCharacteristicsAndDomainAndRange() throws Exception {
        PropertiesPanel panel = new PropertiesPanel(iri -> { }, () -> { });
        panel.show(outline());
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        DefaultMutableTreeNode age = (DefaultMutableTreeNode) root.getChildAt(1).getChildAt(0);

        String text = PropertiesPanel.textOf((PropertyOutline.Entry) age.getUserObject());

        assertTrue(text.contains("<b>age</b>"), text);
        assertTrue(text.contains("functional") && text.contains("Person → integer"), text);
    }

    /**
     * The hover says the whole entry: in Protégé's column a line like "inverse of measurement
     * datum of · ? → measurement datum" ran past the panel's edge (probe host, 2026-11-20).
     */
    @Test
    void theHoverSaysTheWholeEntry() throws Exception {
        PropertiesPanel panel = new PropertiesPanel(iri -> { }, () -> { });
        panel.show(outline());
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
        PropertyOutline.Entry hasTopping = (PropertyOutline.Entry)
                ((DefaultMutableTreeNode) root.getChildAt(0).getChildAt(0).getChildAt(0)).getUserObject();

        String hover = PropertiesPanel.hoverOf(hasTopping);

        assertTrue(hover.contains("hasTopping") && hover.contains("Pizza → Topping")
                && hover.contains(U + "hasTopping"), hover);
    }

    @Test
    void aClickSelectsThePropertyAndTheBoardKeepsTheKeyboard() throws Exception {
        List<String> selected = new ArrayList<String>();
        PropertiesPanel panel = new PropertiesPanel(selected::add, () -> { });
        panel.show(outline());
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();

        panel.tree().setSelectionPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(1).getChildAt(0)).getPath()));
        panel.tree().setSelectionPath(new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath()));

        assertEquals(java.util.Collections.singletonList(U + "age"), selected, "a folder selects nothing");
        assertFalse(panel.tree().isFocusable(), "the board's shortcuts stay with the board");
    }

    @Test
    void thePanelReadsAtItsSize() throws Exception {
        PropertiesPanel panel = new PropertiesPanel(iri -> { }, () -> { });
        panel.show(outline());
        panel.setSize(300, 360);
        layOut(panel);
        BufferedImage picture = new BufferedImage(300, 360, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = picture.createGraphics();
        panel.printAll(graphics);
        graphics.dispose();
        File png = new File("target/properties-panel.png");
        ImageIO.write(picture, "PNG", png);
        assertTrue(png.length() > 0);
    }

    private static void layOut(java.awt.Component component) {
        component.doLayout();
        if (component instanceof java.awt.Container) {
            for (java.awt.Component child : ((java.awt.Container) component).getComponents()) {
                layOut(child);
            }
        }
    }
}
