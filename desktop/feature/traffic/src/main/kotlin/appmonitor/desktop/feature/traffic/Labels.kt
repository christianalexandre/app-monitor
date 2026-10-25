package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Run
import appmonitor.desktop.ui.formatRunStart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ADR-0111: a replayed POST really runs a second time on the server.
fun replayLabelOf(method: String?): String =
    if (method?.uppercase() in SAFE_METHODS) "Send again" else "Send again: runs ${method ?: "it"} on the server again"

private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

internal fun spanOf(
    run: Run,
    current: Boolean,
): String =
    listOfNotNull(
        formatRunStart(run.startedAtMs) +
            (run.endedAtMs?.let { " – ${TIME_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: ""),
        if (current) "current" else null,
    ).joinToString(" · ")

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
