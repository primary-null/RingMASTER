package local.ringleader.core

import java.net.URI
import java.net.URLEncoder

/**
 * Deep links into the Proxmox VE web interface for the open direct connection. The hash routes are the ones the
 * web UI itself writes: `#v1:0:=qemu/{vmid}`, `=lxc/{vmid}`, `=node/{node}`, `=storage/{node}/{storage}`.
 * Only an https origin is accepted; the link carries no credential, so the browser asks for its own login.
 */
object PveWebUi {
    fun datacenter(origin: String): String? = base(origin)?.let { "$it#v1:0:" }
    fun guest(origin: String, kind: String, vmid: Int): String? = base(origin)?.let { "$it#v1:0:=${if (kind == "VM") "qemu" else "lxc"}/$vmid" }
    fun node(origin: String, node: String): String? = base(origin)?.takeIf { node.isNotBlank() }?.let { "$it#v1:0:=node/${segment(node)}" }
    fun storage(origin: String, node: String, storage: String): String? =
        base(origin)?.takeIf { node.isNotBlank() && storage.isNotBlank() }?.let { "$it#v1:0:=storage/${segment(node)}/${segment(storage)}" }

    /** A task opens its guest when it names one, otherwise its node. */
    fun task(origin: String, node: String, type: String, id: String?): String? {
        val vmid = id?.toIntOrNull()?.takeIf { it > 0 }
        val kind = when { type.startsWith("qm") || type == "vncproxy" || type == "spiceproxy" -> "VM"; type.startsWith("vz") && type != "vzdump" -> "CT"; else -> null }
        return if (vmid != null && kind != null) guest(origin, kind, vmid) else node(origin, node)
    }

    private fun base(origin: String): String? = runCatching {
        val uri = URI(origin.trim())
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrBlank() || uri.userInfo != null) return null
        val host = if (':' in uri.host && !uri.host.startsWith("[")) "[${uri.host}]" else uri.host
        "https://$host${if (uri.port > 0) ":${uri.port}" else ""}/"
    }.getOrNull()

    private fun segment(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
