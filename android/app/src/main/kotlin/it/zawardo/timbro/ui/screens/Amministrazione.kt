package it.zawardo.timbro.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.core.api.Amministrazione
import it.zawardo.timbro.core.api.Articolazione
import it.zawardo.timbro.core.api.CodiceAssociazione
import it.zawardo.timbro.core.api.Contestazioni
import it.zawardo.timbro.core.api.RichiestaAssegnazione
import it.zawardo.timbro.core.api.UtenteSistema
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.ui.LocalFirmatario
import it.zawardo.timbro.ui.Risultato
import it.zawardo.timbro.dati.FirmatarioAdmin
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import it.zawardo.timbro.ui.breve
import it.zawardo.timbro.ui.components.Badge
import it.zawardo.timbro.ui.components.BottonePericolo
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.Forma22
import it.zawardo.timbro.ui.components.Icone
import it.zawardo.timbro.ui.components.Indietro
import it.zawardo.timbro.ui.components.Pillola
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.oraMinuti
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import kotlinx.serialization.builtins.ListSerializer

/*
 * Amministrazione (solo per il ruolo admin): utenti e telefoni, articolazioni orarie con le versioni datate,
 * assegnazioni delle articolazioni ai profili. Sono le API `/api/v1/admin/system/...`, ognuna firmata con la chiave
 * admin: un'impronta per chiamata. Gli elenchi si leggono all'apertura della schermata che li usa; i rifiuti del
 * server (403, 409, 422) si mostrano con il suo messaggio.
 */

/** Stato di un'operazione dell'amministratore: in corso ed errore del server. */
private class Operazione {
    var inCorso by mutableStateOf(false)
    var errore by mutableStateOf<String?>(null)

    fun <T> esegui(vm: StatoApp, blocco: suspend () -> Risultato<T>, poi: (T) -> Unit = {}) {
        if (inCorso) return
        inCorso = true
        errore = null
        vm.viewModelScope.launch {
            when (val r = blocco()) {
                is Risultato.Ok -> poi(r.valore)
                is Risultato.Errore -> errore = r.messaggio
            }
            inCorso = false
        }
    }
}

@Composable
private fun rememberOperazione() = remember { Operazione() }

@Composable
private fun MostraErrore(op: Operazione, modifier: Modifier = Modifier) {
    op.errore?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold), modifier = modifier) }
}

/**
 * Gli elenchi dell'amministratore (utenti, articolazioni): si leggono da soli la prima volta che la schermata si apre
 * (impronta); se l'impronta si annulla o il server non risponde, tasto per riprovare. Già letti: tasto per rileggerli.
 */
@Composable
private fun DatiAdmin(vm: StatoApp, caricati: Boolean, cosa: String, carica: suspend (FirmatarioAdmin) -> Risultato<Unit>) {
    val firmatario = LocalFirmatario.current
    val op = rememberOperazione()
    var tentato by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!caricati && !tentato) {
            tentato = true
            op.esegui(vm, { carica(firmatario) })
        }
    }
    if (!caricati) {
        Text(
            if (op.inCorso) "Leggo $cosa dal server…" else "Servono $cosa del server: si leggono con l'impronta.",
            style = Testo.Piccolo13.copy(color = Colori.Inchiostro),
        )
        MostraErrore(op)
        if (!op.inCorso) BottonePrimario("Leggi $cosa", { op.esegui(vm, { carica(firmatario) }) }, icona = Icone.Impronta, altezza = 48.dp)
    } else {
        BottoneSecondario(
            if (op.inCorso) "Leggo dal server…" else "Rileggi $cosa dal server", { op.esegui(vm, { carica(firmatario) }) },
            altezza = 44.dp, abilitato = !op.inCorso,
        )
        MostraErrore(op)
    }
}

/** Colonna di una schermata dell'amministrazione; fuori dal ruolo admin si torna indietro. */
@Composable
private fun PaginaAdmin(vm: StatoApp, indietro: String, onIndietro: () -> Unit, contenuto: @Composable ColumnScope.() -> Unit) {
    LaunchedEffect(vm.admin) { if (!vm.admin) onIndietro() }
    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Indietro(indietro, onIndietro)
        contenuto()
    }
}

@Composable
private fun Salvato(salvato: Boolean, testo: String = "Salvato") {
    if (salvato) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icone.Fatto, contentDescription = null, tint = Colori.Entrata, modifier = Modifier.size(16.dp))
            Text(testo, style = Testo.Piccolo12.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
        }
    } else {
        Text("Il salvataggio chiede l'impronta digitale.", style = Testo.Piccolo12)
    }
}

private fun nomeDi(u: UtenteSistema): String = u.profile.nome?.takeIf { it.isNotBlank() } ?: u.username

private fun dataDi(iso: String): LocalDate? = runCatching { OffsetDateTime.parse(iso).toLocalDate() }.getOrNull()
    ?: runCatching { LocalDateTime.parse(iso).toLocalDate() }.getOrNull()

/** "lun-gio 8 h 00, ven 4 h 00 senza pausa": i giorni di una versione, in breve. */
internal fun riassunto(v: VersioneOrario?): String {
    if (v == null) return "nessuna versione in vigore"
    val giorni = (1..7).mapNotNull { g -> v.days[g]?.takeIf { it.dovutiMin > 0 }?.let { g to it } }
    if (giorni.isEmpty()) return "nessun giorno con ore dovute"
    val gruppi = mutableListOf<MutableList<Pair<Int, it.zawardo.timbro.core.RegolaGiorno>>>()
    giorni.forEach { (g, r) ->
        val ultimo = gruppi.lastOrNull()?.last()
        if (ultimo != null && ultimo.first == g - 1 && ultimo.second == r) gruppi.last() += g to r else gruppi += mutableListOf(g to r)
    }
    return gruppi.joinToString(", ") { gr ->
        val nome = { g: Int -> Ore.nomeGiorno(g).take(3) }
        val giorniTesto = if (gr.size == 1) nome(gr[0].first) else "${nome(gr.first().first)}-${nome(gr.last().first)}"
        val r = gr[0].second
        "$giorniTesto ${Ore.durata(r.dovutiMin)}${if (r.pause) "" else " senza pausa"}"
    }
}

// --- utenti ---------------------------------------------------------------------------------------------------------

@Composable
fun UtentiScreen(vm: StatoApp, onUtente: (Long) -> Unit, onNuovo: () -> Unit, onIndietro: () -> Unit) {
    val oggi = LocalDate.now()
    PaginaAdmin(vm, "Impostazioni", onIndietro) {
        Text("Utenti", style = Testo.Titolo30)
        Text(
            "Ogni utente vede e gestisce solo le proprie timbrature. Qui crei gli utenti, assegni l'articolazione oraria e " +
                "associ i telefoni.",
            style = Testo.Piccolo13,
        )
        DatiAdmin(vm, vm.utentiCaricati, "gli utenti") { vm.caricaUtenti(it) }
        if (vm.utentiCaricati) BottonePrimario("Nuovo utente", onNuovo, altezza = 50.dp)
        vm.utenti.sortedWith(compareByDescending<UtenteSistema> { it.id == vm.ioId }.thenBy { nomeDi(it).lowercase() }).forEach { u ->
            val a = u.assegnazione(oggi)
            val attivi = u.devices.count { it.attivo }
            Scheda(
                Modifier.clip(Forma22).clickable(role = Role.Button) { onUtente(u.id) },
                padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(nomeDi(u), style = Testo.Corpo16.copy(fontWeight = FontWeight.ExtraBold), modifier = Modifier.weight(1f))
                    if (u.id == vm.ioId) Badge("TU", Colori.SuColore, Colori.Entrata)
                    if (u.admin) Badge("ADMIN", Colori.SuColore, Colori.Azzurro)
                }
                Text(
                    "${u.username}${u.profile.matricola?.let { " · matricola $it" } ?: ""}",
                    style = Testo.Mono13.copy(color = Colori.Testo2), modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    a?.let { "${it.nomeArticolazione ?: "Articolazione"} · dal ${it.validoDal.breve()}" } ?: "Nessuna articolazione assegnata",
                    style = Testo.Piccolo13.copy(color = if (a == null) Colori.Giallo else Colori.Testo3), modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    when (attivi) { 0 -> "Nessun telefono associato"; 1 -> "1 telefono"; else -> "$attivi telefoni" },
                    style = Testo.Piccolo13.copy(color = if (attivi == 0) Colori.Giallo else Colori.Testo3),
                )
            }
        }
    }
}

@Composable
fun NuovoUtenteScreen(vm: StatoApp, onCreato: (Long) -> Unit, onIndietro: () -> Unit) {
    var username by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf("") }
    var matricola by rememberSaveable { mutableStateOf("") }
    var ruolo by rememberSaveable { mutableStateOf(Amministrazione.UTENTE) }
    val firmatario = LocalFirmatario.current
    val op = rememberOperazione()
    val problemi = Amministrazione.problemiNuovoUtente(username, nome.trim(), matricola.trim(), vm.utenti.map { it.username })
    PaginaAdmin(vm, "Utenti", onIndietro) {
        Text("Nuovo utente", style = Testo.Titolo30)
        Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CampoTesto(
                    "Nome utente", username, { username = it.lowercase().take(64) },
                    tastiera = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                    segnaposto = "rossi", errore = username.isNotEmpty() && !Amministrazione.nomeUtenteValido(username),
                )
                Text("Solo per il server: lettere minuscole, cifre, punto e trattini.", style = Testo.Piccolo12)
                CampoTesto(
                    "Nome e cognome", nome, { if (it.length <= Contestazioni.MAX_NOME) nome = it },
                    tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                CampoTesto(
                    "Matricola", matricola, { if (it.length <= Contestazioni.MAX_MATRICOLA) matricola = it },
                    tastiera = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                )
                Text("Ruolo", style = Testo.Piccolo13.copy(color = Colori.Testo3, fontWeight = FontWeight.SemiBold))
                SceltaRuolo(ruolo) { ruolo = it }
            }
        }
        if (username.isNotEmpty()) problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
        BottonePrimario(
            if (op.inCorso) "Creo l'utente…" else "Crea l'utente",
            {
                op.esegui(vm, { vm.creaUtente(username, ruolo, nome.trim().ifEmpty { null }, matricola.trim().ifEmpty { null }, firmatario) }) {
                    onCreato(it)
                }
            },
            icona = Icone.Impronta, altezza = 50.dp, abilitato = username.isNotEmpty() && problemi.isEmpty() && !op.inCorso,
        )
        MostraErrore(op)
        Text("Poi assegna l'articolazione oraria e associa il telefono.", style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SceltaRuolo(ruolo: String, onScelta: (String) -> Unit) {
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Pillola("Utente", ruolo == Amministrazione.UTENTE, { onScelta(Amministrazione.UTENTE) }, altezza = 44.dp, raggio = 22.dp, spenta = Colori.Rilievo)
        Pillola("Amministratore", ruolo == Amministrazione.ADMIN, { onScelta(Amministrazione.ADMIN) }, altezza = 44.dp, raggio = 22.dp, spenta = Colori.Rilievo)
    }
}

@Composable
fun UtenteScreen(vm: StatoApp, id: Long, onAssegnazioni: (Long) -> Unit, onIndietro: () -> Unit) {
    val u = vm.utente(id)
    if (u == null) {
        LaunchedEffect(Unit) { onIndietro() }
        return
    }
    val oggi = LocalDate.now()
    var nome by rememberSaveable(id) { mutableStateOf(u.profile.nome.orEmpty()) }
    var matricola by rememberSaveable(id) { mutableStateOf(u.profile.matricola.orEmpty()) }
    var email by rememberSaveable(id) { mutableStateOf(u.profile.email.orEmpty()) }
    var profiloSalvato by rememberSaveable(id) { mutableStateOf(false) }
    val firmatario = LocalFirmatario.current
    val opProfilo = rememberOperazione()
    val opRuolo = rememberOperazione()
    val opTelefoni = rememberOperazione()
    // il codice in chiaro si vede solo ora: non va nello stato salvato (come i token dei link)
    var codice by remember(id) { mutableStateOf<Pair<CodiceAssociazione, LocalDateTime>?>(null) }
    var daRevocare by rememberSaveable(id) { mutableStateOf<String?>(null) }
    val cambiato = nome.trim() != u.profile.nome.orEmpty() || matricola.trim() != u.profile.matricola.orEmpty() ||
        email.trim() != u.profile.email.orEmpty()
    val problemi = Contestazioni.problemiProfilo(nome.trim(), matricola.trim(), emptyList(), email.trim())

    PaginaAdmin(vm, "Utenti", onIndietro) {
        Column {
            Text(nomeDi(u), style = Testo.Titolo28)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Text(u.username, style = Testo.Mono14.copy(color = Colori.Testo2))
                if (u.id == vm.ioId) Badge("TU", Colori.SuColore, Colori.Entrata)
                if (u.admin) Badge("ADMIN", Colori.SuColore, Colori.Azzurro)
            }
        }

        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Profilo")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CampoTesto(
                    "Nome e cognome", nome, { if (it.length <= Contestazioni.MAX_NOME) { nome = it; profiloSalvato = false } },
                    tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                )
                CampoTesto(
                    "Matricola", matricola, { if (it.length <= Contestazioni.MAX_MATRICOLA) { matricola = it; profiloSalvato = false } },
                    tastiera = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                )
                CampoTesto(
                    "Indirizzo email personale (risposte e copia)", email,
                    { if (it.length <= Contestazioni.MAX_EMAIL) { email = it.replace("\n", ""); profiloSalvato = false } },
                    tastiera = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    errore = email.isNotBlank() && !Contestazioni.emailValida(email.trim()),
                )
                Text(
                    "Destinatari delle mail: ${u.profile.destinatari.size} (li gestisce l'utente nelle sue impostazioni).",
                    style = Testo.Piccolo12,
                )
                problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
                BottonePrimario(
                    if (opProfilo.inCorso) "Salvo…" else "Salva il profilo",
                    {
                        opProfilo.esegui(vm, {
                            vm.modificaProfiloUtente(id, nome.trim().ifEmpty { null }, matricola.trim().ifEmpty { null }, email.trim().ifEmpty { null }, firmatario)
                        }) { profiloSalvato = true }
                    },
                    icona = Icone.Impronta, altezza = 50.dp, abilitato = cambiato && problemi.isEmpty() && !opProfilo.inCorso,
                )
                MostraErrore(opProfilo)
                Salvato(profiloSalvato)
            }
        }

        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Ruolo")
            Text(
                "L'utente gestisce solo le proprie timbrature e il proprio profilo; l'amministratore anche utenti, telefoni e orari.",
                style = Testo.Piccolo12, modifier = Modifier.padding(bottom = 10.dp),
            )
            SceltaRuolo(u.role) { r -> if (r != u.role) opRuolo.esegui(vm, { vm.cambiaRuolo(id, r, firmatario) }) }
            if (opRuolo.inCorso) Text("Salvo il ruolo…", style = Testo.Piccolo12, modifier = Modifier.padding(top = 8.dp))
            if (Amministrazione.ultimoAdmin(vm.utenti, id) && opRuolo.errore == null) {
                Text("È l'unico amministratore: per togliergli il ruolo nominane prima un altro.", style = Testo.Piccolo12.copy(color = Colori.Giallo), modifier = Modifier.padding(top = 10.dp))
            }
            MostraErrore(opRuolo, Modifier.padding(top = 8.dp))
        }

        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Orario")
            if (u.assignments.isEmpty()) {
                Text("Nessuna articolazione assegnata: le sue ore si contano senza regole e senza saldo.", style = Testo.Piccolo13.copy(color = Colori.Giallo))
            }
            val inVigore = u.assegnazione(oggi)
            u.assignments.sortedByDescending { it.validoDal }.forEachIndexed { i, a ->
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                a.nomeArticolazione ?: "Articolazione ${a.articolazioneId}",
                                style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold, color = if (a == inVigore) Colori.Inchiostro else Colori.Testo2),
                            )
                            Text("dal ${a.validoDal.breve()}", style = Testo.Mono13.copy(color = Colori.Testo2))
                        }
                        when {
                            a == inVigore -> Badge("IN VIGORE", Colori.SuColore, Colori.Entrata)
                            a.validoDal.isAfter(oggi) -> Badge("FUTURA", Colori.SuColore, Colori.Azzurro)
                        }
                    }
                    if (i < u.assignments.lastIndex) HorizontalDivider(color = Colori.Divisore)
                }
            }
            BottoneSecondario("Assegnazioni", { onAssegnazioni(id) }, Modifier.padding(top = 10.dp), altezza = 48.dp, fondo = Colori.Rilievo)
        }

        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Telefoni")
            if (u.devices.isEmpty()) Text("Nessun telefono associato.", style = Testo.Piccolo13.copy(color = Colori.Giallo))
            u.devices.forEachIndexed { i, t ->
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold, color = if (t.attivo) Colori.Inchiostro else Colori.Disattivo))
                            Text(
                                "associato il ${dataDi(t.associatoIl)?.breve() ?: "?"}" + (t.revocatoIl?.let { " · revocato il ${dataDi(it)?.breve() ?: "?"}" } ?: ""),
                                style = Testo.Piccolo12,
                            )
                        }
                        when {
                            t.id == vm.questoTelefono -> Badge("QUESTO", Colori.SuColore, Colori.Entrata)
                            !t.attivo -> Badge("REVOCATO", Colori.Testo3, Colori.Rilievo, grassetto = false)
                            else -> TextButton(onClick = { daRevocare = t.id }) {
                                Text("Revoca", style = Testo.Corpo14.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold))
                            }
                        }
                    }
                    if (i < u.devices.lastIndex) HorizontalDivider(color = Colori.Divisore)
                }
            }
            BottonePrimario(
                if (opTelefoni.inCorso) "Chiedo il codice…" else "Associa un telefono",
                { opTelefoni.esegui(vm, { vm.codiceAssociazione(id, firmatario) }) { codice = it to LocalDateTime.now() } },
                Modifier.padding(top = 12.dp), icona = Icone.Impronta, altezza = 50.dp, abilitato = !opTelefoni.inCorso,
            )
            MostraErrore(opTelefoni, Modifier.padding(top = 8.dp))
            codice?.let { (c, creato) -> CodiceGrande(c, creato) }
        }
    }

    daRevocare?.let { tid ->
        val t = u.devices.firstOrNull { it.id == tid }
        AlertDialog(
            onDismissRequest = { daRevocare = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Revocare ${t?.name ?: "il telefono"}?", style = Testo.Titolo22) },
            text = {
                Text(
                    "Token e chiavi di quel telefono non varranno più, e i link che ha creato si revocano. Le timbrature restano.",
                    style = Testo.Corpo14.copy(color = Colori.Testo3),
                )
            },
            confirmButton = {
                TextButton(onClick = { daRevocare = null; opTelefoni.esegui(vm, { vm.revocaTelefono(id, tid, firmatario) }) }) {
                    Text("Revoca", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo))
                }
            },
            dismissButton = {
                TextButton(onClick = { daRevocare = null }) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
}

/** Il codice monouso, grande, da inserire nell'app del telefono nuovo; si vede solo ora. */
@Composable
private fun CodiceGrande(c: CodiceAssociazione, creato: LocalDateTime) {
    val contesto = LocalContext.current
    var copiato by remember(c.code) { mutableStateOf(false) }
    Column(
        Modifier.padding(top = 14.dp).fillMaxWidth().background(Colori.Fondo, RoundedCornerShape(16.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("CODICE PER ${c.username.uppercase()}", style = Testo.Sezione13)
        Text(
            c.code.chunked(4).joinToString(" "),
            style = Testo.Mono21.copy(fontSize = 30.sp, letterSpacing = 2.sp, color = Colori.Entrata),
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { contentDescription = "Codice ${c.code.toList().joinToString(" ")}" },
        )
        Text(
            "Monouso, valido ${c.minuti} minuti (fino alle ${creato.plusMinutes(c.minuti.toLong()).oraMinuti()}). Inseriscilo nell'app " +
                "del telefono nuovo, alla prima apertura: il telefono diventa di ${c.username}.",
            style = Testo.Piccolo12, textAlign = TextAlign.Center,
        )
        BottoneSecondario(
            if (copiato) "Copiato" else "Copia",
            {
                contesto.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Codice Timbro", c.code))
                copiato = true
            },
            altezza = 48.dp, fondo = Colori.Rilievo,
        )
    }
}

// --- assegnazioni ---------------------------------------------------------------------------------------------------

@Composable
fun AssegnazioniScreen(vm: StatoApp, id: Long, onIndietro: () -> Unit) {
    val u = vm.utente(id)
    if (u == null) {
        LaunchedEffect(Unit) { onIndietro() }
        return
    }
    val oggi = LocalDate.now()
    val serializzatore = ListSerializer(RichiestaAssegnazione.serializer())
    var bozzaJson by rememberSaveable(id) {
        mutableStateOf(Amministrazione.JSON.encodeToString(serializzatore, u.assignments.map { RichiestaAssegnazione(it.articolazioneId, it.validoDal) }))
    }
    var scelta by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var sceltaData by rememberSaveable(id) { mutableStateOf(false) }
    var salvato by rememberSaveable(id) { mutableStateOf(false) }
    val firmatario = LocalFirmatario.current
    val op = rememberOperazione()
    val bozza = Amministrazione.JSON.decodeFromString(serializzatore, bozzaJson).sortedBy { it.validoDal }
    fun cambia(nuova: List<RichiestaAssegnazione>) {
        bozzaJson = Amministrazione.JSON.encodeToString(serializzatore, nuova.sortedBy { it.validoDal })
        salvato = false
    }
    val problemi = Amministrazione.problemiAssegnazioni(bozza)
    val cambiato = bozza != u.assignments.map { RichiestaAssegnazione(it.articolazioneId, it.validoDal) }.sortedBy { it.validoDal }
    val inVigore = bozza.lastOrNull { !it.validoDal.isAfter(oggi) }

    PaginaAdmin(vm, nomeDi(u), onIndietro) {
        Text("Assegnazioni", style = Testo.Titolo30)
        Text(
            "Ogni articolazione vale dal suo giorno fino all'assegnazione successiva. Per un cambio di orario di questa sola " +
                "persona aggiungi un'assegnazione dal giorno del cambio: i giorni passati restano calcolati come allora.",
            style = Testo.Piccolo13,
        )
        Scheda(padding = PaddingValues(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 10.dp)) {
            TitoloSezione("Articolazioni di ${nomeDi(u)}")
            if (bozza.isEmpty()) Text("Nessuna.", style = Testo.Piccolo13.copy(color = Colori.Giallo), modifier = Modifier.padding(vertical = 8.dp))
            bozza.sortedByDescending { it.validoDal }.forEachIndexed { i, a ->
                Column {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(vm.articolazione(a.articolazioneId)?.name ?: "Articolazione ${a.articolazioneId}", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold))
                            Text("dal ${a.validoDal.breve()}", style = Testo.Mono13.copy(color = Colori.Testo2))
                        }
                        when {
                            a == inVigore -> Badge("IN VIGORE", Colori.SuColore, Colori.Entrata)
                            a.validoDal.isAfter(oggi) -> Badge("FUTURA", Colori.SuColore, Colori.Azzurro)
                        }
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { cambia(bozza - a) }
                                .semantics { contentDescription = "Togli l'assegnazione dal ${a.validoDal.breve()}" },
                            contentAlignment = Alignment.Center,
                        ) { Text("✕", style = Testo.Corpo16.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold)) }
                    }
                    if (i < bozza.lastIndex) HorizontalDivider(color = Colori.Divisore)
                }
            }
        }
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Aggiungi")
            Text("Scegli l'articolazione, poi il giorno da cui vale.", style = Testo.Piccolo12, modifier = Modifier.padding(bottom = 8.dp))
            if (!vm.articolazioniCaricate) DatiAdmin(vm, false, "le articolazioni") { vm.caricaArticolazioni(it) }
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.articolazioni.forEach { art ->
                    Pillola(art.name, scelta == art.id, { scelta = art.id }, Modifier.fillMaxWidth(), altezza = 48.dp, raggio = 16.dp, spenta = Colori.Rilievo)
                }
            }
            val sceltaArt = scelta?.let { vm.articolazione(it) }
            if (sceltaArt != null) {
                BottonePrimario("${sceltaArt.name}: dal giorno…", { sceltaData = true }, Modifier.padding(top = 12.dp), altezza = 48.dp)
            }
        }
        problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
        BottonePrimario(
            if (op.inCorso) "Salvo…" else "Salva le assegnazioni",
            { op.esegui(vm, { vm.assegna(id, bozza, firmatario) }) { salvato = true } },
            icona = Icone.Impronta, altezza = 50.dp, abilitato = cambiato && problemi.isEmpty() && !op.inCorso,
        )
        MostraErrore(op)
        Salvato(salvato, "Salvato: lo Storico di ${nomeDi(u)} ricalcola le ore")
    }

    val art = scelta
    if (sceltaData && art != null) {
        val usate = bozza.map { it.validoDal }.toSet()
        SceltaData(
            "Assegnata dal", "Aggiungi", generateSequence(oggi) { it.plusDays(1) }.first { it !in usate },
            (oggi.year - 5)..(oggi.year + 3), { it !in usate },
            onScelta = { d ->
                sceltaData = false
                cambia(bozza + RichiestaAssegnazione(art, d))
                scelta = null
            },
            onAnnulla = { sceltaData = false },
        )
    }
}

// --- articolazioni --------------------------------------------------------------------------------------------------

@Composable
fun ArticolazioniScreen(vm: StatoApp, onArticolazione: (Long) -> Unit, onNuova: () -> Unit, onIndietro: () -> Unit) {
    val oggi = LocalDate.now()
    PaginaAdmin(vm, "Impostazioni", onIndietro) {
        Text("Articolazioni orarie", style = Testo.Titolo30)
        Text(
            "Un'articolazione ha un nome e versioni datate, e si assegna a più persone. Se l'ente la cambia per tutti " +
                "aggiungi una versione: vale per ogni profilo assegnato.",
            style = Testo.Piccolo13,
        )
        DatiAdmin(vm, vm.articolazioniCaricate, "le articolazioni") { vm.caricaArticolazioni(it) }
        if (vm.articolazioniCaricate) BottonePrimario("Nuova articolazione", onNuova, altezza = 50.dp)
        vm.articolazioni.sortedBy { it.name.lowercase() }.forEach { a ->
            val chi = vm.assegnataA(a.id)
            val v = a.inVigore(oggi)
            Scheda(
                Modifier.clip(Forma22).clickable(role = Role.Button) { onArticolazione(a.id) },
                padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(a.name, style = Testo.Corpo16.copy(fontWeight = FontWeight.ExtraBold))
                Text(riassunto(v), style = Testo.Piccolo13.copy(color = Colori.Testo3), modifier = Modifier.padding(top = 6.dp))
                Text(
                    "${a.versions.size} ${if (a.versions.size == 1) "versione" else "versioni"}" +
                        (a.versions.filter { it.validoDal.isAfter(oggi) }.minByOrNull { it.validoDal }?.let { " · nuova dal ${it.validoDal.breve()}" } ?: ""),
                    style = Testo.Piccolo12,
                )
                // a chi è assegnata si sa solo con l'elenco degli utenti (letto da Utenti)
                if (vm.utentiCaricati) {
                    Text(
                        if (chi.isEmpty()) "Non assegnata" else "Assegnata a ${chi.joinToString(", ") { nomeDi(it) }}",
                        style = Testo.Piccolo12.copy(color = if (chi.isEmpty()) Colori.Giallo else Colori.Testo2),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ArticolazioneScreen(vm: StatoApp, id: Long, onIndietro: () -> Unit) {
    val a = vm.articolazione(id)
    if (a == null) {
        LaunchedEffect(Unit) { onIndietro() }
        return
    }
    val oggi = LocalDate.now()
    var bozzaJson by rememberSaveable(id) { mutableStateOf(Amministrazione.JSON.encodeToString(Articolazione.serializer(), a)) }
    var sceltaData by rememberSaveable(id) { mutableStateOf(false) }
    var salvato by rememberSaveable(id) { mutableStateOf(false) }
    var eliminare by rememberSaveable(id) { mutableStateOf(false) }
    val firmatario = LocalFirmatario.current
    val op = rememberOperazione()
    val bozza = Amministrazione.JSON.decodeFromString(Articolazione.serializer(), bozzaJson)
    fun cambia(nuova: Articolazione) {
        bozzaJson = Amministrazione.JSON.encodeToString(Articolazione.serializer(), nuova.copy(versions = nuova.versions.sortedBy { it.validoDal }))
        salvato = false
    }
    val chi = vm.assegnataA(id)
    val orario = Orario(bozza.versions)
    val inVigore = orario.inVigore(oggi)
    val problemi = Amministrazione.problemiNomeArticolazione(bozza.name, vm.articolazioni.filter { it.id != id }.map { it.name }) + orario.problemi()
    val cambiato = bozza.copy(name = bozza.name.trim()) != a

    PaginaAdmin(vm, "Articolazioni", onIndietro) {
        Text("Articolazione", style = Testo.Titolo30)
        Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            CampoTesto(
                "Nome", bozza.name, { if (it.length <= Amministrazione.MAX_NOME_ARTICOLAZIONE) cambia(bozza.copy(name = it)) },
                tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            )
            Text(
                when {
                    !vm.utentiCaricati -> "Le modifiche valgono per tutti i profili a cui è assegnata."
                    chi.isEmpty() -> "Non assegnata a nessuno."
                    else -> "Assegnata a ${chi.joinToString(", ") { nomeDi(it) }}: le modifiche valgono per tutti."
                },
                style = Testo.Piccolo12.copy(color = if (vm.utentiCaricati && chi.isEmpty()) Colori.Giallo else Colori.Testo2),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        bozza.versions.sortedByDescending { it.validoDal }.forEach { v ->
            EditorVersione(
                v, statoVersione(v, inVigore, oggi), eliminabile = bozza.versions.size > 1,
                onCambia = { nuova -> cambia(bozza.copy(versions = bozza.versions.map { if (it.validoDal == v.validoDal) nuova else it })) },
                onElimina = { cambia(bozza.copy(versions = bozza.versions.filterNot { it.validoDal == v.validoDal })) },
            )
        }
        BottoneSecondario("Aggiungi una versione dal…", { sceltaData = true }, altezza = 50.dp)
        Text("Parte come copia dell'ultima: cambia solo quello che cambia.", style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
        val chiamate = (if (bozza.name.trim() != a.name) 1 else 0) +
            bozza.versions.count { v -> a.versions.firstOrNull { it.validoDal == v.validoDal } != v } +
            a.versions.count { v -> bozza.versions.none { it.validoDal == v.validoDal } }
        BottonePrimario(
            if (op.inCorso) "Salvo…" else "Salva l'articolazione",
            { op.esegui(vm, { vm.salvaArticolazione(bozza, firmatario) }) { salvato = true } },
            icona = Icone.Impronta, altezza = 50.dp, abilitato = cambiato && problemi.isEmpty() && !op.inCorso,
        )
        if (chiamate > 1 && !salvato) Text("Chiede l'impronta $chiamate volte: una per ogni modifica (nome, versioni).", style = Testo.Piccolo12)
        MostraErrore(op)
        Salvato(salvato, "Salvato: gli Storici dei profili assegnati ricalcolano le ore")
        BottonePericolo("Elimina l'articolazione", { eliminare = true }, abilitato = chi.isEmpty() && !op.inCorso)
        if (chi.isNotEmpty()) Text("Si può eliminare solo quando non è assegnata a nessuno.", style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }

    if (sceltaData) {
        val usate = bozza.versions.map { it.validoDal }.toSet()
        val ultima = bozza.versions.maxByOrNull { it.validoDal }
        SceltaData(
            "Nuova versione dal", "Aggiungi", generateSequence(maxOf(oggi, ultima?.validoDal ?: oggi).plusDays(1)) { it.plusDays(1) }.first { it !in usate },
            (oggi.year - 5)..(oggi.year + 3), { it !in usate },
            onScelta = { d ->
                sceltaData = false
                val base = ultima ?: Orario.predefinito().versions.first()
                cambia(bozza.copy(versions = bozza.versions + base.copy(validoDal = d)))
            },
            onAnnulla = { sceltaData = false },
        )
    }
    if (eliminare) {
        AlertDialog(
            onDismissRequest = { eliminare = false },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Eliminare ${a.name}?", style = Testo.Titolo22) },
            text = { Text("Non è assegnata a nessuno: si elimina con tutte le sue versioni.", style = Testo.Corpo14.copy(color = Colori.Testo3)) },
            confirmButton = {
                TextButton(onClick = { eliminare = false; op.esegui(vm, { vm.eliminaArticolazione(id, firmatario) }) { onIndietro() } }) {
                    Text("Elimina", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo))
                }
            },
            dismissButton = {
                TextButton(onClick = { eliminare = false }) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
}

@Composable
fun NuovaArticolazioneScreen(vm: StatoApp, onCreata: (Long) -> Unit, onIndietro: () -> Unit) {
    val oggi = LocalDate.now()
    var nome by rememberSaveable { mutableStateOf("") }
    var versioneJson by rememberSaveable {
        mutableStateOf(Amministrazione.JSON.encodeToString(VersioneOrario.serializer(), Orario.predefinito().versions.first().copy(validoDal = oggi.withDayOfMonth(1).plusMonths(1))))
    }
    var sceltaData by rememberSaveable { mutableStateOf(false) }
    val firmatario = LocalFirmatario.current
    val op = rememberOperazione()
    val versione = Amministrazione.JSON.decodeFromString(VersioneOrario.serializer(), versioneJson)
    fun cambia(v: VersioneOrario) { versioneJson = Amministrazione.JSON.encodeToString(VersioneOrario.serializer(), v) }
    val problemi = Amministrazione.problemiNomeArticolazione(nome, vm.articolazioni.map { it.name }) + versione.problemi()

    PaginaAdmin(vm, "Articolazioni", onIndietro) {
        Text("Nuova articolazione", style = Testo.Titolo30)
        Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
            CampoTesto(
                "Nome", nome, { if (it.length <= Amministrazione.MAX_NOME_ARTICOLAZIONE) nome = it },
                tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                segnaposto = "36 h su 5 giorni, ven 4 h",
            )
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Prima versione dal ${versione.validoDal.breve()}", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                TextButton(onClick = { sceltaData = true }) { Text("Cambia", style = Testo.Corpo14.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold)) }
            }
        }
        EditorVersione(versione, statoVersione(versione, Orario(listOf(versione)).inVigore(oggi), oggi), eliminabile = false, onCambia = ::cambia, onElimina = {})
        if (nome.isNotEmpty()) problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
        BottonePrimario(
            if (op.inCorso) "Creo l'articolazione…" else "Crea l'articolazione",
            { op.esegui(vm, { vm.creaArticolazione(nome, versione, firmatario) }) { onCreata(it) } },
            icona = Icone.Impronta, altezza = 50.dp, abilitato = nome.isNotBlank() && problemi.isEmpty() && !op.inCorso,
        )
        MostraErrore(op)
        Text("Poi assegnala ai profili dalla pagina di ogni utente.", style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }

    if (sceltaData) {
        SceltaData(
            "Prima versione dal", "Scegli", versione.validoDal, (oggi.year - 5)..(oggi.year + 3), { true },
            onScelta = { d -> sceltaData = false; cambia(versione.copy(validoDal = d)) },
            onAnnulla = { sceltaData = false },
        )
    }
}
