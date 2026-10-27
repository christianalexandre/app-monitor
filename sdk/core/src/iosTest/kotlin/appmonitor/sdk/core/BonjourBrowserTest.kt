package appmonitor.sdk.core

import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.Protocol
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Network.NW_PARAMETERS_DEFAULT_CONFIGURATION
import platform.Network.NW_PARAMETERS_DISABLE_PROTOCOL
import platform.Network.nw_advertise_descriptor_create_bonjour_service
import platform.Network.nw_advertise_descriptor_set_txt_record_object
import platform.Network.nw_connection_cancel
import platform.Network.nw_listener_cancel
import platform.Network.nw_listener_create
import platform.Network.nw_listener_get_port
import platform.Network.nw_listener_set_advertise_descriptor
import platform.Network.nw_listener_set_new_connection_handler
import platform.Network.nw_listener_set_queue
import platform.Network.nw_listener_set_state_changed_handler
import platform.Network.nw_listener_start
import platform.Network.nw_listener_state_ready
import platform.Network.nw_parameters_create_secure_tcp
import platform.Network.nw_txt_record_create_dictionary
import platform.Network.nw_txt_record_set_key
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/** One real Bonjour round on the simulator, against a service this test advertises; needs a multicast-capable interface. */
@OptIn(ExperimentalForeignApi::class)
class BonjourBrowserTest {
    @Test
    fun findsAnAdvertisedDesktopWithItsAddressPortAndPath() =
        runBlocking {
            val name = "AppMonitor test ${Uuid.random().toString().take(8)}"
            val queue = dispatch_queue_create("appmonitor.test.listener", null)
            val listener =
                nw_listener_create(nw_parameters_create_secure_tcp(NW_PARAMETERS_DISABLE_PROTOCOL, NW_PARAMETERS_DEFAULT_CONFIGURATION))
            val advertise = nw_advertise_descriptor_create_bonjour_service(name, "_appmonitor._tcp", null)
            val txt = nw_txt_record_create_dictionary()
            "/test/ws".encodeToByteArray().asUByteArray().usePinned {
                nw_txt_record_set_key(txt, Protocol.MDNS_TXT_PATH, it.addressOf(0), it.get().size.toULong())
            }
            nw_advertise_descriptor_set_txt_record_object(advertise, txt)
            nw_listener_set_advertise_descriptor(listener, advertise)
            val ready = CompletableDeferred<Int>()
            nw_listener_set_queue(listener, queue)
            nw_listener_set_new_connection_handler(listener) { nw_connection_cancel(it) }
            nw_listener_set_state_changed_handler(listener) { state, _ ->
                if (state == nw_listener_state_ready) ready.complete(nw_listener_get_port(listener).toInt())
            }
            nw_listener_start(listener)
            try {
                val port = withTimeout(10.seconds) { ready.await() }
                val found = CompletableDeferred<DesktopAnswer>()
                val browsing =
                    BonjourBrowser { found.completeExceptionally(IllegalStateException(it)) }.browse {
                        if (it.name ==
                            name
                        ) {
                            found.complete(it)
                        }
                    }
                try {
                    val answer = withTimeout(15.seconds) { found.await() }
                    assertEquals(port, answer.port)
                    assertEquals("/test/ws", answer.path)
                    assertTrue(Regex("""\d+\.\d+\.\d+\.\d+""").matches(answer.host), answer.host)
                } finally {
                    browsing.close()
                }
            } finally {
                nw_listener_cancel(listener)
            }
        }
}
