#!/bin/sh
# OntoBoard mounts this over src/ontology/run.sh while one of the project's own scripts
# runs inside ODK's container. There "sh run.sh make ..." has to run make where it stands:
# a container cannot start a container. The project's own run.sh is not changed.
exec "$@"
