package it.zawardo.timbro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import it.zawardo.timbro.core.api.Contestazioni
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.Icone
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.timbro.BuildConfig
import it.zawardo.timbro.dati.FirmatarioAdmin
import it.zawardo.timbro.ui.Collegamento
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.ui.LocalFirmatario
import it.zawardo.timbro.ui.Risultato
import it.zawardo.timbro.ui.errore
import it.zawardo.timbro.core.api.Conservazione
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import it.zawardo.timbro.ui.breve
import it.zawardo.timbro.ui.components.Badge
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.RigaSpunta
import it.zawardo.timbro.ui.components.RigaDato
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.giornoMese
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.ui.oraMinuti
import java.time.LocalDate
import java.util.Locale

/**
 * 7 · Impostazioni: profilo per le contestazioni, il mio orario (sola lettura), amministrazione (solo per il ruolo
 * admin), conservazione dei video, sveglia, marche, telefono.
 */
@Composable
fun ImpostazioniScreen(
    vm: StatoApp,
    collegamento: Collegamento,
    firmatario: FirmatarioAdmin,
    onOrario: () -> Unit,
    onUtenti: () -> Unit,
    onArticolazioni: () -> Unit,
    provaSveglia: () -> String,
) {
    var esitoProva by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Impostazioni", style = Testo.Titolo30, modifier = Modifier.padding(top = 2.dp))
        SchedaProfilo(vm)
        SchedaOrario(vm, onOrario)
        if (vm.admin) {
            SchedaAmministrazione(vm, onUtenti, onArticolazioni)
        } else {
            // per l'utente in sola lettura: la conservazione vale per tutti e la decide l'amministratore
            Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
                TitoloSezione("Conservazione dei video")
                val giorni = vm.conservazioneGiorni
                if (giorni == null) {
                    LeggiConImpronta(vm, "Mostra i giorni") { vm.caricaImpostazioni(it) }
                } else {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("$giorni", style = Testo.Display34)
                        Text("giorni", style = Testo.Piccolo13, modifier = Modifier.padding(bottom = 6.dp))
                    }
                }
                Text(
                    "Poi si cancella solo il file video. Impronte, firme, marche e catena restano per sempre. " +
                        "Esclusi i video contestati, da tenere o inclusi in un link ancora valido. La decide l'amministratore.",
                    style = Testo.Piccolo12,
                )
            }
        }
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Pausa e sveglia")
            Text(
                "Il rientro si timbra almeno 15 minuti dopo l'uscita: prima l'app chiede conferma. La sveglia per il " +
                    "rientro è accesa di default alla prima uscita dei giorni con la pausa, spenta alla seconda e nei " +
                    "giorni senza pausa (come il venerdì a mezza giornata); si cambia sull'uscita.",
                style = Testo.Piccolo13, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            BottoneSecondario("Prova la sveglia: suona tra 10 secondi", { esitoProva = provaSveglia() }, altezza = 48.dp, fondo = Colori.Rilievo)
            esitoProva?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 8.dp)) }
        }
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
            TitoloSezione("Marche qualificate")
            RigaDato("ACCV", "Qualificata · in uso", mono = false, valoreStile = Testo.Corpo14.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
            RigaDato("Sectigo", "Qualificata · in uso", mono = false, valoreStile = Testo.Corpo14.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
            RigaDato("Trusted List UE controllata", "${LocalDate.now().giornoMese()} · n. 190", divisore = false)
        }
        SchedaTelefono(collegamento, firmatario, daInviare = vm.inCoda, prima = vm::ferma) {
            if (vm.io?.admin == true) {
                // per provare: vedere l'app come la vede un utente senza il ruolo di amministratore
                RigaSpunta(
                    "Vedi come amministratore",
                    "Spento: l'app come la vede un utente normale, senza Amministrazione.",
                    vm.vediComeAdmin, { vm.vediComeAdmin = it },
                )
            }
            if (BuildConfig.DEBUG) {
                RigaSpunta(
                    "Modalità dimostrativa (debug)",
                    "Dati finti al posto di quelli del server, per guardare le schermate. Le timbrature vere restano.",
                    vm.dimostrazione, { vm.cambiaDimostrazione(it) }, Colori.Giallo,
                )
            }
        }
    }
}

/** Un dato di gestione che si legge dal server solo su richiesta: tasto, impronta, errore. */
@Composable
private fun LeggiConImpronta(vm: StatoApp, testo: String, leggi: suspend (FirmatarioAdmin) -> Risultato<Unit>) {
    val firmatario = LocalFirmatario.current
    var inCorso by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf<String?>(null) }
    Text("Si legge dal server con l'impronta.", style = Testo.Piccolo12, modifier = Modifier.padding(bottom = 8.dp))
    BottoneSecondario(
        if (inCorso) "Leggo dal server…" else testo,
        {
            inCorso = true
            errore = null
            vm.viewModelScope.launch {
                errore = leggi(firmatario).errore
                inCorso = false
            }
        },
        altezza = 44.dp, fondo = Colori.Rilievo, abilitato = !inCorso,
    )
    errore?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo, fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(top = 6.dp)) }
}

/** L'orario in vigore oggi, in breve, e il tasto per vederlo tutto (in sola lettura: lo modifica l'amministratore). */
@Composable
private fun SchedaOrario(vm: StatoApp, onOrario: () -> Unit) {
    val oggi = LocalDate.now()
    val v = vm.versione(oggi)
    Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
        TitoloSezione("Il mio orario")
        vm.assegnazioneOggi(oggi)?.let { a ->
            Text(
                "${a.nomeArticolazione ?: "Articolazione"} · dal ${a.validoDal.breve()}",
                style = Testo.Corpo14.copy(fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        if (v == null) {
            Text("Nessun orario in vigore: lo Storico mostra le ore fra le timbrature, senza saldo.", style = Testo.Piccolo13.copy(color = Colori.Giallo))
        } else {
            (1..7).forEach { g ->
                val r = v.days[g]
                if (r != null && r.dovutiMin > 0) {
                    RigaDato(
                        Ore.nomeGiorno(g).replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                        Ore.durata(r.dovutiMin) + if (r.pause) "" else " · senza pausa",
                    )
                }
            }
            Text(
                "Ingresso ${v.ingressoDa.oraMinuti()}-${v.ingressoA.oraMinuti()} · pausa fra le ${v.pausaDa.oraMinuti()} e le " +
                    "${v.pausaA.oraMinuti()}, almeno ${v.pausaConteggiataMin} min · in vigore dal ${v.validoDal.breve()}",
                style = Testo.Piccolo12, modifier = Modifier.padding(top = 8.dp),
            )
            vm.orario.versions.filter { it.validoDal.isAfter(oggi) }.forEach {
                Text("Nuovo orario dal ${it.validoDal.breve()}", style = Testo.Piccolo12.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
            }
        }
        Text("Lo modifica l'amministratore.", style = Testo.Piccolo12, modifier = Modifier.padding(top = 6.dp))
        BottoneSecondario("Vedi tutto l'orario", onOrario, Modifier.padding(top = 10.dp), altezza = 48.dp, fondo = Colori.Rilievo)
    }
}

/** Solo per il ruolo admin: utenti e telefoni, articolazioni orarie e assegnazioni. */
@Composable
private fun SchedaAmministrazione(vm: StatoApp, onUtenti: () -> Unit, onArticolazioni: () -> Unit) {
    Scheda(bordo = Colori.Azzurro, padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TitoloSezione("Amministrazione", Modifier.weight(1f))
            Badge("ADMIN", Colori.SuColore, Colori.Azzurro)
        }
        Text(
            "Utenti, telefoni, articolazioni orarie e assegnazioni di tutti. Ogni modifica chiede l'impronta.",
            style = Testo.Piccolo12, modifier = Modifier.padding(bottom = 10.dp),
        )
        val telefoni = vm.utenti.sumOf { u -> u.devices.count { it.attivo } }
        BottoneSecondario(
            "Utenti e telefoni" + if (vm.utentiCaricati) " · ${vm.utenti.size} utenti, $telefoni telefoni" else "",
            onUtenti, altezza = 50.dp, fondo = Colori.Rilievo,
        )
        BottoneSecondario(
            "Articolazioni orarie" + if (vm.articolazioniCaricate) " · ${vm.articolazioni.size}" else "", onArticolazioni,
            Modifier.padding(top = 8.dp), altezza = 50.dp, fondo = Colori.Rilievo,
        )
        // la conservazione vale per tutti: la cambia solo l'amministratore (nell'app vera PATCH /admin/system/settings)
        TitoloSezione("Conservazione dei video", Modifier.padding(top = 8.dp))
        val attuali = vm.conservazioneGiorni
        if (attuali == null) {
            LeggiConImpronta(vm, "Mostra e cambia i giorni") { vm.caricaImpostazioni(it) }
        } else {
            val firmatario = LocalFirmatario.current
            var bozza by rememberSaveable(attuali) { mutableIntStateOf(attuali) }
            var inCorso by remember { mutableStateOf(false) }
            var errore by remember { mutableStateOf<String?>(null) }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Passo("−", "Meno giorni di conservazione") { bozza = (bozza - 30).coerceAtLeast(Conservazione.MIN) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$bozza", style = Testo.Display34)
                    Text(if (bozza == attuali) "giorni, per tutti" else "giorni (ora $attuali)", style = Testo.Piccolo13)
                }
                Passo("+", "Più giorni di conservazione") { bozza = (bozza + 30).coerceAtMost(Conservazione.MAX) }
            }
            if (bozza != attuali) {
                BottonePrimario(
                    if (inCorso) "Salvo…" else "Salva: $bozza giorni",
                    {
                        inCorso = true
                        errore = null
                        vm.viewModelScope.launch {
                            errore = vm.cambiaConservazione(bozza, firmatario).errore
                            inCorso = false
                        }
                    },
                    Modifier.padding(vertical = 6.dp), icona = Icone.Impronta, altezza = 48.dp, abilitato = !inCorso,
                )
            }
            errore?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo, fontWeight = FontWeight.SemiBold)) }
        }
        Text(
            "Poi si cancella solo il file video (impronte, firme, marche e catena restano), esclusi i video contestati, da " +
                "tenere o in un link valido. Fra 7 e 3650 giorni; ogni modifica chiede l'impronta.",
            style = Testo.Piccolo12,
        )
    }
}

@Composable
private fun Passo(segno: String, descrizione: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Colori.Rilievo, RoundedCornerShape(14.dp))
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) { Text(segno, style = Testo.Corpo16.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold)) }
}

/**
 * Profilo per le contestazioni: nome e matricola (nella mail e in testa alla pagina del link) e i destinatari delle
 * mail. Nell'app vera `GET/PUT /admin/profile`, con l'impronta.
 */
@Composable
private fun SchedaProfilo(vm: StatoApp) {
    val p = vm.profilo
    val firmatario = LocalFirmatario.current
    // i campi ripartono dal profilo quando arriva dal server (/me all'avvio, o dopo il salvataggio)
    var nome by rememberSaveable(p) { mutableStateOf(p.nome.orEmpty()) }
    var matricola by rememberSaveable(p) { mutableStateOf(p.matricola.orEmpty()) }
    var email by rememberSaveable(p) { mutableStateOf(p.email.orEmpty()) }
    // la lista come testo a righe: si salva con la rotazione senza un Saver apposta
    var elenco by rememberSaveable(p) { mutableStateOf(p.destinatari.joinToString("\n")) }
    var nuovo by rememberSaveable { mutableStateOf("") }
    var salvato by rememberSaveable { mutableStateOf(false) }
    var inCorso by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf<String?>(null) }
    val destinatari = elenco.split('\n').filter { it.isNotEmpty() }
    val candidato = nuovo.trim()
    val erroreNuovo = when {
        candidato.isEmpty() -> null
        !Contestazioni.emailValida(candidato) -> "Indirizzo non valido"
        destinatari.any { it.equals(candidato, ignoreCase = true) } -> "Già in elenco"
        destinatari.size >= Contestazioni.MAX_DESTINATARI -> "Al massimo ${Contestazioni.MAX_DESTINATARI} destinatari"
        else -> null
    }
    val aggiungibile = candidato.isNotEmpty() && erroreNuovo == null
    val aggiungi = {
        if (aggiungibile) {
            elenco = (destinatari + candidato).joinToString("\n")
            nuovo = ""
            salvato = false
        }
    }
    val problemi = Contestazioni.problemiProfilo(nome.trim(), matricola.trim(), destinatari, email.trim())
    val cambiato = nome.trim() != p.nome.orEmpty() || matricola.trim() != p.matricola.orEmpty() || destinatari != p.destinatari ||
        email.trim() != p.email.orEmpty()

    Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
        TitoloSezione("Profilo per le contestazioni")
        Text(
            "Nome e matricola compaiono nella mail all'ente e in testa alla pagina del link.",
            style = Testo.Piccolo12, modifier = Modifier.padding(bottom = 10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CampoTesto(
                "Nome e cognome", nome, { if (it.length <= Contestazioni.MAX_NOME) { nome = it; salvato = false } },
                tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
            )
            CampoTesto(
                "Matricola", matricola, { if (it.length <= Contestazioni.MAX_MATRICOLA) { matricola = it; salvato = false } },
                tastiera = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
            )
            CampoTesto(
                "Il tuo indirizzo email (riceve le risposte e una copia)", email,
                { if (it.length <= Contestazioni.MAX_EMAIL) { email = it.replace("\n", ""); salvato = false } },
                tastiera = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                segnaposto = "nome.cognome@gmail.com",
                errore = email.isNotBlank() && !Contestazioni.emailValida(email.trim()),
            )
            Text(
                "Le mail partono dalla casella del server: le risposte dell'ente arrivano a questo indirizzo, che riceve anche la copia.",
                style = Testo.Piccolo12,
            )
            Text("Destinatari delle mail", style = Testo.Piccolo13.copy(color = Colori.Testo3, fontWeight = FontWeight.SemiBold))
            if (destinatari.isEmpty()) {
                Text("Nessuno: una contestazione crea solo il link.", style = Testo.Piccolo12.copy(color = Colori.Giallo))
            }
            Column {
                destinatari.forEachIndexed { i, indirizzo ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(indirizzo, style = Testo.Mono13.copy(color = Colori.Inchiostro), modifier = Modifier.weight(1f))
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                                .clickable(role = Role.Button) { elenco = (destinatari - indirizzo).joinToString("\n"); salvato = false }
                                .semantics { contentDescription = "Togli $indirizzo" },
                            contentAlignment = Alignment.Center,
                        ) { Text("✕", style = Testo.Corpo16.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold)) }
                    }
                    if (i < destinatari.lastIndex) HorizontalDivider(color = Colori.Divisore)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoTesto(
                    "", nuovo, { nuovo = it.replace("\n", "") },
                    tastiera = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Done),
                    segnaposto = "ufficio.personale@ente.it", errore = erroreNuovo != null,
                    modifier = Modifier.weight(1f),
                    allInvio = aggiungi,
                )
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                        .background(if (aggiungibile) Colori.Inchiostro else Colori.Rilievo, RoundedCornerShape(14.dp))
                        .clickable(role = Role.Button, enabled = aggiungibile, onClick = aggiungi)
                        .semantics { contentDescription = "Aggiungi il destinatario" },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "+",
                        style = Testo.Corpo16.copy(
                            fontSize = 24.sp, fontWeight = FontWeight.Bold,
                            color = if (aggiungibile) Colori.SuColore else Colori.Disattivo,
                        ),
                    )
                }
            }
            erroreNuovo?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
            problemi.forEach { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
            val mail = vm.mailConfigurata
            RigaDato(
                "Invio mail",
                when (mail) {
                    true -> "configurato sul server"
                    false -> "non configurato sul server"
                    null -> "da verificare (si legge dal server con \"Aggiorna\")"
                },
                mono = false,
                divisore = false,
                valoreStile = Testo.Corpo14.copy(fontWeight = FontWeight.Bold, color = if (mail == true) Colori.Entrata else Colori.Giallo),
            )
            BottonePrimario(
                if (inCorso) "Salvo…" else "Salva il profilo",
                {
                    inCorso = true
                    errore = null
                    vm.viewModelScope.launch {
                        val r = vm.salvaProfilo(
                            p.copy(
                                nome = nome.trim().ifEmpty { null }, matricola = matricola.trim().ifEmpty { null },
                                destinatari = destinatari, email = email.trim().ifEmpty { null },
                            ),
                            firmatario,
                        )
                        errore = r.errore
                        salvato = r is Risultato.Ok
                        inCorso = false
                    }
                },
                icona = Icone.Impronta, altezza = 50.dp, abilitato = cambiato && problemi.isEmpty() && !inCorso,
            )
            errore?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo, fontWeight = FontWeight.SemiBold)) }
            if (salvato) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icone.Fatto, contentDescription = null, tint = Colori.Entrata, modifier = Modifier.size(16.dp))
                    Text("Salvato", style = Testo.Piccolo12.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
                }
            } else {
                Text("Il salvataggio chiede l'impronta digitale.", style = Testo.Piccolo12)
            }
        }
    }
}
