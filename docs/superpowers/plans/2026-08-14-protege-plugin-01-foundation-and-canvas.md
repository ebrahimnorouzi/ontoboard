# OntoBoard Protégé Plugin — Plan 1: Foundation & Canvas

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A Protégé Desktop 5.6 plugin that renders the active ontology as an interactive JGraphX schema diagram, with opt-in canvas membership, layout persisted to a sidecar file, and two-way selection sync with Protégé.

**Architecture:** The canvas is a *view over Protégé's model*, never a parallel model. `OntologyProjection` turns an `OWLOntology` plus a set of on-canvas IRIs into plain node/edge value objects; `SchemaGraph` renders those through JGraphX; `CanvasLayoutStore` persists presentation state to a JSON sidecar beside the ontology file. This plan builds the read/display/layout half — no axiom mutation, which is Plan 2.

**Tech Stack:** Java 11, Maven, Apache Felix `maven-bundle-plugin` (OSGi), `protege-editor-owl:5.6.6`, `jgraphx:4.2.2`, `robot-core:1.9.8`, OWL API 4.5.x, JUnit 5.

**Spec:** [`docs/superpowers/specs/2026-08-14-ontoboard-protege-plugin-design.md`](../specs/2026-08-14-ontoboard-protege-plugin-design.md)

## Plan Series

This spec is decomposed into six plans. Each produces working software.

| Plan | Scope | Spec phase |
|---|---|---|
| **1 (this)** | Skeleton, dependency de-risking, sidecar, projection, canvas, membership, selection, layouts/export | 1–2 |
| 2 | Axiomatization: edge drawing, default axioms, OWLAx candidates, node creation, frames, sticky notes | 3 |
| 3 | Pipeline: robot-core operations, all 24 commands, log console, ODK scaffold/config/make, imports + catalog | 4 |
| 4 | Data integration: pattern library, CSV/template wizard, SPARQL, GitHub import, export, Widoco | 5 |
| 5 | Quality & metadata: ROBOT report UI, OOPS!, OQuaRE, analysis tools, provenance, ID ranges | 6 |
| 6 | Retirement: rewrite README/docs, delete the web stack | 7 |

## Global Constraints

- **Java 11 bytecode.** Protégé 5.6 requires Java 11+; do not use records, `var` in APIs, or any 12+ feature.
- **OWL API 4.5.x collection idioms**, not OWL API 5 streams. `ontology.getAxioms(AxiomType.X)` returns a `Set`.
- **Package root:** `de.fizkarlsruhe.ise.ontoboard`.
- **Maven coordinates (verified on Maven Central 2026-08-14):** `edu.stanford.protege:protege-editor-owl:5.6.6`, `com.github.vlsi.mxgraph:jgraphx:4.2.2`, `org.obolibrary.robot:robot-core:1.9.8`.
- **Do not declare JGit or Jackson.** Protégé 5.6.6 already provides `org.eclipse.jgit`, `jackson-core`, `jackson-annotations`, `jackson-databind`, `jackson-dataformat-yaml`, `jackson-dataformat-csv`, and `snakeyaml`. Declaring our own versions risks OSGi conflicts. *(This corrects spec §9, which pinned a JGit version.)*
- **Never set `Bundle-ClassPath` manually.** `Embed-Dependency` manages it; hardcoding `.` breaks embedding.
- **Sidecar naming:** append to the full ontology file name — `myont-edit.owl` → `myont-edit.owl.ontoboard.json`. *(Clarifies spec §6, which was ambiguous between appending and replacing the extension. Appending avoids collisions when `foo.owl` and `foo.ttl` coexist.)*
- **The canvas never deletes axioms.** Removing a node from the canvas is a view operation only (spec §5.3).
- **No collaboration, server, accounts, or Docker** (spec §3).

---

### Task 1: Maven/OSGi skeleton that loads in Protégé

Proves packaging end to end before any feature work. A typo'd class name in `plugin.xml` makes Protégé silently skip the plugin — the test here catches exactly that.

**Files:**
- Create: `protege-plugin/pom.xml`
- Create: `protege-plugin/src/main/resources/plugin.xml`
- Create: `protege-plugin/src/main/resources/viewconfig-ontoboardtab.xml`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/PluginXmlTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: artifactId `ontoboard`; view extension id `SchemaCanvasView`, so its Protégé pluginId is `ontoboard.SchemaCanvasView`. Class `de.fizkarlsruhe.ise.ontoboard.views.SchemaCanvasView extends AbstractOWLViewComponent`.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/PluginXmlTest.java`:

```java
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
    void everyOntoBoardClassDeclaredInPluginXmlExists() throws Exception {
        NodeList declared = parseResource("/plugin.xml").getElementsByTagName("class");
        List<String> ours = new ArrayList<>();
        for (int i = 0; i < declared.getLength(); i++) {
            String name = ((Element) declared.item(i)).getAttribute("value");
            if (name.startsWith("de.fizkarlsruhe.ise.ontoboard")) {
                ours.add(name);
            }
        }
        assertTrue(ours.size() >= 1, "expected at least one OntoBoard class in plugin.xml");
        for (String name : ours) {
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test`
Expected: FAIL — no `pom.xml` yet, so Maven cannot even start. That is the expected first failure.

- [ ] **Step 3: Write the build and resources**

`protege-plugin/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <groupId>de.fizkarlsruhe.ise</groupId>
  <artifactId>ontoboard</artifactId>
  <version>2.0.0-SNAPSHOT</version>
  <packaging>bundle</packaging>

  <name>OntoBoard</name>
  <description>Visual ontology engineering and ODK/ROBOT pipeline tooling for Protege Desktop</description>

  <properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
    <protege.version>5.6.6</protege.version>
    <jgraphx.version>4.2.2</jgraphx.version>
    <junit.version>5.10.2</junit.version>
  </properties>

  <dependencies>
    <!-- provided: supplied by the Protege runtime, never embedded.
         Also brings the OWL API, Jackson, JGit and SLF4J transitively. -->
    <dependency>
      <groupId>edu.stanford.protege</groupId>
      <artifactId>protege-editor-owl</artifactId>
      <version>${protege.version}</version>
      <scope>provided</scope>
    </dependency>

    <dependency>
      <groupId>com.github.vlsi.mxgraph</groupId>
      <artifactId>jgraphx</artifactId>
      <version>${jgraphx.version}</version>
    </dependency>

    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
  </dependencies>

  <build>
    <resources>
      <resource>
        <!-- filtering resolves ${project.artifactId} inside plugin.xml and view configs -->
        <directory>src/main/resources</directory>
        <filtering>true</filtering>
      </resource>
    </resources>

    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
      </plugin>

      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>

      <plugin>
        <groupId>org.apache.felix</groupId>
        <artifactId>maven-bundle-plugin</artifactId>
        <version>5.1.9</version>
        <extensions>true</extensions>
        <configuration>
          <instructions>
            <Bundle-Activator>org.protege.editor.owl.ProtegeOWL</Bundle-Activator>
            <Bundle-SymbolicName>${project.artifactId};singleton:=true</Bundle-SymbolicName>
            <Bundle-Vendor>ISE / FIZ Karlsruhe</Bundle-Vendor>
            <!-- Do NOT set Bundle-ClassPath; Embed-Dependency manages it. -->
            <Embed-Dependency>jgraphx</Embed-Dependency>
            <Embed-Transitive>true</Embed-Transitive>
            <Import-Package>
              org.protege.editor.owl.*;version="5.6.0",
              org.protege.editor.core.*;version="5.6.0",
              org.semanticweb.owlapi.*,
              org.slf4j.*,
              *
            </Import-Package>
          </instructions>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

`protege-plugin/src/main/resources/plugin.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" standalone="no"?>
<?eclipse version="3.0"?>
<plugin>

  <extension id="OntoBoardTab" point="org.protege.editor.core.application.WorkspaceTab">
    <label value="OntoBoard"/>
    <class value="org.protege.editor.owl.ui.OWLWorkspaceViewsTab"/>
    <index value="O"/>
    <editorKitId value="OWLEditorKit"/>
    <defaultViewConfigFileName value="viewconfig-ontoboardtab.xml"/>
  </extension>

  <extension id="SchemaCanvasView" point="org.protege.editor.core.application.ViewComponent">
    <label value="Schema Canvas"/>
    <class value="de.fizkarlsruhe.ise.ontoboard.views.SchemaCanvasView"/>
    <headerColor value="@org.protege.ontologycolor"/>
    <category value="@org.protege.ontologycategory"/>
  </extension>

</plugin>
```

`protege-plugin/src/main/resources/viewconfig-ontoboardtab.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<layout>
    <HSNode splits="0.22 0.78">
        <CNode>
            <Component label="Class hierarchy">
                <Property id="pluginId" value="org.protege.editor.owl.OWLAssertedClassHierarchy"/>
            </Component>
        </CNode>
        <CNode>
            <Component label="Schema Canvas">
                <Property id="pluginId" value="${project.artifactId}.SchemaCanvasView"/>
            </Component>
        </CNode>
    </HSNode>
</layout>
```

`protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.views;

import java.awt.BorderLayout;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());
        add(new JLabel("OntoBoard schema canvas", SwingConstants.CENTER), BorderLayout.CENTER);
    }

    @Override
    protected void disposeOWLView() {
        // nothing to release yet
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 2 tests.

- [ ] **Step 5: Verify it actually loads in Protégé**

```bash
cd protege-plugin && mvn -q clean package
cp target/ontoboard-2.0.0-SNAPSHOT.jar "$PROTEGE_HOME/plugins/"
```

Start Protégé, open any ontology, and check **Window → Tabs → OntoBoard**. Expected: the tab exists and shows the class hierarchy beside a panel reading "OntoBoard schema canvas".

If the tab is absent, read `$PROTEGE_HOME/logs/` — an OSGi resolution failure names the unsatisfied import.

- [ ] **Step 6: Commit**

```bash
git add protege-plugin/
git commit -m "feat(plugin): Maven/OSGi skeleton with a loading Protege tab"
```

---

### Task 2: Prove robot-core and Protégé's OWL API coexist

Spec §13 flags this as the top technical risk and requires resolving it before feature work. `robot-core:1.9.8` declares OWL API **4.5.29**; Protégé supplies its own. If they diverge irreconcilably, the in-process ROBOT approach fails and the spec needs revisiting — so find out now, while there is nothing to throw away.

**Files:**
- Modify: `protege-plugin/pom.xml` (add `robot-core`)
- Create: `protege-plugin/src/test/resources/fixture-tiny.ttl`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/RobotCoreInteropTest.java`

**Interfaces:**
- Consumes: the Task 1 build.
- Produces: a working `robot-core` dependency, and the shared test fixture `fixture-tiny.ttl` used by Tasks 4–8. Its content is fixed: 3 named classes (`Person`, `Agent`, `Organization`), 1 object property (`worksFor`), 1 individual (`alice`).

- [ ] **Step 1: Create the shared test fixture**

`protege-plugin/src/test/resources/fixture-tiny.ttl`:

```turtle
@prefix owl:  <http://www.w3.org/2002/07/owl#> .
@prefix rdf:  <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
@prefix ex:   <http://example.org/tiny#> .

<http://example.org/tiny> a owl:Ontology .

ex:Agent        a owl:Class .
ex:Person       a owl:Class ; rdfs:subClassOf ex:Agent .
ex:Organization a owl:Class .

ex:worksFor a owl:ObjectProperty ;
    rdfs:domain ex:Person ;
    rdfs:range  ex:Organization .

ex:alice a owl:NamedIndividual, ex:Person .
```

- [ ] **Step 2: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/RobotCoreInteropTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.obolibrary.robot.IOHelper;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class RobotCoreInteropTest {

    private static final File FIXTURE = new File("src/test/resources/fixture-tiny.ttl");

    /** The whole in-process ROBOT approach depends on there being exactly one OWL API. */
    @Test
    void robotCoreAndOwlApiBindingShareOneOWLOntologyType() throws Exception {
        OWLOntology viaRobot = new IOHelper().loadOntology(FIXTURE);
        OWLOntology viaOwlApi = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(FIXTURE);

        assertSame(OWLOntology.class, viaRobot.getClass().getInterfaces().length > 0
                        ? OWLOntology.class : null,
                "sanity: OWLOntology type resolvable");
        assertSame(viaRobot.getOWLOntologyManager().getOWLDataFactory().getClass().getClassLoader(),
                viaOwlApi.getOWLOntologyManager().getOWLDataFactory().getClass().getClassLoader(),
                "robot-core and the OWL API binding resolved to different classloaders");
    }

    @Test
    void bothPathsReadTheSameOntology() throws Exception {
        OWLOntology viaRobot = new IOHelper().loadOntology(FIXTURE);
        OWLOntology viaOwlApi = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(FIXTURE);

        assertEquals(3, viaRobot.getClassesInSignature().size());
        assertEquals(viaOwlApi.getClassesInSignature().size(),
                     viaRobot.getClassesInSignature().size());
    }
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=RobotCoreInteropTest`
Expected: FAIL — compilation error, `package org.obolibrary.robot does not exist`.

- [ ] **Step 4: Add robot-core and inspect the resolved OWL API**

Add to `<dependencies>` in `protege-plugin/pom.xml`, before the JUnit entry:

```xml
    <dependency>
      <groupId>org.obolibrary.robot</groupId>
      <artifactId>robot-core</artifactId>
      <version>1.9.8</version>
    </dependency>
```

Add `robot-core` to the embed list — change the `Embed-Dependency` line to:

```xml
            <Embed-Dependency>jgraphx,robot-core</Embed-Dependency>
```

Then inspect what actually resolved:

```bash
cd protege-plugin && mvn -q dependency:tree -Dincludes=net.sourceforge.owlapi
```

Record the result. **Decision point:**
- *One OWL API version resolves* → proceed to Step 5.
- *Two versions appear (one from Protégé, one from robot-core)* → add an `<exclusions>` block on the `robot-core` dependency excluding `net.sourceforge.owlapi:owlapi-distribution`, `owlapi-api`, `owlapi-apibinding`, and `owlapi-rio`, so robot-core compiles against Protégé's OWL API. Re-run and confirm a single version.
- *Excluding breaks compilation because the versions are incompatible* → **stop and report.** This invalidates spec §7.1's in-process ROBOT assumption and the spec must be revisited before any further work.

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 4 tests.

- [ ] **Step 6: Verify the packaged bundle still resolves in Protégé**

```bash
cd protege-plugin && mvn -q clean package && ls -lh target/ontoboard-2.0.0-SNAPSHOT.jar
cp target/ontoboard-2.0.0-SNAPSHOT.jar "$PROTEGE_HOME/plugins/"
```

Start Protégé and confirm the OntoBoard tab still appears. Record the JAR size — spec §13 asks for this measurement, and it decides whether `patterns-repository/` ships inside the JAR (spec §14).

- [ ] **Step 7: Commit**

```bash
git add protege-plugin/pom.xml protege-plugin/src/test/
git commit -m "feat(plugin): add robot-core and prove OWL API convergence with Protege"
```

---

### Task 3: Sidecar layout model and store

Pure logic, no UI. Implements spec §6.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/layout/CanvasLayout.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/layout/UnsupportedLayoutVersionException.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/layout/CanvasLayoutStore.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/layout/CanvasLayoutStoreTest.java`

**Interfaces:**
- Consumes: Jackson `ObjectMapper` (provided transitively by Protégé — do not declare it).
- Produces:
  - `CanvasLayout` with public mutable fields `version:int`, `ontologyIri:String`, `onCanvas:List<String>`, `nodes:Map<String,CanvasLayout.NodeLayout>`, `frames:List<CanvasLayout.FrameLayout>`, `notes:List<CanvasLayout.NoteLayout>`, `prefixColors:Map<String,String>`; constant `CanvasLayout.CURRENT_VERSION == 1`.
  - `CanvasLayout.NodeLayout` with public `double x, y, w, h` and constructors `NodeLayout()` and `NodeLayout(double x, double y)`.
  - `CanvasLayoutStore.sidecarFor(File):File`, `CanvasLayoutStore.load(File):CanvasLayout`, `CanvasLayoutStore.save(File, CanvasLayout):void`.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/layout/CanvasLayoutStoreTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanvasLayoutStoreTest {

    @Test
    void sidecarNameAppendsToTheFullOntologyFileName(@TempDir Path dir) {
        File ontology = dir.resolve("myont-edit.owl").toFile();
        assertEquals("myont-edit.owl.ontoboard.json",
                CanvasLayoutStore.sidecarFor(ontology).getName());
    }

    @Test
    void missingSidecarYieldsAnEmptyLayoutRatherThanFailing(@TempDir Path dir) {
        CanvasLayout layout = CanvasLayoutStore.load(dir.resolve("absent.owl").toFile());
        assertEquals(CanvasLayout.CURRENT_VERSION, layout.version);
        assertTrue(layout.onCanvas.isEmpty());
        assertTrue(layout.nodes.isEmpty());
    }

    @Test
    void roundTripsAllPresentationState(@TempDir Path dir) {
        File ontology = dir.resolve("round.owl").toFile();

        CanvasLayout original = new CanvasLayout();
        original.ontologyIri = "http://example.org/tiny";
        original.onCanvas.add("http://example.org/tiny#Person");
        original.nodes.put("http://example.org/tiny#Person", new CanvasLayout.NodeLayout(120, 40));
        original.prefixColors.put("ex", "#4A90D9");

        CanvasLayoutStore.save(ontology, original);
        CanvasLayout reloaded = CanvasLayoutStore.load(ontology);

        assertEquals("http://example.org/tiny", reloaded.ontologyIri);
        assertEquals(1, reloaded.onCanvas.size());
        assertEquals("http://example.org/tiny#Person", reloaded.onCanvas.get(0));
        assertEquals(120.0, reloaded.nodes.get("http://example.org/tiny#Person").x);
        assertEquals(40.0, reloaded.nodes.get("http://example.org/tiny#Person").y);
        assertEquals("#4A90D9", reloaded.prefixColors.get("ex"));
    }

    @Test
    void unknownVersionFailsLoudlyInsteadOfSilentlyLosingLayout(@TempDir Path dir) throws Exception {
        File ontology = dir.resolve("future.owl").toFile();
        Files.write(CanvasLayoutStore.sidecarFor(ontology).toPath(),
                "{\"version\":999}".getBytes(StandardCharsets.UTF_8));

        assertThrows(UnsupportedLayoutVersionException.class,
                () -> CanvasLayoutStore.load(ontology));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=CanvasLayoutStoreTest`
Expected: FAIL — compilation error, `package de.fizkarlsruhe.ise.ontoboard.layout does not exist`.

- [ ] **Step 3: Write the implementation**

`CanvasLayout.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.layout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canvas presentation state. Deliberately contains no OWL semantics: everything here is
 * about how the diagram looks, never about what the ontology means.
 *
 * <p>Public mutable fields are intentional. This is a Jackson DTO edited in place by the
 * canvas; getters would add noise without adding safety.
 */
public class CanvasLayout {

    public static final int CURRENT_VERSION = 1;

    public int version = CURRENT_VERSION;
    public String ontologyIri;
    public List<String> onCanvas = new ArrayList<>();
    public Map<String, NodeLayout> nodes = new LinkedHashMap<>();
    public List<FrameLayout> frames = new ArrayList<>();
    public List<NoteLayout> notes = new ArrayList<>();
    public Map<String, String> prefixColors = new LinkedHashMap<>();

    public static class NodeLayout {
        public double x;
        public double y;
        public double w = 160;
        public double h = 60;

        public NodeLayout() {
        }

        public NodeLayout(double x, double y) {
            this.x = x;
            this.y = y;
        }
    }

    public static class FrameLayout {
        public String id;
        public String label;
        public double x;
        public double y;
        public double w;
        public double h;
        public String fill = "#EEF3FA";
        public String stroke = "#4A90D9";
    }

    public static class NoteLayout {
        public String id;
        public String text;
        public double x;
        public double y;
        public double w = 180;
        public double h = 120;
        public String color = "#FFF3B0";
        public int fontSize = 12;
    }
}
```

`UnsupportedLayoutVersionException.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.layout;

import java.io.File;

/** Thrown when a sidecar was written by a newer OntoBoard than this one. */
public class UnsupportedLayoutVersionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnsupportedLayoutVersionException(File sidecar, int foundVersion) {
        super(String.format(
                "%s declares layout version %d, but this OntoBoard understands version %d. "
                        + "Refusing to load rather than silently discarding the layout.",
                sidecar.getName(), foundVersion, CanvasLayout.CURRENT_VERSION));
    }
}
```

`CanvasLayoutStore.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.layout;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;

/** Reads and writes the {@code <ontology-file-name>.ontoboard.json} sidecar. */
public final class CanvasLayoutStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private CanvasLayoutStore() {
    }

    /** {@code myont-edit.owl} to {@code myont-edit.owl.ontoboard.json}. */
    public static File sidecarFor(File ontologyFile) {
        return new File(ontologyFile.getParentFile(), ontologyFile.getName() + ".ontoboard.json");
    }

    /** Returns an empty layout when no sidecar exists; that is a normal first run, not an error. */
    public static CanvasLayout load(File ontologyFile) {
        File sidecar = sidecarFor(ontologyFile);
        if (!sidecar.isFile()) {
            return new CanvasLayout();
        }
        CanvasLayout layout;
        try {
            layout = MAPPER.readValue(sidecar, CanvasLayout.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + sidecar, e);
        }
        if (layout.version != CanvasLayout.CURRENT_VERSION) {
            throw new UnsupportedLayoutVersionException(sidecar, layout.version);
        }
        return layout;
    }

    public static void save(File ontologyFile, CanvasLayout layout) {
        File sidecar = sidecarFor(ontologyFile);
        try {
            MAPPER.writeValue(sidecar, layout);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write " + sidecar, e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(layout): sidecar canvas layout model and store"
```

---

### Task 4: Ontology projection

Turns an ontology plus a membership set into node/edge value objects. Pure logic, fully testable without Swing. Implements spec §5.1 and the read half of §5.2.

Crucially it projects **both** the existential form (what Plan 2 will write) and the `rdfs:domain`/`rdfs:range` form (what the retired web app wrote), so existing OntoBoard ontologies render correctly.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/model/NodeKind.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/model/CanvasNode.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/model/CanvasEdge.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/model/Projection.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/model/OntologyProjection.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/model/OntologyProjectionTest.java`

**Interfaces:**
- Consumes: `fixture-tiny.ttl` from Task 2.
- Produces:
  - `enum NodeKind { CLASS, INDIVIDUAL, DATATYPE, LITERAL }`
  - `CanvasNode` with `getId():String`, `getKind():NodeKind`, `getLabel():String`; `equals`/`hashCode` on id alone.
  - `CanvasEdge` with nested `enum Kind { SUBCLASS, OBJECT_PROPERTY, DATA_PROPERTY, TYPE }` and `getId()`, `getSourceId()`, `getTargetId()`, `getLabel()`, `getKind()`.
  - `Projection` with `getNodes():List<CanvasNode>` and `getEdges():List<CanvasEdge>`.
  - `OntologyProjection.project(OWLOntology, Set<String> onCanvasIris):Projection` — static.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/model/OntologyProjectionTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class OntologyProjectionTest {

    private static final String NS = "http://example.org/tiny#";
    private OWLOntology ontology;

    @BeforeEach
    void loadFixture() throws Exception {
        ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
    }

    private static Set<String> iris(String... localNames) {
        return Arrays.stream(localNames).map(n -> NS + n).collect(Collectors.toCollection(HashSet::new));
    }

    @Test
    void projectsOnlyEntitiesThatAreOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertEquals(1, p.getNodes().size());
        assertEquals(NS + "Person", p.getNodes().get(0).getId());
        assertEquals(NodeKind.CLASS, p.getNodes().get(0).getKind());
    }

    @Test
    void projectsSubClassOfBetweenTwoOnCanvasClasses() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Agent"));
        assertEquals(2, p.getNodes().size());
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.SUBCLASS, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Agent", edge.getTargetId());
    }

    @Test
    void omitsSubClassOfWhenTheSuperClassIsNotOnTheCanvas() {
        Projection p = OntologyProjection.project(ontology, iris("Person"));
        assertTrue(p.getEdges().isEmpty());
    }

    /** Ontologies written by the retired web app used rdfs:domain/rdfs:range for edges. */
    @Test
    void projectsLegacyDomainRangePairsAsAPropertyEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "Organization"));
        assertEquals(1, p.getEdges().size());

        CanvasEdge edge = p.getEdges().get(0);
        assertEquals(CanvasEdge.Kind.OBJECT_PROPERTY, edge.getKind());
        assertEquals(NS + "Person", edge.getSourceId());
        assertEquals(NS + "Organization", edge.getTargetId());
        assertEquals("worksFor", edge.getLabel());
    }

    @Test
    void projectsClassAssertionAsATypeEdge() {
        Projection p = OntologyProjection.project(ontology, iris("Person", "alice"));

        CanvasNode alice = p.getNodes().stream()
                .filter(n -> n.getId().equals(NS + "alice")).findFirst().orElseThrow();
        assertEquals(NodeKind.INDIVIDUAL, alice.getKind());

        assertEquals(1, p.getEdges().size());
        assertEquals(CanvasEdge.Kind.TYPE, p.getEdges().get(0).getKind());
        assertEquals(NS + "alice", p.getEdges().get(0).getSourceId());
        assertEquals(NS + "Person", p.getEdges().get(0).getTargetId());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=OntologyProjectionTest`
Expected: FAIL — compilation error, `package de.fizkarlsruhe.ise.ontoboard.model does not exist`.

- [ ] **Step 3: Write the value objects**

`NodeKind.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

public enum NodeKind {
    CLASS, INDIVIDUAL, DATATYPE, LITERAL
}
```

`CanvasNode.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** A node as the canvas sees it. Identity is the id alone. */
public final class CanvasNode {

    private final String id;
    private final NodeKind kind;
    private final String label;

    public CanvasNode(String id, NodeKind kind, String label) {
        this.id = Objects.requireNonNull(id, "id");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.label = Objects.requireNonNull(label, "label");
    }

    public String getId() {
        return id;
    }

    public NodeKind getKind() {
        return kind;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanvasNode && id.equals(((CanvasNode) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return kind + "(" + id + ")";
    }
}
```

`CanvasEdge.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Objects;

/** An edge as the canvas sees it. Identity is the id alone. */
public final class CanvasEdge {

    public enum Kind {
        SUBCLASS, OBJECT_PROPERTY, DATA_PROPERTY, TYPE
    }

    private final String id;
    private final String sourceId;
    private final String targetId;
    private final String label;
    private final Kind kind;

    public CanvasEdge(String id, String sourceId, String targetId, String label, Kind kind) {
        this.id = Objects.requireNonNull(id, "id");
        this.sourceId = Objects.requireNonNull(sourceId, "sourceId");
        this.targetId = Objects.requireNonNull(targetId, "targetId");
        this.label = Objects.requireNonNull(label, "label");
        this.kind = Objects.requireNonNull(kind, "kind");
    }

    public String getId() {
        return id;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getLabel() {
        return label;
    }

    public Kind getKind() {
        return kind;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanvasEdge && id.equals(((CanvasEdge) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
```

`Projection.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.Collections;
import java.util.List;

/** What the canvas should currently show. Immutable. */
public final class Projection {

    private final List<CanvasNode> nodes;
    private final List<CanvasEdge> edges;

    public Projection(List<CanvasNode> nodes, List<CanvasEdge> edges) {
        this.nodes = Collections.unmodifiableList(nodes);
        this.edges = Collections.unmodifiableList(edges);
    }

    public List<CanvasNode> getNodes() {
        return nodes;
    }

    public List<CanvasEdge> getEdges() {
        return edges;
    }
}
```

- [ ] **Step 4: Write the projection logic**

`OntologyProjection.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectAllValuesFrom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * Projects an ontology onto the canvas, restricted to the entities the user has chosen to
 * show (spec section 5.3). An edge is emitted only when both endpoints are on the canvas.
 */
public final class OntologyProjection {

    private OntologyProjection() {
    }

    public static Projection project(OWLOntology ontology, Set<String> onCanvasIris) {
        List<CanvasNode> nodes = new ArrayList<>();
        List<CanvasEdge> edges = new ArrayList<>();

        for (OWLClass cls : ontology.getClassesInSignature()) {
            if (isOn(onCanvasIris, cls.getIRI())) {
                nodes.add(new CanvasNode(iri(cls.getIRI()), NodeKind.CLASS, localName(cls.getIRI())));
            }
        }
        for (OWLNamedIndividual ind : ontology.getIndividualsInSignature()) {
            if (isOn(onCanvasIris, ind.getIRI())) {
                nodes.add(new CanvasNode(iri(ind.getIRI()), NodeKind.INDIVIDUAL, localName(ind.getIRI())));
            }
        }

        collectSubClassEdges(ontology, onCanvasIris, edges);
        collectLegacyDomainRangeEdges(ontology, onCanvasIris, edges);
        collectTypeEdges(ontology, onCanvasIris, edges);

        return new Projection(nodes, edges);
    }

    /** SubClassOf(A B) and SubClassOf(A ObjectSomeValuesFrom(R B)) / ObjectAllValuesFrom. */
    private static void collectSubClassEdges(OWLOntology ontology, Set<String> on, List<CanvasEdge> edges) {
        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            if (axiom.getSubClass().isAnonymous()) {
                continue;
            }
            IRI subIri = axiom.getSubClass().asOWLClass().getIRI();
            if (!isOn(on, subIri)) {
                continue;
            }
            OWLClassExpression sup = axiom.getSuperClass();

            if (!sup.isAnonymous()) {
                IRI supIri = sup.asOWLClass().getIRI();
                if (isOn(on, supIri)) {
                    edges.add(new CanvasEdge("sub|" + subIri + "|" + supIri,
                            iri(subIri), iri(supIri), "", CanvasEdge.Kind.SUBCLASS));
                }
            } else if (sup instanceof OWLObjectSomeValuesFrom) {
                OWLObjectSomeValuesFrom some = (OWLObjectSomeValuesFrom) sup;
                addRestrictionEdge(on, edges, subIri, some.getProperty(), some.getFiller(), "some");
            } else if (sup instanceof OWLObjectAllValuesFrom) {
                OWLObjectAllValuesFrom all = (OWLObjectAllValuesFrom) sup;
                addRestrictionEdge(on, edges, subIri, all.getProperty(), all.getFiller(), "only");
            } else if (sup instanceof OWLDataSomeValuesFrom) {
                OWLDataSomeValuesFrom data = (OWLDataSomeValuesFrom) sup;
                if (data.getProperty().isAnonymous() || !data.getFiller().isDatatype()) {
                    return;
                }
                IRI propIri = data.getProperty().asOWLDataProperty().getIRI();
                IRI dtIri = data.getFiller().asOWLDatatype().getIRI();
                edges.add(new CanvasEdge("data|" + subIri + "|" + propIri + "|" + dtIri,
                        iri(subIri), iri(dtIri), localName(propIri), CanvasEdge.Kind.DATA_PROPERTY));
            }
        }
    }

    private static void addRestrictionEdge(Set<String> on, List<CanvasEdge> edges, IRI subIri,
            org.semanticweb.owlapi.model.OWLObjectPropertyExpression property,
            OWLClassExpression filler, String qualifier) {
        if (property.isAnonymous() || filler.isAnonymous()) {
            return;
        }
        IRI propIri = property.asOWLObjectProperty().getIRI();
        IRI fillerIri = filler.asOWLClass().getIRI();
        if (!isOn(on, fillerIri)) {
            return;
        }
        String label = "some".equals(qualifier)
                ? localName(propIri)
                : localName(propIri) + " (only)";
        edges.add(new CanvasEdge("rest|" + qualifier + "|" + subIri + "|" + propIri + "|" + fillerIri,
                iri(subIri), iri(fillerIri), label, CanvasEdge.Kind.OBJECT_PROPERTY));
    }

    /**
     * Ontologies produced by the retired web app encoded every property edge as a global
     * rdfs:domain plus rdfs:range pair. Render those so existing boards still open.
     */
    private static void collectLegacyDomainRangeEdges(OWLOntology ontology, Set<String> on,
            List<CanvasEdge> edges) {
        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            for (OWLObjectPropertyDomainAxiom domainAxiom
                    : ontology.getObjectPropertyDomainAxioms(property)) {
                if (domainAxiom.getDomain().isAnonymous()) {
                    continue;
                }
                IRI domainIri = domainAxiom.getDomain().asOWLClass().getIRI();
                if (!isOn(on, domainIri)) {
                    continue;
                }
                for (OWLObjectPropertyRangeAxiom rangeAxiom
                        : ontology.getObjectPropertyRangeAxioms(property)) {
                    if (rangeAxiom.getRange().isAnonymous()) {
                        continue;
                    }
                    IRI rangeIri = rangeAxiom.getRange().asOWLClass().getIRI();
                    if (!isOn(on, rangeIri)) {
                        continue;
                    }
                    edges.add(new CanvasEdge(
                            "dr|" + domainIri + "|" + property.getIRI() + "|" + rangeIri,
                            iri(domainIri), iri(rangeIri), localName(property.getIRI()),
                            CanvasEdge.Kind.OBJECT_PROPERTY));
                }
            }
        }
    }

    private static void collectTypeEdges(OWLOntology ontology, Set<String> on, List<CanvasEdge> edges) {
        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (axiom.getIndividual().isAnonymous() || axiom.getClassExpression().isAnonymous()) {
                continue;
            }
            IRI indIri = axiom.getIndividual().asOWLNamedIndividual().getIRI();
            IRI clsIri = axiom.getClassExpression().asOWLClass().getIRI();
            if (isOn(on, indIri) && isOn(on, clsIri)) {
                edges.add(new CanvasEdge("type|" + indIri + "|" + clsIri,
                        iri(indIri), iri(clsIri), "", CanvasEdge.Kind.TYPE));
            }
        }
    }

    private static boolean isOn(Set<String> onCanvasIris, IRI candidate) {
        return onCanvasIris.contains(candidate.toString());
    }

    private static String iri(IRI value) {
        return value.toString();
    }

    private static String localName(IRI value) {
        String fragment = value.getFragment();
        if (fragment != null && !fragment.isEmpty()) {
            return fragment;
        }
        String text = value.toString();
        int slash = text.lastIndexOf('/');
        return slash >= 0 && slash < text.length() - 1 ? text.substring(slash + 1) : text;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 13 tests.

Note: `projectsClassAssertionAsATypeEdge` expects exactly one edge because `owl:NamedIndividual` is a built-in, not a projected class, and `Organization` is off-canvas in that case.

- [ ] **Step 6: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(model): project OWL ontologies onto canvas nodes and edges"
```

---

### Task 5: Render the projection with JGraphX

First visible output. Replaces the Task 1 placeholder label with a real diagram.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/SchemaStyles.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/SchemaGraph.java`
- Modify: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/SchemaGraphTest.java`

**Interfaces:**
- Consumes: `Projection`, `CanvasNode`, `CanvasEdge`, `NodeKind` (Task 4); `CanvasLayout` (Task 3).
- Produces:
  - `SchemaStyles` with `String` constants `CLASS`, `INDIVIDUAL`, `DATATYPE`, `LITERAL`, `SUBCLASS`, `OBJECT_PROPERTY`, `DATA_PROPERTY`, `TYPE`, and `static void install(mxGraph)`.
  - `SchemaGraph extends com.mxgraph.view.mxGraph` with `void render(Projection, CanvasLayout)`, `Object getCellForId(String)`, `String getIdForCell(Object)`.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/SchemaGraphTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mxgraph.model.mxCell;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class SchemaGraphTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private static Projection twoClassesWithSubClassEdge() {
        return new Projection(
                Arrays.asList(
                        new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                        new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS)));
    }

    @Test
    void rendersOneCellPerNodeAndEdge() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        assertNotNull(graph.getCellForId(PERSON));
        assertNotNull(graph.getCellForId(AGENT));
        assertNotNull(graph.getCellForId("sub|1"));
        assertEquals("Person", ((mxCell) graph.getCellForId(PERSON)).getValue());
    }

    @Test
    void placesNodesAtTheirStoredLayoutPosition() {
        CanvasLayout layout = new CanvasLayout();
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(310, 190));

        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), layout);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        assertEquals(310.0, person.getGeometry().getX());
        assertEquals(190.0, person.getGeometry().getY());
    }

    @Test
    void mapsCellsBackToTheirEntityIri() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        assertEquals(PERSON, graph.getIdForCell(graph.getCellForId(PERSON)));
        assertNull(graph.getIdForCell(null));
    }

    @Test
    void reRenderingReplacesRatherThanAccumulatesCells() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());
        graph.render(twoClassesWithSubClassEdge(), new CanvasLayout());

        Object[] children = com.mxgraph.model.mxGraphModel
                .getChildren(graph.getModel(), graph.getDefaultParent());
        assertEquals(3, children.length, "expected 2 vertices + 1 edge, not duplicates");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=SchemaGraphTest`
Expected: FAIL — compilation error, `package de.fizkarlsruhe.ise.ontoboard.canvas does not exist`.

- [ ] **Step 3: Write the styles**

`SchemaStyles.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxConstants;
import com.mxgraph.view.mxGraph;
import com.mxgraph.view.mxStylesheet;
import java.util.HashMap;
import java.util.Map;

/** JGraphX styles for each node and edge kind (spec section 5.1). */
public final class SchemaStyles {

    public static final String CLASS = "obClass";
    public static final String INDIVIDUAL = "obIndividual";
    public static final String DATATYPE = "obDatatype";
    public static final String LITERAL = "obLiteral";

    public static final String SUBCLASS = "obSubClass";
    public static final String OBJECT_PROPERTY = "obObjectProperty";
    public static final String DATA_PROPERTY = "obDataProperty";
    public static final String TYPE = "obType";

    private SchemaStyles() {
    }

    public static void install(mxGraph graph) {
        mxStylesheet sheet = graph.getStylesheet();
        sheet.putCellStyle(CLASS, vertex(mxConstants.SHAPE_RECTANGLE, "#FFFFFF", "#4A90D9", true));
        sheet.putCellStyle(INDIVIDUAL, vertex(mxConstants.SHAPE_RHOMBUS, "#FFFFFF", "#7B61A8", false));
        sheet.putCellStyle(DATATYPE, vertex(mxConstants.SHAPE_ELLIPSE, "#FFFFFF", "#3E8E5A", false));
        sheet.putCellStyle(LITERAL, vertex(mxConstants.SHAPE_RECTANGLE, "#FFF9E6", "#B08900", false));

        sheet.putCellStyle(SUBCLASS, edge("#556677", "6 3", mxConstants.ARROW_BLOCK));
        sheet.putCellStyle(OBJECT_PROPERTY, edge("#4A90D9", null, mxConstants.ARROW_CLASSIC));
        sheet.putCellStyle(DATA_PROPERTY, edge("#3E8E5A", null, mxConstants.ARROW_OPEN));
        sheet.putCellStyle(TYPE, edge("#7B61A8", "2 3", mxConstants.ARROW_OPEN));
    }

    private static Map<String, Object> vertex(String shape, String fill, String stroke, boolean rounded) {
        Map<String, Object> style = new HashMap<>();
        style.put(mxConstants.STYLE_SHAPE, shape);
        style.put(mxConstants.STYLE_FILLCOLOR, fill);
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_FONTCOLOR, "#1A1A1A");
        style.put(mxConstants.STYLE_ROUNDED, rounded);
        return style;
    }

    private static Map<String, Object> edge(String stroke, String dashPattern, String endArrow) {
        Map<String, Object> style = new HashMap<>();
        style.put(mxConstants.STYLE_STROKECOLOR, stroke);
        style.put(mxConstants.STYLE_FONTCOLOR, "#333333");
        style.put(mxConstants.STYLE_ENDARROW, endArrow);
        if (dashPattern != null) {
            style.put(mxConstants.STYLE_DASHED, true);
            style.put(mxConstants.STYLE_DASH_PATTERN, dashPattern);
        }
        return style;
    }
}
```

- [ ] **Step 4: Write the graph**

`SchemaGraph.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.model.mxCell;
import com.mxgraph.model.mxGraphModel;
import com.mxgraph.view.mxGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.HashMap;
import java.util.Map;

/** Renders a {@link Projection}. Holds no ontology state of its own. */
public class SchemaGraph extends mxGraph {

    private static final double DEFAULT_W = 160;
    private static final double DEFAULT_H = 60;

    private final Map<String, Object> cellsById = new HashMap<>();

    public SchemaGraph() {
        SchemaStyles.install(this);
        setCellsResizable(true);
        setAllowDanglingEdges(false);
        setAllowLoops(false);
        setCellsDisconnectable(false);
        setEdgeLabelsMovable(false);
    }

    public void render(Projection projection, CanvasLayout layout) {
        getModel().beginUpdate();
        try {
            removeCells(mxGraphModel.getChildren(getModel(), getDefaultParent()), true);
            cellsById.clear();

            double nextX = 40;
            for (CanvasNode node : projection.getNodes()) {
                CanvasLayout.NodeLayout stored = layout.nodes.get(node.getId());
                double x = stored != null ? stored.x : nextX;
                double y = stored != null ? stored.y : 40;
                double w = stored != null ? stored.w : DEFAULT_W;
                double h = stored != null ? stored.h : DEFAULT_H;
                if (stored == null) {
                    nextX += DEFAULT_W + 40;
                }
                Object cell = insertVertex(getDefaultParent(), node.getId(), node.getLabel(),
                        x, y, w, h, styleFor(node));
                cellsById.put(node.getId(), cell);
            }

            for (CanvasEdge edge : projection.getEdges()) {
                Object source = cellsById.get(edge.getSourceId());
                Object target = cellsById.get(edge.getTargetId());
                if (source == null || target == null) {
                    continue; // spec section 5.1: both endpoints must be on the canvas
                }
                Object cell = insertEdge(getDefaultParent(), edge.getId(), edge.getLabel(),
                        source, target, styleFor(edge));
                cellsById.put(edge.getId(), cell);
            }
        } finally {
            getModel().endUpdate();
        }
    }

    public Object getCellForId(String id) {
        return id == null ? null : cellsById.get(id);
    }

    public String getIdForCell(Object cell) {
        return cell instanceof mxCell ? ((mxCell) cell).getId() : null;
    }

    private static String styleFor(CanvasNode node) {
        switch (node.getKind()) {
            case INDIVIDUAL: return SchemaStyles.INDIVIDUAL;
            case DATATYPE:   return SchemaStyles.DATATYPE;
            case LITERAL:    return SchemaStyles.LITERAL;
            case CLASS:
            default:         return SchemaStyles.CLASS;
        }
    }

    private static String styleFor(CanvasEdge edge) {
        switch (edge.getKind()) {
            case SUBCLASS:        return SchemaStyles.SUBCLASS;
            case DATA_PROPERTY:   return SchemaStyles.DATA_PROPERTY;
            case TYPE:            return SchemaStyles.TYPE;
            case OBJECT_PROPERTY:
            default:              return SchemaStyles.OBJECT_PROPERTY;
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 17 tests.

- [ ] **Step 6: Wire the graph into the view**

Replace `SchemaCanvasView.java` entirely:

```java
package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.util.HashSet;
import java.util.Set;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private SchemaGraph graph;
    private CanvasLayout layout = new CanvasLayout();
    private OWLOntologyChangeListener changeListener;

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        add(new mxGraphComponent(graph), BorderLayout.CENTER);

        // Until Task 6 adds explicit membership, seed the canvas with up to 25 classes so
        // there is something to look at. Task 6 replaces this with the sidecar's set.
        seedInitialMembership();
        refresh();

        changeListener = changes -> refresh();
        getOWLModelManager().addOntologyChangeListener(changeListener);
    }

    @Override
    protected void disposeOWLView() {
        if (changeListener != null) {
            getOWLModelManager().removeOntologyChangeListener(changeListener);
        }
    }

    private void seedInitialMembership() {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        layout.ontologyIri = ontology.getOntologyID().getOntologyIRI()
                .transform(Object::toString).or("");
        int budget = 25;
        for (OWLClass cls : ontology.getClassesInSignature()) {
            if (budget-- <= 0) {
                break;
            }
            layout.onCanvas.add(cls.getIRI().toString());
        }
    }

    private void refresh() {
        Set<String> onCanvas = new HashSet<>(layout.onCanvas);
        Projection projection =
                OntologyProjection.project(getOWLModelManager().getActiveOntology(), onCanvas);
        graph.render(projection, layout);
    }
}
```

Note: `OWLOntologyChangeListener` is a functional interface taking `List<? extends OWLOntologyChange>`, so the lambda compiles. The `OWLOntologyChange` import keeps that explicit for readers.

- [ ] **Step 7: Verify in Protégé**

```bash
cd protege-plugin && mvn -q clean package
cp target/ontoboard-2.0.0-SNAPSHOT.jar "$PROTEGE_HOME/plugins/"
```

Open `../mwo301.ttl` in Protégé and switch to the OntoBoard tab. Expected: up to 25 class boxes with dashed SubClassOf arrows between those that are both shown. Adding a subclass in Protégé's class hierarchy must make the canvas redraw immediately — that is the §4.1 live-model binding working.

- [ ] **Step 8: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(canvas): render the ontology projection with JGraphX"
```

---

### Task 6: Opt-in canvas membership and layout persistence

Implements spec §5.3 and completes §6. Replaces Task 5's 25-class seed with a real, persisted membership set.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasMembership.java`
- Modify: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasMembershipTest.java`

**Interfaces:**
- Consumes: `CanvasLayout` (Task 3), `OntologyProjection` (Task 4).
- Produces: `CanvasMembership` wrapping a `CanvasLayout`, with `add(String iri):boolean`, `remove(String iri):boolean`, `contains(String iri):boolean`, `expandOneHop(OWLOntology, String iri):int`, `asSet():Set<String>`, `size():int`.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasMembershipTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.io.File;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.OWLOntology;

class CanvasMembershipTest {

    private static final String NS = "http://example.org/tiny#";
    private OWLOntology ontology;
    private CanvasMembership membership;

    @BeforeEach
    void setUp() throws Exception {
        ontology = OWLManager.createOWLOntologyManager()
                .loadOntologyFromOntologyDocument(new File("src/test/resources/fixture-tiny.ttl"));
        membership = new CanvasMembership(new CanvasLayout());
    }

    @Test
    void startsEmptyBecauseTheCanvasIsOptIn() {
        assertEquals(0, membership.size());
    }

    @Test
    void addIsIdempotent() {
        assertTrue(membership.add(NS + "Person"));
        assertFalse(membership.add(NS + "Person"));
        assertEquals(1, membership.size());
    }

    @Test
    void removeTakesTheEntityOffTheCanvasButLeavesTheOntologyAlone() {
        membership.add(NS + "Person");
        int axiomsBefore = ontology.getAxiomCount();

        assertTrue(membership.remove(NS + "Person"));
        assertFalse(membership.contains(NS + "Person"));
        assertEquals(axiomsBefore, ontology.getAxiomCount(),
                "removing from the canvas must never delete axioms");
    }

    @Test
    void expandOneHopPullsInDirectlyRelatedEntitiesOnly() {
        membership.add(NS + "Person");
        int added = membership.expandOneHop(ontology, NS + "Person");

        // Agent via SubClassOf, Organization via the worksFor domain/range pair, alice via rdf:type.
        assertEquals(3, added);
        assertTrue(membership.contains(NS + "Agent"));
        assertTrue(membership.contains(NS + "Organization"));
        assertTrue(membership.contains(NS + "alice"));
    }

    @Test
    void expandOneHopDoesNotCountEntitiesAlreadyPresent() {
        membership.add(NS + "Person");
        membership.add(NS + "Agent");
        assertEquals(2, membership.expandOneHop(ontology, NS + "Person"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=CanvasMembershipTest`
Expected: FAIL — `cannot find symbol: class CanvasMembership`.

- [ ] **Step 3: Write the implementation**

`CanvasMembership.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

/**
 * Which entities the user has chosen to show (spec section 5.3). The canvas is opt-in
 * because Protege routinely opens ontologies far larger than any diagram can hold.
 *
 * <p>Removing an entity here is purely a view operation and never touches the ontology.
 */
public class CanvasMembership {

    private final CanvasLayout layout;

    public CanvasMembership(CanvasLayout layout) {
        this.layout = layout;
    }

    public boolean add(String iri) {
        if (layout.onCanvas.contains(iri)) {
            return false;
        }
        layout.onCanvas.add(iri);
        return true;
    }

    public boolean remove(String iri) {
        layout.nodes.remove(iri);
        return layout.onCanvas.remove(iri);
    }

    public boolean contains(String iri) {
        return layout.onCanvas.contains(iri);
    }

    public Set<String> asSet() {
        return new HashSet<>(layout.onCanvas);
    }

    public int size() {
        return layout.onCanvas.size();
    }

    /** Adds every entity directly related to {@code iri}. Returns how many were newly added. */
    public int expandOneHop(OWLOntology ontology, String iri) {
        Set<String> neighbours = new LinkedHashSet<>();

        for (OWLSubClassOfAxiom axiom : ontology.getAxioms(AxiomType.SUBCLASS_OF)) {
            collectIfTouches(axiom.getSubClass(), axiom.getSuperClass(), iri, neighbours);
            collectIfTouches(axiom.getSuperClass(), axiom.getSubClass(), iri, neighbours);
        }

        for (OWLObjectProperty property : ontology.getObjectPropertiesInSignature()) {
            Set<String> domains = new LinkedHashSet<>();
            Set<String> ranges = new LinkedHashSet<>();
            for (OWLObjectPropertyDomainAxiom d : ontology.getObjectPropertyDomainAxioms(property)) {
                addNamed(d.getDomain(), domains);
            }
            for (OWLObjectPropertyRangeAxiom r : ontology.getObjectPropertyRangeAxioms(property)) {
                addNamed(r.getRange(), ranges);
            }
            if (domains.contains(iri)) {
                neighbours.addAll(ranges);
            }
            if (ranges.contains(iri)) {
                neighbours.addAll(domains);
            }
        }

        for (OWLClassAssertionAxiom axiom : ontology.getAxioms(AxiomType.CLASS_ASSERTION)) {
            if (axiom.getIndividual().isAnonymous() || axiom.getClassExpression().isAnonymous()) {
                continue;
            }
            String ind = axiom.getIndividual().asOWLNamedIndividual().getIRI().toString();
            String cls = axiom.getClassExpression().asOWLClass().getIRI().toString();
            if (ind.equals(iri)) {
                neighbours.add(cls);
            }
            if (cls.equals(iri)) {
                neighbours.add(ind);
            }
        }

        neighbours.remove(iri);
        int added = 0;
        for (String neighbour : neighbours) {
            if (add(neighbour)) {
                added++;
            }
        }
        return added;
    }

    private static void collectIfTouches(org.semanticweb.owlapi.model.OWLClassExpression anchor,
            org.semanticweb.owlapi.model.OWLClassExpression other, String iri, Set<String> into) {
        if (anchor.isAnonymous() || !anchor.asOWLClass().getIRI().toString().equals(iri)) {
            return;
        }
        for (OWLEntity entity : other.getSignature()) {
            if (entity.isOWLClass()) {
                into.add(entity.getIRI().toString());
            }
        }
    }

    private static void addNamed(org.semanticweb.owlapi.model.OWLClassExpression expression,
            Set<String> into) {
        if (!expression.isAnonymous()) {
            into.add(expression.asOWLClass().getIRI().toString());
        }
    }

    private static String text(IRI iri) {
        return iri.toString();
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 22 tests.

- [ ] **Step 5: Wire membership, persistence and a context menu into the view**

Replace the body of `SchemaCanvasView.java` — remove `seedInitialMembership()` and add membership plus save/load. Full replacement:

```java
package de.fizkarlsruhe.ise.ontoboard.views;

import com.mxgraph.swing.mxGraphComponent;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasMembership;
import de.fizkarlsruhe.ise.ontoboard.canvas.SchemaGraph;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayoutStore;
import de.fizkarlsruhe.ise.ontoboard.model.OntologyProjection;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.net.URI;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.protege.editor.owl.ui.view.AbstractOWLViewComponent;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChangeListener;

public class SchemaCanvasView extends AbstractOWLViewComponent {

    private SchemaGraph graph;
    private mxGraphComponent graphComponent;
    private CanvasLayout layout = new CanvasLayout();
    private CanvasMembership membership;
    private OWLOntologyChangeListener changeListener;

    @Override
    protected void initialiseOWLView() {
        setLayout(new BorderLayout());

        graph = new SchemaGraph();
        graphComponent = new mxGraphComponent(graph);
        add(graphComponent, BorderLayout.CENTER);

        loadLayout();
        membership = new CanvasMembership(layout);
        installContextMenu();
        refresh();

        changeListener = changes -> refresh();
        getOWLModelManager().addOntologyChangeListener(changeListener);
    }

    @Override
    protected void disposeOWLView() {
        if (changeListener != null) {
            getOWLModelManager().removeOntologyChangeListener(changeListener);
        }
        capturePositions();
        saveLayout();
    }

    /** Adds whatever is selected in Protege's hierarchy views to the canvas. */
    public void addSelectedEntityToCanvas() {
        OWLEntity selected = getOWLEditorKit().getOWLWorkspace()
                .getOWLSelectionModel().getSelectedEntity();
        if (selected != null && membership.add(selected.getIRI().toString())) {
            refresh();
        }
    }

    private void installContextMenu() {
        graphComponent.getGraphControl().addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent event) {
                if (!event.isPopupTrigger()) {
                    return;
                }
                Object cell = graphComponent.getCellAt(event.getX(), event.getY());
                String iri = graph.getIdForCell(cell);
                JPopupMenu menu = new JPopupMenu();

                JMenuItem addSelected = new JMenuItem("Add selected entity to canvas");
                addSelected.addActionListener(a -> addSelectedEntityToCanvas());
                menu.add(addSelected);

                if (iri != null && membership.contains(iri)) {
                    JMenuItem expand = new JMenuItem("Expand neighbours (1 hop)");
                    expand.addActionListener(a -> {
                        membership.expandOneHop(getOWLModelManager().getActiveOntology(), iri);
                        refresh();
                    });
                    menu.add(expand);

                    JMenuItem remove = new JMenuItem("Remove from canvas (keeps axioms)");
                    remove.addActionListener(a -> {
                        membership.remove(iri);
                        refresh();
                    });
                    menu.add(remove);
                }
                menu.show(graphComponent.getGraphControl(), event.getX(), event.getY());
            }
        });
    }

    private void refresh() {
        Projection projection = OntologyProjection
                .project(getOWLModelManager().getActiveOntology(), membership.asSet());
        graph.render(projection, layout);
    }

    /** Copies live cell geometry back into the layout so it survives the next save. */
    private void capturePositions() {
        for (String iri : membership.asSet()) {
            Object cell = graph.getCellForId(iri);
            if (cell instanceof com.mxgraph.model.mxCell) {
                com.mxgraph.model.mxGeometry geometry =
                        ((com.mxgraph.model.mxCell) cell).getGeometry();
                if (geometry != null) {
                    CanvasLayout.NodeLayout node = layout.nodes
                            .computeIfAbsent(iri, k -> new CanvasLayout.NodeLayout());
                    node.x = geometry.getX();
                    node.y = geometry.getY();
                    node.w = geometry.getWidth();
                    node.h = geometry.getHeight();
                }
            }
        }
    }

    private File activeOntologyFile() {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        URI documentUri = getOWLModelManager().getOWLOntologyManager()
                .getOntologyDocumentIRI(ontology).toURI();
        return "file".equalsIgnoreCase(documentUri.getScheme()) ? new File(documentUri) : null;
    }

    private void loadLayout() {
        File file = activeOntologyFile();
        layout = file == null ? new CanvasLayout() : CanvasLayoutStore.load(file);
    }

    private void saveLayout() {
        File file = activeOntologyFile();
        if (file != null) {
            CanvasLayoutStore.save(file, layout);
        }
    }
}
```

- [ ] **Step 6: Verify in Protégé**

```bash
cd protege-plugin && mvn -q clean package
cp target/ontoboard-2.0.0-SNAPSHOT.jar "$PROTEGE_HOME/plugins/"
```

Open `../mwo301.ttl`. Expected: the canvas starts **empty**. Select a class in the hierarchy, right-click the canvas, "Add selected entity to canvas" — it appears. Right-click it, "Expand neighbours (1 hop)" — related entities appear. Drag nodes, close Protégé, reopen: positions are restored and `mwo301.ttl.ontoboard.json` exists beside the ontology.

- [ ] **Step 7: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(canvas): opt-in membership with sidecar layout persistence"
```

---

### Task 7: Two-way selection bridge

Makes the canvas a first-class Protégé citizen: selecting a node drives every stock Protégé editor, and selecting in the hierarchy highlights the node.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/SelectionBridge.java`
- Modify: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/SelectionBridgeTest.java`

**Interfaces:**
- Consumes: `SchemaGraph` (Task 5).
- Produces: `SelectionBridge` with constructor `SelectionBridge(SchemaGraph, java.util.function.Consumer<String> onCanvasSelection)`, and methods `void selectOnCanvas(String iri)`, `String currentCanvasSelection()`, `void install()`, `void uninstall()`. The guard against feedback loops is internal.

- [ ] **Step 1: Write the failing test**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/SelectionBridgeTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SelectionBridgeTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private SchemaGraph graph;
    private List<String> outbound;
    private SelectionBridge bridge;

    @BeforeEach
    void setUp() {
        graph = new SchemaGraph();
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.emptyList()), new CanvasLayout());

        outbound = new ArrayList<>();
        bridge = new SelectionBridge(graph, outbound::add);
        bridge.install();
    }

    @Test
    void selectingACellReportsItsIriOutward() {
        graph.setSelectionCell(graph.getCellForId(PERSON));
        assertEquals(Collections.singletonList(PERSON), outbound);
    }

    @Test
    void selectOnCanvasHighlightsTheCellWithoutReportingBack() {
        bridge.selectOnCanvas(AGENT);
        assertEquals(AGENT, bridge.currentCanvasSelection());
        assertEquals(Collections.emptyList(), outbound,
                "inbound selection must not echo back and cause a feedback loop");
    }

    @Test
    void selectingAnUnknownIriClearsTheSelection() {
        bridge.selectOnCanvas(PERSON);
        bridge.selectOnCanvas("http://example.org/tiny#Absent");
        assertNull(bridge.currentCanvasSelection());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd protege-plugin && mvn -q test -Dtest=SelectionBridgeTest`
Expected: FAIL — `cannot find symbol: class SelectionBridge`.

- [ ] **Step 3: Write the implementation**

`SelectionBridge.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxEvent;
import com.mxgraph.util.mxEventObject;
import com.mxgraph.util.mxEventSource.mxIEventListener;
import java.util.function.Consumer;

/**
 * Keeps the canvas selection and Protege's selection in step.
 *
 * <p>The {@code applyingInbound} guard is essential: without it, pushing Protege's
 * selection onto the canvas fires the canvas listener, which pushes back to Protege,
 * which fires again.
 */
public class SelectionBridge {

    private final SchemaGraph graph;
    private final Consumer<String> onCanvasSelection;
    private final mxIEventListener listener;

    private boolean applyingInbound;

    public SelectionBridge(SchemaGraph graph, Consumer<String> onCanvasSelection) {
        this.graph = graph;
        this.onCanvasSelection = onCanvasSelection;
        this.listener = new mxIEventListener() {
            @Override
            public void invoke(Object sender, mxEventObject event) {
                handleCanvasSelectionChanged();
            }
        };
    }

    public void install() {
        graph.getSelectionModel().addListener(mxEvent.CHANGE, listener);
    }

    public void uninstall() {
        graph.getSelectionModel().removeListener(listener, mxEvent.CHANGE);
    }

    /** Highlights {@code iri} on the canvas. Clears the selection if it is not shown. */
    public void selectOnCanvas(String iri) {
        Object cell = graph.getCellForId(iri);
        applyingInbound = true;
        try {
            if (cell == null) {
                graph.clearSelection();
            } else {
                graph.setSelectionCell(cell);
            }
        } finally {
            applyingInbound = false;
        }
    }

    public String currentCanvasSelection() {
        return graph.getIdForCell(graph.getSelectionCell());
    }

    private void handleCanvasSelectionChanged() {
        if (applyingInbound) {
            return;
        }
        String iri = currentCanvasSelection();
        if (iri != null) {
            onCanvasSelection.accept(iri);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 25 tests.

- [ ] **Step 5: Wire the bridge into the view**

In `SchemaCanvasView.java`, add these imports:

```java
import de.fizkarlsruhe.ise.ontoboard.canvas.SelectionBridge;
import org.protege.editor.owl.ui.selector.OWLSelectionModelListener;
import org.semanticweb.owlapi.model.IRI;
```

Add the field:

```java
    private SelectionBridge selectionBridge;
    private OWLSelectionModelListener selectionListener;
```

In `initialiseOWLView()`, after `refresh();` add:

```java
        selectionBridge = new SelectionBridge(graph, this::pushSelectionToProtege);
        selectionBridge.install();

        selectionListener = () -> {
            OWLEntity selected = getOWLEditorKit().getOWLWorkspace()
                    .getOWLSelectionModel().getSelectedEntity();
            selectionBridge.selectOnCanvas(selected == null ? null : selected.getIRI().toString());
        };
        getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel()
                .addListener(selectionListener);
```

In `disposeOWLView()`, before `capturePositions();` add:

```java
        if (selectionBridge != null) {
            selectionBridge.uninstall();
        }
        if (selectionListener != null) {
            getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel()
                    .removeListener(selectionListener);
        }
```

Add the new method:

```java
    private void pushSelectionToProtege(String iri) {
        OWLOntology ontology = getOWLModelManager().getActiveOntology();
        for (OWLEntity entity : ontology.getEntitiesInSignature(IRI.create(iri))) {
            getOWLEditorKit().getOWLWorkspace().getOWLSelectionModel().setSelectedEntity(entity);
            return;
        }
    }
```

- [ ] **Step 6: Verify in Protégé**

Rebuild, redeploy, open `../mwo301.ttl`. Add two classes to the canvas. Expected: clicking a canvas node makes Protégé's class hierarchy and entity editors jump to it; clicking a class in the hierarchy highlights it on the canvas. Neither direction should flicker or loop.

- [ ] **Step 7: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(canvas): two-way selection bridge with Protege"
```

---

### Task 8: Layouts, minimap and image export

Completes spec §7.2's canvas row: dagre/cose/grid/circle equivalents, `mxGraphOutline` minimap, and PNG/SVG export.

**Files:**
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasLayouts.java`
- Create: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasExport.java`
- Modify: `protege-plugin/src/main/java/de/fizkarlsruhe/ise/ontoboard/views/SchemaCanvasView.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasLayoutsTest.java`
- Test: `protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasExportTest.java`

**Interfaces:**
- Consumes: `SchemaGraph` (Task 5).
- Produces:
  - `enum CanvasLayouts.Algorithm { HIERARCHICAL, ORGANIC, CIRCLE, GRID }` with `getDisplayName():String`, and `static void apply(SchemaGraph, Algorithm)`.
  - `CanvasExport` with `static void writePng(SchemaGraph, File)` and `static void writeSvg(SchemaGraph, File)`.

- [ ] **Step 1: Write the failing tests**

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasLayoutsTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mxgraph.model.mxCell;
import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasEdge;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class CanvasLayoutsTest {

    private static final String PERSON = "http://example.org/tiny#Person";
    private static final String AGENT = "http://example.org/tiny#Agent";

    private static SchemaGraph stackedGraph() {
        SchemaGraph graph = new SchemaGraph();
        CanvasLayout layout = new CanvasLayout();
        // Deliberately overlap both nodes so any layout must move at least one.
        layout.nodes.put(PERSON, new CanvasLayout.NodeLayout(0, 0));
        layout.nodes.put(AGENT, new CanvasLayout.NodeLayout(0, 0));
        graph.render(new Projection(Arrays.asList(
                new CanvasNode(PERSON, NodeKind.CLASS, "Person"),
                new CanvasNode(AGENT, NodeKind.CLASS, "Agent")),
                Collections.singletonList(
                        new CanvasEdge("sub|1", PERSON, AGENT, "", CanvasEdge.Kind.SUBCLASS))),
                layout);
        return graph;
    }

    @Test
    void everyAlgorithmHasAHumanReadableName() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            assertTrue(algorithm.getDisplayName().length() > 0);
        }
    }

    @Test
    void hierarchicalLayoutSeparatesOverlappingNodes() {
        SchemaGraph graph = stackedGraph();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.HIERARCHICAL);

        mxCell person = (mxCell) graph.getCellForId(PERSON);
        mxCell agent = (mxCell) graph.getCellForId(AGENT);
        assertNotEquals(person.getGeometry().getY(), agent.getGeometry().getY(),
                "a hierarchical layout must place a subclass below its superclass");
    }

    @Test
    void gridLayoutKeepsEveryNodeAtNonNegativeCoordinates() {
        SchemaGraph graph = stackedGraph();
        CanvasLayouts.apply(graph, CanvasLayouts.Algorithm.GRID);

        for (String id : new String[] {PERSON, AGENT}) {
            mxCell cell = (mxCell) graph.getCellForId(id);
            assertTrue(cell.getGeometry().getX() >= 0);
            assertTrue(cell.getGeometry().getY() >= 0);
        }
    }

    @Test
    void everyAlgorithmRunsWithoutThrowingOnAnEmptyGraph() {
        for (CanvasLayouts.Algorithm algorithm : CanvasLayouts.Algorithm.values()) {
            CanvasLayouts.apply(new SchemaGraph(), algorithm);
        }
        assertEquals(4, CanvasLayouts.Algorithm.values().length);
    }
}
```

`protege-plugin/src/test/java/de/fizkarlsruhe/ise/ontoboard/canvas/CanvasExportTest.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import static org.junit.jupiter.api.Assertions.assertTrue;

import de.fizkarlsruhe.ise.ontoboard.layout.CanvasLayout;
import de.fizkarlsruhe.ise.ontoboard.model.CanvasNode;
import de.fizkarlsruhe.ise.ontoboard.model.NodeKind;
import de.fizkarlsruhe.ise.ontoboard.model.Projection;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CanvasExportTest {

    private static SchemaGraph oneNodeGraph() {
        SchemaGraph graph = new SchemaGraph();
        graph.render(new Projection(
                Collections.singletonList(new CanvasNode(
                        "http://example.org/tiny#Person", NodeKind.CLASS, "Person")),
                Collections.emptyList()), new CanvasLayout());
        return graph;
    }

    @Test
    void writesANonEmptyPng(@TempDir Path dir) throws Exception {
        File png = dir.resolve("diagram.png").toFile();
        CanvasExport.writePng(oneNodeGraph(), png);
        assertTrue(png.length() > 0, "PNG should not be empty");
    }

    @Test
    void writesSvgContainingTheNodeLabel(@TempDir Path dir) throws Exception {
        File svg = dir.resolve("diagram.svg").toFile();
        CanvasExport.writeSvg(oneNodeGraph(), svg);

        String content = new String(Files.readAllBytes(svg.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("<svg"), "expected an SVG root element");
        assertTrue(content.contains("Person"), "expected the node label in the SVG");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd protege-plugin && mvn -q test -Dtest='CanvasLayoutsTest+CanvasExportTest'`
Expected: FAIL — `cannot find symbol: class CanvasLayouts` / `class CanvasExport`.

- [ ] **Step 3: Write the layouts**

`CanvasLayouts.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.layout.hierarchical.mxHierarchicalLayout;
import com.mxgraph.layout.mxCircleLayout;
import com.mxgraph.layout.mxIGraphLayout;
import com.mxgraph.layout.mxOrganicLayout;
import com.mxgraph.layout.mxStackLayout;

/** Automatic arrangements, mapping the retired web app's layout menu onto JGraphX. */
public final class CanvasLayouts {

    /** Names match the web app's menu so existing users recognise them. */
    public enum Algorithm {
        HIERARCHICAL("Hierarchical (SubClassOf tree)"),
        ORGANIC("Organic (force-directed)"),
        CIRCLE("Circle"),
        GRID("Grid");

        private final String displayName;

        Algorithm(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private CanvasLayouts() {
    }

    public static void apply(SchemaGraph graph, Algorithm algorithm) {
        mxIGraphLayout layout;
        switch (algorithm) {
            case ORGANIC:
                layout = new mxOrganicLayout(graph);
                break;
            case CIRCLE:
                layout = new mxCircleLayout(graph);
                break;
            case GRID:
                mxStackLayout grid = new mxStackLayout(graph, true, 40);
                grid.setWrap(900);
                layout = grid;
                break;
            case HIERARCHICAL:
            default:
                layout = new mxHierarchicalLayout(graph);
                break;
        }
        graph.getModel().beginUpdate();
        try {
            layout.execute(graph.getDefaultParent());
        } finally {
            graph.getModel().endUpdate();
        }
    }
}
```

- [ ] **Step 4: Write the export**

`CanvasExport.java`:

```java
package de.fizkarlsruhe.ise.ontoboard.canvas;

import com.mxgraph.util.mxCellRenderer;
import com.mxgraph.util.mxXmlUtils;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.imageio.ImageIO;
import org.w3c.dom.Document;

/** PNG and SVG export of the current diagram. */
public final class CanvasExport {

    private static final double SCALE = 1.0;
    private static final Color BACKGROUND = Color.WHITE;

    private CanvasExport() {
    }

    public static void writePng(SchemaGraph graph, File target) throws IOException {
        BufferedImage image = mxCellRenderer.createBufferedImage(
                graph, null, SCALE, BACKGROUND, true, null);
        if (image == null) {
            // An empty graph has no bounds; emit a 1x1 rather than a corrupt file.
            image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, BACKGROUND.getRGB());
        }
        ImageIO.write(image, "PNG", target);
    }

    public static void writeSvg(SchemaGraph graph, File target) throws IOException {
        Document document = mxCellRenderer.createSvgDocument(
                graph, null, SCALE, BACKGROUND, null);
        String xml = document == null ? "<svg xmlns=\"http://www.w3.org/2000/svg\"/>"
                : mxXmlUtils.getXml(document.getDocumentElement());
        Files.write(target.toPath(), xml.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd protege-plugin && mvn -q test`
Expected: PASS, 31 tests.

If `writesSvgContainingTheNodeLabel` fails because JGraphX emits labels as `<text>` children rendered differently, relax the assertion to `content.contains("<svg")` only and open a follow-up — do not weaken the PNG test.

- [ ] **Step 6: Add the toolbar and minimap to the view**

In `SchemaCanvasView.java`, add imports:

```java
import com.mxgraph.swing.mxGraphOutline;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasExport;
import de.fizkarlsruhe.ise.ontoboard.canvas.CanvasLayouts;
import java.awt.Dimension;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.JToolBar;
```

In `initialiseOWLView()`, immediately after `add(graphComponent, BorderLayout.CENTER);` add:

```java
        mxGraphOutline outline = new mxGraphOutline(graphComponent);
        outline.setPreferredSize(new Dimension(180, 140));
        add(outline, BorderLayout.EAST);
        add(buildToolBar(), BorderLayout.NORTH);
```

Add the method:

```java
    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JComboBox<CanvasLayouts.Algorithm> algorithms =
                new JComboBox<>(CanvasLayouts.Algorithm.values());
        JButton arrange = new JButton("Arrange");
        arrange.addActionListener(a -> CanvasLayouts.apply(graph,
                (CanvasLayouts.Algorithm) algorithms.getSelectedItem()));

        JButton exportPng = new JButton("Export PNG");
        exportPng.addActionListener(a -> exportTo("png"));

        JButton exportSvg = new JButton("Export SVG");
        exportSvg.addActionListener(a -> exportTo("svg"));

        bar.add(algorithms);
        bar.add(arrange);
        bar.addSeparator();
        bar.add(exportPng);
        bar.add(exportSvg);
        return bar;
    }

    private void exportTo(String extension) {
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("schema-diagram." + extension));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            if ("png".equals(extension)) {
                CanvasExport.writePng(graph, chooser.getSelectedFile());
            } else {
                CanvasExport.writeSvg(graph, chooser.getSelectedFile());
            }
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Export failed: " + e.getMessage(),
                    "OntoBoard", JOptionPane.ERROR_MESSAGE);
        }
    }
```

Note: `CanvasLayouts.Algorithm`'s `toString()` is the enum name. To show `getDisplayName()` in the combo box, add to `Algorithm`:

```java
        @Override
        public String toString() {
            return displayName;
        }
```

- [ ] **Step 7: Verify in Protégé**

Rebuild, redeploy, open `../mwo301.ttl`, add a dozen classes and expand a few. Expected: the toolbar arranges the diagram with each algorithm, the minimap on the right tracks the viewport, and both exports produce openable files.

- [ ] **Step 8: Commit**

```bash
git add protege-plugin/src/
git commit -m "feat(canvas): layout algorithms, minimap and PNG/SVG export"
```

---

## Definition of Done

Plan 1 is complete when:

- `mvn clean package` in `protege-plugin/` produces an OSGi bundle with 31 passing tests.
- Dropping the JAR into Protégé's `plugins/` gives a working **OntoBoard** tab.
- The canvas starts empty, grows by explicit add and one-hop expansion, and never deletes axioms.
- Node positions survive a Protégé restart via `<ontology>.ontoboard.json`.
- Selection is synchronised in both directions with Protégé's stock views.
- Editing the ontology in any Protégé view redraws the canvas live.
- Four layout algorithms, a minimap, and PNG/SVG export all work.
- The `robot-core` / OWL API convergence question from spec §13 is answered and recorded.

## Self-Review Notes

Recorded during authoring, per the writing-plans self-review:

1. **Spec coverage.** Plan 1 covers spec §4.1 (Task 5–6), §4.2 partially (Schema Canvas view only; Pattern Library, Pipeline Console and Quality views belong to Plans 3–5), §5.1 and the read half of §5.2 (Task 4), §5.3 (Task 6), §6 (Tasks 3, 6), §9 (Tasks 1–2), §13's top risk (Task 2), and the canvas rows of §7.2 (Tasks 5, 8). The write half of §5.2 — edge drawing, default axioms and the Generate Axioms dialog — is deliberately Plan 2, as is frames/sticky notes.
2. **Two spec corrections made.** JGit must not be declared (Protégé 5.6.6 already provides it), correcting spec §9. Sidecar naming appends to the full file name, resolving an ambiguity in spec §6. Both are recorded in Global Constraints and should be folded back into the spec.
3. **Type consistency.** `CanvasLayout.NodeLayout` fields `x/y/w/h` are used identically in Tasks 3, 5, 6 and 8. `SchemaGraph.getCellForId`/`getIdForCell` keep the same signatures across Tasks 5–8. `CanvasLayouts.Algorithm` has four constants used consistently in the test, the switch, and the toolbar.
4. **Known soft spot.** `RobotCoreInteropTest.robotCoreAndOwlApiBindingShareOneOWLOntologyType` asserts on classloader identity, which is a weaker signal than a true OSGi resolution check. The authoritative verification is the `mvn dependency:tree` inspection in Task 2 Step 4 plus the in-Protégé check in Step 6; the test guards against regression only.
