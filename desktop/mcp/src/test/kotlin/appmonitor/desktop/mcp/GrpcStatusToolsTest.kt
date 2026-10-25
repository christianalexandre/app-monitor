package appmonitor.desktop.mcp

import appmonitor.v1.CaptureLayer
import appmonitor.v1.Header
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class GrpcStatusToolsTest {
    /** Base run: one OK call per writer. Run: the same calls ended with a status other than OK. */
    private suspend fun Running.seed(): Pair<Long, Long> {
        suspend fun calls(
            session: String,
            adapterStatus: Int,
            proxyStatus: Int,
        ) {
            storage.connected(session, hello(), null)
            storage.received(session, envelope(request("$session-a", "POST", ADAPTER_URL), 1_000))
            storage.received(session, envelope(response("$session-a", headers = adapterHeaders(adapterStatus)), 1_010))
            val proxied = request("$session-p", "POST", PROXY_URL).copy(capture_layer = CaptureLayer.CAPTURE_LAYER_PROXY)
            storage.received(session, envelope(proxied, 2_000))
            storage.received(session, envelope(response("$session-p", headers = proxyHeaders(proxyStatus)), 2_010))
            storage.disconnected(session)
        }
        calls("base", 0, 0)
        val base = storage.runId
        val run = storage.newRun()
        calls("run", 13, 5)
        storage.flush()
        return base to run
    }

    @Test
    fun searchRequestsCarriesGrpcStatus() =
        withServer { running ->
            val (_, run) = running.seed()
            val rows =
                running
                    .client { it.callTool(SearchRequests.NAME, mapOf("run_id" to run)) }
                    .json["requests"]!!
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(listOf(200L, 200L), rows.map { it["status_code"]!!.jsonPrimitive.long })
            assertEquals(listOf(13L, 5L), rows.map { it["grpc_status"]!!.jsonPrimitive.long })
        }

    @Test
    fun endpointStatsAndDiffRunsCountAFailedCallFromEachWriter() =
        withServer { running ->
            val (base, run) = running.seed()
            val stats = running.client { it.callTool(EndpointStats.NAME, mapOf("run_id" to run)) }.json
            assertEquals(listOf(1.0, 1.0), stats.list("endpoints").map { it["error_rate"]!!.jsonPrimitive.double })
            val baseStats = running.client { it.callTool(EndpointStats.NAME, mapOf("run_id" to base)) }.json
            assertEquals(listOf(0.0, 0.0), baseStats.list("endpoints").map { it["error_rate"]!!.jsonPrimitive.double })
            val diff = running.client { it.callTool(DiffRuns.NAME, mapOf("base_run_id" to base, "run_id" to run)) }.json
            assertEquals(
                listOf(Triple(0.0, 1.0, 1.0), Triple(0.0, 1.0, 1.0)),
                diff.list("changed").map { change ->
                    change["error_rate"]!!.jsonObject.let {
                        Triple(it["base"]!!.jsonPrimitive.double, it["run"]!!.jsonPrimitive.double, it["delta"]!!.jsonPrimitive.double)
                    }
                },
            )
        }

    @Test
    fun aPlainResponseHasNoGrpcStatus() =
        withServer { running ->
            running.storage.connected("s", hello(), null)
            running.storage.received("s", envelope(request("r")))
            running.storage.received("s", envelope(response("r")))
            running.storage.flush()
            val row =
                running
                    .client { it.callTool(SearchRequests.NAME, emptyMap()) }
                    .json
                    .list("requests")
                    .single()
            assertEquals(JsonNull, row["grpc_status"])
        }

    private fun JsonObject.list(name: String) = this[name]!!.jsonArray.map { it.jsonObject }

    private companion object {
        const val ADAPTER_URL = "https://api.example.com/test.Echo/Unary"
        const val PROXY_URL = "http://127.0.0.1:50051/test.Echo/Missing"

        // As GRPCCaptureTests.statusAfterMessagesEndsTheResponse expects: grpc-status after the metadata.
        fun adapterHeaders(status: Int) =
            listOfNotNull(
                Header("content-type", "application/grpc"),
                Header("grpc-status", "$status"),
                if (status != 0) Header("grpc-message", "broke") else null,
            )

        // As Http2ProxyTest's error case stores it: the trailers after the response's headers.
        fun proxyHeaders(status: Int) =
            listOfNotNull(
                Header("content-type", "application/grpc"),
                Header("grpc-status", "$status"),
                if (status != 0) Header("grpc-message", "no such thing") else null,
            )
    }
}
