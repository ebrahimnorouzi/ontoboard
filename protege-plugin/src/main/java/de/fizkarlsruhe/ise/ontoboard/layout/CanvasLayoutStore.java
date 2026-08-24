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
