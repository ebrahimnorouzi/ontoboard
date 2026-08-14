package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.File;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Guards the pom.xml configuration that the fix rounds in Task 2 established: the
 * dependencyManagement scope pins that keep OWL API and Guava imported from the Protege host
 * rather than embedded, the Embed-Dependency exclusion that keeps log4j-api's own
 * Bundle-Activator from colliding with ours, and the Java 8 compiler configuration.
 *
 * RobotCoreInteropTest.owlApiIsPinnedToTheVersionTheHostShips only checks the resolved OWL API
 * jar's *version*. It says nothing about *scope*: if someone strips
 * {@code <scope>provided</scope>} from an owlapi-* or the guava dependencyManagement entry, that
 * test still passes (the version is still 4.5.9), mvn test still reports green, but mvn package
 * now embeds a second copy of a class the host already provides. That only surfaces later as an
 * OSGi duplicate-class/resolution failure inside a running Protege - never at test time. This
 * class exists to catch that regression before it reaches a running Protege instance.
 */
class BundleConfigurationTest {

    private static final File POM = new File("pom.xml");

    private Document parsePom() throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(POM);
    }

    /** First direct child element of {@code parent} named {@code tagName}, or null if absent. */
    private static Element childElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                Element child = (Element) children.item(i);
                if (child.getTagName().equals(tagName)) {
                    return child;
                }
            }
        }
        return null;
    }

    private static String childText(Element parent, String tagName) {
        Element child = childElement(parent, tagName);
        return child == null ? null : child.getTextContent().trim();
    }

    private Element dependencyManagementElement(Document pom) {
        NodeList blocks = pom.getElementsByTagName("dependencyManagement");
        assertEquals(1, blocks.getLength(), "expected exactly one <dependencyManagement> block in pom.xml");
        return (Element) blocks.item(0);
    }

    @Test
    void everyManagedOwlApiDependencyIsProvided() throws Exception {
        Element dependencyManagement = dependencyManagementElement(parsePom());
        NodeList deps = dependencyManagement.getElementsByTagName("dependency");
        int owlApiEntriesChecked = 0;
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            String groupId = childText(dep, "groupId");
            if (!"net.sourceforge.owlapi".equals(groupId)) {
                continue;
            }
            owlApiEntriesChecked++;
            String artifactId = childText(dep, "artifactId");
            String scope = childText(dep, "scope");
            assertEquals("provided", scope,
                    "net.sourceforge.owlapi:" + artifactId + " in dependencyManagement must be "
                    + "scope=provided. The host's owlapi-osgidistribution bundle already exports "
                    + "every OWL API package robot-core needs; if this dependency reverts to the "
                    + "default compile scope, mvn test stays green (the version pin is untouched) "
                    + "but mvn package embeds a second copy of the OWL API into the bundle, which "
                    + "fails to resolve as a duplicate/conflicting package only once Protege "
                    + "actually tries to load it.");
        }
        assertTrue(owlApiEntriesChecked >= 6,
                "expected at least the 6 net.sourceforge.owlapi entries pinned in Task 2's fix "
                + "rounds (owlapi-api, owlapi-apibinding, owlapi-impl, owlapi-parsers, owlapi-rio, "
                + "owlapi-distribution) to still be present in dependencyManagement; found only "
                + owlApiEntriesChecked + ". The owlapi-distribution entry is deliberately kept as "
                + "defensive pinning even though it does not appear in the resolved tree today - "
                + "do not remove it.");
    }

    @Test
    void guavaIsManagedAsProvidedAndPinnedToTheHostsVersion() throws Exception {
        Element dependencyManagement = dependencyManagementElement(parsePom());
        NodeList deps = dependencyManagement.getElementsByTagName("dependency");
        Element guava = null;
        for (int i = 0; i < deps.getLength(); i++) {
            Element dep = (Element) deps.item(i);
            if ("com.google.guava".equals(childText(dep, "groupId"))
                    && "guava".equals(childText(dep, "artifactId"))) {
                guava = dep;
                break;
            }
        }
        assertTrue(guava != null,
                "no com.google.guava:guava entry found in dependencyManagement. The host's OWL "
                + "API returns host-Guava types (e.g. OWLOntologyID.getOntologyIRI() returns "
                + "com.google.common.base.Optional); without this pin, robot-core's own declared "
                + "Guava (31.1-jre) could resolve and get embedded, and our code would link "
                + "against a different Optional class than what the host's OWL API actually "
                + "returns - a ClassCastException/LinkageError at runtime inside Protege.");
        assertEquals("18.0", childText(guava, "version"),
                "com.google.guava:guava must stay pinned to 18.0, the exact version the Protege "
                + "5.5.0 host ships and the host's owlapi bundle imports. Any other version "
                + "produces an Import-Package range bnd generates that the host cannot satisfy, "
                + "or - if bumped and still resolves - a different Guava than the host's OWL API "
                + "hands back, causing the same ClassCastException/LinkageError this pin exists "
                + "to prevent.");
        assertEquals("provided", childText(guava, "scope"),
                "com.google.guava:guava must be scope=provided so it is imported from the host's "
                + "own Guava 18.0 bundle rather than embedded. If this reverts to compile scope, "
                + "mvn test stays green but mvn package embeds our own Guava copy alongside the "
                + "host's, and our code links against a different Optional/Guava class than the "
                + "one the host's OWL API returns - a ClassCastException/LinkageError only "
                + "visible once the bundle actually runs inside Protege.");
    }

    @Test
    void embedDependencyExcludesLog4jApi() throws Exception {
        NodeList embedNodes = parsePom().getElementsByTagName("Embed-Dependency");
        assertEquals(1, embedNodes.getLength(), "expected exactly one <Embed-Dependency> instruction in pom.xml");
        String instruction = embedNodes.item(0).getTextContent().trim();
        assertTrue(instruction.contains("artifactId=!log4j-api"),
                "<Embed-Dependency> must keep the 'artifactId=!log4j-api' exclusion (found: '"
                + instruction + "'). log4j-api-2.24.3.jar, pulled in transitively via Apache POI "
                + "(robot-core's XLSX report-template dependency), is itself a real OSGi bundle "
                + "with its own Bundle-Activator header. Embedding it wholesale collides with our "
                + "own Bundle-Activator (org.protege.editor.owl.ProtegeOWL) and makes mvn package "
                + "fail outright with 'The Bundle-Activator header only supports a single type' - "
                + "this is fix round 1/2's exact regression, not a hypothetical one.");
    }

    @Test
    void compilerReleaseIsEightWithNoSourceOrTargetOverride() throws Exception {
        NodeList projectNodes = parsePom().getElementsByTagName("project");
        assertEquals(1, projectNodes.getLength(), "expected exactly one <project> root element in pom.xml");
        Element properties = childElement((Element) projectNodes.item(0), "properties");
        if (properties == null) {
            fail("pom.xml has no <properties> block; expected <maven.compiler.release>8</maven.compiler.release> in it");
        }
        assertEquals("8", childText(properties, "maven.compiler.release"),
                "<maven.compiler.release> must be 8. The target Protege 5.5.0 host bundles a "
                + "Java 8 JRE; release=8 rejects any Java 9+ API surface (List.of, "
                + "Optional.isEmpty, etc.) at compile time by compiling against the Java 8 "
                + "bootclasspath/ct.sym, not just emitting Java 8 class file format from whatever "
                + "JDK APIs happen to be present.");
        assertNull(childText(properties, "maven.compiler.source"),
                "<maven.compiler.source> must not be set. Task 1 deliberately removed source/"
                + "target in favour of maven.compiler.release: source/target only control the "
                + "emitted bytecode version, not the API surface javac compiles against, so code "
                + "using a Java 9+-only API (e.g. List.of) would compile fine under source/target "
                + "8 on a newer JDK and then fail to link at runtime inside Protege's Java 8 JRE. "
                + "Re-adding source/target reopens that hole even with release still set.");
        assertNull(childText(properties, "maven.compiler.target"),
                "<maven.compiler.target> must not be set, for the same reason "
                + "maven.compiler.source must not be: it does not enforce the Java 8 API surface "
                + "the way maven.compiler.release does, so re-adding it would let Java 9+-only "
                + "APIs slip past the compiler and fail only at runtime inside Protege's Java 8 "
                + "JRE.");
    }
}
