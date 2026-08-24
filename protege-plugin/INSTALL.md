# Installing / upgrading the OntoBoard plugin

## Requirements

- Protégé Desktop 5.5.0 or later. The bundle's `Import-Package` versions
  are declared as `[5.5.0, infinity)`, so it also loads on 5.6.x.
- `-Xmx500M` in `Protege.l4j.ini` (next to `Protege.exe`) is **too low** once
  ROBOT operations land in later releases of this plugin. Raise it (e.g.
  `-Xmx2G` or higher) before relying on those features; leaving it at the
  default risks `OutOfMemoryError` during larger ontology processing.

## Installing a new version

1. Locate the Protégé `plugins/` directory, e.g.:
   `C:\Users\<you>\Documents\Protege-5.5.0\plugins\`
2. **Delete any existing `ontoboard-*.jar` first.** The bundle declares
   `Bundle-SymbolicName: ontoboard;singleton:=true`, so OSGi allows only one
   resolved version at a time. Leaving an old jar next to a new one does not
   give you the newer version — it creates an unresolved singleton
   collision, and the plugin fails to load at all. (This is the same defect
   already present in this Protégé install for other plugins, e.g. CoModIDE
   1.1.1 + 1.1.2, cellfie ×2, swrltab ×2, shacl4protege ×2 — do not add
   OntoBoard to that list.)
3. Copy the new `ontoboard-<version>.jar` into `plugins/`.
4. Restart Protégé.

## Verifying the install

- Confirm exactly one `ontoboard-*.jar` exists in `plugins/`.
- In Protégé, check **Help → About Protégé → Plugins** (or the plugin
  manager) for a single OntoBoard entry at the expected version.
