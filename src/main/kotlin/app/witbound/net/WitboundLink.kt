package app.witbound.net

import org.json.JSONObject

/**
 * The direct Mac/PC -> phone transfer protocol, byte-for-byte the same contract
 * the macOS app proved (docs/desktop-companion-api.md). The phone advertises
 * _witbound._tcp and runs a control listener; this desktop app browses, POSTs a
 * small offer, then the phone PULLS the files + RASM map from this app's file
 * server and installs the map so the book opens already synced.
 */
object WitboundLink {
    const val SERVICE_TYPE = "_witbound._tcp"
    const val VERSION = 1
}

data class LinkFileRef(val name: String, val sha256: String, val bytes: Long) {
    fun json() = JSONObject().put("name", name).put("sha256", sha256).put("bytes", bytes)
}
data class LinkMapRef(val sha256: String, val bytes: Long, val format: String, val matchRate: Double) {
    fun json() = JSONObject().put("sha256", sha256).put("bytes", bytes).put("format", format).put("matchRate", matchRate)
}

data class LinkOffer(
    val version: Int, val pairId: String, val title: String, val author: String,
    val durationSec: Double, val source: String, val ackBase: String, val senderName: String,
    val book: LinkFileRef, val audio: LinkFileRef, val map: LinkMapRef,
) {
    fun json(): JSONObject = JSONObject()
        .put("version", version).put("pairId", pairId).put("title", title).put("author", author)
        .put("durationSec", durationSec).put("source", source).put("ackBase", ackBase)
        .put("senderName", senderName)
        .put("book", book.json()).put("audio", audio.json()).put("map", map.json())
}

data class LinkOfferReply(val decision: String, val deviceName: String, val message: String?) {
    companion object {
        fun parse(s: String): LinkOfferReply {
            val o = JSONObject(s)
            return LinkOfferReply(o.optString("decision", "error"),
                o.optString("deviceName", "phone"), o.optString("message", null))
        }
    }
}
