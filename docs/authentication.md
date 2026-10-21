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
| `--public-url`    | The server as clients reach it, without a path. Required with `--oidc-issuer`.                       |
| `--oidc-audience` | What an access token must carry in `aud`. Default: the resource, `<public url>/mcp`.                 |
| `--oidc-scope`    | A scope an access token must carry. Default: none.                                                   |

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
3. **Dynamic client registration.** Two anonymous policies of *Clients → Client registration* refuse it by default:
   - *Trusted Hosts*: add the hosts of the clients' redirect URIs (such as `localhost`), with *Client URIs Must Match*
     on;
   - *Allowed Client Scopes*: allow `atlas.read` and `offline_access`. This is how a registered client gets `atlas.read`.

   Opening them lets anyone who reaches Keycloak register a client. They still have to sign in as a user of the realm
   to get a token.

Then run the server with `--oidc-issuer https://<keycloak>/realms/<realm>`.
