<!--
  Written for: the OntoBoard maintainer, getting the plugin listed in Protege.

  protege-plugin/src/main/resources/update.properties already exists and is filled in
  by Maven from the pom version, so the parts of this that describe writing it from
  scratch are already done.
-->

# Getting OntoBoard into Protégé's plugin registry

How `File > Check for plugins` actually works, what OntoBoard has to publish, and the
two files to install. Staging only — nothing here has been written into the repo.

## Which specification this is read from

There is no versioned, published "registry spec". The format is defined by the code
that parses it and by one wiki page. Everything below is read from these, at these
revisions:

| Source | Revision used | What it defines |
|---|---|---|
| `protege-editor-core/.../update/PluginInfoDocumentParser.java` | tag `5.6.9` (Protégé 5.6.9, released 2026-03-07) and `master` — identical | the descriptor's field names and which are mandatory |
| `.../update/PluginRegistryImpl.java` | same | the registry file syntax and the walk |
| `.../update/PluginManager.java` | tags `5.5.0`, `5.6.9` and `6.0.0-alpha-1` — all three identical | which registry URL Protégé reads by default |
| `.../update/PluginInstaller.java` | tag `5.6.9` | download, `.zip` handling, install path |
| `.../update/PluginPanel.java` | tag `5.6.9` | how `readme`, `license`, `author` are displayed |
| `protegeproject/autoupdate` @ `master` | head of 2026-09-30, "Merge pull request #36 … Add clean Protégé 6 plugin registry" | the live registry files, the validator, the submission target |
| Protégé wiki, `EnablePluginAutoUpdate` | last edited 23 May 2016 | the only prose documentation; partly stale, see "Update-Url is dead" |

URLs:

- <https://github.com/protegeproject/protege/tree/5.6.9/protege-editor-core/src/main/java/org/protege/editor/core/update>
- <https://github.com/protegeproject/autoupdate>
- <https://raw.githubusercontent.com/protegeproject/autoupdate/master/update-info/5.0.0/plugins.repository>
- <https://protegewiki.stanford.edu/wiki/EnablePluginAutoUpdate>

## The mechanism, end to end

1. `PluginManager.getPluginRegistryLocation()` reads preference
   `plugin.registry-5.0.url`, defaulting to

   ```
   https://raw.githubusercontent.com/protegeproject/autoupdate/master/update-info/5.0.0/plugins.repository
   ```

   That default string is byte-identical in `PluginManager.java` at tags `5.5.0`,
   `5.6.9` and `6.0.0-alpha-1`. **Both Protégé versions OntoBoard targets read the same
   registry file**, so one entry covers 5.5.0 and 5.6.9.

2. `File > Check for plugins` calls `runCheckForPlugins()`, which first does an HTTP
   `HEAD` on the registry URL, then `PluginRegistryImpl.reload()`.

3. `PluginRegistryImpl.Calculator` walks the registry. Per URL: if it ends in
   `.repository` it is read as a registry; otherwise it is parsed as a plugin
   descriptor, and **if parsing throws, it is retried as a registry**. A malformed
   descriptor therefore raises no error to the user — it silently contributes nothing.

4. Registry syntax, from `readRegistry()`: one URL per line; lines are trimmed; empty
   lines and lines starting with `//` are skipped; a line that is not a valid URL is
   logged at debug and skipped. Already-visited URLs are skipped, so cycles are safe.
   That is the whole grammar — there are no other directives.

5. For each descriptor, Protégé looks up an installed bundle with the same symbolic
   name. If one exists, the entry is offered **only if** the descriptor's version is
   strictly greater (`bundle.getVersion().compareTo(info.getAvailableVersion()) < 0`).
   If none exists, it is offered as a brand-new install.

6. `PluginInstaller` streams `download` to a temp file. If the URL path ends `.zip` it
   is extracted and the first `.jar` found is used; otherwise the file is used as is.
   It is then copied into Protégé's bundle directory as `<id>-<version>.jar` — for us
   `ontoboard-1.119.0.jar` — falling back to the per-user plugin directory if that is
   not writable, where it is named `<id>.jar` (`ontoboard.jar`, version dropped). An existing install of the same bundle is renamed `.old` first and
   deleted on success. A new plugin is `installBundle` + `start`ed live; an update needs
   a restart.

## Format of the descriptor (update.properties)

A Java `Properties` file. `PluginInfoDocumentParser` reads exactly seven keys and
ignores anything else:

| Key | Constant | Required | Notes |
|---|---|---|---|
| `id` | `ID_PROPERTY_NAME` | **yes** — parse fails without it | Must equal the bundle's `Bundle-SymbolicName`. Note *where* that bites: during the registry walk `Calculator` calls `new UpdateChecker(node, Optional.empty())`, so the parser's own id-vs-symbolic-name check never fires there. What matters is step 5 — `bundleByIds.get(info.getId())` is the lookup that decides "update" vs "brand-new install", so a wrong `id` makes Protégé offer a fresh install over an existing OntoBoard. The parser-level `PluginDocumentParseException` only fires on the per-plugin update path, where a bundle is passed in. |
| `version` | `VERSION_PROPERTY_NAME` | **yes** | Parsed by `org.osgi.framework.Version`: `MAJOR.MINOR.MICRO[.qualifier]`. An unparseable value fails the whole descriptor. |
| `download` | `DOWNLOAD_PROPERTY_NAME` | **yes** | Any `http(s)` URL. `.zip` is extracted; anything else is taken as the jar. |
| `name` | `LABEL_PROPERTY_NAME` | no | The label in the plugin table; falls back to `id`. Also the sort key, case-insensitive. |
| `readme` | `DOC_PROPERTY_NAME` | no | Fetched on a background thread and rendered in a `JEditorPane`. If the body contains `<html` it is forced to `text/html` regardless of the served content type. **Optional but strict**: if present it goes through `new URL(readmeStr)`, whose `MalformedURLException` is an `IOException` and so is caught and rethrown as `PluginDocumentParseException` — a malformed `readme` kills the whole descriptor exactly like a bad `version`. `license` and `author`, by contrast, are never validated at parse time. |
| `license` | `LICENSE_PROPERTY_NAME` | no | Shown as a `LinkLabel`; clicking does `new URL(label.getText())`, so **the value must itself be a URL** or the link silently does nothing. |
| `author` | `AUTHOR_PROPERTY_NAME` | no | Plain `JLabel`, free text. |

There is no other field. In particular there is **no field for an ORCID, an e-mail
address, a homepage, a description, a category, a dependency or a Protégé version
range**, so none is emitted — a TODO placeholder would be an unknown key, not a usable
one. `author` is the only place a person is named, and it is free text, which is why it
carries the name and affiliation and no address.

Hosting: anywhere reachable over HTTP(S) that is stable, because the URL is what gets
written into the central registry. Current practice in the live 5.0.0 registry is
`raw.githubusercontent.com/<owner>/<repo>/<branch>/update-info/protege-5/update.properties`
(dlquery, owlviz, owl-diff, cellfie, swrltab, sparql-query, shacl-plugin and others).

### Protégé version compatibility: there are no fields

Worth stating flatly, because it is the question the format has no answer to. From
`protegeproject/protege` issue #1390 (open), filed by the Protégé maintainer alongside
the commit that created the Protégé 6 registry:

> The current registry is for Protégé 5, and **the registry format does not carry
> Protégé compatibility metadata**. Protégé 6 must therefore use a separate registry
> rather than inheriting the existing catalogue.

Compatibility is expressed two ways, neither of them in `update.properties`:

1. **Which registry file lists you.** `update-info/5.0.0/plugins.repository` for
   Protégé 5.x; `update-info/6.0.0/plugins.repository` for Protégé 6.
2. **The OSGi manifest.** OntoBoard's `Import-Package` already carries
   `org.protege.editor.owl.*;version="5.5.0"` and
   `org.protege.editor.core.*;version="5.5.0"`, which is the range `[5.5.0, infinity)`.
   Felix refuses to resolve the bundle on an older host; that, not the registry, is
   what enforces "5.5.0 or newer".

So OntoBoard submits **one** entry, to the 5.0.0 registry, and it serves both 5.5.0 and
5.6.9. Do not also submit to 6.0.0: that registry currently lists three plugins
(HermiT 1.5.0, DL Query 5.0.0, OWLViz 6.0.0), its CI validator downloads every listed
jar and asserts bundle identity, and #1390 requires a completed Protégé 6 migration
first. Note also that Protégé `6.0.0-alpha-1` still defaults to the **5.0.0** registry —
the switch to `plugin.registry-6.0.url` exists only on the unmerged branch
`protege6/issue-1390-plugin-registry`.

### `Update-Url` in the manifest is dead

The wiki tells you to add an `Update-Url` manifest header pointing at your descriptor.
That was the Protégé 4 mechanism. In Protégé 5 and 6, `PluginRegistryImpl` declares
`public static final String UPDATE_URL = "Update-Url";` and **never reads it** — a code
search over `protegeproject/protege` returns that single declaration and no use.
Discovery is registry-walk only. Adding the header is harmless but buys nothing; not
having it (OntoBoard does not) costs nothing.

## Does OntoBoard already have one? Yes — and it cannot be used as it stands

`protege-plugin/src/main/resources/update.properties` exists, committed in
`2029820 feat(plugin): set version 1.0.0 with auto-update descriptor and identity headers`.
It has the right seven keys and the right `id`. There is no registry file in the repo
and no mention of `plugins.repository` anywhere outside the design plans.

Two problems, both verified rather than assumed:

**1. It is a Maven template, and raw-serving it ships the template.** The file holds
`version=${project.version}` and `${github.repo}`; `src/main/resources` is filtered
(`<filtering>true</filtering>`), so the resolved copy exists only inside the built jar
and in `target/classes`. The committed source is what `raw.githubusercontent.com`
serves, and it serves it today:

```
$ curl https://raw.githubusercontent.com/ebrahimnorouzi/ontoboard/main/protege-plugin/src/main/resources/update.properties
id=ontoboard
version=${project.version}
...
http=200
```

Submitting that URL would put `new Version("${project.version}")` into
`PluginInfoDocumentParser`, which throws `IllegalArgumentException`, wrapped as
`PluginDocumentParseException`; `PluginRegistryImpl` then retries the file as a
*registry*, finds no valid URLs, and OntoBoard simply never appears. No error reaches
the user. **The hosted descriptor must be a separate, literal, unfiltered file.**

**2. The in-jar copy is validated but never served.** `BundleConfigurationTest` already
asserts that the descriptor's `version` matches the pom, that `download` contains the
pom version and ends `.jar`, that `id` equals `ontoboard`, and that
`{id,name,version,download,license,author}` are present and non-empty. Those tests
guard the copy Protégé never sees. Extending them to the hosted file is the cheap fix —
see step 3 under "What to install".

## Is a GitHub release required, and does 66 MB fit?

Not required — `download` is any HTTP(S) URL — but it is the right choice, and the size
is a non-issue.

- **GitHub release asset limit: 2 GiB.** "Each file included in a release must be under
  2 GiB" (GitHub Docs, *About releases*; a release may hold up to 1000 assets, with no
  cap on total size or bandwidth).
- **Already proven for this exact pattern.** The `v1.118.0` release carries
  `ontoboard-1.118.0.jar` at 66,484,154 bytes, and
  `https://github.com/ebrahimnorouzi/ontoboard/releases/download/v1.118.0/ontoboard-1.118.0.jar`
  resolves `200` through GitHub's asset redirect. The local 1.119.0 build is
  66,520,129 bytes.
- **Do not commit the jar into a git repository instead.** GitHub warns above 50 MiB
  and "blocks files larger than 100 MiB" (GitHub Docs, *About large files on GitHub*).
  66 MB would be accepted-with-a-warning today and would break the moment the bundle
  grows. Some older registry entries do host jars inside `protegeproject/autoupdate`
  (snap-sparql-query); OntoBoard must not.
- The three Protégé 6 descriptors download from `repo1.maven.org` instead. That is an
  option if OntoBoard is ever deployed to Maven Central, but it changes nothing about
  the registry entry.
- **Use the versioned asset, not the stable `ontoboard.jar`.** The release workflow
  publishes both. `download` must point at `ontoboard-1.119.0.jar`: the descriptor
  declares `version=1.119.0`, and the Protégé 6 validator — and any honest reading of
  the format — requires the served bundle's `Bundle-Version` to equal it. Pointing at
  `/releases/latest/download/ontoboard.jar` would serve a different version than the
  descriptor advertises as soon as 1.120.0 ships.

## Submission process

A **pull request against `protegeproject/autoupdate`** (default branch `master`),
adding two lines to `update-info/5.0.0/plugins.repository`. That is how every entry
since 2016 has arrived: PRs #12, #15, #18, #19, #22, #23, #24, #25 and #27 are all
third-party plugin authors adding their own descriptor URL.

- **Merged by**: a Protégé maintainer. `matthewhorridge` merged most of them (#12, #18,
  #19, #22, #23, #27, #36); `rsgoncalves` merged #25; `johardi` merged #24. There is no
  CODEOWNERS and no CONTRIBUTING file; review is by hand.
- **Expect it to be slow.** The repository had no merged PR between March 2023 and
  September 2026, and PR #35 (ELK 0.6.0) is still open. Plan for the GitHub release to
  be the real distribution channel and the registry to be a bonus.
- The wiki's older route — "e-mail the protege-dev list with the location of your
  updates file" — still stands as a nudge if the PR stalls:
  <https://protege.stanford.edu/support.php>.
- **CI**: the validator workflow triggers only on `update-info/6.0.0/**`. A change to
  the 5.0.0 registry runs no checks at all, so the descriptor has to be right before it
  is submitted. Run the 6.0.0 validator's logic by hand regardless — step 4 below.

## What to install

### 1. The hosted descriptor

Commit the companion file `update.properties` from this directory to

```
protege-plugin/update-info/protege-5/update.properties
```

matching the path every other protegeproject plugin uses. It is literal — no `${...}` —
and written with CRLF to match the working copy. Contents:

```properties
id=ontoboard
name=OntoBoard
version=1.119.0
download=https://github.com/ebrahimnorouzi/ontoboard/releases/download/v1.119.0/ontoboard-1.119.0.jar
readme=https://github.com/ebrahimnorouzi/ontoboard/releases/tag/v1.119.0
license=https://www.apache.org/licenses/LICENSE-2.0.txt
author=Ebrahim Norouzi, Information Service Engineering (ISE), FIZ Karlsruhe - Leibniz Institute for Information Infrastructure, Germany
```

Three deliberate differences from the in-jar copy:

- `license` is the canonical Apache-2.0 text rather than `blob/main/LICENSE`. The panel
  uses the value verbatim as a URL, and this is what ELK does for the same licence.
  `blob/main/LICENSE` would also open, but it is a GitHub page, not a licence.
- `readme` is the release tag page, which is what all three Protégé 6 descriptors do,
  rather than `blob/main/README.md`.
- `author` carries the full affiliation. No e-mail address and no ORCID: the format has
  no field for either.

**Optional improvement to `readme`.** The panel renders the fetched body in Swing's
HTML 3.2 `JEditorPane`, so any real github.com page renders as a mess of chrome. The
older convention — cellfie, owl-diff, snap-sparql-query — is a tiny hand-written
`readme.html` served raw. If you add `protege-plugin/update-info/protege-5/readme.html`
(a few paragraphs, one list of features, a link to the docs site, no CSS), change the
one line to:

```properties
readme=https://raw.githubusercontent.com/ebrahimnorouzi/ontoboard/main/protege-plugin/update-info/protege-5/readme.html
```

Shipped as the release-tag URL because that file does not exist yet, and a `readme`
pointing at a 404 puts "Problem obtaining details: ..." in the panel.

### 2. The registry entry

Pull request to `protegeproject/autoupdate`, file
`update-info/5.0.0/plugins.repository`. Two lines, in the alphabetical block,
immediately **before** `// OntoGraf` — so after the `// Matrix` entry, since
"OntoBoard" sorts before "OntoGraf":

```
// OntoBoard
https://raw.githubusercontent.com/ebrahimnorouzi/ontoboard/main/protege-plugin/update-info/protege-5/update.properties
```

That is the entire entry — the registry has no fields. Suggested PR title: "Add
OntoBoard plugin". Body: one sentence on what it is, the Protégé versions it was
smoke-tested on (5.5.0 and 5.6.9, receipts in
`protege-plugin/tools/smoke-receipt/`), and the licence.

### 3. Order of operations

The descriptor's `download` must resolve before the PR is merged, or the first user to
click Install gets a 404.

1. Get a green `release` run for `v1.119.0`, so it publishes `ontoboard-1.119.0.jar`.
   **The tag is already pushed and the run already failed** — pushing it again is a no-op.
   `git ls-remote --tags origin` shows `refs/tags/v1.119.0` at `56ddb71` (dereferencing to
   `d9edf81`), the same object as the local tag, and run `38477943558` (`release`, tag
   `v1.119.0`, 2026-10-13T22:27Z) ended in **failure** at the Test step:

   ```
   [ERROR] SheetClipboardTest.theKeysAreBoundOnTheGridItself:203 » Headless
   No X11 DISPLAY variable was set, but this program performed an operation which requires it.
   [ERROR] Tests run: 2385, Failures: 0, Errors: 1, Skipped: 31
   ```

   So the blocker is a headless-unsafe test, not an unpushed tag. `SheetClipboardTest.java`
   is modified-but-uncommitted in the working tree, so whatever fix exists is local only.
   Commit the fix, then either re-run the failed workflow on the existing tag or move the
   tag — do not expect `git push --tags` to do anything.
2. Confirm `curl -IL <download URL>` returns `200`. It returns `404` today.
3. Commit `protege-plugin/update-info/protege-5/update.properties` to `main` and
   confirm the raw URL serves it with `1.119.0` literal.
4. Only then open the PR on `protegeproject/autoupdate`.

Because the hosted descriptor is pinned to a version while the pom moves on, **every
future release must also bump that file**, and nothing currently enforces it. Add it to
the release checklist, or better, extend `BundleConfigurationTest` with a test that
reads `update-info/protege-5/update.properties` from disk and asserts its `version` and
`download` match the pom — the same two assertions that already guard the in-jar copy.
A registry advertising 1.119.0 while the repo is on 1.130.0 is the exact failure the
existing test comments warn about.

### 4. Pre-submission check

The Protégé 6 validator's logic, run by hand against the 5.x descriptor:

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

Both assertions pass against the local 1.119.0 build: the manifest reads
`Bundle-SymbolicName: ontoboard;singleton:=true`, which reduces to `ontoboard`, and
`Bundle-Version: 1.119.0`. The `;singleton:=true` directive is stripped both by the
validator's `sed` and by OSGi's `Bundle.getSymbolicName()`, so `id=ontoboard` is correct
and no change to the pom is needed.

## Where the repo contradicts the stated ground truth

1. **1.119.0 is not released, and the release run is red.** The ground truth says
   "Current released version: 1.119.0, tagged v1.119.0, released 2026-10-14 (today)".
   `gh release list` shows the newest GitHub release as **`v1.118.0` (2026-10-12)**, and
   `gh release view v1.119.0` returns "release not found". The `download` URL in the file
   written here returns `404` right now. The tag is **not** merely unpushed: it is on
   `origin` (`refs/tags/v1.119.0` → `56ddb71` → `d9edf81`) and the `release` workflow ran
   for it on 2026-10-13 (run `38477943558`) and **failed** on
   `SheetClipboardTest.theKeysAreBoundOnTheGridItself:203 » Headless` ("No X11 DISPLAY
   variable was set"). It becomes correct only once that test is fixed and a `release` run
   for the tag succeeds; the registry PR must wait for that.
2. **An `update.properties` already exists**, contrary to the implication that one needs
   writing from scratch — but it is a filtered Maven template and unusable as a hosted
   descriptor, verified above. The file written here is a second, literal copy for a
   different purpose, not a replacement.
3. **The MIT claim is real.** `paper/ontoboard-iswc2026.tex:285` reads "OntoBoard is
   open-source under the MIT license". `LICENSE` at the repo root is the Apache License.
   Outside this task's scope, but confirmed.

Consistent with the ground truth, for the record: `artifactId` is `ontoboard`, pom
version `1.119.0`, `maven.compiler.release` 8, `robot.version` 1.9.8, `protege.version`
5.5.0, `Bundle-Vendor: ISE / FIZ Karlsruhe`, shaded jar 66,520,129 bytes.

## Things I was unsure about

- **Whether the maintainers will accept a 66 MB plugin.** Nothing in the format or the
  repository forbids it, and no reviewer has ever commented on size, but every other
  entry is one to two orders of magnitude smaller. If a reviewer objects, the answer is
  Maven Central — what the Protégé 6 descriptors use — not a smaller jar.
- **Whether a Protégé 6 entry will later need a different `id`.** Unknowable until the
  Protégé 6 migration is attempted; #1390 is still open and the Protégé 6 default
  registry is unmerged.
- **The autoupdate head commit SHA.** The table above dates the revision by its merge
  commit ("Add clean Protégé 6 plugin registry", 2026-09-30) rather than quoting a SHA.
- **PR turnaround.** Unknowable. #35 has been open for some time and the repository went
  three and a half years without a merge.
