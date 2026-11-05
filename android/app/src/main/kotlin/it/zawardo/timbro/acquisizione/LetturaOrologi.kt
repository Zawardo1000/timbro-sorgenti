package it.zawardo.timbro.acquisizione

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import it.zawardo.timbro.core.Manifest
import it.zawardo.timbro.core.Orologi
import java.time.Clock
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Gli orologi che confermano l'ora del telefono (docs/PROGETTO.md §24). L'ora del telefono si cambia dalle
 * impostazioni senza modificare nulla; questi no:
 * - ora di rete: `SystemClock.currentNetworkTimeClock()` (Android 13+), l'ultima sincronizzazione con la rete più il
 *   tempo trascorso, anche se l'utente ha spostato l'orologio a mano;
 * - ora satellitare: `SystemClock.currentGnssTimeClock()`, dall'ultimo fix GNSS.
 * Si leggono a fine ripresa (il GPS ha avuto il tempo di agganciarsi) e si riportano all'istante d'inizio con
 * l'orologio monotono, che non si sposta. Un orologio non disponibile resta null: meglio niente che un valore inventato.
 */
object LetturaOrologi {
    fun leggi(contesto: Context, inizioMonotonoMs: Long): Orologi {
        val trascorsoMs = SystemClock.elapsedRealtime() - inizioMonotonoMs
        fun allInizio(orologio: () -> Clock): String? = runCatching {
            val istante = orologio().instant().minusMillis(trascorsoMs)
            Manifest.oraDispositivo(ZonedDateTime.ofInstant(istante, ZoneId.systemDefault()))
        }.getOrNull()
        val rete = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) allInizio { SystemClock.currentNetworkTimeClock() } else null
        val satelliti = allInizio { SystemClock.currentGnssTimeClock() }
        val automatica = runCatching {
            Settings.Global.getInt(contesto.contentResolver, Settings.Global.AUTO_TIME) == 1
        }.getOrNull()
        return Orologi(autoTime = automatica, network = rete, gnss = satelliti)
    }
}
