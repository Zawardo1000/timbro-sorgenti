package it.zawardo.timbro

import android.app.Application
import it.zawardo.timbro.dati.ClientApi
import it.zawardo.timbro.dati.Rete
import it.zawardo.timbro.dati.coda.CodaDb
import it.zawardo.timbro.sicurezza.Associazione
import it.zawardo.timbro.sicurezza.Chiavi

/** Un'istanza per processo di chiavi, associazione, stato della rete, client dell'API e database locale (coda e storico). */
class TimbroApp : Application() {
    val chiavi by lazy { Chiavi() }
    val associazione by lazy { Associazione(this, chiavi) }
    val rete by lazy { Rete(this) }
    val api by lazy { ClientApi(associazione, chiavi, rete) }
    val coda by lazy { CodaDb.apri(this) }
}
