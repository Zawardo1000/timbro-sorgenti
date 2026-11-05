package it.zawardo.timbro.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.zawardo.timbro.core.EsitoOre
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.api.Contestazioni
import it.zawardo.timbro.core.api.StatoAvviso
import it.zawardo.timbro.core.api.TestoMail
import it.zawardo.timbro.ui.ContestazioneInviata
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.ui.LocalFirmatario
import it.zawardo.timbro.ui.Risultato
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.remember
import it.zawardo.timbro.ui.breve
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.Icone
import it.zawardo.timbro.ui.components.Indietro
import it.zawardo.timbro.ui.components.Pillola
import it.zawardo.timbro.ui.components.RigaSpunta
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.giornoMese
import it.zawardo.timbro.ui.oraMinuti
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale

/**
 * Contesta una timbratura ([id]) o segnala tutta una giornata ([giornoScelto], [id] null): il server crea un link con
 * tutte le timbrature del giorno e manda ai destinatari scelti la mail standard ([TestoMail], uguale al server), con
 * l'elenco delle timbrature e le ore valide secondo l'orario. La segnalazione della giornata serve quando le
 * timbrature sbagliate sono più d'una, o quando sono tutte registrate ma l'ente ha calcolato male le ore.
 * Durata del link scelta qui (15 giorni se non si tocca). Senza destinatari si crea solo il link; senza nome e
 * matricola nel profilo la mail non parte.
 */
@Composable
fun ContestaScreen(
    vm: StatoApp,
    id: Int?,
    giornoScelto: LocalDate?,
    onIndietro: () -> Unit,
    onImpostazioni: () -> Unit,
    onSparita: () -> Unit,
) {
    val t = id?.let { vm.timbratura(it) }
    val giorno = t?.quando?.toLocalDate() ?: giornoScelto
    val delGiorno = giorno?.let { g -> vm.timbrature.filter { it.quando.toLocalDate() == g }.sortedBy { it.quando } }.orEmpty()
    // una timbratura ancora in coda non si contesta: il server non ce l'ha
    if (giorno == null || (id != null && (t == null || !t.sulServer)) || delGiorno.isEmpty()) {
        LaunchedEffect(id, giornoScelto) { onSparita() }
        return
    }
    val chiave = id?.toString() ?: "giorno-$giorno"
    val profilo = vm.profilo
    // destinatari spenti a mano (si salvano gli indirizzi tolti: quelli nuovi del profilo restano accesi)
    var spenti by rememberSaveable(chiave) { mutableStateOf("") }
    val tolti = spenti.split('\n').filter { it.isNotEmpty() }.toSet()
    val scelti = profilo.destinatari.filterNot { it in tolti }
    var giorni by rememberSaveable(chiave) { mutableIntStateOf(Contestazioni.GIORNI_PREDEFINITI) }
    var nota by rememberSaveable(chiave) { mutableStateOf("") }
    val firmatario = LocalFirmatario.current
    var inCorso by remember(chiave) { mutableStateOf(false) }
    var errore by remember(chiave) { mutableStateOf<String?>(null) }
    val esito = vm.esitoContestazione
    val inviata = esito?.let { vm.contestazione(it.contestazioneId) }
    val ore = vm.ore(giorno, LocalDateTime.now())
    val versione = vm.versione(giorno)
    val oreMail = if (versione != null) ore else null  // senza orario la mail non parla di ore, come il server

    val conMail = scelti.isNotEmpty()
    val mancaProfilo = conMail && !profilo.completo
    val mancaServer = conMail && !profilo.mailConfigurata
    val problemi = Contestazioni.problemiRichiesta(giorni, scelti, nota)
    val puoInviare = inviata == null && problemi.isEmpty() && !mancaProfilo && !mancaServer && !inCorso

    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Indietro(if (t != null) "Dettaglio" else "Indietro", onIndietro)
        Text(
            when {
                t == null -> "Segnala la giornata"
                t.contestata && inviata == null -> "Contesta di nuovo"
                else -> "Contesta"
            },
            style = Testo.Titolo30,
        )

        Scheda(bordo = Colori.Giallo, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Text(
                if (t != null) {
                    "Contesti ${TestoMail.timbratura(t.tipo.kind, t.quando.toLocalTime())} di ${giorno.senzaAnnoMinuscolo()}: " +
                        "il link mostra tutta la giornata."
                } else {
                    "Segnali tutta la giornata di ${giorno.senzaAnnoMinuscolo()}: il link mostra " +
                        (if (delGiorno.size == 1) "la timbratura" else "le ${delGiorno.size} timbrature") +
                        (if (versione != null) " e il calcolo delle ore." else ".")
                },
                style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold),
            )
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                delGiorno.forEach { x ->
                    val questa = x.id == t?.id
                    Text(
                        x.quando.oraMinuti(),
                        style = Testo.Mono15.copy(color = if (questa) Colori.SuColore else x.tipo.colore),
                        modifier = if (questa) Modifier.background(Colori.Giallo, RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        else Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            if (versione != null) RiepilogoOre(ore, Modifier.padding(top = 12.dp))
        }

        if (inviata == null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TitoloSezione("Destinatari", Modifier.padding(top = 0.dp))
                if (profilo.destinatari.isEmpty()) {
                    Avviso("Nessun destinatario nel profilo: si crea solo il link, senza mail.", "Aggiungi i destinatari", onImpostazioni)
                } else {
                    Scheda(padding = PaddingValues(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)) {
                        profilo.destinatari.forEach { indirizzo ->
                            RigaSpunta(indirizzo, "", indirizzo !in tolti, { acceso ->
                                spenti = (if (acceso) tolti - indirizzo else tolti + indirizzo).joinToString("\n")
                            })
                        }
                    }
                    if (!conMail) Text("Nessuno scelto: si crea solo il link, senza mail.", style = Testo.Piccolo12.copy(color = Colori.Giallo))
                }
                if (mancaProfilo) Avviso("Nel profilo mancano nome e matricola: servono alla mail.", "Completa il profilo", onImpostazioni)
                if (mancaServer) Avviso("L'invio delle mail non è configurato sul server: togli i destinatari per creare solo il link.", null, onImpostazioni)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Il link vale", style = Testo.Piccolo13.copy(color = Colori.Testo3, fontWeight = FontWeight.SemiBold))
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Contestazioni.DURATE.forEach { n ->
                        Pillola("$n gg", n == giorni, { giorni = n }, Modifier.weight(1f), altezza = 44.dp, raggio = 14.dp)
                    }
                }
                Text("Fino al ${LocalDate.now().plusDays(giorni.toLong()).breve()}: dopo il link non si apre più.", style = Testo.Piccolo12)
            }

            CampoTesto(
                "Nota per l'ente (facoltativa)", nota, { if (it.length <= Contestazioni.MAX_NOTA) nota = it }, righe = 3,
                segnaposto = if (t != null) "Es. il terminale non ha emesso il segnale acustico" else "Es. il cartellino riporta 7 h 50 invece di 8 h 34",
            )

            if (conMail && profilo.completo) {
                val nome = profilo.nome.orEmpty()
                val matricola = profilo.matricola.orEmpty()
                Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                    Text("ANTEPRIMA DELLA MAIL", style = Testo.Sezione13)
                    Text("A: ${scelti.joinToString(", ")}", style = Testo.Piccolo12.copy(color = Colori.Testo3), modifier = Modifier.padding(top = 8.dp))
                    profilo.email?.takeIf { it.isNotBlank() }?.let {
                        Text("Risposte e copia a: $it", style = Testo.Piccolo12.copy(color = Colori.Testo3))
                    }
                    Text(TestoMail.oggetto(nome, matricola, giorno), style = Testo.Corpo14.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.padding(top = 8.dp))
                    Text("Gentile ufficio,", style = Testo.Corpo14.copy(color = Colori.Testo3), modifier = Modifier.padding(top = 8.dp))
                    Text(
                        TestoMail.primoParagrafo(nome, matricola, giorno, t?.let { it.tipo.kind to it.quando.toLocalTime() }),
                        style = Testo.Corpo14.copy(color = Colori.Testo3), modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        TestoMail.paragrafoGiornata(delGiorno.map { it.tipo.kind to it.quando.toLocalTime() }, oreMail) + " …",
                        style = Testo.Corpo14.copy(color = Colori.Testo3), modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        "Segue il link (valido fino al ${LocalDate.now().plusDays(giorni.toLong()).breve()})" +
                            (if (nota.isNotBlank()) ", la tua nota" else "") + " e la richiesta di regolarizzare.",
                        style = Testo.Piccolo12, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text("Parte dalla casella configurata sul server, a: ${scelti.joinToString(", ")}", style = Testo.Piccolo12, modifier = Modifier.padding(top = 6.dp))
                }
            }

            problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
            BottonePrimario(
                when {
                    inCorso -> "Invio in corso…"
                    conMail && t != null -> "Invia contestazione"
                    conMail -> "Invia segnalazione"
                    t != null -> "Contesta e crea il link"
                    else -> "Segnala e crea il link"
                },
                {
                    inCorso = true
                    errore = null
                    vm.viewModelScope.launch {
                        (vm.contesta(t, giorno, scelti, giorni, nota, firmatario) as? Risultato.Errore)?.let { errore = it.messaggio }
                        inCorso = false
                    }
                },
                icona = Icone.Impronta,
                abilitato = puoInviare,
            )
            errore?.let { Text(it, style = Testo.Corpo14.copy(color = Colori.Pericolo, fontWeight = FontWeight.SemiBold)) }
            Text(
                "Chiede l'impronta digitale. Impronta, firme e marche delle timbrature non cambiano." +
                    (if (t == null) " Nessuna timbratura viene segnata come contestata." else ""),
                style = Testo.Piccolo12,
            )
        } else {
            Esito(inviata, esito.indirizzo)
            BottoneSecondario(if (t != null) "Torna al dettaglio" else "Torna indietro", onIndietro, altezza = 48.dp)
        }
    }
}

/** "8 h 34 valide · dovute 8 h 00 · saldo +34′", con le cose da verificare in giallo. */
@Composable
internal fun RiepilogoOre(ore: EsitoOre, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            ValoreOre(if (ore.aperta) "FINORA" else "ORE VALIDE", Ore.durata(ore.lavoratiMin), Colori.Inchiostro)
            ore.dovutiMin?.let { ValoreOre("DOVUTE", Ore.durata(it), Colori.Inchiostro) }
            ore.saldoMin?.let { ValoreOre("SALDO", Ore.saldo(it), if (it < 0) Colori.Giallo else Colori.Entrata) }
        }
        ore.anomalie.forEach { Text("Da verificare: ${it.testo}", style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold)) }
    }
}

@Composable
private fun ValoreOre(etichetta: String, valore: String, colore: Color) {
    Column {
        Text(etichetta, style = Testo.Etichetta11.copy(color = Colori.Testo2))
        Text(valore, style = Testo.Mono18.copy(color = colore))
    }
}

@Composable
private fun Avviso(testo: String, azione: String?, onAzione: () -> Unit) {
    Scheda(bordo = Colori.Giallo, padding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icone.Avviso, contentDescription = null, tint = Colori.Giallo, modifier = Modifier.size(18.dp))
            Text(testo, style = Testo.Piccolo13.copy(color = Colori.Inchiostro), modifier = Modifier.weight(1f))
        }
        if (azione != null) BottoneSecondario(azione, onAzione, Modifier.padding(top = 10.dp), altezza = 44.dp, fondo = Colori.Rilievo)
    }
}

/** Dopo l'invio: il link (visto solo ora) e lo stato della mail. */
@Composable
private fun Esito(c: ContestazioneInviata, indirizzo: String) {
    val contesto = LocalContext.current
    Scheda(bordo = Colori.Entrata, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (c.timbraturaId != null) "Timbratura contestata" else "Giornata segnalata", style = Testo.Titolo22.copy(color = Colori.Entrata))
            StatoMail(c)
            Text("Link con tutta la giornata, valido fino al ${c.scade?.breve() ?: "?"}:", style = Testo.Piccolo13.copy(color = Colori.Inchiostro))
            Text(
                indirizzo, style = Testo.Mono13,
                modifier = Modifier.fillMaxWidth().background(Colori.Fondo, RoundedCornerShape(10.dp)).padding(10.dp),
            )
            Text("Viene mostrato solo ora: il server ne conserva solo l'impronta.", style = Testo.Piccolo12)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BottoneSecondario("Copia", { copiaTesto(contesto, indirizzo) }, Modifier.weight(1f), altezza = 44.dp, fondo = Colori.Rilievo)
                BottoneSecondario("Condividi…", { condividiTesto(contesto, indirizzo) }, Modifier.weight(1f), altezza = 44.dp, fondo = Colori.Rilievo)
            }
        }
    }
}

/** "In invio…", "Inviata alle 12:40 a 2 destinatari", "Solo link, nessuna mail". */
@Composable
internal fun StatoMail(c: ContestazioneInviata) {
    val (testo, colore) = when {
        !c.conMail -> "Solo link, nessuna mail" to Colori.Testo2
        c.stato == StatoAvviso.INVIATA -> "Mail inviata alle ${c.inviataAlle?.oraMinuti() ?: "?"} a ${destinatari(c.destinatari.size)}" to Colori.Entrata
        c.stato == StatoAvviso.FALLITA -> "Mail non inviata: ${c.errore ?: "controlla l'indirizzo"}" to Colori.Pericolo
        c.stato == StatoAvviso.RITENTA -> "Mail non ancora partita: il server riprova" to Colori.Giallo
        else -> "Mail in invio a ${destinatari(c.destinatari.size)}…" to Colori.Giallo
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            if (c.conMail && c.stato != StatoAvviso.INVIATA) Icone.Attesa else Icone.Fatto,
            contentDescription = null, tint = colore, modifier = Modifier.size(18.dp),
        )
        Text(testo, style = Testo.Corpo14.copy(color = colore, fontWeight = FontWeight.Bold))
    }
}

/** Riga di una contestazione già mandata, per il Dettaglio. */
@Composable
internal fun RigaContestazione(c: ContestazioneInviata) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${c.creata.toLocalDate().giornoMese()} alle ${c.creata.oraMinuti()}" + if (c.timbraturaId == null) " · tutta la giornata" else "",
            style = Testo.Corpo14.copy(fontWeight = FontWeight.Bold),
        )
        StatoMail(c)
        val chi = if (c.destinatari.isEmpty()) "" else "A ${c.destinatari.first()}" + when (c.destinatari.size) { 1 -> ""; 2 -> " e un altro"; else -> " e altri ${c.destinatari.size - 1}" } + " · "
        Text(chi.removeSuffix(" · ") + (c.scade?.let { (if (chi.isEmpty()) "" else " · ") + "link fino al ${it.breve()}" } ?: ""), style = Testo.Piccolo12)
        c.nota?.let { Text("Nota: $it", style = Testo.Piccolo12) }
    }
}

private fun destinatari(n: Int) = if (n == 1) "1 destinatario" else "$n destinatari"

private fun LocalDate.senzaAnnoMinuscolo(): String =
    java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ITALIAN).format(this)

internal fun copiaTesto(contesto: Context, testo: String) {
    contesto.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link Timbro", testo))
}

internal fun condividiTesto(contesto: Context, testo: String) {
    val invio = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, testo)
    contesto.startActivity(Intent.createChooser(invio, "Condividi il link").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
