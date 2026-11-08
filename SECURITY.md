# Security policy

## Supported versions

Atlas has no release yet: fixes go to `main` and to the `edge` image (see
[Published tags](docs/container.md#published-tags)). Once releases are published, the newest one receives security
fixes; older ones do not.

## Reporting a vulnerability

Do not open a public issue, discussion or pull request for a vulnerability.

Report it privately through GitHub: open the repository's **Security** tab and choose **Report a vulnerability**
([direct link](https://github.com/felipebz/zpa-atlas/security/advisories/new)). Include:

- the version or image digest, and how Atlas runs (command line, container, options such as `--oidc-issuer`);
- what an attacker can do, and what they need first (network access, a valid token, sources they control);
- the steps or a minimal proof of concept to reproduce it.

You will get an answer within 7 days. The report stays private while it is confirmed and fixed; the fix is then
published with a GitHub security advisory, crediting you unless you prefer otherwise.

## Scope

In scope: this repository's code and the image `ghcr.io/felipebz/zpa-atlas`, for example:

- the server: authentication of `/api` and `/mcp` (access tokens, the page sign-in and its sessions), the `Host` and
  origin checks of `/mcp`, and anything that reads files outside the index and the sources it serves;
- the indexer reading sources, project files and Forms modules;
- the image and how it is built and published.

Out of scope:

- that a server started without `--oidc-issuer` answers whoever reaches it with the index and the sources it serves:
  that is what it does without authentication, as [docs/serving.md](docs/serving.md) says. A way past what it is meant
  to give, even without authentication, is in scope: reading other files, reaching `/mcp` through a `Host` or origin
  it refuses, running code;
- vulnerabilities of the PL/SQL analyzer itself: report them to [ZPA](https://github.com/felipebz/zpa);
- vulnerabilities of dependencies or of the base image with no effect on Atlas: they are updated as they are released.
