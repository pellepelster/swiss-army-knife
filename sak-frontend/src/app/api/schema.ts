// generated from openapi.json by scripts/api-types.mjs (npm run api:types), not edited
export interface paths {
  '/api/v1/info': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The deployment that answers
     * @description The name, the dns mode (domain or subdomain), the user mode (multi with a mail server, single without) and the version of SAK of the deployment the backend belongs to. Needs no session.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The name, the dns mode, the user mode and the version of the deployment */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['InfoResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/login/link': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Request a login link
     * @description Sends a link with a login token, valid for 15 minutes, to the signin page of the apps if the address may sign in. An android app sends a nonce of 16 to 128 characters, the link signs in only with it. Answered the same way for every address.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['LoginRequest'];
        };
      };
      responses: {
        /** @description The link is on its way if the address may sign in */
        202: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description No valid e-mail address or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Too many login requests from this client */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/login/exchange': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Exchange a login token for a session token
     * @description Every login token can be exchanged once, a token of a link asked for with a nonce only with that nonce, one asked for without only without; the session token is valid for 30 days.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['ExchangeRequest'];
        };
      };
      responses: {
        /** @description The session token and where the login started */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ExchangeResponse'];
          };
        };
        /** @description The login token is invalid, expired or was already used */
        401: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/login/password': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Sign in as the admin with the admin password
     * @description The admin password is the password of the deployment. The session token is valid for 30 days. Only a few attempts an hour are answered.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['PasswordLoginRequest'];
        };
      };
      responses: {
        /** @description The session token of the admin and where the login started */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ExchangeResponse'];
          };
        };
        /** @description The password is not the admin password */
        401: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Too many attempts from this client or altogether */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/login/connect': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Exchange the token of a QR code for a session token
     * @description The token of the QR code a signed in user shows to connect an app (POST /api/v1/auth/connect), valid for 2 minutes and only once; the session token is valid for 30 days. Only a few attempts an hour are answered.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['ConnectExchangeRequest'];
        };
      };
      responses: {
        /** @description The session token */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ExchangeResponse'];
          };
        };
        /** @description The token is invalid, expired or was already used, or the user may no longer sign in */
        401: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Too many attempts from this client */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/who-am-i': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The signed in user */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The address, its name if it has one, its roles and when the session expires */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['WhoAmIResponse'];
          };
        };
        /** @description No valid session token */
        401: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/connect': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * A QR code that connects an app
     * @description The link the QR code shows: the SAK address with a token in its fragment, valid for 2 minutes and only once, that an app exchanges for a session of the signed in user (POST /api/v1/auth/login/connect). Whoever has the code signs in as the user until it expires. Only for a session, a few codes an hour.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The link of the QR code, its id and when it expires */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ConnectResponse'];
          };
        };
        /** @description A signed request of sakctl, which has no session to connect */
        403: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Too many codes of this user */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/auth/connect/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** Whether an app was connected with a QR code */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the code */
          id: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description Whether the code was used */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ConnectStatusResponse'];
          };
        };
        /** @description The user has no such code, or it expired */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The buckets with the number of their objects and their size
     * @description The numbers are counted in the background and kept, recounted after 5 minutes and after every write to a bucket; a bucket not counted yet is still computing, ask again.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the buckets with the domains they are served at and their statistics */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['BucketResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Create a bucket
     * @description Creates the bucket with a key pair that may access this bucket only, served at the domains in their order and at <name>.<root_domain>. The name can not be changed later.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['CreateBucketRequest'];
        };
      };
      responses: {
        /** @description The new bucket */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['BucketResponse'];
          };
        };
        /** @description No valid bucket name or one reserved for the controller's sites, a domain that is not valid or one the controller serves or redirects, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description A bucket of the name exists, <name>.<root_domain> is already served, or another bucket is served at one of the domains */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A bucket with the number of its objects and its size */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The name of the bucket */
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The bucket, the domains it is served at and its statistics */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['BucketResponse'];
          };
        };
        /** @description Unknown bucket */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    /**
     * Delete a bucket
     * @description The bucket is no longer served and its key pair is refused. Its name stays taken and its objects stay in the storage until it is purged. A bucket that redirects lead to is not deleted.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The name of the bucket */
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The bucket is deleted */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description Unknown bucket */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Redirects lead to the bucket, named in the message */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}/domains': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Change the domains of a bucket
     * @description Serves the bucket at the domains in their order instead of its current ones, none for no domain.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The name of the bucket */
          bucket: string;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['UpdateDomainsRequest'];
        };
      };
      responses: {
        /** @description The bucket with its new domains */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['BucketResponse'];
          };
        };
        /** @description A domain that is not valid or one the controller serves or redirects, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Unknown bucket */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Another bucket is served at one of the domains */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}/secret': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The key pair of a bucket */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The name of the bucket */
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The access key and the secret that may access the bucket */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['BucketSecretResponse'];
          };
        };
        /** @description Unknown bucket */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}/access-logs': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The site of the access logs and the ranges of its metrics */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The site with its domains, the ranges and the default range */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['AccessLogsResponse'];
          };
        };
        /** @description No such site, or it is not served at a domain */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}/access-logs/metrics': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The request metrics of a site
     * @description Requests per status class and code, transferred bytes and user agent groups over a range.
     */
    get: {
      parameters: {
        query?: {
          /** @description The range, one of the keys the access logs list, 24h by default */
          range?: string;
        };
        header?: never;
        path: {
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The metrics of the range */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['SiteMetricsResponse'];
          };
        };
        /** @description Unknown range */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description No such site, or it is not served at a domain */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The logs are not available */
        503: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/static-hosting/{bucket}/access-logs/search': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Search the logs
     * @description The requests of the last 30d whose request line (method, host, path, status) or user agent contains the text, case-insensitively, newest first.
     */
    get: {
      parameters: {
        query: {
          /** @description The text to search for, at most 200 characters */
          q: string;
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path: {
          bucket: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the matching requests, newest first */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['LogEntryResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description No, too long or control characters in the search text, or an invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description No such site, or it is not served at a domain */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The logs are not available */
        503: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/redirects': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The redirected domains */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the redirects, each with its target and the domain it leads to */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['RedirectResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Redirect a domain
     * @description Redirects every request for the domain to an external domain, or to the first domain a bucket is served at. Caddy is configured right away.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['CreateRedirectRequest'];
        };
      };
      responses: {
        /** @description The new redirect */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['RedirectResponse'];
          };
        };
        /** @description No valid domain, the source is the target, a domain the controller or a bucket serves, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The domain is redirected already */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The bucket is unknown or not served at a domain */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/redirects/{source}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A redirected domain */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The redirected domain */
          source: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The redirect with its target and the domain it leads to */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['RedirectResponse'];
          };
        };
        /** @description The domain is not redirected */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Change where a domain is redirected to
     * @description The redirected domain stays, its target is replaced. Caddy is configured right away.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The redirected domain */
          source: string;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['UpdateRedirectRequest'];
        };
      };
      responses: {
        /** @description The changed redirect */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['RedirectResponse'];
          };
        };
        /** @description No valid domain, the redirected domain itself, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The domain is not redirected */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The bucket is unknown or not served at a domain */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /** Stop redirecting a domain */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The redirected domain */
          source: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The redirect is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The domain is not redirected */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/labels': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The labels of the signed in user */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the labels, sorted by name */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['LabelResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Create a label
     * @description The name is stored in lower case with single spaces.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['LabelRequest'];
        };
      };
      responses: {
        /** @description The new label */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['LabelResponse'];
          };
        };
        /** @description An invalid name or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description A label of the name exists */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/labels/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A label */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the label */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The label */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['LabelResponse'];
          };
        };
        /** @description There is no such label of the signed in user */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Rename a label
     * @description Every entity the label is attached to has it under the new name.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the label */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['LabelRequest'];
        };
      };
      responses: {
        /** @description The renamed label */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['LabelResponse'];
          };
        };
        /** @description An invalid name or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description There is no such label */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Another label has the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Delete a label
     * @description The label is detached from every entity, its name can be used again.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the label */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The label is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description There is no such label */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/notes/settings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * What the notes app may do
     * @description For every signed in user: whether the text of a photo or a recording can be discovered now (NOTES-5). The switches themselves are settings of the admins (GET /api/v1/settings/notes).
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description What the notes app may do */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['NotesSettingsResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/notes': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The notes of the signed in user */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
          /** @description Only the notes with the label of the name */
          label?: string;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the notes, the newest first */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['NoteResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Create a note
     * @description The note belongs to the signed in user. The labels are given by name, the ones that do not exist yet are created.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['NoteRequest'];
        };
      };
      responses: {
        /** @description The new note */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['NoteResponse'];
          };
        };
        /** @description A too long text, an unknown color, an invalid label name, too many labels, too many photos, too many recordings or a malformed request, also with an unknown photo or recording */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Only photos or recordings the user has not stored, or the user has no list of notes (they get one when they sign in) */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/notes/labels': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The labels of the notes
     * @description The labels a note of the signed in user has, by name: the ones the notes filter by. GET /api/v1/labels has every label.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the labels */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['LabelResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/notes/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** A note */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the note */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The note */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['NoteResponse'];
          };
        };
        /** @description The signed in user has no such note */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Change a note
     * @description Replaces the text, the color, the labels, the photos and the recordings; the labels that do not exist yet are created.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the note */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['NoteRequest'];
        };
      };
      responses: {
        /** @description The changed note */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['NoteResponse'];
          };
        };
        /** @description A too long text, an unknown color, an invalid label name, too many labels, too many photos, too many recordings or a malformed request, also with an unknown photo or recording */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The signed in user has no such note */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Only photos or recordings the user has not stored */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /** Delete a note */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the note */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The note is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The signed in user has no such note */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/settings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * What the grocery lists may do
     * @description For every signed in user: whether the items in a photo can be discovered now (GROCERY-16). The switches themselves are settings of the admins (GET /api/v1/settings/grocery).
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description What the grocery lists may do */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GrocerySettingsResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/discover/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Discover the items in a photo
     * @description Reads the ingredients of a recipe or the items of a shopping list in a photo of the file storage with a vision model at OpenRouter; providers that collect the data are not asked. Nothing is added to a list: the items are added once the user confirmed them (POST /api/v1/grocery/items). Needs the AI features of the grocery lists turned on in the settings.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the photo in the file storage */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The items in the photo, empty when it shows none */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryDiscoveredResponse'];
          };
        };
        /** @description The file is no photo the user uploaded */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The signed in user has no such file */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The AI features of the grocery lists are turned off in the settings */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user had too many photos or texts read at OpenRouter lately */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description OpenRouter could not read the photo */
        502: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description No OpenRouter API key is set in the settings */
        503: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The grocery lists
     * @description The lists of the signed in user and the shared lists of the others, by name.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the lists */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['GroceryListResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Create a grocery list
     * @description With the default categories, named in the language of the request (Accept-Language, GROCERY-13).
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryListRequest'];
        };
      };
      responses: {
        /** @description The new list */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryListResponse'];
          };
        };
        /** @description An invalid name or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user has a list of the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * A grocery list
     * @description A list of the signed in user or a shared list of another user.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The list */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryListResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Rename or share a grocery list
     * @description Every user who sees a list changes its name and its icon, a shared list of another user too (LIST-2); only its owner shares or unshares it.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryListRequest'];
        };
      };
      responses: {
        /** @description The changed list */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryListResponse'];
          };
        };
        /** @description An invalid name or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user is not the owner and would rename, share or unshare the list */
        403: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Another list of the owner has the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Delete a grocery list
     * @description Its items go with it; only its owner deletes a list, their last one too.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The list is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user has no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The items to buy of a grocery list
     * @description By their category, the ones without one last; in a category the ones to buy in the order they were added, with checked=true the latest bought item of each name that is not to buy after them, the last bought first (GROCERY-14).
     */
    get: {
      parameters: {
        query?: {
          /** @description true answers the latest bought item of each name too, each with its checkedAt */
          checked?: boolean;
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the items to buy */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['GroceryItemResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/suggestions': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * Suggestions for an item of a grocery list
     * @description The names of the items of the list that contain the text, one for each name with the values of its latest item, the names of the most items and the latest first, paged like every list; the names to buy are left out. Only this list suggests them, every user who sees it sees them (GROCERY-11).
     */
    get: {
      parameters: {
        query?: {
          /** @description What the name contains, all when it is missing */
          text?: string;
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the suggestions */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['GrocerySuggestionResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/suggestions/{suggestionId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    /**
     * Forget a suggestion of a grocery list
     * @description For every user who sees the list: the checked items of its name go for good, an item to buy stays. Adding an item of the name brings it back (GROCERY-11).
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
          /** @description The id of the suggestion (the latest item of its name), or of any live item of the list with the name */
          suggestionId: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The suggestion is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user sees no such list, or it has no such suggestion */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/categories': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The categories of a grocery list
     * @description In the order the list shows the groups of its items; every user who sees the list sees them (GROCERY-13).
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the categories */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['GroceryCategoryResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Add a category to a grocery list
     * @description The last one; every user who sees the list (GROCERY-13), at most 30.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryCategoryRequest'];
        };
      };
      responses: {
        /** @description The new category */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryCategoryResponse'];
          };
        };
        /** @description An invalid name or icon, too many categories, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The list has a category of the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/categories/order': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Order the categories of a grocery list
     * @description The ids of all categories of the list, each once, in their new order; every user who sees the list (GROCERY-13).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryCategoryOrderRequest'];
        };
      };
      responses: {
        /** @description A page of all categories in their new order */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['GroceryCategoryResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description Ids that are not the categories of the list, each once, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/lists/{id}/categories/{categoryId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Change a category of a grocery list
     * @description Its name and its icon; every user who sees the list (GROCERY-13).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
          /** @description The id of the category */
          categoryId: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryCategoryRequest'];
        };
      };
      responses: {
        /** @description The changed category */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryCategoryResponse'];
          };
        };
        /** @description An invalid name or icon, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list, or it has no such category */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The list has another category of the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Delete a category of a grocery list
     * @description Its items and the suggestions of the list lose it; every user who sees the list (GROCERY-13).
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
          /** @description The id of the category */
          categoryId: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The category is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user sees no such list, or it has no such category */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Add an item to a grocery list
     * @description An item names its list, one the user sees.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryNewItemRequest'];
        };
      };
      responses: {
        /** @description The new item */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description An invalid name, amount, unit or category, or a malformed request, e.g. without a list */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * An item of a grocery list
     * @description An item of a list the user sees.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The item */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /** Change an item */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryItemRequest'];
        };
      };
      responses: {
        /** @description The changed item */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description An invalid name, amount, unit or category, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Delete an item
     * @description An item, to buy or bought, goes for good, e.g. one added by mistake: no list shows it, no suggestion counts it.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The item is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items/{id}/category': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Set the category of an item
     * @description Of an item, to buy or bought: one of the categories of its list, or none; the next item of the name on the list takes it (GROCERY-13).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryItemCategoryRequest'];
        };
      };
      responses: {
        /** @description The item with its category */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description No category of the list, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items/{id}/photo': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Attach a photo to an item
     * @description Of an item, to buy or bought: a file the user uploaded to the file storage (POST /files) that is an image. The next item of the name on the list takes it (GROCERY-12).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['GroceryPhotoRequest'];
        };
      };
      responses: {
        /** @description The item with its photo */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description The file is no image the user uploaded, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Take the photo away from an item
     * @description From an item, to buy or bought; the next item of the name on the list has none; the file stays in the file storage.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The item without a photo */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['GroceryItemResponse'];
          };
        };
        /** @description The user sees no such item */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items/{id}/photos/{fileId}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The photo of an item
     * @description To every user who sees the list of the item. With size, the photo scaled to the smallest of 128, 256, 480, 1024 pixels on the longer side that is at least that large, a JPEG; larger, or without size, the photo itself. The apps ask for the size the photo shows at on the screen.
     */
    get: {
      parameters: {
        query?: {
          /** @description The pixels the photo is to have at least on its longer side */
          size?: number;
        };
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
          /** @description The file of the photo, photoId of the item */
          fileId: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The photo, scaled or itself */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description No file id or no positive size */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user sees no such item, or the file is not its photo */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/items/{id}/check': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Check an item
     * @description The item is bought (GROCERY-3): the list no longer shows it, it stays as the history of the list, what its suggestions are made of (GROCERY-11).
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the item */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The item is checked */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user sees no such item to buy */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/grocery/changes': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The changes of the grocery lists
     * @description A stream of server-sent events (text/event-stream) that stays open: an event changed with the id of the list ({"listId": 2}) whenever a list the user sees, or its items, change, also when it is deleted, and when its owner unshares it, to every user who saw it until then. The event carries no data of the list, the client loads it again. A comment every 25 seconds keeps the connection open.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The stream of the changes */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/lists': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The todo lists
     * @description The lists of the signed in user and the shared lists of the others, by name.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the lists */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['TodoListResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /** Create a todo list */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoListRequest'];
        };
      };
      responses: {
        /** @description The new list */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoListResponse'];
          };
        };
        /** @description An invalid name or icon, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user has a list of the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/lists/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * A todo list
     * @description A list of the signed in user or a shared list of another user.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The list */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoListResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Rename or share a todo list
     * @description Every user who sees a list changes its name and its icon, a shared list of another user too (LIST-2); only its owner shares or unshares it.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoListRequest'];
        };
      };
      responses: {
        /** @description The changed list */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoListResponse'];
          };
        };
        /** @description An invalid name or icon, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user is not the owner and would rename, share or unshare the list */
        403: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description Another list of the owner has the name */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    /**
     * Delete a todo list
     * @description Its tasks go with it; only its owner deletes a list, their last one too.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The list is gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The user has no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/lists/{id}/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The tasks of a todo list
     * @description Open and done alike, a done task keeps its place (TODO-3): by priority, the high ones first (TODO-5), then in the order they were added.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the tasks */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['TodoItemResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/lists/{id}/purge': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Purge the done tasks of a todo list
     * @description The done tasks of the list the request names, the ones the user saw done, are deleted; the open ones stay, and so does one done meanwhile (TODO-6). Every user who sees the list purges it (LIST-2).
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the list */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoPurgeRequest'];
        };
      };
      responses: {
        /** @description The done tasks are gone */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description A malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/items': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Add a task to a todo list
     * @description A task names its list, one the user sees, and may have a day it is to be done by (TODO-4) and a priority (TODO-5).
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoNewItemRequest'];
        };
      };
      responses: {
        /** @description The new task */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoItemResponse'];
          };
        };
        /** @description An invalid title, due date or priority, or a malformed request, e.g. without a list */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such list */
        422: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/items/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * An task of a todo list
     * @description An task of a list the user sees.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the task */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The task */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoItemResponse'];
          };
        };
        /** @description The user sees no such task */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/items/{id}/priority': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Change the priority of a task
     * @description High, normal or low (TODO-5), of an open or a done task; the list shows it in its new place. Every user who sees its list changes it (LIST-2).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the task */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoPriorityRequest'];
        };
      };
      responses: {
        /** @description The task */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoItemResponse'];
          };
        };
        /** @description An invalid priority, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such task */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/items/{id}/due-date': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Set or remove the due date of a task
     * @description The day a task is to be done by, of an open or a done task; none takes it away (TODO-4). Every user who sees its list changes it (LIST-2).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the task */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoDueDateRequest'];
        };
      };
      responses: {
        /** @description The task */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoItemResponse'];
          };
        };
        /** @description An invalid due date, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such task */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/items/{id}/done': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    /**
     * Mark a task done or open again
     * @description A done task stays on its list, struck through, until the list is purged (TODO-3, TODO-6). Every user who sees its list marks it, also one another user added (LIST-2).
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the task */
          id: number;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['TodoDoneRequest'];
        };
      };
      responses: {
        /** @description The task */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['TodoItemResponse'];
          };
        };
        /** @description A malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user sees no such task */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/todo/changes': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The changes of the todo lists
     * @description A stream of server-sent events (text/event-stream) that stays open: an event changed with the id of the list ({"listId": 2}) whenever a list the user sees, or its tasks, change, also when it is deleted, and when its owner unshares it, to every user who saw it until then. The event carries no data of the list, the client loads it again. A comment every 25 seconds keeps the connection open.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The stream of the changes */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The users
     * @description The admins of the configuration of the deployment first, then the users an admin added, by address.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the users */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['UserResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    /**
     * Add a user
     * @description The user signs in with a link by e-mail and uses the notes, the grocery lists and the todo lists (the role user). No mail is sent.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['UserRequest'];
        };
      };
      responses: {
        /** @description The added user */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['UserResponse'];
          };
        };
        /** @description An invalid address or name, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description A user of the address exists, or it is an admin of the configuration */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/app-link': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Send the links of the Android apps
     * @description Sends a user or an admin of the deployment an e-mail with the links to the pages that download the Android apps: the grocery lists (/grocery/download/app), the notes (/notes/download/app) and the todo lists (/todo/download/app), in the language of the request.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': components['schemas']['AppLinkRequest'];
        };
      };
      responses: {
        /** @description The e-mail is sent */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The address is no user or admin of the deployment */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The deployment has no mail server */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The mail server did not take the e-mail */
        502: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/users/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    post?: never;
    /**
     * Delete a user
     * @description The user can no longer sign in, their sessions end with their next request; their data stays.
     */
    delete: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the added user */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The user is deleted */
        204: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description No such added user; an admin of the configuration is not deleted here */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/settings': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The scopes of settings
     * @description Every scope with its settings, their labels, types and defaults, what the settings page is built from. Admins only.
     */
    get: {
      parameters: {
        query?: {
          /** @description Where the page starts in the whole list, 0 by default */
          offset?: number;
          /** @description How many items the page has at most, 50 by default and 100 at most */
          limit?: number;
        };
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description A page of the scopes */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              items: components['schemas']['SettingScopeResponse'][];
              pagination: components['schemas']['PaginationResponse'];
            };
          };
        };
        /** @description An invalid page */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/settings/{scope}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The settings of a scope
     * @description Every setting of the scope with its stored value, also while it is off; with effective=true as the apps use it, the default for a setting that is off. A masked value shows its first characters only. Admins only.
     */
    get: {
      parameters: {
        query?: {
          /** @description true for the values as the apps use them, false by default */
          effective?: boolean;
        };
        header?: never;
        path: {
          /** @description The name of the scope, e.g. openrouter */
          scope: string;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The settings of the scope, by their names */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              [key: string]: string;
            };
          };
        };
        /** @description There is no such scope */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    /**
     * Change the settings of a scope
     * @description Sets the values of the settings named; the others keep their values. Every value is validated first, one that is not valid sets none. Admins only.
     */
    put: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The name of the scope, e.g. openrouter */
          scope: string;
        };
        cookie?: never;
      };
      requestBody: {
        content: {
          'application/json': {
            [key: string]: components['schemas']['JsonElement'];
          };
        };
      };
      responses: {
        /** @description Every setting of the scope with its value, after the change */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': {
              [key: string]: string;
            };
          };
        };
        /** @description An unknown setting, a value of the wrong type or not valid, or a malformed request */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description There is no such scope */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/health': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The health of the backend
     * @description Runs every health check, e.g. whether the domains of the buckets and the redirects point to the server.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The status of every check, in a fixed order */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['HealthStatusResponse'][];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/files': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Store a file
     * @description The body is the content of the file. Every upload is a file of its own, served at /api/v1/files/{id}. The type is told by the content.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The stored file */
        201: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['FileResponse'];
          };
        };
        /** @description A type the storage does not take, content that is no such file, an image larger than 2048 pixels, or a recording longer than 5 minutes */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description A file larger than the storage takes */
        413: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/files/{id}/discover/text': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    get?: never;
    put?: never;
    /**
     * Discover the text in an image or a recording
     * @description Reads the text in an image with a vision model, writes down a recording with a speech to text model, both at OpenRouter; providers that collect the data are not asked. The text is kept with the file, a file is only read once. Needs the AI features turned on in the settings.
     */
    post: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the file */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The text in the image or the recording, empty when there is none */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['DiscoverTextResponse'];
          };
        };
        /** @description The file is neither an image nor a recording */
        400: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The signed in user has no such file */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The AI features are turned off in the settings */
        409: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description The user had too many texts discovered at OpenRouter lately; a known text does not count */
        429: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description OpenRouter could not read the file */
        502: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
        /** @description No OpenRouter API key is set in the settings */
        503: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/files/{id}': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /** The content of a stored file */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the file */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The content with its type */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The signed in user has no such file */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/files/{id}/thumbnail': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The thumbnail of a stored image or recording
     * @description A JPEG of at most 480 pixels on the longer side, of a recording its waveform; made when it is first asked for.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path: {
          /** @description The id of the file */
          id: number;
        };
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The thumbnail */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content?: never;
        };
        /** @description The signed in user has no such file, or it is neither an image nor a recording that can be read */
        404: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['ApiErrorResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
  '/api/v1/deployment': {
    parameters: {
      query?: never;
      header?: never;
      path?: never;
      cookie?: never;
    };
    /**
     * The settings of the deployment
     * @description The name, the dns mode, the root domain, the user mode, the admin, the mail server with its password and the acme ca the deployment runs with, so sakctl can deploy it again with them. Only for a request signed by sakctl, no session.
     */
    get: {
      parameters: {
        query?: never;
        header?: never;
        path?: never;
        cookie?: never;
      };
      requestBody?: never;
      responses: {
        /** @description The settings of the deployment */
        200: {
          headers: {
            [name: string]: unknown;
          };
          content: {
            'application/json': components['schemas']['DeploymentResponse'];
          };
        };
      };
    };
    put?: never;
    post?: never;
    delete?: never;
    options?: never;
    head?: never;
    patch?: never;
    trace?: never;
  };
}
export type webhooks = Record<string, never>;
export interface components {
  schemas: {
    /** InfoResponse */
    InfoResponse: {
      name: string;
      /** @enum {string} */
      dnsMode: 'domain' | 'subdomain';
      /** @enum {string} */
      userMode: 'single' | 'multi';
      version?: string | null;
    };
    /** LoginRequest */
    LoginRequest: {
      email: string;
      redirect?: string | null;
      /** @enum {string} */
      client?: 'web' | 'grocery-android' | 'notes-android' | 'todo-android';
      nonce?: string | null;
    };
    /** ApiErrorMessage */
    ApiErrorMessage: {
      code: string;
      message: string;
    };
    /** ApiErrorResponse */
    ApiErrorResponse: {
      errors: components['schemas']['ApiErrorMessage'][];
    };
    /** ExchangeRequest */
    ExchangeRequest: {
      token: string;
      nonce?: string | null;
    };
    /** ExchangeResponse */
    ExchangeResponse: {
      sessionToken: string;
      email: string;
      expiresAt: string;
      roles: string[];
      redirect: string;
    };
    /** PasswordLoginRequest */
    PasswordLoginRequest: {
      password: string;
      redirect?: string | null;
    };
    /** ConnectExchangeRequest */
    ConnectExchangeRequest: {
      token: string;
    };
    /** WhoAmIResponse */
    WhoAmIResponse: {
      username: string;
      name?: string | null;
      expiresAt?: string | null;
      roles: string[];
    };
    /** ConnectResponse */
    ConnectResponse: {
      link: string;
      id: string;
      expiresAt: string;
    };
    /** ConnectStatusResponse */
    ConnectStatusResponse: {
      used: boolean;
    };
    /** BucketStatisticsResponse */
    BucketStatisticsResponse: {
      computing: boolean;
      failed?: boolean;
      objects?: number | null;
      bytes?: number | null;
      computedAt?: string | null;
    };
    /** BucketResponse */
    BucketResponse: {
      name: string;
      domains: string[];
      subdomain?: string | null;
      createdAt: string;
      updatedAt?: string | null;
      statistics: components['schemas']['BucketStatisticsResponse'];
    };
    /** PaginationResponse */
    PaginationResponse: {
      offset: number;
      limit: number;
      total: number;
    };
    /** CreateBucketRequest */
    CreateBucketRequest: {
      name: string;
      domains?: string[];
    };
    /** UpdateDomainsRequest */
    UpdateDomainsRequest: {
      domains: string[];
    };
    /** BucketSecretResponse */
    BucketSecretResponse: {
      accessKeyId: string;
      secretAccessKey: string;
    };
    /** SiteResponse */
    SiteResponse: {
      id: string;
      domains: string[];
    };
    /** MetricsRangeResponse */
    MetricsRangeResponse: {
      key: string;
      label: string;
    };
    /** AccessLogsResponse */
    AccessLogsResponse: {
      site: components['schemas']['SiteResponse'];
      ranges: components['schemas']['MetricsRangeResponse'][];
      defaultRange: string;
    };
    /** StatusClassSeriesResponse */
    StatusClassSeriesResponse: {
      statusClass: string;
      values: number[];
    };
    /** StatusCodeSeriesResponse */
    StatusCodeSeriesResponse: {
      status: string;
      statusClass: string;
      values: number[];
    };
    /** UserAgentSeriesResponse */
    UserAgentSeriesResponse: {
      group: string;
      values: number[];
      requests?: number;
    };
    /** SiteMetricsResponse */
    SiteMetricsResponse: {
      site: string;
      range: string;
      start: string;
      end: string;
      stepSeconds: number;
      buckets: string[];
      series: components['schemas']['StatusClassSeriesResponse'][];
      statusCodes: components['schemas']['StatusCodeSeriesResponse'][];
      bytes: number[];
      totalRequests: number;
      errorRequests: number;
      errorRate: number;
      totalBytes: number;
      userAgents: components['schemas']['UserAgentSeriesResponse'][];
    };
    /** LogEntryResponse */
    LogEntryResponse: {
      time: string;
      host: string;
      path: string;
      status?: number | null;
    };
    /** RedirectTarget.Bucket */
    'RedirectTarget.Bucket': {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      type: 'bucket';
      bucket: string;
    };
    /** RedirectTarget.Domain */
    'RedirectTarget.Domain': {
      /**
       * @description discriminator enum property added by openapi-typescript
       * @enum {string}
       */
      type: 'domain';
      domain: string;
    };
    /** RedirectTarget */
    RedirectTarget:
      | components['schemas']['RedirectTarget.Bucket']
      | components['schemas']['RedirectTarget.Domain'];
    /** RedirectResponse */
    RedirectResponse: {
      sourceDomain: string;
      target: components['schemas']['RedirectTarget'];
      location?: string | null;
      createdAt: string;
      updatedAt?: string | null;
    };
    /** CreateRedirectRequest */
    CreateRedirectRequest: {
      sourceDomain: string;
      target: components['schemas']['RedirectTarget'];
    };
    /** UpdateRedirectRequest */
    UpdateRedirectRequest: {
      target: components['schemas']['RedirectTarget'];
    };
    /** LabelResponse */
    LabelResponse: {
      id: number;
      name: string;
      createdAt: string;
      updatedAt?: string | null;
    };
    /** LabelRequest */
    LabelRequest: {
      name: string;
    };
    /** NotesSettingsResponse */
    NotesSettingsResponse: {
      discoverText: boolean;
    };
    /** PhotoResponse */
    PhotoResponse: {
      id: number;
      url: string;
      thumbnailUrl: string;
    };
    /** RecordingResponse */
    RecordingResponse: {
      id: number;
      url: string;
      thumbnailUrl: string;
    };
    /** NoteResponse */
    NoteResponse: {
      id: number;
      text: string;
      /** @enum {string} */
      color: 'yellow' | 'pink' | 'blue' | 'green' | 'orange';
      labels: components['schemas']['LabelResponse'][];
      photos: components['schemas']['PhotoResponse'][];
      recordings: components['schemas']['RecordingResponse'][];
      createdAt: string;
      updatedAt?: string | null;
    };
    /** NoteRequest */
    NoteRequest: {
      text?: string;
      color?: string;
      labels?: string[];
      photos?: number[];
      recordings?: number[];
    };
    /** GrocerySettingsResponse */
    GrocerySettingsResponse: {
      discoverItems: boolean;
    };
    /** GroceryDiscoveredItem */
    GroceryDiscoveredItem: {
      name: string;
      amount?: number | null;
      unit?: string | null;
    };
    /** GroceryDiscoveredResponse */
    GroceryDiscoveredResponse: {
      items: components['schemas']['GroceryDiscoveredItem'][];
    };
    /** GroceryListResponse */
    GroceryListResponse: {
      id: number;
      name: string;
      shared: boolean;
      mine: boolean;
      items: number;
      icon?: string | null;
      createdAt: string;
      updatedAt?: string | null;
    };
    /** GroceryListRequest */
    GroceryListRequest: {
      name: string;
      shared?: boolean;
      icon?: string | null;
    };
    /** GroceryItemResponse */
    GroceryItemResponse: {
      id: number;
      listId: number;
      name: string;
      amount?: number | null;
      unit?: string | null;
      categoryId?: number | null;
      createdAt: string;
      updatedAt?: string | null;
      photoId?: number | null;
      checkedAt?: string | null;
    };
    /** GrocerySuggestionResponse */
    GrocerySuggestionResponse: {
      id: number;
      name: string;
      amount?: number | null;
      unit?: string | null;
      categoryId?: number | null;
    };
    /** GroceryCategoryResponse */
    GroceryCategoryResponse: {
      id: number;
      name: string;
      icon?: string | null;
    };
    /** GroceryCategoryRequest */
    GroceryCategoryRequest: {
      name: string;
      icon?: string | null;
    };
    /** GroceryCategoryOrderRequest */
    GroceryCategoryOrderRequest: {
      ids: number[];
    };
    /** GroceryNewItemRequest */
    GroceryNewItemRequest: {
      listId: number;
      name: string;
      amount?: number | null;
      unit?: string | null;
      categoryId?: number | null;
    };
    /** GroceryItemRequest */
    GroceryItemRequest: {
      name: string;
      amount?: number | null;
      unit?: string | null;
      categoryId?: number | null;
    };
    /** GroceryItemCategoryRequest */
    GroceryItemCategoryRequest: {
      categoryId?: number | null;
    };
    /** GroceryPhotoRequest */
    GroceryPhotoRequest: {
      fileId: number;
    };
    /** TodoListResponse */
    TodoListResponse: {
      id: number;
      name: string;
      shared: boolean;
      mine: boolean;
      items: number;
      icon?: string | null;
      createdAt: string;
      updatedAt?: string | null;
    };
    /** TodoListRequest */
    TodoListRequest: {
      name: string;
      shared?: boolean;
      icon?: string | null;
    };
    /** TodoItemResponse */
    TodoItemResponse: {
      id: number;
      listId: number;
      title: string;
      createdAt: string;
      updatedAt?: string | null;
      dueDate?: string | null;
      /** @enum {string} */
      priority: 'high' | 'normal' | 'low';
      doneAt?: string | null;
    };
    /** TodoPurgeRequest */
    TodoPurgeRequest: {
      itemIds: number[];
    };
    /** TodoNewItemRequest */
    TodoNewItemRequest: {
      listId: number;
      title: string;
      dueDate?: string | null;
      priority?: string | null;
    };
    /** TodoPriorityRequest */
    TodoPriorityRequest: {
      priority: string;
    };
    /** TodoDueDateRequest */
    TodoDueDateRequest: {
      dueDate?: string | null;
    };
    /** TodoDoneRequest */
    TodoDoneRequest: {
      done: boolean;
    };
    /** UserResponse */
    UserResponse: {
      id?: number | null;
      email: string;
      name?: string | null;
      configured: boolean;
    };
    /** UserRequest */
    UserRequest: {
      email: string;
      name?: string | null;
    };
    /** AppLinkRequest */
    AppLinkRequest: {
      email: string;
    };
    /** SettingRequirementResponse */
    SettingRequirementResponse: {
      scope: string;
      setting: string;
    };
    /** SettingResponse */
    SettingResponse: {
      name: string;
      label: string;
      description: string;
      /** @enum {string} */
      type: 'boolean' | 'text';
      default: string | boolean | number;
      maxLength?: number | null;
      mask?: number | null;
      readOnly: boolean;
      requires?: components['schemas']['SettingRequirementResponse'] | null;
    };
    /** SettingScopeResponse */
    SettingScopeResponse: {
      name: string;
      label: string;
      description: string;
      settings: components['schemas']['SettingResponse'][];
      enabledBy?: string | null;
    };
    /** JsonElement */
    JsonElement: Record<string, never>;
    /** HealthDetailResponse */
    HealthDetailResponse: {
      name: string;
      /** @enum {string} */
      state: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN';
      message?: string | null;
    };
    /** HealthStatusResponse */
    HealthStatusResponse: {
      name: string;
      /** @enum {string} */
      state: 'HEALTHY' | 'UNHEALTHY' | 'UNKNOWN';
      message?: string | null;
      details: components['schemas']['HealthDetailResponse'][];
    };
    /** FileResponse */
    FileResponse: {
      id: number;
      contentType: string;
      size: number;
      url: string;
      thumbnailUrl: string;
      createdAt: string;
    };
    /** DiscoverTextResponse */
    DiscoverTextResponse: {
      text: string;
    };
    /** SmtpConfigResponse */
    SmtpConfigResponse: {
      host: string;
      port: number;
      username?: string | null;
      passwordSet: boolean;
      password?: string | null;
      from: string;
      tls: boolean;
      starttls: boolean;
    } | null;
    /** DeploymentResponse */
    DeploymentResponse: {
      name: string;
      dnsMode: string;
      rootDomain: string;
      userMode: string;
      adminEmail?: string | null;
      smtp?: components['schemas']['SmtpConfigResponse'];
      acmeCa?: string | null;
    };
  };
  responses: never;
  parameters: never;
  requestBodies: never;
  headers: never;
  pathItems: never;
}
export type $defs = Record<string, never>;
export type operations = Record<string, never>;
