package de.fizkarlsruhe.ise.ontoboard.menu;

import de.fizkarlsruhe.ise.ontoboard.odk.MakeRun;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkContainer;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkImage;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRelease;
import de.fizkarlsruhe.ise.ontoboard.odk.OdkRoute;
import de.fizkarlsruhe.ise.ontoboard.odk.Toolchain;
import de.fizkarlsruhe.ise.ontoboard.proc.ProcessRunner;
import java.io.File;
import java.util.function.BooleanSupplier;

/**
 * Who runs ODK's import targets for a project on this machine: found out once - whether a native
 * ODK environment is set, which program runs containers, which image is here - and kept, so
 * that what a form said and what the run then does are the same. Refresh imports and Import
 * terms share it. Nothing is run or fetched to find out.
 */
final class OdkImportsRoute {

    private final OdkImportsForm.Way way;
    private final String runtime;
    private final String image;
    private final String imageNote;

    private OdkImportsRoute(OdkImportsForm.Way way, String runtime, String image,
            String imageNote) {
        this.way = way;
        this.runtime = runtime;
        this.image = image;
        this.imageNote = imageNote;
    }

    /**
     * Asks the machine who would run ODK's targets for this project.
     *
     * @param editFile the project's edit file, in its {@code src/ontology}
     */
    static OdkImportsRoute detect(File editFile) {
        ProcessRunner.Runner real = ProcessRunner.real();
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        String found = MakeRun.containerRuntime(real);
        // An image that is on this machine, or none: nothing is fetched to refresh imports.
        OdkImage.Choice choice = found == null ? null
                : OdkImage.forProject(ontologyDirectory, OdkImage.probe(found, real));
        boolean container = choice != null && choice.reference() != null;
        File environment = Toolchain.nativeEnvironment();
        String noContainer = container ? null
                : found == null ? OdkRoute.noContainerBecause(real)
                        : "ODK's image is not on this machine";
        OdkImportsForm.Way way = OdkImportsForm.way(environment == null ? null
                : Toolchain.activationScript(environment), OdkRoute.use(),
                container ? found : null, container ? choice.reference() : null, noContainer,
                container || choice == null ? null : choice.note());
        boolean inContainer = way.kind() == OdkImportsForm.Way.Kind.CONTAINER;
        return new OdkImportsRoute(way, inContainer ? found : null,
                inContainer ? choice.reference() : null, inContainer ? choice.note() : null);
    }

    /** Who runs the targets - or why nobody does. */
    OdkImportsForm.Way way() {
        return way;
    }

    /**
     * Why the image that runs them is not the ODK that wrote this project's Makefile, for the
     * log; null when it is, and when no container runs them.
     */
    String imageNote() {
        return imageNote;
    }

    /**
     * Who runs make in the project's {@code src/ontology}, as found. Not on the dispatch
     * thread: for a container, the machine is asked how it reaches the project's folder.
     *
     * @param stopped whether whoever waits has given up, for OntoBoard's own engine
     * @throws IllegalStateException when nobody runs the targets ({@link #way()} refuses)
     */
    OdkRelease.Odk odk(File editFile, BooleanSupplier stopped) {
        if (way.kind() == null) {
            throw new IllegalStateException(way.refusal());
        }
        File ontologyDirectory = editFile.getAbsoluteFile().getParentFile();
        ProcessRunner.Runner real = ProcessRunner.real();
        if (way.kind() == OdkImportsForm.Way.Kind.NATIVE) {
            return OdkRelease.inNativeEnvironment(
                    Toolchain.activationScript(Toolchain.nativeEnvironment()),
                    ontologyDirectory, real, null);
        }
        if (way.kind() == OdkImportsForm.Way.Kind.CONTAINER) {
            return OdkRelease.inContainer(runtime, image,
                    OdkContainer.Host.detect(runtime, real), ontologyDirectory, real, null);
        }
        return OdkRelease.byOntoBoardItself(editFile, stopped);
    }
}
