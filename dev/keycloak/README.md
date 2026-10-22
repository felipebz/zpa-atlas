# Keycloak for a local authentication test

For testing `serve --oidc-issuer` on one machine, not for users. It creates the realm `atlas` with:

- the client scope `atlas.read`, on no realm list, whose audience mapper puts `http://localhost:8090/mcp` in `aud`; it
  goes only into the tokens of users with the realm role `atlas-reader`;
- the user `ana`, password `ana`, with `atlas-reader`, and the user `bob`, password `bob`, without it;
- the page's confidential client `atlas-web`, secret `atlas-web-secret`, redirect URI `http://localhost:8090/auth/callback`;
- the public client `atlas-mcp`, with `atlas.read` as a default scope, redirect URI `http://localhost:8765/callback`, PKCE S256;
- the anonymous client registration policies opened for clients on `localhost`.

Run again, it deletes the realm and creates it anew.

```bash
podman run -d --name keycloak-atlas -p 8180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.4 start-dev
# the admin token is asked over http: let the master realm accept it
podman exec keycloak-atlas /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password admin
podman exec keycloak-atlas /opt/keycloak/bin/kcadm.sh update realms/master -s sslRequired=NONE
python3 dev/keycloak/setup-realm.py

zpa-atlas serve --db atlas.db --port 8090 \
  --oidc-issuer http://localhost:8180/realms/atlas --public-url http://localhost:8090 --oidc-scope atlas.read \
  --oidc-client-id atlas-web --oidc-client-secret-env ATLAS_WEB_SECRET   # with ATLAS_WEB_SECRET=atlas-web-secret
```

`KEYCLOAK_URL` and `ATLAS_PUBLIC_URL` change the Keycloak address and the server's public URL.

Under a base path, behind a reverse proxy at `http://localhost:8088/atlas/` (see the main README), give the realm the
public URL with its path, and the server the same:

```bash
ATLAS_PUBLIC_URL=http://localhost:8088/atlas python3 dev/keycloak/setup-realm.py
zpa-atlas serve --db atlas.db --port 8090 --base-path /atlas \
  --oidc-issuer http://localhost:8180/realms/atlas --public-url http://localhost:8088/atlas --oidc-scope atlas.read \
  --oidc-client-id atlas-web --oidc-client-secret-env ATLAS_WEB_SECRET
```

The realm then registers `http://localhost:8088/atlas/auth/callback`, the post logout redirect URI
`http://localhost:8088/atlas`, and the audience `http://localhost:8088/atlas/mcp`.
