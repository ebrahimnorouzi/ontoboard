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
 * OWL API {@code RioRenderer}. The documented reason that fails is a signature change - the
 * constructor takes an {@code org.openrdf.rio.RDFHandler} on Protege 5.5.0's OWL API 4.5.9 and an
 * {@code org.eclipse.rdf4j.rio.RDFHandler} on 5.6.9's 4.5.29, and {@code robot-core} is compiled
 * against the newer one.
 *
 * <p>That is true and it is not the binding constraint. <b>Neither {@code owlapi-osgidistribution}
 * exports any {@code org.openrdf.*} or {@code org.eclipse.rdf4j.*} package</b>: 4.5.9 keeps 16 such
 * jars and 4.5.29 keeps 17 on their own private {@code Bundle-ClassPath}, their
 * {@code Export-Package} headers list 75 and 82 packages with none of these among them, and this
 * bundle embeds no rdf4j jar. So an rdf4j type is invisible here on 5.6.9 exactly as on 5.5.0, and
 * the call dies with {@code NoClassDefFoundError} before any signature is compared.
 *
 * <p>Which makes this class the only route to a Jena model in this bundle, on either host, rather
 * than a workaround for the older OWL API. Going through bytes sidesteps the whole question: the
 * OWL API writes RDF/XML, which every version can do, and Jena reads RDF/XML, which needs no OWL
 * API at all.
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

        // Ontology annotations are not axioms, so addAxioms does not carry them. Without this the
        // merged copy loses the ontology's own dcterms:title, description and license - and
        // ROBOT's report then flags missing_ontology_title, missing_ontology_description and
        // missing_ontology_license as ERRORs on an ontology that declares all three. It fired on
        // every ontology with an import, which is every real ODK project, and it was invisible
        // here because this method returns early when there are none.
        //
        // The root ontology's annotations only. An import's title belongs to the import; copying
        // it would put two titles on one subject and trade three false errors for a
        // multiple-labels problem of our own making.
        for (org.semanticweb.owlapi.model.OWLAnnotation annotation : ontology.getAnnotations()) {
            manager.applyChange(new org.semanticweb.owlapi.model.AddOntologyAnnotation(
                    merged, annotation));
        }
        return merged;
    }
}
