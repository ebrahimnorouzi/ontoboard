package org.geneontology.obographs.owlapi;

import org.semanticweb.owlapi.formats.PrefixDocumentFormatImpl;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/**
 * The OboGraphs JSON document format, as a class Java 8 can load.
 *
 * <p>This is OntoBoard's, in obographs' package and under obographs' name on purpose. ROBOT asks
 * of every ontology it saves {@code format instanceof OboGraphJsonDocumentFormat}
 * ({@code IOHelper.saveOntologyFile}), whatever the format is. obographs 0.3.1, which ROBOT
 * 1.9.8 depends on, is compiled for Java 11, and Protege 5.5 runs on Java 8: there the question
 * alone failed with "has been compiled by a more recent version of the Java Runtime", so not one
 * ROBOT command line could write a file. With Docker that never mattered. Without it, OntoBoard
 * runs ODK's recipes as ROBOT command lines in Protege's own JVM
 * ({@code de.fizkarlsruhe.ise.ontoboard.odk.OdkRobot}), and a build that cannot save is no
 * build.
 *
 * <p>A bundle's own classes are looked in before the jars it carries ({@code Bundle-ClassPath}
 * starts with "."), so this one is found first, on every Java. It is the original in everything
 * ROBOT asks of it - the same parent, the same interface, the same key - and
 * {@code OdkRobotTest} holds it against the original, so that a later obographs cannot drift
 * away unseen. Nothing else of obographs is replaced: writing JSON is still its work, and still
 * needs Java 11, which {@code OdkRobot} says before it runs such a line on Java 8.
 *
 * <p>When obographs' own class is no longer newer than Java 8's - or Protege 5.5 is no longer
 * supported - this file goes; a test says so.
 */
@SuppressWarnings("serial")
public class OboGraphJsonDocumentFormat extends PrefixDocumentFormatImpl
        implements OWLDocumentFormat {

    @Override
    public String getKey() {
        return "OboGraphs JSON Syntax";
    }
}
