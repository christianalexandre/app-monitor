# Ask your AI agent (MCP)

The desktop app serves its history over MCP (Streamable HTTP) on
`http://127.0.0.1:47475/mcp`, as soon as it starts. The server can only be
reached from your own machine and needs a bearer token
([SDD 10](../sdd/10_desktop_mcp.md)).

Its tools:

- **Read-only:** `list_runs`, `search_requests`, `get_request`,
  `endpoint_stats`, `diff_runs`, `export_har`, `to_curl`, `list_issues`.
- **`save_issue`** creates and edits issues, the same ones the window shows.
- **`create_mock`** makes a mock rule. It reaches no device until you confirm
  it in the window's *Mocks* panel.
- **`replay_request`** asks for a request to be sent again. It is sent once
  you confirm it in the window.

Bodies and headers are what the app sent: masked on the device, unless the
app turned masking off.

## Claude Code

Open the desktop's settings, *MCP server*, click *Copy command* and paste it
into a terminal:

```sh
claude mcp add --transport http app-monitor http://127.0.0.1:47475/mcp \
  --header "Authorization: Bearer <token>"
```

To share the server with a project without committing the token, put it in
the project's `.mcp.json` and read the token from the environment
(`export APPMONITOR_MCP_TOKEN=<token>` before starting `claude`):

```json
{
  "mcpServers": {
    "app-monitor": {
      "type": "http",
      "url": "http://127.0.0.1:47475/mcp",
      "headers": { "Authorization": "Bearer ${APPMONITOR_MCP_TOKEN}" }
    }
  }
}
```

Then ask, for example, *"why did a request fail in the current run, and which
endpoints are new compared with run 1?"*.

## Any other Streamable HTTP client

URL `http://127.0.0.1:47475/mcp`, header `Authorization: Bearer <token>`. A
client that sends an `Origin` must
send `http://127.0.0.1:47475` or `http://localhost:47475`; anything else gets
`403`, a missing or wrong token `401`.

## Check it by hand

With the [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
(Node 22.19 or newer):

```sh
npx @modelcontextprotocol/inspector --cli http://127.0.0.1:47475/mcp \
  --header "Authorization: Bearer <token>" --method tools/list
npx @modelcontextprotocol/inspector --cli http://127.0.0.1:47475/mcp \
  --header "Authorization: Bearer <token>" --method tools/call --tool-name list_runs
```

## A client that only speaks stdio

It can reach the server through [`mcp-remote`](https://github.com/geelen/mcp-remote) (not tested by this
project): `npx mcp-remote http://127.0.0.1:47475/mcp --header-file <file>`,
the file holding the `Authorization: Bearer <token>` line.

## Token and port

*Regenerate token* in the settings, or *Clear all data*, replaces the token:
paste the command again. The switch in the same section turns the server
off; `APPMONITOR_MCP_PORT=<n>` moves it to another port.
