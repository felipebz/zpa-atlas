# Authentication

With `--oidc-issuer`, `zpa-atlas serve` is an OAuth resource server. `/mcp` (every method) and `/api/*` answer only
requests that carry an access token issued by that OpenID Connect provider for this server. MCP clients that follow the
[MCP authorization spec](https://modelcontextprotocol.io/specification/latest/basic/authorization) find the provider
and sign in by themselves. The static page files stay open: they hold no data.

```sh
zpa-atlas serve --db atlas.db --host 0.0.0.0 \
  --oidc-issuer https://id.example.com/realms/atlas \
  --public-url https://atlas.example.com \
  --oidc-scope atlas.read
```

| Option            | Meaning                                                                                              |
|-------------------|------------------------------------------------------------------------------------------------------|
| `--oidc-issuer`   | The provider's issuer URL. Turns authentication on.                                                  |
| `--public-url`    | The server as clients reach it, without a path but the base path. Required with `--oidc-issuer`.     |
| `--oidc-audience` | What an access token must carry in `aud`. Default: the resource, `<public url>/mcp`.                 |
| `--oidc-scope`    | A scope an access token must carry. Default: none.                                                   |
| `--oidc-client-id` | The page's client id. Turns the page sign-in on.                                                    |
| `--oidc-client-secret-env` | The environment variable holding that client's secret. Default: `ZPA_ATLAS_OIDC_CLIENT_SECRET`; unset, the client is public. |

Each option can also be an environment variable: `ZPA_ATLAS_OIDC_ISSUER`, `ZPA_ATLAS_PUBLIC_URL`, `ZPA_ATLAS_OIDC_AUDIENCE`,
`ZPA_ATLAS_OIDC_SCOPE`, `ZPA_ATLAS_OIDC_CLIENT_ID`; the command line wins.

At startup the server reads `<issuer>/.well-known/openid-configuration`. If it cannot, it stops with exit code 3: it
never starts without the authentication it was asked for. Both URLs must be `https`, except on `localhost`, `127.0.0.1`
or `[::1]`, for a local run. For TLS, put a proxy in front that passes the `Host` header. The host of the public URL is
added to the `Host` names `/mcp` accepts.

## What the server checks

An access token is accepted when:

- it is read from the `Authorization: Bearer` header. A token in the URL (`?access_token=`) is never read;
- it is a JWT signed with RS256, RS384, RS512, ES256, ES384 or ES512 by a key of the issuer's JWKS. `none` and `HS*`
  are refused;
- `iss` is the issuer, and `aud` holds the audience;
- it has an `exp` that has not passed, with one minute of clock skew;
- it does not say it is something other than an access token, in a `typ` header or a `token_use` claim;
- with `--oidc-scope`, the scope is in `scope` (a space-separated string) or `scp` (a string or a list).

The answers:

- **No valid token:** 401 with
  `WWW-Authenticate: Bearer resource_metadata="<public url>/.well-known/oauth-protected-resource/mcp"`, followed by
  `, scope="<scope>"` when `--oidc-scope` is set, and a JSON `{"error": …}` body.
- **A valid token without the required scope:** 403 with
  `WWW-Authenticate: Bearer error="insufficient_scope", scope="<scope>", resource_metadata="…"`.
- **The provider's keys cannot be read:** 503 with a short message.

The token is never logged.

## Signing in to the page

With `--oidc-client-id`, a person signs in to the page with the same provider. The server runs the authorization code
flow, with PKCE S256, `state` and `nonce`, and keeps the tokens: the browser holds only a session id, in a cookie.

- **The client:** registered at the provider for the page, with the redirect URI `<public url>/auth/callback`. A
  confidential client gives its secret in the environment variable `ZPA_ATLAS_OIDC_CLIENT_SECRET`, or in the one named by
  `--oidc-client-secret-env`, never on the command line; without it, the client is public and uses PKCE alone. The server refuses to start when the client
  id is the audience.
- **Signing in:** `/auth/login?return=<path>` sends the browser to the provider, asking for `openid` and the required
  scope. Back at the callback, the access token the provider gave is checked as a bearer token is: issuer, audience,
  expiry, scope. A person whose token would be refused on `/mcp` gets a short error page and no session. The ID token
  must be for the client and carry the nonce of the sign-in.
- **The session:** the cookie `atlas_session`, `HttpOnly`, `SameSite=Lax`, `Path=/` (the base path, under one), and `Secure` when the public URL is
  `https`. When the access token expires, the server refreshes it with the refresh token; without one, or when the
  provider refuses, the session ends. Sessions are kept in memory: **a restart signs everyone out.**
- **Where the session counts:** `/api` accepts it, as it accepts a bearer token. `/mcp` never does: it takes only a bearer
  token.
- **Signing out:** the page's "Sign out" button posts to `/auth/logout`, which is accepted only with an `Origin` or a
  `Referer` of the public URL. The session ends; when the provider has an `end_session_endpoint`, the browser goes there
  with `id_token_hint` and `post_logout_redirect_uri` set to the public URL, so that it comes back signed out.
- **`/auth/me`** answers the signed-in user's name, or 401. Without the page sign-in it answers 404, as do the other
  `/auth` paths.

## The metadata document

`<public url>/.well-known/oauth-protected-resource/mcp` is served without a token (RFC 9728):

```json
{
  "resource": "https://atlas.example.com/mcp",
  "authorization_servers": ["https://id.example.com/realms/atlas"],
  "scopes_supported": ["atlas.read"],
  "bearer_methods_supported": ["header"]
}
```

`scopes_supported` is present only with `--oidc-scope`.

## Under a base path

With `--base-path /atlas` (see the [README](../README.md#serving-under-a-base-path)), the public URL is the address
clients use, base path included, and the server refuses to start when its path is another one:

```sh
zpa-atlas serve --db atlas.db --host 0.0.0.0 --base-path /atlas \
  --oidc-issuer https://id.example.com/realms/atlas \
  --public-url https://atlas.example.com/atlas \
  --oidc-client-id atlas-web
```

Everything above then holds under the base path:

- **The resource and default audience** are `https://atlas.example.com/atlas/mcp`.
- **The metadata document** is at `https://atlas.example.com/atlas/.well-known/oauth-protected-resource/mcp`, and the
  `resource_metadata` of `WWW-Authenticate` points there. Its `resource` is the `/mcp` URL the client used.
- **At the provider,** register the page's redirect URI `https://atlas.example.com/atlas/auth/callback` and the post
  logout redirect URI `https://atlas.example.com/atlas`. The audience mapper gives `https://atlas.example.com/atlas/mcp`.
- **The cookies** have `Path=/atlas` (and `/atlas/auth` while a sign-in runs): another application on the host, or
  another atlas under `/atlas2`, never receives them. After sign-in the browser lands on the page it came from, under
  the base path; a return path outside it lands on the base path's home page.
- **A client that ignores the header.** RFC 9728 derives the metadata location from the resource by inserting the
  well-known path after the host: `https://atlas.example.com/.well-known/oauth-protected-resource/atlas/mcp`. That is
  outside the base path, so the server cannot answer it. The MCP authorization spec makes a client use the header's URL
  when there is one; for a client that does not, route the derived location to the server's document at the proxy:

  ```nginx
  location = /.well-known/oauth-protected-resource/atlas/mcp {
      proxy_pass http://127.0.0.1:8080/atlas/.well-known/oauth-protected-resource/mcp;
      proxy_set_header Host $http_host;
  }
  ```

## What the provider must give

- **An access token whose `aud` is exactly the audience:** `--oidc-audience`, or `<public url>/mcp` by default. Clients
  send the resource in a `resource` parameter, but some providers ignore it, so set the audience on the provider.
- **The scope,** when one is required.
- **The audience is never a client id.** An ID token's `aud` is the client id of the application that signed the user
  in, so the audience check is what keeps an ID token from being accepted as an access token.

## What a client needs

A client finds the provider in the metadata document, then gets a client id in one of two ways:

- **Dynamic client registration,** if the provider allows it.
- **A client registered by hand:** a public client with the authorization code flow, PKCE S256, and the redirect URI
  the client uses (often `http://localhost:<port>/callback`). The client is then given that client id and port.

Clients may also ask for `offline_access`, to get a refresh token: allow it on the provider.

## Example: Keycloak

In the realm:

1. **The scope and the audience.** Create a client scope `atlas.read` (protocol OpenID Connect, *Include in token
   scope* on). Add a mapper *By configuration → Audience* to it, with *Included Custom Audience* set to
   `<public url>/mcp` and *Add to access token* on. Do not add it to the realm's default or optional client scopes:
   a realm default goes into the tokens of every client, and a realm optional scope can be asked for by any client.
   **Every client given `atlas.read` gets tokens the server accepts,** so give it only to the clients meant for it.
2. **A client registered by hand.** Create a client with *Client authentication* off (public), *Standard flow* on,
   *Valid redirect URIs* set to the client's redirect URI, and *Advanced → Proof Key for Code Exchange Code Challenge
   Method* set to `S256`. Add `atlas.read` to its default client scopes, and make sure `offline_access` is among its
   optional client scopes.
3. **The page's client.** Create a client with *Standard flow* on, *Valid redirect URIs* set to
   `<public url>/auth/callback`, *Valid post logout redirect URIs* set to `<public url>`, and PKCE method `S256`. Turn
   *Client authentication* on for a confidential client, and give its secret to the server in the environment variable
   that `--oidc-client-secret-env` names. Add `atlas.read` to its default client scopes.
4. **Dynamic client registration.** Two anonymous policies of *Clients → Client registration* refuse it by default:
   - *Trusted Hosts*: add the hosts of the clients' redirect URIs (such as `localhost`), with *Client URIs Must Match*
     on;
   - *Allowed Client Scopes*: allow `atlas.read` and `offline_access`. This is how a registered client gets `atlas.read`.

   Opening them lets anyone who reaches Keycloak register a client. They still have to sign in as a user of the realm
   to get a token.

To let only some users read the index, give the client scope `atlas.read` a role scope mapping (its *Scope* tab), such
as a realm role `atlas-reader`: Keycloak then puts the scope, and its audience, only into the tokens of users with that
role. Other users get the error page when they sign in to the page, and 401 or 403 on `/mcp`.

Then run the server with `--oidc-issuer https://<keycloak>/realms/<realm>`. For the page, add
`--oidc-client-id <client id>` and, for a confidential client, `--oidc-client-secret-env <variable>`.
