package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Drops
import appmonitor.desktop.domain.Run
import appmonitor.desktop.ui.formatRunStart
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ADR-0111: a replayed POST really runs a second time on the server.
fun replayLabelOf(method: String?): String =
    if (method?.uppercase() in SAFE_METHODS) "Send again" else "Send again: runs ${method ?: "it"} on the server again"

private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

/** The devices pane's line for a session's [drops] (ADR-0166): each count above zero, and "lost unknown" when it is. */
internal fun dropsLabel(drops: Drops): String =
    "Dropped: " +
        listOfNotNull(
            "${drops.evicted} buffer full".takeIf { drops.evicted > 0 },
            "${drops.oversized} too large".takeIf { drops.oversized > 0 },
            when (val lost = drops.lost) {
                null -> "lost unknown"
                0L -> null
                else -> "$lost lost"
            },
        ).joinToString(", ")

internal fun spanOf(
    run: Run,
    recording: Boolean,
): String =
    listOfNotNull(
        formatRunStart(run.startedAtMs) +
            (run.endedAtMs?.let { " – ${TIME_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: ""),
        if (recording) "recording" else null,
    ).joinToString(" · ")

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
