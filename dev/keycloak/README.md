# Keycloak for a local authentication test

For testing `serve --oidc-issuer` on one machine, not for users. It creates the realm `atlas` with:

- the client scope `atlas.read`, on no realm list, whose audience mapper puts `http://localhost:8090/mcp` in `aud`;
- the user `ana`, password `ana`;
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
  --oidc-issuer http://localhost:8180/realms/atlas --public-url http://localhost:8090 --oidc-scope atlas.read
```

`KEYCLOAK_URL` and `ATLAS_RESOURCE` change the Keycloak address and the audience.
