# sak-e2e

End to end tests of the apps with Playwright, against the local environment:

```sh
mise run run       # ingress, victorialogs, vector, mailpit, the backend and the frontend
mise run e2e:run   # in another terminal, once `mise run run` lists the addresses
```

The login tests read the links from mailpit (http://localhost:8025) and sign in as
`admin@localhost` (config/local-config.yaml). The backend sends 5 links an hour to an address and
keeps that count in memory, a run asks for one; restart the backend to run more often.
`SAK_E2E_FRONTEND_URL` and `SAK_E2E_MAILPIT_URL` point the tests elsewhere.
