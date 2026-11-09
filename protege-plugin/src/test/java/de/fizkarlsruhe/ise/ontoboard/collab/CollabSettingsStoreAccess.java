package de.fizkarlsruhe.ise.ontoboard.collab;

/** Reaches the package-private description from another test package. */
public final class CollabSettingsStoreAccess {

    private CollabSettingsStoreAccess() {
    }

    public static String tokenStorageDescription() {
        return CollabSettingsStore.tokenStorageDescription();
    }
}
