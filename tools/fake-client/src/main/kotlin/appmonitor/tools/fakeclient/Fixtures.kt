package appmonitor.tools.fakeclient

import appmonitor.v1.Envelope

/** Golden envelopes from `protocol/fixtures`, bundled as resources by the build. */
object Fixtures {
    val hello: Envelope = load("hello.bin")

    /** Every `network` fixture, in the order a request lives through them. */
    val traffic: List<Envelope> =
        listOf("request_started.bin", "response_received.bin", "request_failed.bin", "websocket_frame.bin").map(::load)

    private fun load(name: String): Envelope {
        val bytes =
            checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
                .use { it.readBytes() }
        return Envelope.ADAPTER.decode(bytes)
    }
}
