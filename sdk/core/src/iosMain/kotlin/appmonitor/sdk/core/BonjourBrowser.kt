package appmonitor.sdk.core

import appmonitor.protocol.DesktopAnswer
import appmonitor.protocol.Protocol
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.Network.nw_browse_descriptor_create_bonjour_service
import platform.Network.nw_browse_descriptor_set_include_txt_record
import platform.Network.nw_browse_result_copy_endpoint
import platform.Network.nw_browse_result_copy_txt_record_object
import platform.Network.nw_browse_result_t
import platform.Network.nw_browser_cancel
import platform.Network.nw_browser_create
import platform.Network.nw_browser_set_browse_results_changed_handler
import platform.Network.nw_browser_set_queue
import platform.Network.nw_browser_set_state_changed_handler
import platform.Network.nw_browser_start
import platform.Network.nw_browser_state_failed
import platform.Network.nw_browser_state_waiting
import platform.Network.nw_browser_t
import platform.Network.nw_endpoint_get_bonjour_service_name
import platform.Network.nw_error_domain_dns
import platform.Network.nw_error_get_error_code
import platform.Network.nw_error_get_error_domain
import platform.Network.nw_error_t
import platform.Network.nw_parameters_create
import platform.Network.nw_txt_record_access_key
import platform.Network.nw_txt_record_find_key_non_empty_value
import platform.darwin.DNSServiceGetAddrInfo
import platform.darwin.DNSServiceRef
import platform.darwin.DNSServiceRefDeallocate
import platform.darwin.DNSServiceRefVar
import platform.darwin.DNSServiceResolve
import platform.darwin.DNSServiceSetDispatchQueue
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.kDNSServiceErr_NoError
import platform.darwin.kDNSServiceFlagsAdd
import platform.darwin.kDNSServiceProtocol_IPv4
import platform.posix.AF_INET
import platform.posix.sockaddr
import platform.posix.sockaddr_in

/** `kDNSServiceErr_PolicyDenied`: local network access is not granted (TN3179). */
private const val POLICY_DENIED = -65570

/** The mDNS source of SDD 04 on iOS; a browser that cannot run is logged once per client. */
internal fun bonjourBrowser(log: (String) -> Unit = ::platformLog): () -> MdnsBrowser? {
    val logged = atomic(false)
    val browser =
        BonjourBrowser { reason ->
            if (logged.compareAndSet(expect = false, update = true)) {
                log("mDNS discovery failed ($reason); $CONNECT_FAILURE_HINT, or set AppMonitorOptions.host")
            }
        }
    return { browser }
}

/**
 * [MdnsBrowser] on Network.framework's `nw_browser` with TXT records; each new name is resolved to an
 * IPv4 address with `DNSServiceResolve` then `DNSServiceGetAddrInfo` (ADR-0068).
 */
internal class BonjourBrowser(
    private val onFailure: (String) -> Unit,
) : MdnsBrowser {
    override fun browse(onAnswer: (DesktopAnswer) -> Unit): AutoCloseable = BonjourSession(onAnswer, onFailure).also { it.start() }
}

/** One browse; every field is touched on [queue] only. Names are keyed lowercased (RFC 4343). */
@OptIn(ExperimentalForeignApi::class)
private class BonjourSession(
    private val onAnswer: (DesktopAnswer) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    val queue: dispatch_queue_t = dispatch_queue_create("appmonitor.bonjour", null)
    private var browser: nw_browser_t = null
    private var closed = false
    private val resolves = mutableMapOf<String, Resolve>()
    private val addresses = mutableMapOf<String, Pair<String, Int>>()
    private val paths = mutableMapOf<String, String>()
    private val ids = mutableMapOf<String, String>()

    fun start() =
        dispatch_async(queue) {
            if (closed) return@dispatch_async
            val descriptor = nw_browse_descriptor_create_bonjour_service(SERVICE_TYPE, null)
            nw_browse_descriptor_set_include_txt_record(descriptor, true)
            val browser = nw_browser_create(descriptor, nw_parameters_create())
            this.browser = browser
            nw_browser_set_queue(browser, queue)
            nw_browser_set_state_changed_handler(browser) { state, error ->
                if (closed) return@nw_browser_set_state_changed_handler
                if (state == nw_browser_state_failed || (state == nw_browser_state_waiting && error.isPolicyDenied())) {
                    onFailure(error.describe())
                }
            }
            nw_browser_set_browse_results_changed_handler(browser) { _, result, _ ->
                if (!closed && result != null) seen(result)
            }
            nw_browser_start(browser)
        }

    override fun close() =
        dispatch_async(queue) {
            closed = true
            browser?.let { nw_browser_cancel(it) }
            browser = null
            resolves.values.forEach { it.cancel() }
            resolves.clear()
        }

    /** [host] null: the resolve failed, and the name is resolved again on its next result. */
    fun resolved(
        resolve: Resolve,
        host: String?,
        port: Int,
    ) {
        if (closed || resolves[resolve.key] !== resolve) return
        resolves.remove(resolve.key)
        if (host == null) return
        addresses[resolve.key] = host to port
        onAnswer(DesktopAnswer(resolve.name, host, port, paths[resolve.key], ids[resolve.key]))
    }

    private fun seen(result: nw_browse_result_t) {
        val name = nw_endpoint_get_bonjour_service_name(nw_browse_result_copy_endpoint(result))?.toKString() ?: return
        val key = name.lowercase()
        txtValue(result, Protocol.MDNS_TXT_PATH)?.let {
            paths[key] = it
            txtValue(result, Protocol.MDNS_TXT_ID)?.let { id -> ids[key] = id } ?: ids.remove(key)
        }
        val address = addresses[key]
        if (address != null) {
            onAnswer(DesktopAnswer(name, address.first, address.second, paths[key], ids[key]))
        } else if (key !in resolves) {
            val resolve = Resolve(this, name, key)
            resolves[key] = resolve
            if (!resolve.start()) resolves.remove(key)
        }
    }

    private fun txtValue(
        result: nw_browse_result_t,
        key: String,
    ): String? {
        val txt = nw_browse_result_copy_txt_record_object(result) ?: return null
        var text: String? = null
        nw_txt_record_access_key(txt, key) { _, found, value, length ->
            if (found == nw_txt_record_find_key_non_empty_value && value != null) {
                text = value.readBytes(length.toInt()).decodeToString()
            }
            true
        }
        return text
    }

    companion object {
        // nw_browse_descriptor and dns_sd take the type without the `.local.` domain.
        val SERVICE_TYPE = Protocol.MDNS_SERVICE_TYPE.removeSuffix(".local.")
    }
}

/**
 * The resolve of one name on its session's queue: SRV to target and port, then the target's first
 * IPv4 address. Reports once to [BonjourSession.resolved] unless [cancel]led first.
 */
@OptIn(ExperimentalForeignApi::class)
private class Resolve(
    val session: BonjourSession,
    val name: String,
    val key: String,
) {
    private val self = StableRef.create(this)
    private var ref: DNSServiceRef? = null
    private var done = false
    private var port = 0

    fun start(): Boolean =
        begin { out ->
            DNSServiceResolve(out, 0u, 0u, name, BonjourSession.SERVICE_TYPE, "local.", onResolveReply, self.asCPointer())
        }

    fun cancel() {
        ref?.let { DNSServiceRefDeallocate(it) }
        ref = null
        if (!done) self.dispose()
        done = true
    }

    fun onResolved(
        error: Int,
        target: String?,
        networkPort: UShort,
    ) {
        if (done) return
        if (error != kDNSServiceErr_NoError || target == null) return finish(null)
        port = networkPort.toInt().let { ((it and 0xff) shl 8) or (it shr 8) }
        if (!begin { DNSServiceGetAddrInfo(it, 0u, 0u, kDNSServiceProtocol_IPv4, target, onAddressReply, self.asCPointer()) }) {
            session.resolved(this, null, port)
        }
    }

    fun onAddress(
        error: Int,
        flags: UInt,
        address: CPointer<sockaddr>?,
    ) {
        if (done) return
        if (error != kDNSServiceErr_NoError) return finish(null)
        if (flags and kDNSServiceFlagsAdd == 0u || address == null || address.pointed.sa_family.toInt() != AF_INET) return
        val ip =
            address
                .reinterpret<sockaddr_in>()
                .pointed.sin_addr.s_addr
        // s_addr is in network order: its lowest byte is the first octet on this little-endian CPU.
        finish((0 until 4).joinToString(".") { ((ip shr (8 * it)) and 0xffu).toString() })
    }

    /** Replaces the current query with the one [call] opens; false, and cancelled, when it cannot run. */
    private fun begin(call: (CPointer<DNSServiceRefVar>) -> Int): Boolean {
        ref?.let { DNSServiceRefDeallocate(it) }
        ref = null
        val started =
            memScoped {
                val out = alloc<DNSServiceRefVar>()
                if (call(out.ptr) != kDNSServiceErr_NoError) return@memScoped false
                ref = out.value
                DNSServiceSetDispatchQueue(ref, session.queue) == kDNSServiceErr_NoError
            }
        if (!started) cancel()
        return started
    }

    private fun finish(host: String?) {
        cancel()
        session.resolved(this, host, port)
    }
}

@OptIn(ExperimentalForeignApi::class)
private val onResolveReply =
    staticCFunction {
        _: DNSServiceRef?,
        _: UInt,
        _: UInt,
        error: Int,
        _: CPointer<ByteVar>?,
        target: CPointer<ByteVar>?,
        port: UShort,
        _: UShort,
        _: CPointer<UByteVar>?,
        context: COpaquePointer?,
        ->
        context?.asStableRef<Resolve>()?.get()?.onResolved(error, target?.toKString(), port)
        Unit
    }

@OptIn(ExperimentalForeignApi::class)
private val onAddressReply =
    staticCFunction {
        _: DNSServiceRef?,
        flags: UInt,
        _: UInt,
        error: Int,
        _: CPointer<ByteVar>?,
        address: CPointer<sockaddr>?,
        _: UInt,
        context: COpaquePointer?,
        ->
        context?.asStableRef<Resolve>()?.get()?.onAddress(error, flags, address)
        Unit
    }

@OptIn(ExperimentalForeignApi::class)
private fun nw_error_t.isPolicyDenied(): Boolean =
    this != null && nw_error_get_error_domain(this) == nw_error_domain_dns && nw_error_get_error_code(this) == POLICY_DENIED

@OptIn(ExperimentalForeignApi::class)
private fun nw_error_t.describe(): String =
    if (this ==
        null
    ) {
        "no error"
    } else {
        "domain ${nw_error_get_error_domain(this)} code ${nw_error_get_error_code(this)}"
    }
