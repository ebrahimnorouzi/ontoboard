<!--
  Written for: the OntoBoard maintainer, doing the release.
-->

# Giving OntoBoard a DOI and a permanent URL

Neither is done yet. `.zenodo.json` is at the repository root, and the w3id redirect is ready in
[w3id/ontoboard.htaccess](w3id/ontoboard.htaccess).

## A DOI from Zenodo

Zenodo archives a GitHub **release** - not a tag, and not a draft - published after the repository
was switched on there. Earlier releases are never archived; a pre-release is.

1. Sign in at `https://zenodo.org` with GitHub, open **GitHub** in the profile menu, press
   **Sync now** and switch `ebrahimnorouzi/ontoboard` on.
2. Check `.zenodo.json` before the next release. Zenodo reads it from the tagged commit, and then
   ignores `CITATION.cff`, so keep the two in step. Neither holds an ORCID yet; add the real one,
   never a placeholder.
3. Publish the next GitHub release. Zenodo archives the source zip of the tag; the jar, a release
   asset, is not in the record, and `.zenodo.json` says where it is.
4. Open the record and check the title, the creator, the licence (Apache License 2.0), the one
   `.zip`, and the version, which is the tag's name.
5. Note both DOIs. The **concept DOI** ("Cite all versions?") never changes and leads to the newest
   version; the **version DOI** (the Versions panel) names one release. A paper cites the version
   it describes and the concept DOI for all versions; a one-line DOI field takes the concept DOI.
6. Put the badge in `README.md`, beside the download badge, and the DOI in `CITATION.cff` and
   `codemeta.json`:

   ```markdown
   [![DOI](https://zenodo.org/badge/DOI/10.5281/zenodo.CONCEPT_ID.svg)](https://doi.org/10.5281/zenodo.CONCEPT_ID)
   ```

## A permanent URL from w3id.org

`https://w3id.org/ontoboard/` would redirect to the documentation site, with `/repo`, `/issues` and
`/download` for the repository, the issue tracker and the latest release.

1. Ask on `public-perma-id@w3.org` first. w3id is for identifiers embedded in data, and a link to a
   tool is close to its limit, so say what embeds it: the paper, the release metadata, the
   documentation.
2. Fork `perma-id/w3id.org` and add `ids/ontoboard/.htaccess` - in GitHub's *Create new file*, type
   the whole path, as a leading dot cannot be typed on its own - and, if you like,
   `ids/ontoboard/README.md` with the maintainer's contact and GitHub username.
3. Check it: `(cd tools/check && npm ci)` once, then
   `node tools/check/bin/w3id-check.js ids/ontoboard`. Only errors block a pull request.
4. Open one pull request with one commit, `ontoboard: add redirect for the OntoBoard project
   namespace`, that touches nothing outside `ids/ontoboard/`.
5. After the merge, `curl -sIL https://w3id.org/ontoboard/`. Once the concept DOI exists, add a
   `/doi` rule, and the w3id to `.zenodo.json` as `isAlternateIdentifier`.

The `.htaccess` must be LF, without a BOM, with a final newline and no trailing spaces, or Apache
answers 500 for the whole directory. It negotiates no content: OntoBoard publishes no RDF under
the name yet.

If the name is declined, a path under an institutional namespace (`https://w3id.org/fiz/ontoboard/`,
which needs ISE's agreement) or under `https://w3id.org/people/<you>/` would do. The DOI is a
permanent URL already, so a paper loses nothing meanwhile.
