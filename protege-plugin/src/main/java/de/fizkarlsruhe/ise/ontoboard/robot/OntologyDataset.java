package de.fizkarlsruhe.ise.ontoboard.robot;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;

/**
 * The open ontology as a Jena model, so SPARQL can be run over it without ROBOT's Rio path.
 *
 * <p>ROBOT's own route to this is {@code QueryOperation.loadOntologyAsModel}, which constructs an
 * OWL API {@code RioRenderer}. That constructor takes an {@code org.openrdf.rio.RDFHandler} on
 * Protege 5.5.0's OWL API 4.5.9 and an {@code org.eclipse.rdf4j.rio.RDFHandler} on 5.6.9's 4.5.29,
 * and {@code robot-core} is compiled against the newer one - so calling it fails with
 * {@code NoSuchMethodError} on 5.5.0. That is the barrier {@code docs/limitations.md} has always
 * described, and it is real, but it applies to this one call site.
 *
 * <p>Going through bytes instead sidesteps it entirely: the OWL API writes RDF/XML, which every
 * version can do, and Jena reads RDF/XML, which needs no OWL API at all. So this works on both
 * hosts, which is why the quality report can now work on 5.5.0 - something the documentation called
 * impossible.
 *
 * <p>In memory rather than through a temporary file. An ontology large enough to make that a
 * problem is one where the report itself would be the bottleneck, and a temporary file is a thing
 * to fail to clean up.
 *
 * <p>Pure OWL API and Jena; no Protege types and no Swing.
 */
public final class OntologyDataset {

    private OntologyDataset() {
    }

    /**
     * The ontology and its imports closure as a Jena model.
     *
     * <p>{@code Imports.INCLUDED}, because that is what ROBOT reports on and what a curator means
     * by "my ontology" - a rule about a term that comes from an import is still a rule about
     * something the release will contain.
     *
     * @throws RobotException if the ontology cannot be serialised or reparsed, which would mean
     *     the OWL API cannot write what it just read
     */
    public static Model modelOf(OWLOntology ontology) {
        if (ontology == null) {
            throw new IllegalArgumentException("no ontology to query");
        }
        byte[] rdfXml;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 20);
            // A fresh manager holding a flattened copy: saveOntology writes one ontology, and the
            // report has to see the imports closure as a single graph.
            OWLOntology flattened = flatten(ontology);
            flattened.getOWLOntologyManager()
                    .saveOntology(flattened, new RDFXMLDocumentFormat(), bytes);
            rdfXml = bytes.toByteArray();
        } catch (Exception cannotWrite) {
            throw new RobotException("Could not read this ontology as RDF for querying: "
                    + cannotWrite.getMessage(), cannotWrite);
        }

        try {
            Model model = ModelFactory.createDefaultModel();
            RDFDataMgr.read(model, new ByteArrayInputStream(rdfXml), Lang.RDFXML);
            return model;
        } catch (RuntimeException cannotParse) {
            throw new RobotException("The ontology was written as RDF but could not be read back "
                    + "for querying: " + cannotParse.getMessage(), cannotParse);
        }
    }

    /** The ontology with its imports closure merged in, in a manager of its own. */
    private static OWLOntology flatten(OWLOntology ontology) throws Exception {
        if (ontology.getImportsDeclarations().isEmpty()) {
            return ontology;
        }
        org.semanticweb.owlapi.model.OWLOntologyManager manager =
                org.semanticweb.owlapi.apibinding.OWLManager.createOWLOntologyManager();
        OWLOntology merged = manager.createOntology(
                ontology.getOntologyID().getOntologyIRI().isPresent()
                        ? ontology.getOntologyID().getOntologyIRI().get()
                        : org.semanticweb.owlapi.model.IRI.create(
                                "http://www.ontoboard.org/report-subject"));
        manager.addAxioms(merged, ontology.getAxioms(Imports.INCLUDED));
        return merged;
    }
}
