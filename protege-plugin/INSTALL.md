# Installing / upgrading the OntoBoard plugin

## Requirements

- Protégé Desktop 5.5.0 or later. The bundle's `Import-Package` versions
  are declared as `[5.5.0, infinity)`, so it also loads on 5.6.x.
- `-Xmx500M` in `Protege.l4j.ini` (next to `Protege.exe`) is **too low** for the
  ROBOT operations this plugin ships today — reasoning, the quality report,
  transforms, term extraction. Raise it (e.g. `-Xmx2G` or higher) before using
  them; this is not future work. Leaving it at the
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

## Opening the tab

**Window → Tabs → OntoBoard.** The tab shows Protégé's entity views in a
tabbed column on the left — Classes, Object properties, Data properties,
Annotation properties, Datatypes, Individuals — with the Schema Canvas
beside them.

### If the layout looks wrong after upgrading

Protégé stores the arrangement of each tab per user and restores that in
preference to the one shipped in the jar, so a new release's layout would
otherwise never reach anyone who had already opened the tab. OntoBoard
detects this and adopts the new layout once, the first time you open the tab
after upgrading — no action needed, and any rearranging you do afterwards is
kept until the shipped layout genuinely changes again.

If it somehow does not take effect — a stacked layout instead of two columns,
or a missing panel — apply it by hand:

**Window → Reset selected tab to default state**

`~/.Protege/logs/protege.log` records the outcome; search for `OntoBoard`.
