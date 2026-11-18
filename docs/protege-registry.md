<!--
  Written for: the OntoBoard maintainer, getting the plugin listed in Protégé's
  File > Check for plugins.
-->

# Getting OntoBoard into Protégé's plugin registry

Not done yet. This is what it takes, read from Protégé's own code (`protege-editor-core`, package
`org.protege.editor.core.update`, tag `5.6.9`) and from
[protegeproject/autoupdate](https://github.com/protegeproject/autoupdate).

## How Protégé finds plugins

- Protégé 5.5.0 and 5.6.9 read the same registry,
  `https://raw.githubusercontent.com/protegeproject/autoupdate/master/update-info/5.0.0/plugins.repository`,
  so one entry covers both.
- The registry is a list of URLs, one per line; `//` starts a comment. Each URL points at a
  plugin's descriptor, an `update.properties` file.
- A plugin is offered when no bundle with its `id` is installed, or when the descriptor's `version`
  is greater than the installed one. An update needs a restart.
- A descriptor that cannot be parsed is skipped without a word: the plugin just does not appear.
- The `Update-Url` manifest header the Protégé wiki mentions is never read by Protégé 5.

## The descriptor

Protégé reads seven keys and nothing else:

| Key | Required | Note |
|---|---|---|
| `id` | yes | `ontoboard`, the bundle's `Bundle-SymbolicName` |
| `version` | yes | `MAJOR.MINOR.MICRO`, the jar's `Bundle-Version` |
| `download` | yes | the release's versioned jar, not `ontoboard.jar` |
| `name` | no | the label in the plugin table |
| `readme` | no | must be a valid URL, or the whole descriptor is skipped |
| `license` | no | a URL |
| `author` | no | free text |

There is no field for a Protégé version range; the bundle's `Import-Package` (5.5.0 or newer)
decides that.

The `update.properties` in the source tree cannot be served: it is a Maven template
(`version=${project.version}`). The hosted descriptor is a separate, literal file,
`protege-plugin/update-info/protege-5/update.properties`:

```properties
id=ontoboard
name=OntoBoard
version=<version>
download=https://github.com/ebrahimnorouzi/ontoboard/releases/download/v<version>/ontoboard-<version>.jar
readme=https://github.com/ebrahimnorouzi/ontoboard/releases/tag/v<version>
license=https://www.apache.org/licenses/LICENSE-2.0.txt
author=Ebrahim Norouzi, Information Service Engineering (ISE), FIZ Karlsruhe - Leibniz Institute for Information Infrastructure, Germany
```

The jar stays a GitHub release asset; never commit it to a repository.

## Submitting

1. Check that the `download` URL answers 200 (`curl -IL <url>`).
2. Commit the descriptor to `main`, and check that its raw URL serves the literal version.
3. Check that the jar's manifest matches the descriptor:

   ```bash
   d=protege-plugin/update-info/protege-5/update.properties
   id=$(sed -n 's/^id=//p' "$d" | tr -d '\r')
   version=$(sed -n 's/^version=//p' "$d" | tr -d '\r')
   download=$(sed -n 's/^download=//p' "$d" | tr -d '\r')
   curl --fail --location --silent --show-error "$download" --output /tmp/p.jar
   unzip -p /tmp/p.jar META-INF/MANIFEST.MF | tr -d '\r' > /tmp/m
   test "$(sed -n 's/^Bundle-SymbolicName: //p' /tmp/m | sed 's/;.*//' | head -1)" = "$id"
   test "$(sed -n 's/^Bundle-Version: //p' /tmp/m | head -1)" = "$version"
   ```

4. Open a pull request on `protegeproject/autoupdate` that adds two lines to
   `update-info/5.0.0/plugins.repository`, just before `// OntoGraf`:

   ```
   // OntoBoard
   https://raw.githubusercontent.com/ebrahimnorouzi/ontoboard/main/protege-plugin/update-info/protege-5/update.properties
   ```

   Nothing checks a change to the 5.0.0 registry automatically, and merges there are slow, so the
   GitHub release stays the main way to get OntoBoard.

From then on every release bumps the hosted descriptor as well; a test that compares it with the
pom's version would keep the two in step.
