package de.fizkarlsruhe.ise.ontoboard.odk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import org.junit.jupiter.api.Test;

/**
 * Project configuration says beside each key what ODK says of it: the key's cell has ODK's
 * words as its tooltip, and the line under the table says them for the row that is chosen
 * (the testers of 1.132.0 asked for "help beside each key").
 */
class ProjectConfigHelpTest {

    private static final String CONFIG = "id: egg\n"
            + "title: \"Egg boiling ontology\"\n"
            + "robot_java_args: '-Xmx8G'\n"
            + "import_group:\n"
            + "  products:\n"
            + "    - id: ro\n"
            + "robot_report:\n"
            + "  fail_on: ERROR\n";

    private static int rowOf(List<OdkYaml.Entry> entries, String path) {
        for (int row = 0; row < entries.size(); row++) {
            if (entries.get(row).getPath().equals(path)) {
                return row;
            }
        }
        throw new AssertionError("no row " + path);
    }

    @Test
    void aKeysCellAndTheLineUnderTheTableSayOdksWords() {
        final List<OdkYaml.Entry> entries = OdkYaml.entriesIn(CONFIG);
        JTable table = new JTable(ProjectConfigAction.modelFor(entries));

        JTextArea under = ProjectConfigAction.withHelp(table, () -> entries);

        int row = rowOf(entries, "robot_java_args");
        Component cell = table.getCellRenderer(row, 0).getTableCellRendererComponent(table,
                table.getValueAt(row, 0), false, false, row, 0);
        assertEquals(OdkSchema.line("robot_java_args"), ((JComponent) cell).getToolTipText());
        assertEquals(ProjectConfigAction.CHOOSE_A_KEY, under.getText(), "before a row is chosen");

        table.setRowSelectionInterval(row, row);
        assertEquals(OdkSchema.line("robot_java_args"), under.getText());

        int listed = rowOf(entries, "import_group.products[0].id");
        table.setRowSelectionInterval(listed, listed);
        assertEquals(OdkSchema.line("import_group.products[0].id"), under.getText());
    }

    /** The table and the line under it at the dialog's own size, for a person to look at. */
    @Test
    void theFormRendersToAnImageSomebodyCanLookAt() throws Exception {
        final List<OdkYaml.Entry> entries = OdkYaml.entriesIn(CONFIG);
        JTable table = new JTable(ProjectConfigAction.modelFor(entries));
        JTextArea under = ProjectConfigAction.withHelp(table, () -> entries);
        int row = rowOf(entries, "robot_report.fail_on");
        table.setRowSelectionInterval(row, row);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        JScrollPane rows = new JScrollPane(table);
        content.add(rows, BorderLayout.CENTER);
        content.add(under, BorderLayout.SOUTH);
        content.setPreferredSize(new Dimension(720, 420));
        content.setSize(content.getPreferredSize());
        content.addNotify();
        content.validate();
        BufferedImage image = new BufferedImage(720, 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 720, 420);
        content.paint(graphics);
        graphics.dispose();
        File png = new File("target/design/project-configuration.png");
        png.getParentFile().mkdirs();
        ImageIO.write(image, "PNG", png);

        assertTrue(png.length() > 2048, png.getAbsolutePath());
        assertTrue(rows.getHeight() >= 300, "the table keeps its room: " + rows.getHeight()
                + " px of 420, the line under it " + under.getHeight() + " px");
        assertTrue(under.getHeight() >= under.getFontMetrics(under.getFont()).getHeight() * 3,
                "three lines for ODK's words: " + under.getHeight());
        assertTrue(under.getWidth() <= 720, "no wider than the dialog: " + under.getWidth());
    }
}
