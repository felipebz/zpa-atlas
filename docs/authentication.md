# Set up authentication

With authentication on, `/mcp` and `/api` answer only signed-in users: each request carries an access token issued for
this server. People can sign in to the page too. The page's static files stay open: they hold no index data.

At the provider you set up three things:

- a scope, `atlas.read`, that puts the server's audience in the token;
- a client for the page (optional);
- a separate client for the agents.

The server refuses to start when an option is wrong or when it cannot read the provider's discovery document. It never
runs without the authentication it was asked for.

The examples use these names. Replace them with yours.

| What | Example |
|---|---|
| Issuer (the realm's URL) | `https://id.example.com/realms/atlas` |
| Public URL, with the base path | `https://atlas.example.com/atlas` |
| Audience: the public URL plus `/mcp` | `https://atlas.example.com/atlas/mcp` |
| Scope | `atlas.read` |
| The page's client | `atlas-web` |
| The agents' client | `atlas-agent` |
| The agents' callback port | `8765` |

Without a base path, drop `/atlas` everywhere: the public URL is `https://atlas.example.com` and the audience is
`https://atlas.example.com/mcp`.

## 1. Set up the provider (Keycloak)

Open the Keycloak admin console and select your realm. The field names are those of Keycloak 26.4.

### Step 1: the scope `atlas.read` and its audience

1. Go to **Client scopes → Create client scope** and fill in:

   | Field | Value |
   |---|---|
   | Name | `atlas.read` |
   | Type | `None` |
   | Protocol | `OpenID Connect` |
   | Include in token scope | **On** (the form starts with Off) |

   Save.
2. Open its **Mappers** tab. Choose **Configure a new mapper → Audience** (on a scope that already has mappers:
   **Add mapper → By configuration → Audience**) and fill in:

   | Field | Value |
   |---|---|
   | Name | `atlas-audience` |
   | Included Client Audience | empty |
   | Included Custom Audience | `https://atlas.example.com/atlas/mcp` |
   | Add to ID token | Off |
   | Add to access token | On |

   Save.

The audience is the public URL plus `/mcp`, **base path included**. The server accepts only access tokens whose `aud`
holds it. MCP clients send it in a `resource` parameter, but Keycloak ignores that parameter: the mapper is what puts it
in the token.

**Keep `Type` at `None`.** Do not add `atlas.read` to the realm's default or optional client scopes. A realm default
goes into the tokens of every client. A realm optional scope can be asked for by any client. Every client given
`atlas.read` gets tokens the server accepts, so give it only to the two clients below.

### Step 2: the page's client (optional)

Skip this step if only agents will connect.

Go to **Clients → Create client**. The wizard has three pages:

| Page | Field | Value |
|---|---|---|
| General settings | Client type | `OpenID Connect` |
| | Client ID | `atlas-web` |
| Capability config | Client authentication | On (confidential) |
| | Standard flow | On |
| | Direct access grants | Off |
| | PKCE Method | `S256` |
| Login settings | Valid redirect URIs | `https://atlas.example.com/atlas/auth/callback` |
| | Valid post logout redirect URIs | `https://atlas.example.com/atlas` |

Save. Then:

1. In its **Client scopes** tab, choose **Add client scope**, select `atlas.read` and add it as **Default**.
2. In its **Credentials** tab, copy the **Client Secret**. The server reads it from an environment variable (see
   [Run the server](#2-run-the-server)).

### Step 3: the agents' client

Create **one** public client for everyone who uses an agent. Do not reuse the page's client:

- the page's client keeps its secret on the server; an agent runs on each person's computer and has nowhere to keep a
  secret;
- an agent receives the sign-in on its own computer, at a `localhost` redirect URI that the page's client must not
  accept.

Go to **Clients → Create client**:

| Page | Field | Value |
|---|---|---|
| General settings | Client type | `OpenID Connect` |
| | Client ID | `atlas-agent` |
| Capability config | Client authentication | **Off** (public, no secret) |
| | Standard flow | On |
| | Direct access grants | Off |
| | PKCE Method | `S256` |
| Login settings | Valid redirect URIs | `http://localhost:8765/callback` |

Save. Then, in its **Client scopes** tab:

1. **Add client scope → `atlas.read` → Default.**
2. Check that `offline_access` is listed as **Optional** (new clients get it from the realm; add it otherwise). An agent
   that asks for it gets a refresh token that outlives the browser session.

**The redirect URI** is where the MCP client listens for the sign-in answer: `http://localhost:<port>/callback`, with
the port, path and host name the client uses. Pick one port and set it in everyone's MCP client. For another port, add
that exact URI too. Prefer exact URIs to a wildcard. `localhost` and `127.0.0.1` are different hosts to Keycloak.

### Step 4: who may read (optional)

By default every user of the realm can read the index. To allow only some:

1. **Realm roles → Create role:** `atlas-reader`.
2. **Client scopes → `atlas.read` → Scope → Assign role:** `atlas-reader`.
3. Give the role to each reader: **Users → (user) → Role mapping → Assign role**, or through a group.

Keycloak then applies `atlas.read`, and its audience mapper, only to users with the role. Other users can still sign in
to Keycloak, but their tokens lack the audience: the page shows *Sign-in refused* and `/mcp` answers 401.

## 2. Run the server

Put a reverse proxy in front for TLS. It passes the `Host` header and the path unchanged: see the
[nginx example](serving.md#serving-under-a-base-path).

Set the page client's secret, then start the server under `/atlas`:

```sh
export ATLAS_WEB_SECRET='<the Client Secret of atlas-web>'
zpa-atlas serve --db atlas.db --base-path /atlas \
  --oidc-issuer https://id.example.com/realms/atlas \
  --public-url https://atlas.example.com/atlas \
  --oidc-scope atlas.read \
  --oidc-client-id atlas-web --oidc-client-secret-env ATLAS_WEB_SECRET
```

Without a base path:

```sh
zpa-atlas serve --db atlas.db \
  --oidc-issuer https://id.example.com/realms/atlas \
  --public-url https://atlas.example.com \
  --oidc-scope atlas.read \
  --oidc-client-id atlas-web --oidc-client-secret-env ATLAS_WEB_SECRET
```

For agents only, leave out `--oidc-client-id`, `--oidc-client-secret-env` and the secret.

The server binds `127.0.0.1:8080` by default; set `--host` and `--port` for a proxy that reaches it another way. At
startup it prints what it checks. Compare the audience with the mapper's:

```
Authentication: /mcp and /api need an access token of https://id.example.com/realms/atlas, for audience https://atlas.example.com/atlas/mcp and scope atlas.read
Protected resource metadata: https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp
Page sign-in: client atlas-web (confidential), redirect URI https://atlas.example.com/atlas/auth/callback
```

Then open the public URL and sign in. The page shows your name at the top right.

| Option | Meaning | Default |
|---|---|---|
| `--oidc-issuer` | The provider's issuer URL. Turns authentication on. | none: no authentication |
| `--public-url` | The server as clients reach it: scheme, host, port and exactly the base path, nothing else. | required with `--oidc-issuer` |
| `--oidc-audience` | What an access token must carry in `aud`. Never a client id. | `<public url>/mcp` |
| `--oidc-scope` | A scope an access token must carry: one word, no spaces or quotes. | none |
| `--oidc-client-id` | The page's client id. Turns the page sign-in on. | none: no page sign-in |
| `--oidc-client-secret-env` | The name of the variable holding the page client's secret. | `ZPA_ATLAS_OIDC_CLIENT_SECRET` |
| `--base-path` | The path everything is served under. | none: the root |

**Variables.** Every option of `serve` can come from the environment: `ZPA_ATLAS_` and the option's name in upper case,
`-` as `_`. For example `ZPA_ATLAS_OIDC_ISSUER`, `ZPA_ATLAS_PUBLIC_URL`, `ZPA_ATLAS_OIDC_AUDIENCE`,
`ZPA_ATLAS_OIDC_SCOPE`, `ZPA_ATLAS_OIDC_CLIENT_ID`, `ZPA_ATLAS_OIDC_CLIENT_SECRET_ENV` and `ZPA_ATLAS_BASE_PATH`. The
command line wins; a blank variable counts as unset.

**The secret** is never an option, so it never shows in a process list:

- With `--oidc-client-secret-env NAME`, the secret is in `NAME`, which must be set.
- Without it, the server reads `ZPA_ATLAS_OIDC_CLIENT_SECRET`. Unset, the page's client is public and uses PKCE alone.

**The server refuses to start:**

- with exit code 2 when an option is wrong: an OIDC option without `--oidc-issuer`, no `--public-url`, a URL that is
  not `https` (only `localhost`, `127.0.0.1` and `[::1]` may use `http`), a public URL whose path is not the base path,
  a page client id equal to the audience, `--oidc-client-secret-env` without `--oidc-client-id`, or a named secret
  variable that is not set;
- with exit code 3 when it cannot read `<issuer>/.well-known/openid-configuration`, after three attempts two seconds
  apart.

## 3. Connect an agent

Give the MCP client three things. Where they go depends on the client: look for its OAuth settings.

| Setting | Value |
|---|---|
| Server URL | `https://atlas.example.com/atlas/mcp` |
| OAuth client id | `atlas-agent` (no secret) |
| Callback port | `8765`, so that its redirect URI is the one registered in Step 3 |

What happens next:

1. The client calls `/mcp` and gets a 401. Its `WWW-Authenticate` header points to the server's metadata document.
2. The client reads the metadata, which names the provider, then reads the provider's own metadata.
3. It opens the browser at Keycloak's sign-in page. The person signs in.
4. Keycloak sends the browser back to `http://localhost:8765/callback`. The client trades the code for a token, with
   PKCE, and calls `/mcp` again with it.

If the client has nowhere to put a client id, it can only register itself: see
[Dynamic client registration](#5-dynamic-client-registration-optional).

## 4. When something goes wrong

The page shows the reason a token was refused; the server's log does not. When an agent gets 401 or 403, sign in to the
page with the same user: it shows the same checks.

| What the person sees | Cause | Fix |
|---|---|---|
| Keycloak shows "We are sorry... Invalid Request". Its log: `Parameter 'client_id' not present or present multiple times in the HTTP request parameters` | The MCP client has no client id. It tried to register itself and was refused, or it does not register. | Give the client `atlas-agent` as its client id. |
| Keycloak shows "We are sorry... Invalid parameter: redirect_uri". Its log: `error="invalid_redirect_uri"` with the URI it got | The client's callback URI (port, path or host) is not in `atlas-agent`'s **Valid redirect URIs**. | Set the client's port to the registered one, or add its exact URI. |
| The sign-in at Keycloak works, but `/mcp` answers 401 with the new token. The page shows *Sign-in refused*: "The access token the identity provider gave is not one this server accepts: it is not for https://atlas.example.com/atlas/mcp." | The token's `aud` is not the audience: the mapper is missing, has a URL without the base path, or `atlas.read` is not a default scope of the client. With Step 4, the user may lack the role. | Compare the mapper's **Included Custom Audience** with the audience the server printed. Check the client's scopes and the user's roles. |
| `/mcp` answers 403 with `error="insufficient_scope"`. The page shows *Sign-in refused*: "Your account is not allowed to read this index: the access token lacks the scope atlas.read." | The token has the audience but not the scope: **Include in token scope** is off on `atlas.read`. | Turn it on. |
| The page shows *Sign-in refused* with another reason | The sign-in itself failed. For example "The sign-in expired or was not started here. Sign in again." (more than ten minutes, or another browser), or "The identity provider did not give the tokens of the sign-in." (often a wrong client secret). | Sign in again; check the page client's secret and redirect URI. |
| The server stops with exit code 3: `Cannot read the OpenID Connect configuration of https://id.example.com/realms/atlas: …` | The server cannot read the provider's discovery document. | Check the issuer URL, and that the server's machine reaches it (DNS, proxy, TLS certificates). |

## 5. Dynamic client registration (optional)

Prefer the `atlas-agent` client above. Read this only for an MCP client that cannot take a client id.

**What it is.** The client asks the provider for a client id of its own, with no administrator involved. Keycloak calls
it anonymous client registration. It lets **anyone who reaches Keycloak** create a client in the realm, so Keycloak
refuses it until two policies allow it. They are in **Clients → Client registration → Anonymous access polices**
(Keycloak's spelling):

- **Trusted Hosts.** Add to **Trusted Hosts** the address that **sends** the registration, as Keycloak sees it: each
  person's computer, or the proxy or NAT address in front of them. Trusting `localhost` is not enough when Keycloak runs
  on another machine. With **Client URIs Must Match** on, the hosts of the client's redirect URIs (`localhost`) must
  be trusted as well. A refused registration is answered with
  `Policy 'Trusted Hosts' rejected request to client-registration service. Details: Host not trusted.`, and
  Keycloak's log names the address it refused, in `ipAddress="…"` of its `CLIENT_REGISTER_ERROR` event.
- **Allowed Client Scopes.** Add `atlas.read` and `offline_access`. Otherwise:
  `Policy 'Allowed Client Scopes' rejected request to client-registration service. Details: Not permitted to use specified clientScope`.
  A registered client gets them as **optional** scopes, so the MCP client must ask for `atlas.read` (it is in the
  server's metadata). The default **Consent Required** policy also makes Keycloak ask each person to approve the client.

When a registration is refused, some clients still send the browser to Keycloak, without a client id: that is the
"Invalid Request" row of the table above.

**Why it is not an open door.** A registered client gets nothing by itself. It needs a person to sign in as a user of the
realm, through the authorization code flow, and Step 4's role restriction still applies to that user.

See [Keycloak's client registration guide](https://www.keycloak.org/securing-apps/client-registration).

## Reference

URLs below use the example names; `/mcp` means `https://atlas.example.com/atlas/mcp`.

### What the server checks in an access token

- It comes in one `Authorization: Bearer` header. A token in the URL (`?access_token=`) is never read. The token is never
  logged.
- It is a JWT signed with RS256, RS384, RS512, ES256, ES384 or ES512 by a key of the issuer's JWKS. `none` and `HS*` are
  refused.
- `iss` is the issuer, and `aud` holds the audience.
- It has an `exp` that has not passed, with one minute of clock skew.
- It does not say it is something else than an access token: when present, `typ` is `JWT`, `at+jwt` or `Bearer`, and
  `token_use` is `access` or `access_token`.
- With `--oidc-scope`, the scope is in `scope` (a space-separated string) or `scp` (a string or a list).

The audience is never a client id: an ID token's `aud` is the client id of the application that signed the user in, so
the audience check is what keeps an ID token from passing as an access token.

### HTTP answers

| Case | Status | `WWW-Authenticate` | Body `{"error": …}` |
|---|---|---|---|
| No valid token | 401 | `Bearer resource_metadata="https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp", scope="atlas.read"` (no `scope` without `--oidc-scope`) | `Unauthorized: an access token from https://id.example.com/realms/atlas is required` |
| A valid token without the scope | 403 | `Bearer error="insufficient_scope", scope="atlas.read", resource_metadata="…"` | `The access token lacks the scope atlas.read` |
| The provider's keys cannot be read | 503 | — | `The identity provider cannot be reached to check the access token; try again later` |

The host of the public URL is added to the `Host` names `/mcp` accepts.

### The page's session

- **Sign-in:** `/auth/login?return=<path>` sends the browser to the provider with the authorization code flow, PKCE
  S256, `state` and `nonce`, asking for `openid` and the scope. Back at `/auth/callback`, the access token is checked as
  a bearer token is (issuer, audience, expiry, scope), and the ID token must be for the page's client and carry the
  sign-in's nonce. A refused sign-in gets the *Sign-in refused* page and no session.
- **Cookies:** the browser holds only a session id, in `atlas_session`: `HttpOnly`, `SameSite=Lax`, `Secure` when the
  public URL is `https`, `Path=/atlas` (`/` without a base path). While a sign-in runs, `atlas_login` (path
  `/atlas/auth`) binds it to the browser for ten minutes.
- **Refresh:** when the access token expires, the server refreshes it with the refresh token. Without one, when the
  provider refuses, or when the new token would be refused on `/mcp`, the session ends. When the provider cannot be
  reached, `/api` answers 503: `The identity provider cannot be reached to check the session; try again later`.
- **Limits:** sessions live in memory, so **a restart signs everyone out.** At most 10,000 sessions (the one unused the
  longest ends first) and 10,000 sign-ins in progress.
- **Where it counts:** `/api` accepts the session when the request has no `Authorization` header. `/mcp` never does: it
  takes only a bearer token.
- **Sign-out:** the page's button posts to `/auth/logout`, accepted only with an `Origin` or a `Referer` of the public
  URL (otherwise 403, `Sign out from the page of this server`). The session ends. When the provider has an
  `end_session_endpoint`, the browser goes there with `id_token_hint`, `client_id`, and `post_logout_redirect_uri` set
  to the public URL, so that it comes back signed out.
- **`/auth/me`** answers `{"name": "…"}` for the signed-in user, or 401 `Not signed in`. Without `--oidc-client-id`,
  `/auth/me` and the other `/auth` paths answer 404.

### The metadata document

`/.well-known/oauth-protected-resource/mcp`, under the public URL, is served without a token (RFC 9728), with
`Cache-Control: no-store`:

```json
{
  "resource": "https://atlas.example.com/atlas/mcp",
  "authorization_servers": ["https://id.example.com/realms/atlas"],
  "scopes_supported": ["atlas.read"],
  "bearer_methods_supported": ["header"]
}
```

`scopes_supported` is present only with `--oidc-scope`.

### Under a base path

- The public URL ends with the base path; the server refuses to start when its path is another one. Everything built
  from it carries the base path: the audience, the metadata document, the redirect and post-logout URIs.
- The cookies' paths are the base path, so another application on the host, or another atlas under `/atlas2`, never
  receives them. After sign-in, the browser lands on the page it came from; a return path outside the base path lands
  on the base path's home page.
- **A client that ignores the header.** RFC 9728 derives the metadata location from the resource by inserting the
  well-known path after the host: `https://atlas.example.com/.well-known/oauth-protected-resource/atlas/mcp`. That is
  outside the base path, so the server cannot answer it. The MCP authorization spec makes a client use the header's URL
  when there is one. For a client that does not, route the derived location to the server's document at the proxy:

  ```nginx
  location = /.well-known/oauth-protected-resource/atlas/mcp {
      proxy_pass http://127.0.0.1:8080/atlas/.well-known/oauth-protected-resource/mcp;
      proxy_set_header Host $http_host;
  }
  ```
