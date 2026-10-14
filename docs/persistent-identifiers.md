<!--
  Written for: the OntoBoard maintainer, doing the release.

  Checked against the live Zenodo and w3id documentation. The .htaccess this describes
  is ready to submit at docs/w3id/ontoboard.htaccess - submitting it means opening a
  pull request against someone else's repository, which is yours to do, not a tool's.
-->

# Giving OntoBoard a DOI and a permanent URL

Procedure written 2026-10-14 against the specifications as they stood that day.
Nothing here is installed: every file this procedure needs is in this staging
directory, and the repository has not been touched.

| File in this directory | Installs as | Part |
| --- | --- | --- |
| `zenodo.json` | `.zenodo.json` at the **root** of `ebrahimnorouzi/ontoboard` | A |
| `ontoboard-w3id.htaccess` | `ids/ontoboard/.htaccess` in a fork of `perma-id/w3id.org` | B |
| `w3id-README.md` | `ids/ontoboard/README.md` in the same fork (optional) | B |
| `w3id-htaccess.txt` | nothing - it is the annotated version of the `.htaccess`, with two appendices | B |

## Specifications used

| Specification | Version / state | URL | Read |
| --- | --- | --- | --- |
| Zenodo help, "GitHub and Software" section | live site, served by **InvenioRDM 15.0** (`<meta name="generator">`); pages carry no version or date | `https://help.zenodo.org/docs/github/` and its children `enable-repository/`, `describe-software/`, `describe-software/zenodo-json/`, `describe-software/citation-file/`, `archive-software/github-upload/`, `archive-software/manual-upload/` | 2026-10-14 |
| Zenodo help, file limits | live | `https://help.zenodo.org/docs/deposit/manage-files/`, `.../manage-versions/` | 2026-10-14 |
| Zenodo DOI versioning | live | `https://zenodo.org/help/versioning` | 2026-10-14 |
| Zenodo Developers (REST API, **legacy** deposit metadata) | undated; its own JSON Schema link `https://zenodo.org/schemas/deposits/records/legacyrecord.json` now returns **404**, so the schema could not be used to validate anything | `https://developers.zenodo.org/` | 2026-10-14 |
| Zenodo live vocabularies (authoritative for identifier strings) | `licenses` and `resourcetypes`, revision 1, created 2023-10-12 | `https://zenodo.org/api/vocabularies/licenses?q=apache-2.0`, `https://zenodo.org/api/vocabularies/resourcetypes` | 2026-10-14 |
| `invenio-github` (the code behind the webhook) | `master` | `https://github.com/inveniosoftware/invenio-github` - `invenio_github/api.py`, `invenio_github/receivers.py` | 2026-10-14 |
| GitHub Docs, "Referencing and citing content" | live | `https://docs.github.com/en/repositories/archiving-a-github-repository/referencing-and-citing-content` | 2026-10-14 |
| w3id.org service | `perma-id/w3id.org` at `master`: `README.md`, `docs/overview/scope.md`, `docs/guides/create-an-id.md`, `docs/guides/content-negotiation.md`, `docs/rules/index.md`, `docs/rules/htaccess/anchor-patterns.md`, `docs/rules/htaccess/no-greedy-capture.md`. Documentation site: `https://w3id.org/docs/` | 2026-10-14 |

Two notes on provenance. Zenodo's help pages are undated and unversioned, so
"the current specification" means "what the site served on 2026-10-14"; where a
page did not answer a question, this document says so rather than filling the
gap from memory. And `help.zenodo.org/docs/profile/linking-repositories/`, a URL
that circulates in guides, is now a **404** - the live path is
`help.zenodo.org/docs/github/enable-repository/`.

---

# Part A - Zenodo

## A1. The three questions, answered first

**Does enabling the webhook archive past releases, or only releases created
after enabling?** **Only releases created after enabling.** No current Zenodo
page states this in so many words, so here is the evidence, strongest first:

1. The integration installs a GitHub webhook subscribed to exactly one event:
   `events=["release"]` (`invenio_github/api.py`, lines 328-334). It is purely
   push-based. No code path in `invenio-github` enumerates a repository's
   existing releases, so a release that was published before the switch was
   flipped never reaches Zenodo - nothing will ever tell Zenodo it happened.
2. Zenodo's own wording is forward-looking only: "Once connected, **new
   releases** from the repository will be automatically ingested and archived"
   (`help.zenodo.org/docs/github/enable-repository/`). GitHub's documentation
   matches: "Zenodo archives your repository and issues a new DOI **each time
   you create a new GitHub release**", and its remedy for wanting another DOI is
   to create another release.
3. An older Zenodo FAQ, quoted verbatim on the OSGeo wiki
   (`https://grasswiki.osgeo.org/wiki/GitHub-Zenodo_linkage`), said it outright:
   "Only the repositories which were enabled before a release was made will be
   archived automatically", with the remedy "you can always download a release
   ZIP from GitHub and upload it to Zenodo using our web interface as a regular
   upload." I could not find that text on the current help site, so treat it as
   corroboration, not as the specification.

Consequence for OntoBoard: **enable the repository first, then make a release.**
The 34 existing GitHub releases (v1.76.0 up to v1.118.0, not every tag in
between has one) will not be archived,
and that is fine - do not try to backfill 34 DOIs. If one historical release
matters, upload its source zip by hand as a separate record and link it with a
`isPreviousVersionOf` / `isNewVersionOf` relation.

**What does the maintainer click, in order?** Section A2.

**Concept DOI vs version DOI, and which goes in a paper?** Section A5.

## A2. The click path

Two routes; both end on the same page.

*If the Zenodo account does not exist yet* (GitHub Docs, verbatim steps):

1. Go to `https://zenodo.org/login`.
2. Click **Log in with GitHub**.
3. Review the access permissions, then click **Authorize zenodo**.
4. Go to `https://zenodo.org/account/settings/github/`.
5. "To the right of the name of the repository you want to archive, toggle the
   button to **On**."

*If a Zenodo account already exists* (Zenodo help, `enable-repository`):

1. Link the GitHub account first - profile menu, **Linked accounts**, connect
   GitHub (the page links this out as a prerequisite).
2. Open the **profile menu** in the header and select **GitHub**.
3. Click **Sync now** in the header. (Needed whenever a repository is missing
   from the list - the list is a cached copy of what GitHub told Zenodo.)
4. Find `ebrahimnorouzi/ontoboard` and **toggle its slider on**.
5. The page confirms the repository is connected.
6. **Refresh** the page so the enabled-repositories list updates.

Then, and only then:

7. On GitHub, publish a **Release** - not just a tag. See A3.
8. Wait. "This could take some time, depending on the size of the release file
   and the load of Zenodo at that time"
   (`help.zenodo.org/docs/github/archive-software/github-upload/`). The zipball
   fetch has a 300-second default timeout (`GITHUB_ZIPBALL_TIMEOUT`,
   `invenio_github/api.py`); OntoBoard's source zip is 3.4 MB, so this is not a
   concern.
9. Back on `zenodo.org/account/settings/github/` the repository now shows a DOI
   badge. Click the record's DOI to open it, and check **External resources >
   Archived in** for the Software Heritage archival status.

## A3. A tag is not a release - and this is the one thing that will bite

The webhook fires on GitHub's `release` event. `invenio-github`'s receiver acts
when `action` is one of `published`, `released`, `created` **and** the release
is not a draft (`invenio_github/receivers.py`, lines 54-67).

As of today the repository has **46 tags but 34 releases**, and
`https://github.com/ebrahimnorouzi/ontoboard/releases/latest` resolves to
**v1.118.0**. `v1.119.0` is a pushed tag with **no Release object**
(`GET /repos/ebrahimnorouzi/ontoboard/releases/tags/v1.119.0` returns 404). So:

- the task brief's "Current released version: 1.119.0 ... released 2026-10-14"
  is true of the **tag**, not of a GitHub Release;
- if nothing else changes, **Zenodo will never see 1.119.0**;
- the first DOI will be minted for whatever the next published Release is.

Also from the same code, two behaviours worth knowing: **draft releases are
skipped**, and **pre-releases are not** - there is no pre-release check
anywhere in the receiver, so marking a release "pre-release" still mints a DOI.
If OntoBoard ever publishes release candidates, every one of them gets its own
version DOI.

## A4. What ends up in the record, and the 66 MB jar

The integration uploads **one file**: the release's `zipball_url`, which is
GitHub's auto-generated **source archive of the tag**, stored under the name
`{owner}/{repo}-{tag}.zip` (`release_file_name` and `release_zipball_url`,
`invenio_github/api.py`, lines 535-545).

**Release assets are never fetched.** There is no code in `invenio-github` that
looks at `assets`. So `ontoboard-1.119.0.jar` (66,484,154 bytes in the 1.118.0
release) will not be in the Zenodo record unless a human puts it there.

Confirmed against a live record created this way -
`10.5281/zenodo.17778667`, `IDA-FBK/FuS-KG`: exactly one file,
`IDA-FBK/FuS-KG-v1.0.0-stable.zip`; `version` equal to the tag name; and an
automatically added related identifier, `isSupplementTo` ->
`https://github.com/IDA-FBK/FuS-KG/tree/v1.0.0-stable`.

Measured for OntoBoard: the v1.119.0 source zipball is **3,527,717 bytes
(3.4 MB)**.

**Is 66 MB a problem for Zenodo?** No, on two counts.

- It is not uploaded at all, so the size is moot for the webhook path.
- Even if it were: "You can upload up to a 100 files and a total volume of 50GB
  (50,000,000,000 bytes)" per record
  (`help.zenodo.org/docs/deposit/manage-files/`), with up to 200 GB available on
  request via the storage-quota page. A 66 MB jar is 0.13% of the default quota.
  The page states no separate per-file limit.

**So how does the citable archive reach a runnable jar?** The recommendation is
to leave it where it is and say so in the metadata, which is what
`zenodo.json` in this directory does: the `notes` field states that the archive
is the source tree and that the installable jar is a GitHub release asset, and
`description` repeats it. A Zenodo record whose files are the source at a tag is
exactly what a reproducibility reviewer wants; a 66 MB binary blob in the same
record adds nothing they can verify.

If you decide you want the jar in the record anyway, understand the cost before
you start: a published record's files are immutable, and a new version is "a
completely new record with separate metadata, files and persistent identifier"
(`help.zenodo.org/docs/deposit/manage-versions/`). Adding the jar after the fact
therefore means **two DOIs for one software release**. The alternative people use
- a GitHub Action that deposits to Zenodo with assets attached - requires
removing the webhook first, or you get two records per release. I did not verify
either path, and neither is recommended here.

## A5. Concept DOI vs version DOI

From `https://zenodo.org/help/versioning`:

- At first publication Zenodo registers **two** DOIs: one for that specific
  version, and one for all versions - the **Concept DOI**. Every later version
  gets a new version DOI; the Concept DOI never changes.
- "The Concept DOI resolves to the landing page of the latest version of your
  record."
- Zenodo's own citation advice: "You should normally always use the DOI for the
  specific version of your record in citations", so that others reach the exact
  artefact; the Concept DOI is for "when it is desirable to cite an evolving
  research artifact, without being specific about the version".
- DOIs deliberately carry no `.v1` suffix, because version information in an
  identifier does not stay true.
- A metadata-only edit creates neither a new version nor a new DOI.

Numerically, on the FuS-KG record checked above: concept `...17778666`,
version `...17778667` - the concept DOI is simply a different record id, not a
suffix of the version DOI, so **do not try to construct one from the other**.
Read both off the record page: the right-hand "Versions" panel shows the version
DOI, and "Cite all versions?" reveals the Concept DOI.

**Which goes in the paper?** Both, in different sentences. For OntoBoard, whose
version number moves weekly, a paper that cites only the concept DOI sends the
reader to a release the reviewers never saw, and a paper that cites only one
version DOI looks abandoned the week after. Write the Resource Availability
Statement like this (also fixing the licence, see Part C):

> **Resource Availability.** OntoBoard is open source under the Apache License
> 2.0. Source: `https://github.com/ebrahimnorouzi/ontoboard`. Documentation:
> `https://w3id.org/ontoboard/`. Archived releases:
> `https://doi.org/10.5281/zenodo.CONCEPT_ID` (all versions); the version
> described in this paper is 1.119.0,
> `https://doi.org/10.5281/zenodo.VERSION_ID`.

For the one-line `DOI:` field of a submission form, use the **concept DOI**.

## A6. `.zenodo.json`

**Where.** "Add a `.zenodo.json` file at the root of your GitHub repository"
(developers.zenodo.org; the help page's steps are: open the repository, add a
new file, name it `.zenodo.json`, commit, then archive the release). Repository
**root** - not `protege-plugin/`, where the Maven project lives.

**When.** It is read from the commit the release tag points at. Commit it
*before* tagging, or the first record will be built from GitHub's bare metadata.

**Precedence.** "If your repository contains both a `.zenodo.json` and a
`CITATION.cff` file, Zenodo will only use the `.zenodo.json` metadata", and the
`CITATION.cff` "will be completely ignored for the GitHub release archiving".
This matters here: a sibling task in this same staging directory produced a
`CITATION.cff`. Install both if you like - GitHub's "Cite this repository"
widget reads the CFF, Zenodo reads the JSON - but they will drift, so change
them in the same commit, every time.

**Fields Zenodo documents for this file.** The help page's example uses, in
order: `creators` (`name`, `orcid`, `affiliation`, `type`), `contributors`
(`name`, `affiliation`, `type`), `title`, `version`, `access_right`,
`related_identifiers` (`identifier`, `relation`, `resource_type`), `keywords`,
`license`, `upload_type`, `language`, `grants` (`id`), `communities`
(`identifier`). The page names `grants`, `communities`, `access_right`,
`related_identifiers` and contributor roles as the Zenodo-specific fields that
`CITATION.cff` cannot express. developers.zenodo.org adds that the file's
"contents are based on our deposit metadata documentation", which is where
`description`, `notes`, `publication_date`, `doi` and the rest come from, and
that Zenodo "automatically extract[s] metadata about your release from GitHub
APIs" for anything the file does not set.

Verified values, not guessed:

| Field | Value used | Checked against |
| --- | --- | --- |
| `upload_type` | `software` | enum on developers.zenodo.org: `publication, poster, presentation, dataset, image, video, software, lesson, physicalobject, other` |
| `access_right` | `open` | enum: `open, embargoed, restricted, closed` (defaults to `open`) |
| `license` | `apache-2.0` | `GET /api/vocabularies/licenses?q=apache-2.0` -> `{"id": "apache-2.0", "title": "Apache License 2.0", "props": {"scheme": "spdx", "osi_approved": "y"}, "tags": ["recommended","all","software"]}`. Note the id is lower-case, like the `"mit"` in Zenodo's own example - **not** the SPDX-cased `Apache-2.0` |
| `language` | `eng` | "ISO 639-2 or 639-3 code" |
| `related_identifiers[].relation` | `isDocumentedBy` | relation enum on developers.zenodo.org (33 values, `isDocumentedBy` among them) |
| `related_identifiers[].resource_type` | `publication-softwaredocumentation` | `GET /api/vocabularies/resourcetypes` - 43 ids, including `software`, `publication-softwaredocumentation`, `publication-conferencepaper` |
| `creators[].name` | `Norouzi, Ebrahim` | "Name of creator in the format Family name, Given names" |

**What I deliberately left out of the file, and why:**

- **`version`.** Zenodo fills it from the release tag when the file does not set
  it - on the FuS-KG record, `version` is literally `v1.0.0-stable`, the tag
  name. A hard-coded `"version": "1.119.0"` in a repository that has cut 46 tags
  would be wrong by the following week unless someone bumps it in the release
  commit. (This inference is from the live record and from the integration's
  behaviour, not from a documented sentence - see Part D.) If you prefer it
  explicit, add `"version": "1.119.0"` and put the file in the same bump list as
  `protege-plugin/pom.xml`.
- **`publication_date`.** "Defaults to current date." Pinning it would date
  every future release to today.
- **`grants`** - no funder/award id was supplied, and `grants` entries are
  resolved against OpenAIRE, so an invented one fails.
- **`communities`** - joining a community is a curator's decision; pick one on
  the site after the first record exists.
- **`contributors`** - single author. (The integration pulls the repository's
  GitHub contributor list by itself; setting the field would override that.)
- **A second `related_identifiers` entry for `https://w3id.org/ontoboard`** -
  that identifier returns 404 today (checked). Add it, as
  `isAlternateIdentifier`, after the w3id pull request is merged. Likewise add
  the paper once it has a DOI, as
  `{"identifier": "<paper DOI>", "relation": "isSupplementTo", "resource_type": "publication-conferencepaper"}`.
- **`doi`** - leave it empty: "leave the field empty and we will register a new
  DOI for you when you publish."

**The ORCID. Read this before committing the file.**

`zenodo.json` contains:

```json
"orcid": "XXXX-XXXX-XXXX-XXXX"
```

That is a deliberate, loud placeholder, and it is the one thing in this tier you
must resolve by hand:

> `XXXX-XXXX-XXXX-XXXX`  # TODO Ebrahim: your ORCID, as `0000-0002-1825-0097`

I did not write `0000-0000-0000-0000`. An all-zeros string *looks* like a
placeholder but is accepted by pattern-based validators, which means it would
sail through CI and be published as a **false author identifier**, propagated to
DataCite and to everything that harvests it. (It also fails ORCID's mod-11-2
checksum - the check digit for fifteen zeros is `1`, not `0` - so Zenodo's own
identifier validation would probably reject the deposit anyway, which is the
worse of two failures to discover during a release.) `XXXX-...` cannot be
mistaken for an identifier by a human or a machine. Before committing, either
replace it with the real ORCID or **delete the whole `"orcid"` line** (and the
trailing comma on the line above it - the file must stay valid JSON). If you
leave it, expect the release ingest to fail; that is the intended behaviour.
The sibling `CITATION.cff` in this directory takes exactly the same position,
for the same reason.

**No email.** Zenodo's deposit metadata has no creator-email field, so there is
nothing to fill in and nothing to leak. See Part C for the two different
institutional addresses the repository already publishes.

**Install checks.** The file is UTF-8, no BOM, LF, final newline, and parses
(`json.loads`, 1,998 bytes). Keep it that way: a BOM breaks strict JSON parsers,
and `protege-plugin/pom.xml` in this repository *does* start with one, so do not
copy that habit. The working copy is CRLF (`core.autocrlf=true`); git will
normalise on commit, but if you edit the file with a script, preserve whatever
line ending git checked out.

## A7. The DOI badge, and exactly where it goes in README.md

Zenodo offers the markdown on `https://zenodo.org/account/settings/github/`:
click the **DOI badge** beside the enabled repository and copy the **Markdown**
line. That page is behind a login, so I could not read the modal; what I could
do is verify the URL shapes it hands out, against the live service:

| URL | Result (checked 2026-10-14) |
| --- | --- |
| `https://zenodo.org/badge/<github-repo-id>.svg` | 302 -> `https://zenodo.org/badge/DOI/10.5281/zenodo.<LATEST VERSION DOI>.svg` |
| `https://zenodo.org/badge/latestdoi/<github-repo-id>` | 302 -> `https://doi.org/10.5281/zenodo.<LATEST VERSION DOI>` |
| `https://zenodo.org/badge/DOI/<any DOI>.svg` | 200, `image/svg+xml` - works for the concept DOI too |

Note what that first row means: the repository-id badge tracks the **latest
version DOI**, not the concept DOI, contrary to a lot of community advice. It
does keep itself up to date, which is why many projects use it. OntoBoard's
repository id is `1207672429`; `https://zenodo.org/badge/1207672429.svg` is 404
today, as expected for a repository that has not been enabled.

Pick one of the two, knowingly:

```markdown
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.CONCEPT_ID.svg)](https://doi.org/10.5281/zenodo.CONCEPT_ID)
```

- the stable, citable identifier; always the right thing for a README that a
  paper points at; needs no editing ever again, and
  `https://doi.org/10.5281/zenodo.CONCEPT_ID` still lands on the newest release.

```markdown
[![DOI](https://zenodo.org/badge/1207672429.svg)](https://zenodo.org/badge/latestdoi/1207672429)
```

- always shows the newest release's version DOI; what Zenodo's own modal hands
  you; one click further from a citable concept DOI.

**Recommended: the concept-DOI form.** And the exact placement: `README.md`
**line 9** currently reads

```markdown
[![Download](https://img.shields.io/badge/download-latest%20release-blue)](https://github.com/ebrahimnorouzi/ontoboard/releases/latest)
```

Replace that one line with the two badges on **one** line, DOI first, separated
by a single space (a newline between them would render as a space anyway in a
GitHub README, but one line makes the intent unambiguous):

```markdown
[![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.CONCEPT_ID.svg)](https://doi.org/10.5281/zenodo.CONCEPT_ID) [![Download](https://img.shields.io/badge/download-latest%20release-blue)](https://github.com/ebrahimnorouzi/ontoboard/releases/latest)
```

Line 9 is the right home: it sits after the one-line description and the "Why"
teaser at lines 3-7 and before the `---` at line 11, so the badge is in the
block a reader already scans, above the fold, and nothing in the document has to
move. Keep the file CRLF.

While you are there, the same DOI belongs in three other places: the
`identifier`/`doi` field of `CITATION.cff`, `codemeta.json` (`identifier`), and
the paper's availability statement.

## A8. The whole Zenodo procedure, in order

1. Fix the licence sentence in the two `.tex` files (Part C) - the archive will
   carry `apache-2.0` and the paper must not contradict it.
2. Copy `zenodo.json` from this directory to the repository root as
   `.zenodo.json`. Fill in or delete the ORCID line. Commit.
3. Enable `ebrahimnorouzi/ontoboard` on Zenodo (A2, steps 1-6). **Before** the
   next release, not after.
4. Publish a GitHub **Release** - for `v1.119.0`, whose tag is already pushed,
   or as part of cutting the next version. A tag alone does nothing (A3).
5. Open the new record and check: title, creator and affiliation, licence
   "Apache License 2.0", one `.zip` file, `isSupplementTo` pointing at the tag,
   and that the version is the tag name.
6. Read the **concept DOI** off "Cite all versions?" and the **version DOI** off
   the Versions panel. Record both.
7. Put the concept-DOI badge in `README.md` line 9 (A7), and the DOI in
   `CITATION.cff`, `codemeta.json` and the paper.
8. Check Software Heritage archival on the record ("External resources >
   Archived in"), which the `archive-software/github-upload` page says to
   expect shortly after publication.

---

# Part B - w3id.org

## B1. The exact procedure

From the repository README and `docs/guides/create-an-id.md`, 2026-10-14:

1. **Fork** `https://github.com/perma-id/w3id.org`.
2. **Create one directory** under `ids/`: `ids/ontoboard/`. "The directory name
   is the identifier: `ids/my-project/` becomes `https://w3id.org/my-project/`."
   Nothing else in the repository changes.
3. **Add `ids/ontoboard/.htaccess`** - the file from this directory,
   `ontoboard-w3id.htaccess`. It must be named exactly `.htaccess` (rule
   `files/only-allowed-names`). In the GitHub web editor you cannot type a
   leading dot into an empty filename box; the documented workaround is **Add
   file -> Create new file** and type the whole path,
   `ids/ontoboard/.htaccess`. Do not create `htaccess.txt` and rename it later -
   the guide calls that out as one of the most common follow-up commits.
4. **Optionally add `ids/ontoboard/README.md`** - `w3id-README.md` from this
   directory. Optional, but `meta/document-identifier-root` (warning) wants the
   root of an identifier claimed with a maintainer record, and the contact block
   is written as a bulleted list on purpose (`markdown/prefer-list-over-line-breaks`:
   the repository strips trailing whitespace, so two-space line breaks silently
   stop working).
5. **Test** before opening anything:
   `(cd tools/check && npm ci)` once, then
   `node tools/check/bin/w3id-check.js ids/ontoboard`. Only `error` findings
   block a pull request; `warning` is what a reviewer will ask about. For a real
   check, run the rules against the local Apache in `tools/server/` - "nothing
   can tell whether your redirect points where you meant it to".
6. **Open the pull request** - one identifier, one squashed commit, a message
   that names the project, nothing outside your directory.

**What the `.htaccess` must contain.** Minimally: a comment header naming the
path and the target, a contact block **including a GitHub username**,
`RewriteEngine on`, and at least one `RewriteRule` with `[R=302,L]`. An empty
`.htaccess` resolves to 404 (`files/no-empty-htaccess`), and a syntax error
"returns a 500 for every URL in that directory". The file must be LF, no BOM,
final newline, no trailing whitespace. The full annotated version, with every
rule it satisfies, is in `w3id-htaccess.txt`.

**What the pull request must say.** Ready to paste:

- Commit message (one commit; `git/descriptive-commit-message` exists because
  "Update .htaccess" is by a wide margin the most common message in that
  repository):

  ```
  ontoboard: add redirect for the OntoBoard project namespace
  ```

- Pull request body:

  > Adds `ids/ontoboard/`, a namespace for OntoBoard - a Protege Desktop plugin
  > for ODK and ROBOT ontology engineering, developed at Information Service
  > Engineering, FIZ Karlsruhe.
  >
  > `https://w3id.org/ontoboard/` 302s to the documentation site at
  > `https://ebrahimnorouzi.github.io/ontoboard/`, path preserved, with
  > `/repo`, `/issues` and `/download` for the source repository, the issue
  > tracker and the latest release. All targets are live over HTTPS today.
  >
  > The identifier is wanted because it is written into published artefacts -
  > the project's documentation, its release metadata and a paper under
  > submission - and the project's current hosting (GitHub Pages under a
  > personal account) is exactly the kind of location that will move.
  >
  > No content files are committed; this is redirect rules plus a maintainer
  > record. Maintainer contact, including GitHub username, is in both
  > `.htaccess` and `README.md`. `node tools/check/bin/w3id-check.js
  > ids/ontoboard` is clean, and the rules were tested against a local Apache.
  > One squashed commit; nothing outside `ids/ontoboard/` is touched.

**Who reviews it.** The documentation does not name reviewers. What it does say:
the service is run by the **W3C Permanent Identifier Community Group**, and it is
operated by a consortium that has pledged to keep it running - **Digital Bazaar,
3 Round Stones, OpenLink Software, Applied Testing and Technology, Bosatsu
Consulting, KurrawongAI**. One of those maintainers reviews and merges, and
"the change is deployed to the service ... normally live shortly afterwards".
Before that, **automated checks** run on every pull request and comment inline
on the changed lines under *Files changed*, with the full report under *Checks*
and each finding linking to the rule it came from. Discussion happens on
`public-perma-id@w3.org`; per-identifier problems go to the issue tracker.

**After the merge:** `curl -sIL https://w3id.org/ontoboard/`. "If it does not
work, open an issue - do not open a second pull request changing the same thing
until you know what went wrong." Then add the `/doi` rule (Appendix B of
`w3id-htaccess.txt`) once the Zenodo concept DOI exists.

**The non-GitHub route.** Mail `public-perma-id@w3.org` with the w3id URL you
want, the URL it should redirect to, the status code, and your contact details
including a GitHub username. "An administrator will create the redirect. This is
slower than a pull request, but it is a perfectly legitimate route."

## B2. The rules about the identifier name

Quoting `docs/overview/scope.md` ("Naming") and `create-an-id.md` step 1:

- "There is no formal naming policy, and no published list of reserved names."
- Claim **one top-level directory** and mint second-level identifiers under it:
  `https://w3id.org/PROJECT-ID/SUB-ID`. Do not claim several, and "do not claim
  a top-level directory when a path under an existing namespace would do".
- Lowercase, digits, hyphens. `ids/Foo/` and `ids/foo/` cannot coexist
  (`tree/no-case-collision`) - check case variants when you check the name is
  free.
- Requests are declined for names that are **too generic** ("a single common
  English word, or a term the whole community would plausibly need"),
  **confusing** (close to an existing identifier or a well-known organisation),
  **inappropriate**, or **speculative** ("claimed with no concrete project
  behind them, or claimed in bulk").
- Personal identifiers go under the shared `/people/` namespace, not a new top
  level.
- "If you are unsure whether a name will be acceptable, ask on the mailing list
  before writing anything. That is much cheaper than a rejected pull request."

## B3. Will `ontoboard` be accepted? The honest answer

**The name is fine. The use is the risk.**

In favour, all verified today:

- `ids/ontoboard/` **does not exist** (404 on the GitHub contents API), and
  neither does a case variant.
- "OntoBoard" is a coined project name, not a common English word, not a term
  the community would plausibly need, and not confusable with an existing
  identifier or organisation.
- There is a concrete project behind it: 46 tags, 34 releases, a published
  documentation site, a paper under submission. That is the opposite of
  speculative.
- The closest precedents already exist. `ids/widoco/` - a *tool*, not a
  vocabulary - 302s its bare identifier to
  `https://github.com/dgarijo/Widoco/` with `/benchmark`, `/bestPractices`,
  `/gallery` and `/usage` sub-paths. `ids/ontoeditor/` is a single rule that
  302s everything to `https://github.com/ahemaid/OntoEditor`. Both were
  accepted.

Against, and this is the part worth taking seriously: w3id's own scope page says
the service "is not a CDN or a general-purpose URL shortener ... It exists so
that *identifiers embedded in data* keep resolving. If nothing is going to embed
the identifier, you probably do not need one." A w3id whose only job is to be a
shorter link to a GitHub repository is close to that line, and the two
precedents above are weak cover: the rules page says, in a warning box, "The
existing repository is not a style guide ... That a pattern appears in hundreds
of files is evidence it was *copied*, not that it is *correct*." Both of those
files also open with `Options +FollowSymLinks`, which current guidance no longer
wants.

So the request should be made on the strongest ground available, which is real
rather than rhetorical:

1. **Point the bare identifier at the documentation site, not at the git
   repository** - as the `.htaccess` in this directory does. A docs site is the
   durable, citable face of the project and reads less like a shortener.
2. **Say what will embed the identifier.** For OntoBoard that is true and
   checkable: it goes into the paper's availability statement, into
   `CITATION.cff` / `codemeta.json` / `.zenodo.json` metadata that gets
   harvested, and into the documentation. The project also already tells users
   to give ontologies absolute `https://w3id.org/<id>` IRIs
   (`docs/testing-collaboration-locally.md`), scaffolds ODK projects that need a
   base IRI, and bundles 163 design patterns - so if OntoBoard ever mints IRIs
   of its own (pattern IRIs, provenance annotation properties, a project base
   IRI it suggests by default), they belong under this namespace. **Say that in
   the pull request, and do not overstate it**: as of today OntoBoard publishes
   no RDF under this namespace, which is the single honest weakness of the
   request.
3. **Ask first.** The documentation says twice that asking on
   `public-perma-id@w3.org` is cheaper than a rejected pull request. One short
   message - what you want to identify, where the content lives - costs a day
   and removes the guesswork. For a top-level claim that is a link to a tool,
   that is the right order.

**Fallbacks, in the order I would try them:**

1. **A path under an institutional namespace.** `ids/pmd/` already exists
   (PMDco), and `ids/fiz/`, `ids/fizkarlsruhe/`, `ids/ise/`, `ids/nfdi/` and
   `ids/matwerk/` are all free. If ISE or FIZ Karlsruhe would rather hold one
   namespace for its tools, `https://w3id.org/fiz/ontoboard/` or
   `https://w3id.org/ise/ontoboard/` sidesteps the top-level question entirely -
   but claiming a top level named after an institution is an institutional
   decision, not a personal one, so this needs a word with ISE first.
2. **`https://w3id.org/people/<you>/ontoboard`** under the existing shared
   `/people/` namespace. Documented, uncontroversial, and nobody has to approve
   a new top level - at the cost of a longer, personal-looking URL for a tool.
3. **The DOI is already a permanent URL.** `https://doi.org/10.5281/zenodo.<concept>`
   resolves forever, is maintained by someone else, and is the identifier a
   publisher actually asks for. If the w3id request stalls, the paper loses
   nothing: cite the DOI and the GitHub URL, and add the w3id later.
4. **A PURL from `purl.archive.org`** (the Internet Archive's successor to
   purl.org) as a second opinion on the same job. Not evaluated here; its
   process and its rules are not covered by this document.

What I would *not* do: open the pull request with a bare 302 to the GitHub
repository and no explanation. That is the version most likely to be declined
as out of scope, and a declined request costs more than the question that would
have avoided it.

## B4. Content negotiation

The service supports it, and `docs/guides/content-negotiation.md` is explicit
about how: w3id.org cannot serve content, so it reads the `Accept` header and
**303**s to a different URL per format. 303 is the documented status for an
identifier that names a concept while the target is a document about it.

OntoBoard has nothing to negotiate - there is no Turtle, RDF/XML or JSON-LD
document behind this identifier - so the committed `.htaccess` contains no
negotiation rules, and pointing rules at files that do not exist would be worse
than having none. The ready-to-enable block, for when `codemeta.json` is
published on the docs site, is Appendix A of `w3id-htaccess.txt`, along with the
three traps the guide names: escape the `+` in `application/ld\+json`; order
most specific first with an unconditional default last, because a browser's
`Accept` header contains `*/*`; and never end negotiation in a 406
(`htaccess/no-406-fallback`).

---

# Part C - where the repository contradicts the brief, or itself

1. **The two MIT claims, confirmed.** `paper/ontoboard-iswc2026.tex:285` and
   `paper/sections/conclusion.tex:15` both read "OntoBoard is open-source under
   the MIT license". `LICENSE` is the Apache License 2.0, `NOTICE` lists
   third-party licences under an Apache-2.0 product, and the GitHub API reports
   `Apache-2.0`. Fix both lines; the Zenodo record will say `apache-2.0` and the
   paper must not contradict its own archive.
2. **"Released 1.119.0" is a tag, not a release.** 46 tags on the remote,
   including `v1.119.0` (`56ddb71`); 34 GitHub Releases, the newest
   `v1.118.0`, "OntoBoard 1.118.0 (2026-10-12)", published 2026-10-12;
   `releases/latest` resolves to `v1.118.0`. There is no Release object for
   `v1.119.0`, draft or otherwise. Zenodo mints a DOI on a Release, so this
   blocks the whole of Part A until a Release is published.
3. **Two different institutional email addresses are already published.**
   `NOTICE:5` gives `ebrahim.norouzi@fiz-karlsruhe.de`; `paper/main.tex:42` and
   `paper/ontoboard-iswc2026.tex:33` give `ebrahim.norouzi@kit.edu`. The brief
   says the publication address is unknown, but the repository is already
   publishing two. I used the `@fiz-karlsruhe.de` one in the w3id contact block,
   because that is the address the licence notice carries and the affiliation the
   brief fixes; change it if the citable address is the KIT one. No gmail address
   appears in any file I wrote, or anywhere in the repository.
4. **Release and tag counts in the docs are stale.**
   `docs/compared-with-ontoeditor.md:75` says "OntoBoard has 45 tags" (now 46)
   and "no DOI, no PURL, no `CITATION.cff`" - which this tier is in the middle
   of fixing. `docs/evaluation-plan.md:215` claims "89 host smoke receipts over
   45 releases"; GitHub reports 34 Releases. If that sentence goes into a paper,
   the number a reviewer can check is 34.
5. **`.zenodo.json` goes at the repository root** even though the Maven module
   is `protege-plugin/pom.xml`. Zenodo reads it from the root of the repository
   only.
6. **`protege-plugin/pom.xml` starts with a UTF-8 BOM.** Harmless for Maven;
   fatal if the habit spreads. A BOM breaks strict JSON parsers, and in the w3id
   repository `format/no-bom` is an **error** that makes Apache return 500. All
   four files in this directory are written without one.
7. **Sibling staging files are nearly but not quite consistent with mine.**
   `codemeta.json` keeps the keyword `"Protégé plugin"`; my `.zenodo.json` uses
   `"Protege plugin"`, the spelling the brief fixes. `codemeta.json` carries
   `"version": "1.119.0"`; my `.zenodo.json` deliberately omits `version`
   (A6). `CITATION.cff` omits `orcid` and `email` entirely; mine carries the
   loud `XXXX-XXXX-XXXX-XXXX` placeholder because a JSON file cannot hold the
   `# TODO` comment. These are choices, not oversights, but pick one spelling
   and one ORCID policy before installing all three.

---

# Part D - what I could not verify

Listed so that nobody mistakes an inference here for a quotation.

1. **"Past releases are not archived" is nowhere on the current Zenodo site.**
   The claim rests on the webhook's `events=["release"]` subscription in
   `invenio-github`, on Zenodo's forward-looking "new releases ... will be
   automatically ingested", and on an older FAQ quoted second-hand by the OSGeo
   wiki. I am confident in the conclusion and transparent about its basis.
2. **That `version` comes from the tag** when `.zenodo.json` omits it. Inferred
   from a live record (`version` == tag name) and the absence of any other
   source. Not documented on the pages I read.
3. **Whether supplying `related_identifiers` replaces Zenodo's automatic
   `isSupplementTo` link to the tag.** Undocumented. Check the first record and
   re-add the link by hand if it is missing.
4. **Whether `.zenodo.json` rejects unknown keys.** The legacy JSON Schema the
   developer docs point at (`.../legacyrecord.json`) now 404s, so nothing could
   be validated against it. This is why the file sticks to fields that appear in
   Zenodo's own example or in the deposit metadata reference, and why no
   comment-shaped key was smuggled in.
5. **Whether a DOI can be pre-reserved for a GitHub release.** A help article
   titled "Can I pre-reserved a DOI before a GitHub release?" exists in the
   GitHub-integration FAQ category, but the article URL 404s on the path the
   category page prints, so I did not read it. Treat "reserve the DOI before
   publishing the release" as unverified.
6. **Whether a private repository can be enabled.** Not stated on the pages I
   read. Moot here: `ebrahimnorouzi/ontoboard` is public.
7. **The content of Zenodo's "DOI badge" modal**, which is behind a login. The
   badge URL shapes in A7 were verified against the live service instead.
8. **Who, by name, reviews w3id pull requests.** The documentation names the
   community group and the operating consortium, not reviewers.
9. **Timelines.** Neither service documents one. "Normally live shortly
   afterwards" is w3id's own phrase for post-merge deployment; nothing documents
   how long review takes, and nothing documents how long Zenodo takes to process
   a release beyond "this could take some time".
10. **The `purl.archive.org` fallback** in B3 is named as an option, not
    evaluated. Its current procedure was not fetched.
