"""Creates the realm `atlas` for a local test of `serve --oidc-issuer`: see README.md."""
import json, os, urllib.request, urllib.parse
KC = os.environ.get("KEYCLOAK_URL", "http://localhost:8180")
PUBLIC_URL = os.environ.get("ATLAS_PUBLIC_URL", "http://localhost:8090")
RESOURCE = PUBLIC_URL + "/mcp"
def call(method, path, body=None, token=None, form=None):
    data = None; headers = {}
    if form is not None: data = urllib.parse.urlencode(form).encode(); headers["Content-Type"] = "application/x-www-form-urlencoded"
    elif body is not None: data = json.dumps(body).encode(); headers["Content-Type"] = "application/json"
    if token: headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(KC + path, data, headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read(); return r.status, (json.loads(raw) if raw else None), r.headers
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode(), e.headers
s, tok, _ = call("POST", "/realms/master/protocol/openid-connect/token", form={"grant_type": "password", "client_id": "admin-cli", "username": "admin", "password": "admin"})
T = tok["access_token"]
call("DELETE", "/admin/realms/atlas", None, T)
print("realm", call("POST", "/admin/realms", {"realm": "atlas", "enabled": True, "sslRequired": "none", "accessTokenLifespan": 600}, T)[0])
# the scope: named as the server's required scope, with the audience of the resource
s, _, h = call("POST", "/admin/realms/atlas/client-scopes", {
    "name": "atlas.read", "protocol": "openid-connect", "description": "Read the zpa-atlas index",
    "attributes": {"include.in.token.scope": "true", "display.on.consent.screen": "false"},
    "protocolMappers": [{"name": "atlas-audience", "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
        "config": {"included.custom.audience": RESOURCE, "access.token.claim": "true", "id.token.claim": "false", "introspection.token.claim": "true"}}]}, T)
scope_id = h["Location"].rsplit("/", 1)[1]; print("scope", s, scope_id)
# only users with the role atlas-reader get the scope, and with it the audience
print("role", call("POST", "/admin/realms/atlas/roles", {"name": "atlas-reader"}, T)[0])
role = call("GET", "/admin/realms/atlas/roles/atlas-reader", None, T)[1]
print("scope needs the role", call("POST", f"/admin/realms/atlas/client-scopes/{scope_id}/scope-mappings/realm", [role], T)[0])
for user, first, reader in [("ana", "Ana", True), ("bob", "Bob", False)]:
    s, _, h = call("POST", "/admin/realms/atlas/users", {"username": user, "enabled": True, "email": f"{user}@example.com", "emailVerified": True, "firstName": first, "lastName": "Souza",
        "credentials": [{"type": "password", "value": user, "temporary": False}]}, T)
    print("user", user, s)
    if reader:
        print(" atlas-reader", call("POST", f"/admin/realms/atlas/users/{h['Location'].rsplit('/', 1)[1]}/role-mappings/realm", [role], T)[0])
# the page's client: confidential, its secret given to serve through --oidc-client-secret-env
print("page client", call("POST", "/admin/realms/atlas/clients", {"clientId": "atlas-web", "publicClient": False, "secret": "atlas-web-secret",
    "standardFlowEnabled": True, "directAccessGrantsEnabled": False, "redirectUris": [PUBLIC_URL + "/auth/callback"],
    "attributes": {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": PUBLIC_URL},
    "defaultClientScopes": ["atlas.read", "profile", "email", "roles", "web-origins", "basic"]}, T)[0])
# a client registered by hand, for a client that does not register itself
print("client", call("POST", "/admin/realms/atlas/clients", {"clientId": "atlas-mcp", "publicClient": True, "standardFlowEnabled": True, "directAccessGrantsEnabled": False,
    "redirectUris": ["http://localhost:8765/callback"], "attributes": {"pkce.code.challenge.method": "S256"},
    "defaultClientScopes": ["atlas.read", "profile", "email", "roles", "web-origins", "basic"], "optionalClientScopes": ["offline_access"]}, T)[0])
# anonymous client registration: from clients on localhost, which get atlas.read through Allowed Client Scopes
s, comps, _ = call("GET", "/admin/realms/atlas/components?type=org.keycloak.services.clientregistration.policy.ClientRegistrationPolicy", None, T)
for c in comps:
    if c["providerId"] == "trusted-hosts" and c["subType"] == "anonymous":
        c["config"] = {"host-sending-registration-request-must-match": ["false"], "client-uris-must-match": ["true"], "trusted-hosts": ["localhost", "127.0.0.1"]}
        print("trusted hosts", call("PUT", f"/admin/realms/atlas/components/{c['id']}", c, T)[0])
    if c["providerId"] == "allowed-client-templates" and c["subType"] == "anonymous":
        c["config"] = {"allow-default-scopes": ["true"], "allowed-client-scopes": ["atlas.read", "offline_access"]}
        print("allowed client scopes", call("PUT", f"/admin/realms/atlas/components/{c['id']}", c, T)[0])
    if c["providerId"] == "consent-required" and c["subType"] == "anonymous":
        print("consent policy removed", call("DELETE", f"/admin/realms/atlas/components/{c['id']}", None, T)[0])
