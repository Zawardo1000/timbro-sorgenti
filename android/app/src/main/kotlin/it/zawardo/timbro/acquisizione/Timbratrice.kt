package it.zawardo.timbro.acquisizione

import it.zawardo.timbro.BuildConfig
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.core.Manifest
import it.zawardo.timbro.core.api.ApiJson
import it.zawardo.timbro.dati.coda.Invio
import it.zawardo.timbro.dati.coda.TimbraturaInCoda
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Da una registrazione confermata alla timbratura in coda (docs/PROGETTO.md §5.4, §19):
 * 1. manifest con impronta del video, tipo, ora d'inizio della ripresa (con gli orologi di rete e satellitare che la
 *    confermano), posizione, reti e versione dell'app;
 * 2. firma ECDSA dei **byte esatti** con la chiave delle timbrature (chip sicuro);
 * 3. attestazione hardware con challenge = SHA-256 di quei byte;
 * 4. tutto nella coda locale **prima** di qualunque rete: se il telefono è offline, o l'app si chiude, la timbratura
 *    resta e parte appena si può (InvioWorker).
 */
class Timbratrice(private val app: TimbroApp) {

    suspend fun timbra(r: Ripresa, kind: String): TimbraturaInCoda = withContext(Dispatchers.Default) {
        val telefono = app.associazione.telefonoId ?: error("telefono non associato")
        val m = Manifest(
            clientId = UUID.randomUUID().toString(),
            deviceId = telefono,
            kind = kind,
            sha256 = r.sha256,
            size = r.dimensione,
            durationMs = r.durataMs,
            deviceTime = Manifest.oraDispositivo(r.inizio),
            location = r.posizione,
            wifi = r.wifi,
            clocks = r.orologi,
            appVersion = versione(),
        )
        val byte = m.bytes()
        val firma = app.chiavi.firmaTimbratura(byte)
        val catena = app.chiavi.attestaManifest(MessageDigest.getInstance("SHA-256").digest(byte))
        val voce = TimbraturaInCoda(
            clientId = m.clientId,
            kind = kind,
            manifest = byte,
            firma = firma,
            attestazione = catena?.let { ApiJson.encodeToString(ListSerializer(String.serializer()), it) },
            percorsoVideo = r.percorso,
            videoSha256 = r.sha256,
            creataIl = r.inizio.toInstant().toEpochMilli(),
        )
        val dao = app.coda.coda()
        dao.aggiungi(voce)
        Invio.pianifica(app)
        dao.perClientId(m.clientId) ?: voce
    }

    companion object {
        /** versionName più il commit da cui è stata compilata (al massimo 40 caratteri, come vuole il server). */
        fun versione(): String = "${BuildConfig.VERSION_NAME}+${BuildConfig.GIT_SHA}".take(40)
    }
}
