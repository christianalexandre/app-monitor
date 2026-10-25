package appmonitor.desktop.feature.traffic

import appmonitor.desktop.domain.Exchange
import appmonitor.desktop.domain.Run
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The methods a filter or a rule offers. */
val METHODS = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")

fun formatDuration(ms: Long): String = if (ms < 1_000) "$ms ms" else String.format(Locale.ROOT, "%.2f s", ms / 1_000.0)

fun statusOf(exchange: Exchange): String =
    when {
        exchange.failed -> "failed"
        exchange.isConnection -> if (exchange.endedAtMs != null) "closed" else "open"
        (exchange.grpcStatus ?: 0) != 0 -> "grpc ${exchange.grpcStatus}"
        exchange.statusCode != null -> exchange.statusCode.toString()
        else -> "…"
    }

/** A run's name, or its start time while it has none (ADR-0084 B.1). */
fun titleOf(run: Run): String = run.name ?: RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs))

// ADR-0111: a replayed POST really runs a second time on the server.
fun replayLabelOf(method: String?): String =
    if (method?.uppercase() in SAFE_METHODS) "Send again" else "Send again: runs ${method ?: "it"} on the server again"

private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

internal fun spanOf(
    run: Run,
    current: Boolean,
): String =
    listOfNotNull(
        RUN_FORMAT.format(Instant.ofEpochMilli(run.startedAtMs)) +
            (run.endedAtMs?.let { " – ${TIME_FORMAT.format(Instant.ofEpochMilli(it))}" } ?: ""),
        if (current) "current" else null,
    ).joinToString(" · ")

private val RUN_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault())

private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
