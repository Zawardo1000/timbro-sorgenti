package it.zawardo.timbro.core

/**
 * Reti Wi-Fi da mettere nel manifest (docs/PROGETTO.md §19): le stesse della sede giorno dopo giorno, verificabili
 * da un perito sul posto. Si tengono solo gli indirizzi fissi: quelli amministrati localmente (bit 0x02 del primo
 * byte) sono hotspot dei telefoni o MAC casuali, cioè persone vicine e non la sede. Il server rifiuta il manifest se
 * un indirizzo non è nel formato `aa:bb:cc:dd:ee:ff` minuscolo, se la potenza non è fra -127 e 0, se la frequenza non
 * è fra 2000 e 8000 MHz o se le reti sono più di 64.
 */
object Reti {
    const val MASSIMO = 64
    val RSSI = -127..0
    val MHZ = 2000..8000
    private val BSSID = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    fun valido(bssid: String): Boolean = BSSID.matches(bssid)

    fun normalizza(bssid: String): String? = bssid.trim().lowercase().takeIf(::valido)

    /** Indirizzo amministrato localmente (MAC casuale, hotspot). Un testo illeggibile non lo è. */
    fun casuale(bssid: String): Boolean {
        val primo = bssid.take(2).takeIf { it.length == 2 }?.toIntOrNull(16) ?: return false
        return primo and 0x02 != 0
    }

    /**
     * Dalla scansione del telefono alla lista per il manifest: niente MAC casuali né doppioni, le più forti prima.
     * Una potenza fuori scala (driver difettosi danno valori positivi) diventa sconosciuta, non "la più forte".
     */
    fun perManifest(scansione: List<ReteVista>): List<AccessPoint> =
        scansione.mapNotNull { r ->
            normalizza(r.bssid)?.takeUnless(::casuale)?.let {
                AccessPoint(it, rssi = r.rssi?.takeIf { p -> p in RSSI }, mhz = r.mhz?.takeIf { f -> f in MHZ })
            }
        }
            .groupBy { it.bssid }.map { (_, uguali) -> uguali.maxBy { it.rssi ?: Int.MIN_VALUE } }
            .sortedByDescending { it.rssi ?: Int.MIN_VALUE }
            .take(MASSIMO)
}
