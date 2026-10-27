# sak-frontend

The apps, each under a path of its own; the index page lists them (`src/app/apps.ts`).

## Notes (`/notes`)

A prototype of a note taking app: the notes are post-its that can be written on in place, colored
and labelled, and filtered by one of their labels. The notes are kept in memory only
(`NotesStore`), a reload starts again with the sample notes. The page works on phones (one column,
larger hit targets) and on desktops (a wall of slightly turned post-its).

## Development

```shell
mise run frontend:run           # ng serve on http://localhost:4300
npm test -- --watch=false       # unit tests
```
