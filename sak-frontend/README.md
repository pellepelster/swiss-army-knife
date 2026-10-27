# sak-frontend

The apps, each under a path of its own; the index page lists them (`src/app/apps.ts`). Every app
needs a session: without one the login page asks for an e-mail address, the backend mails a link
to `/signin` (the console's e-mail login, `app: frontend`, `auth.frontend_url` of the backend's
configuration) and the page the login started from opens afterwards.

## Notes (`/notes`)

A note taking app: the notes are post-its that can be written on in place, colored and labelled,
and filtered by one of their labels. The backend keeps them (`/api/v1/notes`), every user has
their own; the labels are the ones all apps share (`/api/v1/labels`). Changes show at once and are
saved in the background, the text once typing pauses (`NotesStore`). A note can have photos, taken
with the camera of a phone or chosen from the files: they are scaled down to 2048 pixels and
re-encoded as JPEG in the browser (`PhotoScaler`, which drops the exif data with the location),
stored in the backend's file storage (`POST /api/v1/files`); the notes show their thumbnails
(`/api/v1/files/<sha256>/thumbnail`), each leads to the whole photo (`/api/v1/files/<sha256>`). The page works on phones (one column,
larger hit targets) and on desktops (a wall of slightly turned post-its).

## Development

```shell
mise run backend:run            # the backend on :8080, its login links go to mailpit on :8025
mise run frontend:run           # ng serve on http://localhost:4300, proxies /api to the backend
npm test -- --watch=false       # unit tests
```
