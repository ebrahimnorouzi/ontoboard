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
