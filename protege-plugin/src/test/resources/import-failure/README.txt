What make and the fetch said when an import's source could not be fetched - recorded
2026-11-17 for ImportFailureTest, as they came, line breaks and curl's progress meter included.

engine-*.txt  OntoBoard's own engine (OdkEngine.run; ROBOT 1.9.8 and OWL API 4.5.9 in-process).
image-*.txt   ODK's image v1.6.1 (obolibrary/odkfull), offline: docker run --network none.

Each is ODK's seed "oboimports" (id obim) refreshing one import with its source at an address
that fails:

  refused         make refresh-ro OBOBASE=http://127.0.0.1:9/obo     nothing listens there
  noname          make refresh-ro OBOBASE=http://no-such-host.invalid/obo
  notfound        make refresh-iao OBOBASE=http://127.0.0.1:8765/obo  a local server: 404
  page            make refresh-ro OBOBASE=http://127.0.0.1:8765/obo  the same server: an HTML page
  all             make refresh-imports OBOBASE=http://127.0.0.1:9/obo
  pato-refused    make refresh-pato, mirror_from: http://127.0.0.1:9/pato-base.owl
  pato-notfound   make refresh-pato, mirror_from: http://127.0.0.1:8765/missing.owl
  pato-page       make refresh-pato, mirror_from: http://127.0.0.1:8765/page.owl

ro and iao are fetched with curl, pato with ROBOT's convert -I (its mirror_from was changed in
obim-odk.yaml and the Makefile rendered again from it). ODK's curl is called without --fail, so
in ODK's image a 404 is saved as the file and ROBOT then cannot read it; OntoBoard's curl stops
at the 404. OWL API 4.5.9 reads the HTML page as an empty TriX document, so engine-page.txt and
engine-pato-page.txt get past the fetch and stop at the module; OWL API 4.5.29 (ODK's, and
Protege 5.6.9's) refuses the page as image-page.txt shows.
