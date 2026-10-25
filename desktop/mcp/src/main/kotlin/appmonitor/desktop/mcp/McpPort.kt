package appmonitor.desktop.mcp

/** The MCP server's port when `APPMONITOR_MCP_PORT` is not set (ADR-0090 D.1). */
const val DEFAULT_MCP_PORT = 47475

/** `APPMONITOR_MCP_PORT` when it is a port number, else [DEFAULT_MCP_PORT]. */
fun mcpPort(env: (String) -> String? = System::getenv): Int =
    env("APPMONITOR_MCP_PORT")?.trim()?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: DEFAULT_MCP_PORT
