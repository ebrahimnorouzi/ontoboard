# API Reference

OntoBoard exposes a comprehensive REST API at `http://localhost:8000`. Interactive Swagger documentation is available at `http://localhost:8000/docs`.

All authenticated endpoints require a `Authorization: Bearer <token>` header. Tokens are obtained via `POST /api/auth/login`.

---

## Health Check

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/health` | No | Returns `{"status": "ok"}` |

---

## Authentication (`/api/auth`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/auth/login` | No | Login with username/password; returns JWT token |
| POST | `/api/auth/signup` | No | Register a new user account |
| GET | `/api/auth/me` | Yes | Get current authenticated user info |

### Login Request

```json
{
  "username": "admin",
  "password": "admin"
}
```

### Login Response

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIs...",
  "token_type": "bearer"
}
```

---

## User Management (`/api/users`)

Requires admin role unless noted.

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/users/pending` | Admin | List users pending approval |
| GET | `/api/users/` | Admin | List all users |
| POST | `/api/users/` | Admin | Create a new user |
| GET | `/api/users/{user_id}` | Admin | Get user details |
| PATCH | `/api/users/{user_id}` | Admin | Update user fields |
| DELETE | `/api/users/{user_id}` | Admin | Deactivate a user |
| POST | `/api/users/{user_id}/approve` | Admin | Approve a pending user |
| POST | `/api/users/{user_id}/reject` | Admin | Reject a pending user |
| DELETE | `/api/users/{user_id}/permanent` | Admin | Permanently delete a user |

---

## Boards (`/api/boards`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/boards/` | Yes | List boards owned by or shared with the current user |
| GET | `/api/boards/{board_id}` | Optional | Get board details |
| POST | `/api/boards/{board_id}` | Yes | Create a new board |
| POST | `/api/boards/{board_id}/from-file` | Yes | Create board from uploaded OWL/ZIP file |
| PATCH | `/api/boards/{board_id}` | Yes | Update board settings (name, description, visibility, tags) |
| DELETE | `/api/boards/{board_id}` | Yes | Delete a board |
| POST | `/api/boards/{board_id}/clone` | Yes | Clone a board |
| GET | `/api/boards/{board_id}/members` | Yes | List board members |
| POST | `/api/boards/{board_id}/share` | Yes | Share board with a user |
| DELETE | `/api/boards/{board_id}/share/{username}` | Yes | Remove a user's access |
| POST | `/api/boards/{board_id}/request-access` | Yes | Request access to a board |
| POST | `/api/boards/{board_id}/star` | Yes | Toggle star/favourite |
| GET | `/api/boards/{board_id}/activity` | Yes | Get board activity log |
| GET | `/api/boards/{board_id}/sync-check` | Optional | Check last update timestamp (used by polling fallback) |
| GET | `/api/boards/admin/stats` | Admin | Admin statistics across all boards |

---

## Canvas / OWL (`/api/owl`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/owl/{board_id}/load` | Optional | Load canvas state (classes, properties, individuals, literals, sticky notes, frames) |
| POST | `/api/owl/{board_id}/save` | Yes | Save canvas state and update OWL file |
| POST | `/api/owl/{board_id}/upload-csv` | Yes | Upload CSV file for KG building |
| POST | `/api/owl/{board_id}/build-kg` | Yes | Build knowledge graph from uploaded CSV |

### Save Request Body

```json
{
  "classes": [
    { "id": "c1", "iri": "http://example.org/Person", "label": "Person", "x": 100, "y": 200, "w": 120, "h": 50, "color": "#4a90d9" }
  ],
  "properties": [
    { "id": "p1", "iri": "http://example.org/worksFor", "label": "worksFor", "source_id": "http://example.org/Person", "target_id": "http://example.org/Organization", "property_type": "object" }
  ],
  "individuals": [],
  "literals": [],
  "sticky_notes": [],
  "frames": [],
  "track_provenance": true,
  "provenance_target": "both"
}
```

---

## Ontology Metadata (`/api/ontology`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/ontology/{board_id}/metadata` | Optional | Get ontology IRI, version, imports, prefixes |
| GET | `/api/ontology/{board_id}/statistics` | Optional | Get class/property/individual counts |
| POST | `/api/ontology/{board_id}/report` | Yes | Run ROBOT report |
| GET | `/api/ontology/{board_id}/dashboard` | Optional | Combined metadata + statistics + prefixes |
| PUT | `/api/ontology/{board_id}/metadata` | Yes | Update ontology metadata (DC terms) |
| POST | `/api/ontology/{board_id}/prefixes` | Yes | Add a new prefix binding |
| PUT | `/api/ontology/{board_id}/prefixes/{old_prefix}` | Yes | Update a prefix binding (name and/or IRI) |
| DELETE | `/api/ontology/{board_id}/prefixes/{prefix}` | Yes | Remove a prefix binding |
| POST | `/api/ontology/{board_id}/find-replace` | Yes | Find and replace in annotations |
| GET | `/api/ontology/{board_id}/identity` | Optional | Get ontology IRI and version IRI |
| PUT | `/api/ontology/{board_id}/identity` | Yes | Set ontology IRI and version IRI |
| GET | `/api/ontology/{board_id}/annotations` | Optional | Get ontology-level annotations |
| POST | `/api/ontology/{board_id}/annotations` | Yes | Add ontology-level annotation |
| DELETE | `/api/ontology/{board_id}/annotations` | Yes | Remove ontology-level annotation |
| GET | `/api/ontology/{board_id}/resolve/{compact_iri}` | Optional | Resolve compact IRI to full IRI |

---

## Tree Browser (`/api/tree`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/tree/{board_id}/classes` | Optional | Get class hierarchy tree |
| GET | `/api/tree/{board_id}/object-properties` | Optional | Get object property hierarchy |
| GET | `/api/tree/{board_id}/data-properties` | Optional | Get data property hierarchy |
| GET | `/api/tree/{board_id}/annotation-properties` | Optional | Get annotation property hierarchy |
| GET | `/api/tree/{board_id}/individuals` | Optional | Get individuals list |
| GET | `/api/tree/{board_id}/entity/{entity_iri}` | Optional | Get entity detail (annotations, types, supers, characteristics) |
| PUT | `/api/tree/{board_id}/entity/{entity_iri}/annotations` | Yes | Update entity annotations |
| POST | `/api/tree/{board_id}/entity` | Yes | Create a new entity |
| DELETE | `/api/tree/{board_id}/entity/{entity_iri}` | Yes | Delete an entity |

---

## Axiom Editor (`/api/axiom`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/axiom/{board_id}/entity-names` | Optional | List all entity names (for auto-completion) |
| GET | `/api/axiom/{board_id}/axioms/{entity_iri}` | Optional | List axioms for an entity (structured view) |
| GET | `/api/axiom/{board_id}/manchester/{entity_iri}` | Optional | Get Manchester Syntax rendering for an entity |
| PUT | `/api/axiom/{board_id}/axioms/{entity_iri}` | Yes | Update axioms from Manchester Syntax (parsed via recursive descent) |
| POST | `/api/axiom/{board_id}/validate` | Yes | Validate Manchester Syntax expressions |

---

## Restrictions (`/api/restrictions`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/restrictions/{board_id}/entity/{entity_iri}` | Optional | List restrictions on an entity |
| POST | `/api/restrictions/{board_id}/entity/{entity_iri}` | Yes | Add a restriction (some/all/cardinality/hasValue) |
| DELETE | `/api/restrictions/{board_id}/entity/{entity_iri}` | Yes | Remove a restriction |
| POST | `/api/restrictions/{board_id}/complex/{entity_iri}` | Yes | Add complex class expression (union/intersection/complement) |

---

## Property Characteristics (`/api/characteristics`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/characteristics/{board_id}/entity/{entity_iri}` | Optional | Get property characteristics (7 booleans) |
| PUT | `/api/characteristics/{board_id}/entity/{entity_iri}` | Yes | Set property characteristics |
| POST | `/api/characteristics/{board_id}/property-chain` | Yes | Add property chain axiom |
| POST | `/api/characteristics/{board_id}/all-disjoint` | Yes | Declare all disjoint classes |
| POST | `/api/characteristics/{board_id}/disjoint-properties` | Yes | Declare disjoint properties |

### Characteristics Request/Response

```json
{
  "functional": true,
  "inverse_functional": false,
  "transitive": false,
  "symmetric": false,
  "asymmetric": false,
  "reflexive": false,
  "irreflexive": false
}
```

### Property Chain Request

```json
{
  "property_iri": "http://example.org/hasUncle",
  "chain": [
    "http://example.org/hasParent",
    "http://example.org/hasBrother"
  ]
}
```

---

## Reasoning (`/api/reasoning`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/reasoning/{board_id}/run` | Yes | Run reasoner (ELK, HermiT, JFact, Whelk, or owlready2) |
| GET | `/api/reasoning/{board_id}/inferences` | Optional | Get inferred axioms |
| POST | `/api/reasoning/{board_id}/apply-fix` | Yes | Apply a consistency fix suggestion |

---

## SPARQL (`/api/sparql`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/sparql/{board_id}/query` | Optional | Execute SPARQL query (SELECT/CONSTRUCT/ASK) |
| POST | `/api/sparql/{board_id}/visualize` | Optional | Visualize CONSTRUCT results as graph |
| GET | `/api/sparql/{board_id}/prefixes` | Optional | Get ontology prefixes for SPARQL |

---

## DL Query and SWRL (`/api/dlquery`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/dlquery/{board_id}/dl-query` | Yes | Execute DL query |
| GET | `/api/dlquery/{board_id}/swrl` | Optional | List SWRL rules |
| POST | `/api/dlquery/{board_id}/swrl` | Yes | Add SWRL rule (native OWL/XML format) |
| DELETE | `/api/dlquery/{board_id}/swrl` | Yes | Delete SWRL rule |

### SWRL Rule Request

```json
{
  "rule": "Person(?x) ^ hasAge(?x, ?age) ^ swrlb:greaterThan(?age, 18) -> Adult(?x)"
}
```

---

## CSV Import (`/api/csv`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/csv/{board_id}/upload` | Yes | Upload and analyze CSV file |
| GET | `/api/csv/{board_id}/files` | Yes | List uploaded CSV files |
| POST | `/api/csv/{board_id}/preview` | Yes | Preview generated triples |
| POST | `/api/csv/{board_id}/build` | Yes | Build KG from CSV with column mapping |

---

## Patterns (`/api/patterns`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/patterns/` | No | List all available patterns (13 ODPA + user) |
| GET | `/api/patterns/{pattern_id}` | No | Get pattern details and OWL content |
| POST | `/api/patterns/{board_id}/apply/{pattern_id}` | Yes | Apply a pattern to a board (batched state update) |
| POST | `/api/patterns/reload` | Yes | Reload pattern list from disk |
| DELETE | `/api/patterns/{pattern_id}` | Yes | Delete a user-uploaded pattern |
| POST | `/api/patterns/upload` | Yes | Upload a custom pattern (JSON + OWL) |
| POST | `/api/patterns/upload-batch` | Yes | Upload multiple patterns at once |

---

## Search (`/api/search`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/search/{board_id}` | Optional | Full-text search across entity labels, IRIs, comments |

---

## Refactoring (`/api/refactor`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| PUT | `/api/refactor/{board_id}/rename` | Yes | Rename entity IRI (cascading update) |
| PUT | `/api/refactor/{board_id}/move` | Yes | Move entity in hierarchy |
| POST | `/api/refactor/{board_id}/undo` | Yes | Undo last operation |
| POST | `/api/refactor/{board_id}/redo` | Yes | Redo undone operation |

---

## Import Management (`/api/imports`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/imports/{board_id}` | Optional | List ontology imports with resolve status |
| POST | `/api/imports/{board_id}` | Yes | Add a new import (downloads and creates catalog entry) |
| DELETE | `/api/imports/{board_id}/{import_iri}` | Yes | Remove an import |

---

## Version Management (`/api/version`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/version/{board_id}` | Optional | Get version info (versionIRI, etc.) |
| PUT | `/api/version/{board_id}` | Yes | Update version info |
| GET | `/api/version/{board_id}/strategy` | Optional | Get versioning strategy |
| PUT | `/api/version/{board_id}/strategy` | Yes | Set versioning strategy |

---

## ROBOT Commands (`/api/robot`)

7 of 24 ROBOT commands are implemented. All commands run as local subprocesses (no Docker-in-Docker).

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/robot/{board_id}/convert` | Yes | Run ROBOT convert (between OWL formats) |
| POST | `/api/robot/{board_id}/explain` | Yes | Run ROBOT explain (justification axioms) |
| POST | `/api/robot/{board_id}/annotate` | Yes | Run ROBOT annotate |
| POST | `/api/robot/{board_id}/repair` | Yes | Run ROBOT repair |
| POST | `/api/robot/{board_id}/extract` | Yes | Run ROBOT extract (STAR/TOP/BOT) |
| POST | `/api/robot/{board_id}/filter` | Yes | Run ROBOT filter |
| POST | `/api/robot/{board_id}/expand` | Yes | Run ROBOT expand |
| POST | `/api/robot/{board_id}/collapse` | Yes | Run ROBOT collapse |
| POST | `/api/robot/{board_id}/relax` | Yes | Run ROBOT relax |
| POST | `/api/robot/{board_id}/merge` | Yes | Run ROBOT merge |
| POST | `/api/robot/{board_id}/unmerge` | Yes | Run ROBOT unmerge |
| GET | `/api/robot/{board_id}/download/{filename}` | Yes | Download ROBOT output file |
| POST | `/api/robot/{board_id}/rename-cmd` | Yes | Run ROBOT rename |

**Note**: The 7 fully implemented and tested ROBOT commands are: `convert`, `report`, `reason`, `template`, `diff`, `query`, `explain`. The remaining commands have API endpoints but may have limited testing.

---

## ODK Build (`/api/odk`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/odk/{board_id}/build` | Yes | Run ODK build (scaffold generation, no Docker dependency) |

---

## ODK Mediator (`/api/odk-mediator`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/odk-mediator/{board_id}/seed` | Yes | Seed a new ODK project |
| POST | `/api/odk-mediator/{board_id}/update-repo` | Yes | Update ODK repo structure |
| POST | `/api/odk-mediator/{board_id}/refresh-imports` | Yes | Refresh ontology imports |
| POST | `/api/odk-mediator/{board_id}/reason` | Yes | Run reasoning via ODK |
| POST | `/api/odk-mediator/{board_id}/test` | Yes | Run ODK tests |
| POST | `/api/odk-mediator/{board_id}/verify` | Yes | Verify ontology |
| POST | `/api/odk-mediator/{board_id}/release` | Yes | Run release workflow |
| GET | `/api/odk-mediator/{board_id}/release/artifacts` | Yes | List release artifacts |
| GET | `/api/odk-mediator/{board_id}/release/download/{filename}` | Yes | Download release artifact |
| POST | `/api/odk-mediator/{board_id}/dosdp` | Yes | Process DOSDP pattern |

---

## ODK Configuration (`/api/odk-config`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/odk-config/{board_id}/config` | Optional | Get ODK YAML configuration |
| PUT | `/api/odk-config/{board_id}/config` | Yes | Update ODK YAML configuration |
| GET | `/api/odk-config/{board_id}/targets` | Optional | List available Makefile targets |
| GET | `/api/odk-config/{board_id}/changelog` | Optional | Generate changelog from git |
| GET | `/api/odk-config/{board_id}/ci-yaml` | Optional | Generate GitHub Actions CI YAML |

---

## ODK Imports (`/api/odk-imports`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/odk-imports/{board_id}` | Optional | List declared ODK imports |
| POST | `/api/odk-imports/{board_id}/declare` | Yes | Declare a new import |
| GET | `/api/odk-imports/{board_id}/check-makefile` | Optional | Check if Makefile has import targets |
| POST | `/api/odk-imports/{board_id}/add-terms` | Yes | Add terms to an import |
| GET | `/api/odk-imports/{board_id}/terms/{import_id}` | Optional | List terms in an import |
| POST | `/api/odk-imports/{board_id}/register` | Yes | Register an import |
| POST | `/api/odk-imports/{board_id}/add-makefile-target/{import_id}` | Yes | Add Makefile target for import |
| POST | `/api/odk-imports/{board_id}/configure` | Yes | Configure import settings |

---

## ODK Setup (`/api/odk-setup`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/odk-setup/create-board` | Yes | Create board with ODK structure |
| POST | `/api/odk-setup/import-zip` | Yes | Import board from ZIP file |
| POST | `/api/odk-setup/import-github` | Yes | Import board from GitHub (with OWL Functional Syntax auto-conversion) |
| GET | `/api/odk-setup/{board_id}/yaml` | Optional | Get ODK YAML file content |
| PUT | `/api/odk-setup/{board_id}/yaml` | Yes | Update ODK YAML file |
| GET | `/api/odk-setup/{board_id}/files` | Optional | List files in board directory |
| GET | `/api/odk-setup/{board_id}/file/{file_path}` | Optional | Read a file's content |
| PUT | `/api/odk-setup/{board_id}/file/{file_path}` | Yes | Update a file's content |
| DELETE | `/api/odk-setup/{board_id}/file/{file_path}` | Yes | Delete a file |
| POST | `/api/odk-setup/{board_id}/rename` | Yes | Rename a file |
| POST | `/api/odk-setup/{board_id}/new-file` | Yes | Create a new file |

---

## Publish (`/api/publish`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/publish/{board_id}/check` | Yes | Run quality checks before publishing |
| POST | `/api/publish/{board_id}/run` | Yes | Run the publish pipeline |
| GET | `/api/publish/{board_id}/status` | Optional | Get publish status |

---

## Jobs (`/api/jobs`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/jobs/{board_id}/submit` | Yes | Submit a background job (ROBOT commands) |
| GET | `/api/jobs/status/{job_id}` | Yes | Get job status |
| GET | `/api/jobs/stream/{job_id}` | Yes | Stream job progress via SSE |
| GET | `/api/jobs/{board_id}/list` | Yes | List jobs for a board |

---

## Tasks (`/api/tasks`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/tasks/{board_id}` | Yes | List tasks for a board |
| POST | `/api/tasks/{board_id}` | Yes | Create a new task |
| PATCH | `/api/tasks/{board_id}/{task_id}` | Yes | Update a task (status, priority, assignee) |
| DELETE | `/api/tasks/{board_id}/{task_id}` | Yes | Delete a task |
| GET | `/api/tasks/{board_id}/{task_id}/comments` | Yes | List task comments |
| POST | `/api/tasks/{board_id}/{task_id}/comments` | Yes | Add a task comment |
| POST | `/api/tasks/{board_id}/{task_id}/github` | Yes | Link or create GitHub issue |

---

## Comments (`/api/comments`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/comments/{board_id}` | Yes | List comments (optional entity_iri filter for entity-level) |
| POST | `/api/comments/{board_id}` | Yes | Create a comment (with optional @mentions, threading via parent_id) |
| DELETE | `/api/comments/{board_id}/{comment_id}` | Yes | Delete a comment |

---

## Documentation (`/api/docs`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/docs/{board_id}/build` | Yes | Build ontology documentation |
| GET | `/api/docs/{board_id}/status` | Optional | Get documentation build status |
| GET | `/api/docs/{board_id}/files` | Optional | List generated doc files |
| GET | `/api/docs/{board_id}/serve/{file_path}` | Optional | Serve a doc file |

---

## Invite Links (`/api/invite`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/invite/{board_id}/create` | Yes | Create an invite link (role, expiration, max uses) |
| GET | `/api/invite/{board_id}/links` | Yes | List invite links for a board |
| DELETE | `/api/invite/{board_id}/{token}` | Yes | Revoke an invite link |
| POST | `/api/invite/accept/{token}` | Yes | Accept an invite link |
| GET | `/api/invite/info/{token}` | No | Get invite link info (board name, role) |

---

## Notifications (`/api/notifications`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/notifications/` | Yes | List notifications |
| GET | `/api/notifications/count` | Yes | Get unread count |
| POST | `/api/notifications/{notification_id}/read` | Yes | Mark as read |
| POST | `/api/notifications/read-all` | Yes | Mark all as read |
| DELETE | `/api/notifications/{notification_id}` | Yes | Delete a notification |

---

## Quality (`/api/quality`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/quality/{board_id}/oops` | Yes | Run OOPS! pitfall detection |
| GET | `/api/quality/{board_id}/oquare` | Optional | Calculate OQuaRE metrics |
| GET | `/api/quality/{board_id}/compliance/{registry}` | Optional | Check registry compliance |
| POST | `/api/quality/{board_id}/registry/submit` | Yes | Submit to registry |

---

## Analysis (`/api/analysis`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/analysis/{board_id}/unused` | Optional | Find unused entities |
| GET | `/api/analysis/{board_id}/deprecated` | Optional | List deprecated entities |
| GET | `/api/analysis/{board_id}/import-health` | Optional | Check import health (resolve status) |
| GET | `/api/analysis/{board_id}/circular-deps` | Optional | Detect circular dependencies |
| POST | `/api/analysis/{board_id}/batch-annotate` | Yes | Batch annotate entities |

---

## ID Ranges (`/api/idranges`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/idranges/{board_id}` | Optional | List ID range allocations (OWL Functional Syntax support) |
| POST | `/api/idranges/{board_id}/allocate` | Yes | Allocate a new ID range |
| PUT | `/api/idranges/{board_id}/{owner}` | Yes | Update an ID range |
| DELETE | `/api/idranges/{board_id}/{owner}` | Yes | Delete an ID range |
| POST | `/api/idranges/{board_id}/reserve` | Yes | Reserve an ID within a range |

---

## Export (`/api/export`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| POST | `/api/export/{board_id}` | Yes | Export ontology in specified format (OWL/XML, Turtle, N-Triples, Functional, etc.) |
| POST | `/api/export/{board_id}/zip` | Yes | Download board as ZIP |

---

## Help (`/api/help`)

| Method | Path | Auth | Description |
|--------|------|:----:|-------------|
| GET | `/api/help/topics` | No | List help topics |
| GET | `/api/help/topic/{topic_id}` | No | Get help topic content |
| GET | `/api/help/topic/{topic_id}/field/{field_id}` | No | Get field-level help |
| GET | `/api/help/import-step/{step_num}` | No | Get import step guidance |
