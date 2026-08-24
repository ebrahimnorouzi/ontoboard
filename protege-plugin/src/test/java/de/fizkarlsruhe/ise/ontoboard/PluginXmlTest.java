package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class PluginXmlTest {

    private Document parseResource(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream(name)) {
            assertNotNull(in, name + " missing from the built classpath");
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
    }

    @Test
    void everyClassDeclaredInPluginXmlExists() throws Exception {
        NodeList declared = parseResource("/plugin.xml").getElementsByTagName("class");
        List<String> all = new ArrayList<>();
        List<String> ours = new ArrayList<>();
        for (int i = 0; i < declared.getLength(); i++) {
            String name = ((Element) declared.item(i)).getAttribute("value");
            all.add(name);
            if (name.startsWith("de.fizkarlsruhe.ise.ontoboard")) {
                ours.add(name);
            }
        }
        assertTrue(ours.size() >= 1, "expected at least one OntoBoard class in plugin.xml");
        // Every declared class - ours and third-party (e.g. Protege's own
        // OWLWorkspaceViewsTab) - must resolve. A typo in any of them produces the same
        // silent-non-loading failure this test exists to prevent, and nothing in Java
        // source references third-party plugin.xml class names, so javac won't catch it.
        for (String name : all) {
            Class.forName(name); // ClassNotFoundException on a typo
        }
    }

    @Test
    void viewConfigHasNoUnfilteredMavenProperties() throws Exception {
        NodeList props = parseResource("/viewconfig-ontoboardtab.xml").getElementsByTagName("Property");
        assertTrue(props.getLength() >= 1, "expected at least one Property in the view config");
        for (int i = 0; i < props.getLength(); i++) {
            String value = ((Element) props.item(i)).getAttribute("value");
            assertFalse(value.contains("${"), "unfiltered Maven property in view config: " + value);
        }
    }
}
