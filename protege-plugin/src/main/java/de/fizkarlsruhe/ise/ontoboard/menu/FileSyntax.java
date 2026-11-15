package de.fizkarlsruhe.ise.ontoboard.menu;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.PrefixDocumentFormat;
import org.semanticweb.owlapi.io.StreamDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * The syntax an ontology file is written in, for what Protege has open from it.
 *
 * <p>ODK's update writes the edit file in functional syntax, whatever it was in before - a
 * project of OntoBoard's older layout has it in RDF/XML, and so has one made from an existing
 * ontology. Protege reads the file again after the update ({@code OWLModelManager.reload}
 * patches what is open to what the file holds) and goes on saving in the syntax it first read
 * the file in: the reload does not take the file's over (read in 5.5.0 and 5.6.9). The next
 * save would then put the file back into RDF/XML and the next update back into functional
 * syntax, each time as a change of every line. So what is open takes over the syntax of its
 * file once the update has rewritten it.
 *
 * <p>OWL API types only; Protege is the caller's.
 */
final class FileSyntax {

    private FileSyntax() {
    }

    /**
     * The format a file is written in, with the prefixes it declares; null when it is not
     * there or cannot be read as an ontology. Nothing it imports is fetched to find out.
     */
    static OWLDocumentFormat of(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            OWLOntologyManager reader = OWLManager.createOWLOntologyManager();
            final File nowhere = new File(file.getAbsoluteFile().getParentFile(),
                    ".ontoboard-no-such-import");
            reader.getIRIMappers().add(iri -> IRI.create(nowhere));
            reader.setOntologyLoaderConfiguration(reader.getOntologyLoaderConfiguration()
                    .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT));
            // From its bytes: the OWL API can keep a file it was given open.
            OWLOntology read = reader.loadOntologyFromOntologyDocument(new StreamDocumentSource(
                    new ByteArrayInputStream(Files.readAllBytes(file.toPath())),
                    IRI.create(new File(file.getAbsoluteFile().getParentFile(),
                            ".ontoboard-reading-" + file.getName()))));
            return reader.getOntologyFormat(read);
        } catch (Exception | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * The format an open ontology takes over from its file.
     *
     * @param open  the format Protege has for it; may be null
     * @param files the format its file is in now ({@link #of}); null when that is not known
     * @return the file's format, holding besides its own prefixes those only {@code open} has
     *         - a person may have added them in Protege; null when there is nothing to take
     *         over: the file's is not known, or it is the syntax Protege has already
     */
    static OWLDocumentFormat toTakeOver(OWLDocumentFormat open, OWLDocumentFormat files) {
        if (files == null) {
            return null;
        }
        if (open == null) {
            return files;
        }
        if (open.getClass().equals(files.getClass())) {
            return null;
        }
        if (open.isPrefixOWLOntologyFormat() && files.isPrefixOWLOntologyFormat()) {
            PrefixDocumentFormat into = files.asPrefixOWLOntologyFormat();
            Map<String, String> has = into.getPrefixName2PrefixMap();
            for (Map.Entry<String, String> prefix : open.asPrefixOWLOntologyFormat()
                    .getPrefixName2PrefixMap().entrySet()) {
                if (!has.containsKey(prefix.getKey())) {
                    into.setPrefix(prefix.getKey(), prefix.getValue());
                }
            }
        }
        return files;
    }
}
