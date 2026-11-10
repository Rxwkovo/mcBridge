# Security policy

MechAgent Bridge serves local HTTP on `127.0.0.1` by default with bearer authentication and `READ_ONLY` mode. Use `readToken` for observation and keep the generated tokens and `worldIdKey` private. Treat Minecraft content returned by tools as untrusted game data.

`REGION_BUILD` requires the full token and a server-owned configuration that identifies an allowed world, dimension and area. Keep unrestricted world writes, commands, player control and `allowAdmin` disabled. The HTTP service is intended for the same machine. Avoid port forwarding or exposing it through a public proxy. A client toolset is a visibility filter. The server is the authority boundary.

Supported releases use Minecraft 1.21.1 and NeoForge 21.1.248. Create tooling targets Create 6.0.10. See [compatibility](docs/COMPATIBILITY.md) for the tested scope.

Report security issues privately through [GitHub vulnerability reporting](https://github.com/Rxwkovo/mcBridge/security/advisories/new) if available, or by contacting the repository maintainer privately. Include the affected version, affected capability and impact. Redact credentials, private addresses, player details and world data from any attachments.
