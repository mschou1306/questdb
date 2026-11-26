# Shared schema groups

Schema groups are Web Console presentation metadata, not SQL schemas. They do
not change object names, SQL resolution, DDL, ownership or permissions. Layouts
are shared by one database instance. Expansion/collapse state is not stored here.

The HTTP endpoint is `/schema-groups`, beneath `http.context.web.console` when
configured. Both methods use the same authentication requirement as SQL HTTP
requests. This metadata is **not** included in `/settings`.

## Read

`GET /schema-groups` returns:

```json
{
  "version": "opaque revision",
  "editable": true,
  "sections": {
    "table": {
      "groups": [{"id": "opaque group ID", "name": "Orders"}],
      "assignments": {"42": "opaque group ID"}
    },
    "matview": {"groups": [], "assignments": {}},
    "view": {"groups": [], "assignments": {}},
    "liveview": {"groups": [], "assignments": {}}
  }
}
```

Definitions and names are deliberately visible to all authenticated schema
readers. Assignments are filtered by the current principal's schema visibility;
no hidden object names, IDs or membership counts are returned. Groups are sorted
by display name (UTF-16 lexical order). Each kind has a separate group namespace.

## Change

`POST /schema-groups` accepts exactly one command with the last observed version:

```json
{"version":"...","kind":"table","type":"create","name":"Orders"}
{"version":"...","kind":"table","type":"delete","groupId":"..."}
{"version":"...","kind":"table","type":"move","objectIds":[42,43],"groupId":"..."}
{"version":"...","kind":"table","type":"move","objectIds":[42],"groupId":null}
```

`kind` is `table`, `matview`, `view` or `liveview`. Group IDs are opaque,
case-sensitive UUID strings, independent of names. Recreating a deleted group
with the same name creates a new ID. Empty groups are retained.

Names are trimmed using ECMAScript `String.trim` whitespace (including NBSP and
BOM, excluding U+0085). Uniqueness is tested using Java
`String.toLowerCase(Locale.ROOT)` per kind, without Unicode normalization.
Names must contain 1–128 UTF-16 code units after trimming; ISO control characters
and unpaired surrogates are rejected. There are at most 256 groups per kind,
1–1024 IDs per move, and 65,536 UTF-8 bytes per request. IDs must be positive
32-bit JSON integers. Unknown/duplicate fields and malformed JSON are rejected.

Changes require `SecurityContext.authorizeSettings()` and honor
`http.settings.readonly`. `editable` reports that policy for the current reader.
Every move target must exist, have the requested kind and be visible. A partly
invalid batch makes no layout change, and invalid/hidden targets use the same
generic error. An authorized settings editor may delete a group containing
hidden members: all members are ungrouped without exposing those memberships.

Success is HTTP 200 with a fresh, filtered snapshot. HTTP 409 means the version
has changed; there is no automatic merge. Invalid commands return 400, denied
edits follow the existing 401 settings convention, oversized bodies return 413,
and storage failures return a generic 500. Version comparison, layout commit
and response snapshot are serialized independently of instance preferences.

## Persistence and lifecycle

`db/_schema_groups~store` is a private block file. Writes stage a separate file,
then atomically replace it before publishing the in-memory layout. Failed
pre-publication writes leave both revision and layout unchanged. Each commit
has a random UUID revision, stable across ordinary restarts.

Assignments rely on the persisted monotonic table-ID generator (`_tab_index.d`)
not reusing numeric object IDs within an instance.
All four object kinds retain membership on rename; drop removes the assignment,
and startup reconciliation removes IDs absent from the section's live objects.
Recreating the same name does not inherit a group. WAL rebase transfers membership
to the new object ID.
If cleanup persistence fails after a committed drop, the stale record remains
invisible and reconciliation retries it on startup.
An interrupted or failed membership write after a committed rebase can leave the
rebased object ungrouped; the data rebase is not rolled back.

Checkpoints include the layout alongside the objects. Checkpoint restore copies
the layout, reconciles it against restored objects, and issues a fresh revision
on initialization, including repeated restores of the same checkpoint.
Pre-feature checkpoints restore an empty layout. File-level backups must include
the private layout file with the objects; use the supported checkpoint
restore path rather than overwriting these files on a running instance.
