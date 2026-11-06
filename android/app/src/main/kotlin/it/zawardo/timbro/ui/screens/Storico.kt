package it.zawardo.timbro.ui.screens

import androidx.compose.foundation.Canvas
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import it.zawardo.timbro.core.api.AccessoLink
import it.zawardo.timbro.core.api.Contestazioni
import it.zawardo.timbro.core.api.DettaglioTimbratura
import it.zawardo.timbro.core.api.Gestione
import it.zawardo.timbro.core.api.LinkCondivisione
import it.zawardo.timbro.core.api.ModificaTimbratura
import it.zawardo.timbro.core.api.StatoTimbratura
import it.zawardo.timbro.ui.ContestazioneInviata
import it.zawardo.timbro.ui.LocalFirmatario
import it.zawardo.timbro.ui.Risultato
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlinx.coroutines.launch
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import it.zawardo.timbro.core.Ricerca
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.material3.AlertDialog
import it.zawardo.timbro.core.EsitoOre
import it.zawardo.timbro.core.Giornata
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.Settimana
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.ui.StatoInvio
import it.zawardo.timbro.ui.Timbratura
import it.zawardo.timbro.ui.Tipo
import it.zawardo.timbro.ui.breve
import it.zawardo.timbro.ui.components.Badge
import it.zawardo.timbro.ui.components.BadgeQualificata
import it.zawardo.timbro.ui.components.BottonePericolo
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.Forma22
import it.zawardo.timbro.ui.components.Icone
import it.zawardo.timbro.ui.components.Indietro
import it.zawardo.timbro.ui.components.Pillola
import it.zawardo.timbro.ui.components.RigaDato
import it.zawardo.timbro.ui.components.RigaSpunta
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.esteso
import it.zawardo.timbro.ui.giornoMese
import it.zawardo.timbro.ui.giornoMeseParole
import it.zawardo.timbro.ui.nomeGiorno
import it.zawardo.timbro.ui.nomeMese
import it.zawardo.timbro.ui.oraMinuti
import it.zawardo.timbro.ui.oraSecondi
import it.zawardo.timbro.ui.primi
import it.zawardo.timbro.ui.senzaAnno
import it.zawardo.timbro.ui.settimana
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private enum class Filtro(val etichetta: String, val vale: (Timbratura) -> Boolean) {
    TUTTE("Tutte", { true }),
    CONTESTATE("Contestate", { it.contestata }),
    DA_TENERE("Da tenere", { it.daTenere }),
}

/** La barra del giorno va dalle 7 alle 18: la pausa diventa una tacca, la giornata si legge a colpo d'occhio. */
private val INIZIO_BARRA: LocalTime = LocalTime.of(7, 0)
private val FINE_BARRA: LocalTime = LocalTime.of(18, 0)

private fun frazione(t: LocalTime): Float {
    val da = INIZIO_BARRA.toSecondOfDay()
    return ((t.toSecondOfDay() - da).toFloat() / (FINE_BARRA.toSecondOfDay() - da)).coerceIn(0f, 1f)
}

/** Le righe della lista dello Storico: intestazioni di settimana, giornate, e la scheda di un giorno cercato e vuoto. */
private sealed interface Voce {
    val chiave: String
}

private data class VoceSettimana(val lunedi: LocalDate) : Voce {
    override val chiave get() = "settimana-$lunedi"
}

private data class VoceGiorno(val g: Giornata<Timbratura>) : Voce {
    override val chiave get() = "giorno-${g.giorno}"
}

private data class VoceVuota(val data: LocalDate) : Voce {
    override val chiave get() = "vuoto-$data"
}

/**
 * 4 · Storico per giorno (stile C): una scheda per giornata con il totale, la barra 7-18 e tre blocchi (Entrata,
 * Pausa con uscita › rientro, Uscita); le settimane hanno il loro totale. Con un filtro restano i giorni che
 * c'entrano e le altre ore si attenuano. Ogni ora apre il Dettaglio.
 *
 * Ricerca per data: il calendario (niente date future) porta la lista a quel giorno e lo evidenzia per un attimo;
 * un giorno senza timbrature ha la sua scheda al suo posto. Come farà l'app vera con il backend (core `Ricerca`), si
 * vedono i giorni fino a qualche giorno dopo la data scelta; il chip "dal …" riporta a oggi.
 */
@Composable
fun StoricoScreen(vm: StatoApp, onApri: (Int) -> Unit, onCondividi: (List<Int>) -> Unit, onSegnala: (LocalDate) -> Unit) {
    var filtro by rememberSaveable { mutableStateOf(Filtro.TUTTE) }
    var ora by remember { mutableStateOf(LocalDateTime.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ora = LocalDateTime.now() }
    val oggi = ora.toLocalDate()
    var dataScelta by rememberSaveable { mutableStateOf<Long?>(null) }  // giorno epoch: LocalDate non va nel Bundle
    var calendario by rememberSaveable { mutableStateOf(false) }
    var salto by remember { mutableIntStateOf(0) }  // solo un salto chiesto ora: tornando dal Dettaglio non si risalta
    var evidenziato by remember { mutableStateOf<LocalDate?>(null) }
    val scelta = dataScelta?.let(LocalDate::ofEpochDay)

    var calcolo by rememberSaveable { mutableStateOf<Long?>(null) }  // giorno epoch del dettaglio delle ore aperto

    val tutte = vm.timbrature
    val giornate = Giornata.di(tutte)
    // ore valide con l'orario in vigore (core Ore, uguale al server); oggi contano fino ad adesso
    val esiti = giornate.associate { it.giorno to vm.ore(it.giorno, ora) }
    val totali = Settimana.di(giornate).associate { s ->
        val saldi = s.giornate.mapNotNull { esiti.getValue(it.giorno).saldoMin }
        s.lunedi to TotaleSettimana(s.giornate.sumOf { esiti.getValue(it.giorno).lavoratiMin }, saldi.takeIf { it.isNotEmpty() }?.sum())
    }
    val finestra = scelta?.let { Ricerca.finestra(it, oggi) }
    val visibili = giornate.filter { g ->
        (filtro == Filtro.TUTTE || g.segni.any(filtro.vale)) && (finestra == null || !g.giorno.isAfter(finestra.fino))
    }
    val voci: List<Voce> = buildList {
        val giorni: MutableList<Voce> = visibili.map { VoceGiorno(it) }.toMutableList()
        // un giorno cercato e vuoto ha la sua scheda solo quando lo storico di quel giorno è arrivato dal server
        if (scelta != null && vm.caricato(scelta)) {
            val p = Ricerca.posizione(visibili.map { it.giorno }, scelta)
            if (!p.presente) giorni.add(p.indice, VoceVuota(scelta))
        }
        var settimana: LocalDate? = null
        for (v in giorni) {
            val giorno = if (v is VoceGiorno) v.g.giorno else (v as VoceVuota).data
            val lunedi = Settimana.lunedi(giorno)
            if (lunedi != settimana) {
                add(VoceSettimana(lunedi))
                settimana = lunedi
            }
            add(v)
        }
    }
    val lista = rememberLazyListState()
    LaunchedEffect(salto) {
        if (salto == 0) return@LaunchedEffect
        if (scelta == null) {
            lista.animateScrollToItem(0)
            return@LaunchedEffect
        }
        val i = voci.indexOfFirst { (it is VoceGiorno && it.g.giorno == scelta) || (it is VoceVuota && it.data == scelta) }
        if (i < 0) return@LaunchedEffect
        // con l'intestazione della sua settimana, se è subito sopra
        lista.animateScrollToItem(if (i > 0 && voci[i - 1] is VoceSettimana) i - 1 else i)
        evidenziato = scelta
        delay(1600)
        evidenziato = null
    }
    val tornaAOggi: () -> Unit = {
        dataScelta = null
        salto++
    }

    Column(Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Storico", style = Testo.Titolo30, modifier = Modifier.weight(1f).semantics { heading() })
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Colori.Superficie, CircleShape)
                        .clickable(role = Role.Button) { calendario = true }
                        .semantics { contentDescription = "Vai a un giorno" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icone.Calendario, contentDescription = null, tint = Colori.Inchiostro, modifier = Modifier.size(22.dp)) }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Colori.Inchiostro, CircleShape)
                        .clickable(role = Role.Button) {
                            // senza filtro si condividono le contestate (il caso tipico), altrimenti quelle filtrate
                            val f = if (filtro == Filtro.TUTTE) Filtro.CONTESTATE else filtro
                            onCondividi(tutte.filter { f.vale(it) && it.sulServer }.sortedBy { it.quando }.map { it.id })
                        }
                        .semantics { contentDescription = "Condividi con un link" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icone.Link, contentDescription = null, tint = Colori.SuColore, modifier = Modifier.size(20.dp)) }
            }
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Filtro.entries.forEach { f -> Pillola(f.etichetta, f == filtro, { filtro = f }, ruolo = Role.Tab) }
            }
            if (scelta != null) ChipData(scelta, tornaAOggi)
            Legenda()
            StatoStorico(vm)
        }
        LazyColumn(
            Modifier.weight(1f),
            state = lista,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(voci, key = { it.chiave }) { v ->
                when (v) {
                    is VoceSettimana -> IntestazioneSettimana(etichettaSettimana(v.lunedi, oggi), totali[v.lunedi] ?: TotaleSettimana(0, null))
                    is VoceGiorno -> SchedaGiorno(
                        v.g, esiti.getValue(v.g.giorno), oggi, ora, filtro, onApri, vm::uscitaDiPausa,
                        evidenziata = v.g.giorno == evidenziato,
                        onCalcolo = { calcolo = v.g.giorno.toEpochDay() },
                    )
                    is VoceVuota -> SchedaGiornoVuoto(v.data, filtro != Filtro.TUTTE, evidenziata = v.data == evidenziato, tornaAOggi)
                }
            }
            if (voci.isEmpty()) {
                item {
                    Text(
                        when {
                            filtro != Filtro.TUTTE -> "Nessuna timbratura con questo filtro."
                            vm.caricamento -> "Carico lo storico dal server…"
                            else -> "Nessuna timbratura. Quelle fatte con l'app compaiono qui appena registrate."
                        },
                        style = Testo.Piccolo13, modifier = Modifier.padding(4.dp),
                    )
                }
            }
            // pagina precedente dello storico (giorni completi, dal server)
            if (scelta == null && vm.altriPrima != null && !vm.dimostrazione) {
                item(key = "precedenti") {
                    BottoneSecondario(
                        if (vm.caricamento) "Carico…" else "Carica i giorni precedenti", { vm.caricaPrecedenti() },
                        Modifier.padding(top = 4.dp), altezza = 48.dp, abilitato = !vm.caricamento,
                    )
                }
            }
        }
    }

    calcolo?.let(LocalDate::ofEpochDay)?.let { giorno ->
        val firmatario = LocalFirmatario.current
        var controllo by remember(giorno) { mutableStateOf<String?>(null) }  // "…" mentre legge, poi l'eventuale errore
        // le segnalazioni già fatte si leggono con l'impronta (una volta per sessione) prima di segnalare di nuovo
        fun controllaPoi(dopo: () -> Unit) {
            controllo = "…"
            vm.viewModelScope.launch {
                controllo = when (val r = vm.caricaAvvisi(firmatario)) {
                    is Risultato.Ok -> null.also { dopo() }
                    is Risultato.Errore -> r.messaggio
                }
            }
        }
        DettaglioOre(
            giorno, vm.ore(giorno, ora), vm.versione(giorno), vm.contestazioniDelGiorno(giorno), vm.avvisiLetti, controllo,
            onControlla = { controllaPoi {} },
            onSegnala = {
                val vai = {
                    if (!vm.giornataContestata(giorno)) {
                        calcolo = null
                        onSegnala(giorno)
                    }
                }
                if (vm.avvisiLetti) vai() else controllaPoi(vai)
            },
            onChiudi = { calcolo = null },
        )
    }

    if (calendario) {
        Calendario(
            iniziale = scelta ?: oggi,
            oggi = oggi,
            onScelta = { d ->
                calendario = false
                dataScelta = d.toEpochDay()
                vm.caricaGiorno(d)
                salto++
            },
            onAnnulla = { calendario = false },
        )
    }
}

/** Sotto i filtri: aggiornamento in corso, errore con "Riprova", modalità dimostrativa. Niente quando va tutto bene. */
@Composable
private fun StatoStorico(vm: StatoApp) {
    val errore = vm.erroreStorico
    when {
        vm.dimostrazione -> Text("Modalità dimostrativa: dati finti", style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.Bold))
        vm.caricamento -> Text("Aggiornamento dal server…", style = Testo.Piccolo12)
        errore != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Non aggiornato: $errore", style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { vm.aggiornaStorico() }) {
                Text("Riprova", style = Testo.Corpo14.copy(color = Colori.Entrata, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** "dal 15 ottobre ✕": si sta guardando una data scelta; il tocco riporta a oggi. */
@Composable
private fun ChipData(data: LocalDate, onChiudi: () -> Unit) {
    Row(
        Modifier.minimumInteractiveComponentSize().clip(RoundedCornerShape(20.dp))
            .border(2.dp, Colori.Entrata, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onChiudi)
            .semantics(mergeDescendants = true) { contentDescription = "Dal ${data.giornoMeseParole()}: torna a oggi" }
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("dal ${data.giornoMeseParole()}", style = Testo.Corpo14.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Entrata))
        Text("✕", style = Testo.Corpo14.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Entrata))
    }
}

/** Il DatePicker di Material3 in stile C: scuro, menta per il giorno scelto e per oggi, niente date future. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Calendario(iniziale: LocalDate, oggi: LocalDate, onScelta: (LocalDate) -> Unit, onAnnulla: () -> Unit) {
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()  // il DatePicker ragiona in UTC
    fun data(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    val stato = rememberDatePickerState(
        initialSelectedDateMillis = millis(iniziale),
        initialDisplayedMonthMillis = millis(iniziale.withDayOfMonth(1)),
        yearRange = 2024..oggi.year,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = Ricerca.selezionabile(data(utcTimeMillis), oggi)
            override fun isSelectableYear(year: Int) = year <= oggi.year
        },
    )
    val colori = DatePickerDefaults.colors(
        containerColor = Colori.Superficie,
        titleContentColor = Colori.Testo2,
        headlineContentColor = Colori.Inchiostro,
        weekdayContentColor = Colori.Testo2,
        subheadContentColor = Colori.Testo2,
        navigationContentColor = Colori.Inchiostro,
        yearContentColor = Colori.Inchiostro,
        disabledYearContentColor = Colori.Disattivo,
        currentYearContentColor = Colori.Entrata,
        selectedYearContentColor = Colori.SuColore,
        selectedYearContainerColor = Colori.Entrata,
        dayContentColor = Colori.Inchiostro,
        disabledDayContentColor = Colori.Disattivo,
        selectedDayContentColor = Colori.SuColore,
        selectedDayContainerColor = Colori.Entrata,
        todayContentColor = Colori.Entrata,
        todayDateBorderColor = Colori.Entrata,
        dividerColor = Colori.Bordo,
    )
    DatePickerDialog(
        onDismissRequest = onAnnulla,
        colors = colori,
        shape = Forma22,
        confirmButton = {
            TextButton(onClick = { stato.selectedDateMillis?.let { onScelta(data(it)) } }, enabled = stato.selectedDateMillis != null) {
                Text("Vai", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Entrata))
            }
        },
        dismissButton = {
            TextButton(onClick = onAnnulla) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
        },
    ) {
        DatePicker(
            state = stato,
            colors = colori,
            showModeToggle = false,
            title = { Text("Vai a un giorno", style = Testo.Sezione13, modifier = Modifier.padding(start = 24.dp, top = 20.dp)) },
        )
    }
}

/** Un giorno cercato senza timbrature (ferie, festivo, fine settimana): al suo posto nella lista. */
@Composable
private fun SchedaGiornoVuoto(data: LocalDate, conFiltro: Boolean, evidenziata: Boolean, onTornaAOggi: () -> Unit) {
    val fondo by animateColorAsState(if (evidenziata) Colori.AccentoChiaro else Colori.Superficie, tween(500), label = "evidenza")
    Column(
        Modifier.fillMaxWidth().background(fondo, Forma22)
            .border(2.dp, if (evidenziata) Colori.Entrata else Colori.BordoControllo, Forma22)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "%02d", data.dayOfMonth), style = Testo.Titolo28.copy(color = Colori.Testo2))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(data.nomeGiorno(), style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Testo3))
                Text(data.nomeMese(), style = Testo.Piccolo12.copy(fontWeight = FontWeight.SemiBold))
            }
        }
        Text(
            "Nessuna timbratura ${if (conFiltro) "con questo filtro " else ""}il ${data.nomeGiorno().lowercase(Locale.ITALIAN)} ${data.dayOfMonth} ${data.nomeMese()}",
            style = Testo.Corpo15.copy(fontWeight = FontWeight.SemiBold),
        )
        BottoneSecondario("Torna a oggi", onTornaAOggi, altezza = 44.dp, fondo = Colori.Rilievo)
    }
}

private fun etichettaSettimana(lunedi: LocalDate, oggi: LocalDate): String {
    val questa = Settimana.lunedi(oggi)
    return when (lunedi) {
        questa -> "QUESTA SETTIMANA"
        questa.minusWeeks(1) -> "SETTIMANA SCORSA"
        else -> "SETTIMANA DEL ${lunedi.giornoMeseParole().uppercase(Locale.ITALIAN)}"
    }
}

/** Ore valide della settimana e saldo delle giornate complete. */
private data class TotaleSettimana(val lavoratiMin: Int, val saldoMin: Int?)

@Composable
private fun IntestazioneSettimana(etichetta: String, totale: TotaleSettimana) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(etichetta, style = Testo.Settimana12, modifier = Modifier.weight(1f))
        Text(Ore.durata(totale.lavoratiMin), style = Testo.Mono13.copy(color = Colori.Testo2, fontWeight = FontWeight.Bold))
        totale.saldoMin?.let {
            Text(
                "  ${Ore.saldo(it)}",
                style = Testo.Mono13.copy(color = if (it < 0) Colori.Giallo else Colori.Entrata, fontWeight = FontWeight.Bold),
                modifier = Modifier.semantics { contentDescription = "saldo ${Ore.saldo(it)}" },
            )
        }
    }
}

@Composable
private fun Legenda() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        VoceLegenda("contestata") { Box(Modifier.size(12.dp, 10.dp).border(2.dp, Colori.Giallo, RoundedCornerShape(4.dp))) }
        VoceLegenda("da tenere") { Box(Modifier.size(8.dp).background(Colori.Azzurro, CircleShape)) }
        VoceLegenda("in invio") { Box(Modifier.size(12.dp, 10.dp).tratteggio(Colori.Testo2, 4.dp)) }
    }
}

@Composable
private fun VoceLegenda(testo: String, segno: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        segno()
        Text(testo, style = Testo.Piccolo12.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Bordo tratteggiato (timbrature in invio, ore che mancano). */
private fun Modifier.tratteggio(colore: Color, raggio: Dp, spessore: Dp = 2.dp) = drawBehind {
    val s = spessore.toPx()
    drawRoundRect(
        colore, topLeft = Offset(s / 2, s / 2), size = Size(size.width - s, size.height - s),
        cornerRadius = CornerRadius(raggio.toPx()),
        style = Stroke(s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

@Composable
private fun SchedaGiorno(
    g: Giornata<Timbratura>, ore: EsitoOre, oggi: LocalDate, ora: LocalDateTime, filtro: Filtro, onApri: (Int) -> Unit,
    uscitaDiPausa: (LocalDate, LocalTime) -> Boolean, evidenziata: Boolean = false, onCalcolo: () -> Unit,
) {
    val fondo by animateColorAsState(if (evidenziata) Colori.AccentoChiaro else Colori.Superficie, tween(500), label = "evidenza")
    val eOggi = g.giorno == oggi
    val aperta = eOggi && (g.inCorso || g.inPausa)  // oggi non ancora finita: il totale vale "finora"
    val c0 = g.coppie.getOrNull(0)
    val c1 = g.coppie.getOrNull(1)
    // una sola coppia: l'uscita è di fine giornata, salvo oggi se è nella fascia della pausa (si rientrerà)
    val uscita0 = c0?.uscita
    val senzaPausa = g.coppie.size == 1 && uscita0 != null &&
        !(eOggi && uscitaDiPausa(g.giorno, uscita0.quando.toLocalTime()))
    val entrata = c0?.entrata
    val uscitaPausa = if (senzaPausa) null else c0?.uscita
    val rientro = c1?.entrata
    val uscitaFinale = if (senzaPausa) c0?.uscita else c1?.uscita
    val altre = g.coppie.drop(2).flatMap { listOfNotNull(it.entrata, it.uscita) }
    val saldo = ore.saldoMin
    val descrizione = "${g.giorno.nomeGiorno()} ${g.giorno.dayOfMonth} ${g.giorno.nomeMese()}, ${Ore.durata(ore.lavoratiMin)} " +
        (if (aperta) "finora" else "valide") + (saldo?.let { ", saldo ${Ore.saldo(it)}" } ?: "")
    Column(
        Modifier.fillMaxWidth().background(fondo, Forma22)
            .then(if (eOggi || evidenziata) Modifier.border(2.dp, Colori.Entrata, Forma22) else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.semantics(mergeDescendants = true) { contentDescription = descrizione }, verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(Locale.ROOT, "%02d", g.giorno.dayOfMonth), style = Testo.Titolo28)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    g.giorno.nomeGiorno() + if (eOggi) " · oggi" else "",
                    style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = if (eOggi) Colori.Entrata else Colori.Inchiostro),
                )
                Text(g.giorno.nomeMese(), style = Testo.Piccolo12.copy(fontWeight = FontWeight.SemiBold))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Ore.durata(ore.lavoratiMin), style = Testo.Mono18)
                Row {
                    Text(if (aperta) "finora" else "valide", style = Testo.Piccolo12.copy(fontWeight = FontWeight.SemiBold))
                    saldo?.let {
                        Text(
                            " · ${Ore.saldo(it)}",
                            style = Testo.Piccolo12.copy(fontWeight = FontWeight.Bold, color = if (it < 0) Colori.Giallo else Colori.Entrata),
                        )
                    }
                }
            }
            Box(
                Modifier.padding(start = 6.dp).minimumInteractiveComponentSize().size(32.dp).clip(CircleShape)
                    .background(Colori.Rilievo, CircleShape).clickable(role = Role.Button, onClick = onCalcolo)
                    .semantics { contentDescription = "Come sono calcolate le ore, e segnala la giornata" },
                contentAlignment = Alignment.Center,
            ) { Text("i", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold)) }
        }
        BarraGiorno(g, if (aperta) ora else null)
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text("ENTRATA", style = Testo.Etichetta11.copy(color = Colori.Entrata))
                OraTimbratura(entrata, Tipo.ENTRATA, grande = true, filtro, onApri)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val pausa = g.pausa?.let { " ${it.primi()}" } ?: ""
                Text(if (senzaPausa) "SENZA PAUSA" else "PAUSA$pausa", style = Testo.Etichetta11.copy(color = Colori.Testo2))
                if (!senzaPausa) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        OraTimbratura(uscitaPausa, Tipo.USCITA, grande = false, filtro, onApri)
                        Text("›", style = Testo.Corpo14.copy(color = Colori.Disattivo))
                        OraTimbratura(rientro, Tipo.ENTRATA, grande = false, filtro, onApri)
                    }
                }
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text("USCITA", style = Testo.Etichetta11.copy(color = Colori.Uscita))
                OraTimbratura(uscitaFinale, Tipo.USCITA, grande = true, filtro, onApri)
            }
        }
        if (altre.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("ALTRE", style = Testo.Etichetta11.copy(color = Colori.Testo2), modifier = Modifier.padding(end = 6.dp))
                altre.forEach { OraTimbratura(it, it.tipo, grande = false, filtro, onApri) }
            }
        }
        // oggi l'uscita che manca è solo ancora da fare: niente avviso
        val daVerificare = ore.anomalie.filterNot { eOggi && it.codice == "uscita_mancante" }
        if (daVerificare.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(role = Role.Button, onClick = onCalcolo)
                    .background(Colori.ContestataChiaro, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icone.Avviso, contentDescription = null, tint = Colori.Giallo, modifier = Modifier.size(16.dp))
                Text(
                    "Da verificare: ${daVerificare.first().testo}" + if (daVerificare.size > 1) " (e altre ${daVerificare.size - 1})" else "",
                    style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold),
                )
            }
        }
    }
}

/**
 * Come sono calcolate le ore del giorno: ore valide, dovute e saldo, le correzioni applicate dalle regole e le cose
 * da verificare (gli stessi testi della pagina per l'ente), l'orario in vigore. Da qui si segnala la giornata.
 */
@Composable
private fun DettaglioOre(
    giorno: LocalDate, ore: EsitoOre, versione: VersioneOrario?, segnalazioni: List<ContestazioneInviata>, letti: Boolean,
    controllo: String?, onControlla: () -> Unit, onSegnala: () -> Unit, onChiudi: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onChiudi,
        containerColor = Colori.Superficie,
        shape = Forma22,
        title = { Text("Ore di ${giorno.nomeGiorno().lowercase(Locale.ITALIAN)} ${giorno.dayOfMonth} ${giorno.nomeMese()}", style = Testo.Titolo22) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                RiepilogoOre(ore.copy(anomalie = emptyList()))
                if (versione == null) {
                    Text("Nessun orario impostato: sono le ore fra le timbrature, senza regole né saldo.", style = Testo.Piccolo13)
                } else {
                    Text("CORREZIONI APPLICATE", style = Testo.Sezione13)
                    if (ore.correzioni.isEmpty()) {
                        Text("Nessuna: le ore valide coincidono con il tempo fra le timbrature.", style = Testo.Piccolo13)
                    } else {
                        ore.correzioni.forEach { Text("· ${it.testo}", style = Testo.Corpo14.copy(color = Colori.Testo3)) }
                    }
                    if (ore.anomalie.isNotEmpty()) {
                        Text("DA VERIFICARE", style = Testo.Sezione13.copy(color = Colori.Giallo))
                        ore.anomalie.forEach { Text("· ${it.testo}", style = Testo.Corpo14.copy(color = Colori.Giallo)) }
                    }
                    Text("ORARIO IN VIGORE", style = Testo.Sezione13)
                    Ore.descriviOrario(versione).forEach { Text(it, style = Testo.Piccolo12) }
                    Text("Ore e minuti come sul terminale: i secondi non contano.", style = Testo.Piccolo12)
                }
                Text("SEGNALAZIONI", style = Testo.Sezione13.copy(color = if (segnalazioni.isEmpty()) Colori.Testo2 else Colori.Giallo))
                when {
                    segnalazioni.isNotEmpty() -> segnalazioni.forEach { RigaContestazione(it) }
                    letti -> Text("Nessuna segnalazione per questa giornata.", style = Testo.Piccolo13)
                    controllo == "…" -> Text("Leggo le segnalazioni dal server…", style = Testo.Piccolo13)
                    else -> TextButton(onClick = onControlla, contentPadding = PaddingValues(0.dp)) {
                        Text("Controlla le segnalazioni già fatte (impronta)", style = Testo.Corpo14.copy(fontWeight = FontWeight.Bold))
                    }
                }
                controllo?.takeIf { it != "…" }?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Pericolo)) }
            }
        },
        confirmButton = {
            // una giornata si segnala una volta sola: se c'è già una segnalazione il pulsante non c'è
            if (segnalazioni.isEmpty()) {
                TextButton(onClick = onSegnala, enabled = controllo != "…") {
                    Text("Segnala la giornata", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Giallo))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onChiudi) { Text("Chiudi", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
        },
    )
}

/** Barra 7-18 con i periodi lavorati (menta → arancio); quello in corso tratteggiato fino ad adesso. */
@Composable
private fun BarraGiorno(g: Giornata<Timbratura>, ora: LocalDateTime?) {
    Canvas(Modifier.fillMaxWidth().height(12.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Colori.Rilievo, cornerRadius = r)
        g.coppie.forEach { c ->
            val inizio = c.entrata?.quando ?: return@forEach
            val fine = c.uscita?.quando ?: ora ?: return@forEach
            val x0 = frazione(inizio.toLocalTime()) * size.width
            val x1 = maxOf(frazione(fine.toLocalTime()) * size.width, x0 + 4.dp.toPx())
            if (c.uscita != null) {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Colori.Entrata, Colori.Uscita), startX = x0, endX = x1),
                    topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = r,
                )
            } else {
                val passo = 10.dp.toPx()
                var x = x0
                while (x < x1) {
                    drawRoundRect(Colori.Entrata, topLeft = Offset(x, 0f), size = Size(minOf(6.dp.toPx(), x1 - x), size.height), cornerRadius = CornerRadius(2.dp.toPx()))
                    x += passo
                }
            }
        }
    }
}

/** Un'ora della giornata: grande per entrata e uscita, piccola nella pausa; anello giallo, pallino azzurro, tratteggio. */
@Composable
private fun OraTimbratura(t: Timbratura?, tipo: Tipo, grande: Boolean, filtro: Filtro, onApri: (Int) -> Unit) {
    val stile = if (grande) Testo.Mono21 else Testo.Mono15
    val forma = RoundedCornerShape(8.dp)
    if (t == null) {
        Text(
            "··:··", style = stile.copy(color = Colori.Disattivo),
            modifier = Modifier.minimumInteractiveComponentSize().tratteggio(Colori.BordoControllo, 8.dp).padding(horizontal = 5.dp, vertical = 1.dp)
                .semantics { contentDescription = "${tipo.etichetta} non ancora registrata" },
        )
        return
    }
    val segni = listOfNotNull(
        "contestata".takeIf { t.contestata }, "video da tenere".takeIf { t.daTenere },
        "in invio".takeIf { t.stato == StatoInvio.IN_INVIO }, "video eliminato".takeIf { t.videoEliminato },
    )
    val attenuata = filtro != Filtro.TUTTE && !filtro.vale(t)
    Row(
        Modifier.minimumInteractiveComponentSize().alpha(if (attenuata) 0.32f else 1f).clip(forma)
            .then(
                when {
                    t.contestata -> Modifier.border(2.dp, Colori.Giallo, forma)
                    t.stato == StatoInvio.IN_INVIO -> Modifier.tratteggio(Colori.Testo2, 8.dp)
                    else -> Modifier
                },
            )
            .clickable(role = Role.Button) { onApri(t.id) }
            .semantics(mergeDescendants = true) {
                contentDescription = "${t.tipo.etichetta} ${t.quando.oraMinuti()}" + segni.joinToString("") { ", $it" }
            }
            .padding(horizontal = 5.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(t.quando.oraMinuti(), style = stile.copy(color = if (grande) Colori.Inchiostro else t.tipo.colore))
        if (t.daTenere) Box(Modifier.padding(start = 5.dp).size(8.dp).background(Colori.Azzurro, CircleShape))
    }
}

/** Operazioni del Dettaglio: una alla volta (ognuna chiede l'impronta). */
private enum class Lavoro { DETTAGLIO, FLAG, NOTE, VIDEO, PACCHETTO, ELIMINA, AVVISI }

private val NOMI_MARCHE = mapOf(
    "accv" to "ACCV", "sectigo-q" to "Sectigo", "freetsa" to "FreeTSA", "digicert" to "DigiCert", "ots" to "Bitcoin (OpenTimestamps)",
)

/** Ora locale di un istante ISO 8601 del server. */
internal fun oraDi(iso: String?): LocalDateTime? =
    iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime() }.getOrNull() }

/**
 * 5 · Dettaglio. Subito, senza impronta: tipo, ora, marche, video e attestazione (storico e `GET /punches/{id}`).
 * Con l'impronta: posizione, reti Wi-Fi, note e attestazione completa (`GET /admin/punches/{id}`), flag, giustificativo
 * e note (`PATCH`), video (ticket e player), pacchetto di prova da condividere, cancellazione del solo video.
 */
@Composable
fun DettaglioScreen(
    vm: StatoApp,
    id: Int,
    onIndietro: () -> Unit,
    onCondividi: (List<Int>) -> Unit,
    onContesta: (Int) -> Unit,
    onSegnalaGiorno: (LocalDate) -> Unit,
    onSparita: () -> Unit,
) {
    val t = vm.timbratura(id)
    if (t == null) {
        LaunchedEffect(id) { onSparita() }
        return
    }
    val firmatario = LocalFirmatario.current
    val contesto = LocalContext.current
    var stato by remember(id) { mutableStateOf<StatoTimbratura?>(null) }
    LaunchedEffect(id, t.serverId) { stato = vm.statoProve(t) }
    val d = t.serverId?.let { vm.dettagli[it] }
    var lavoro by remember(id) { mutableStateOf<Lavoro?>(null) }
    var errore by remember(id) { mutableStateOf<String?>(null) }
    var video by remember(id) { mutableStateOf<File?>(null) }
    val videoDaCancellare by rememberUpdatedState(video)
    DisposableEffect(id) { onDispose { vm.cancellaVideo(videoDaCancellare) } }
    var eliminare by rememberSaveable(id) { mutableStateOf(false) }
    var giustificativo by rememberSaveable(id, d?.giustificativo) { mutableStateOf(d?.giustificativo.orEmpty()) }
    var note by rememberSaveable(id, d?.notes) { mutableStateOf(d?.notes.orEmpty()) }

    fun <T> esegui(quale: Lavoro, blocco: suspend () -> Risultato<T>, poi: (T) -> Unit = {}) {
        if (lavoro != null) return
        lavoro = quale
        errore = null
        vm.viewModelScope.launch {
            when (val r = blocco()) {
                is Risultato.Ok -> poi(r.valore)
                is Risultato.Errore -> errore = r.messaggio
            }
            lavoro = null
        }
    }

    val gestibile = t.sulServer && lavoro == null
    val bloccata = t.contestata || t.daTenere
    val marche = (d?.stamps ?: stato?.stamps).orEmpty().filter { it.target == "video" }
    val attestazione = d?.attestation ?: stato?.attestation
    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Indietro("Storico", onIndietro)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(t.quando.toLocalDate().esteso(), style = Testo.Corpo14.copy(color = Colori.Testo2, fontWeight = FontWeight.SemiBold))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.tipo.etichetta.uppercase(Locale.ITALIAN), style = Testo.Titolo28.copy(color = t.tipo.colore))
                Text(t.quando.oraMinuti(), style = Testo.Mono21.copy(fontSize = 28.sp, lineHeight = 32.sp))
            }
            if (t.contestata || t.daTenere || t.videoEliminato || t.stato == StatoInvio.IN_INVIO) {
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.contestata) Badge("Contestata", Colori.SuColore, Colori.Giallo)
                    if (t.daTenere) Badge("Da tenere", Colori.SuColore, Colori.Azzurro)
                    if (t.stato == StatoInvio.IN_INVIO) Badge("In invio", Colori.Giallo, Colori.ContestataChiaro)
                    if (t.videoEliminato) Badge("Video eliminato", Colori.Testo3, Colori.Rilievo, grassetto = false)
                }
            }
        }
        PannelloVideo(
            t, video, lavoro == Lavoro.VIDEO,
            onRiproduci = { esegui(Lavoro.VIDEO, { vm.scaricaVideo(t, firmatario) }) { video = it } },
        )
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
            TitoloSezione("Orari e marche")
            RigaDato("Ora del telefono", t.quando.oraSecondi())
            val ricevuta = oraDi(d?.ricevutaIl ?: stato?.ricevutaIl ?: t.ricevutaIl)
            RigaDato(
                "Ricevuta dal server",
                when {
                    ricevuta == null && !t.sulServer -> "non ancora"
                    ricevuta == null -> "—"
                    ricevuta.toLocalDate() == t.quando.toLocalDate() -> ricevuta.oraSecondi()
                    else -> "${ricevuta.toLocalDate().giornoMese()} ${ricevuta.oraSecondi()}"
                },
            )
            when {
                marche.isNotEmpty() -> marche.sortedByDescending { it.qualified }.forEachIndexed { i, m ->
                    val ora = oraDi(m.ora)
                    RigaDato(
                        NOMI_MARCHE[m.provider] ?: m.provider,
                        when {
                            m.status == "complete" && m.provider == "ots" -> "confermata"
                            m.status == "complete" -> ora?.oraSecondi() ?: "fatta"
                            m.status == "failed" -> "non riuscita"
                            else -> "in attesa"
                        },
                        divisore = i < marche.lastIndex,
                        valoreStile = Testo.Mono14.copy(color = if (m.status == "complete") Colori.Inchiostro else Colori.Giallo),
                        accessorio = { if (m.qualified) BadgeQualificata() },
                    )
                }
                t.stato == StatoInvio.MARCATA -> RigaDato("Marca qualificata", "fatta", divisore = false, accessorio = { BadgeQualificata() })
                else -> RigaDato("Marche", if (t.sulServer) "in arrivo" else "dopo l'invio", divisore = false, valoreStile = Testo.Mono14.copy(color = Colori.Giallo))
            }
        }
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)) {
            TitoloSezione("Prove dal telefono")
            val ok = attestazione?.ok ?: t.attestazioneOk
            val livello = d?.attestazione?.livelloLeggibile
            RigaDato(
                "Attestazione hardware",
                when (ok) {
                    true -> "integro" + (livello?.let { " · $it" } ?: "")
                    false -> "non valida"
                    null -> if (t.catena?.conAttestazione == false) "assente" else "da verificare"
                },
                mono = false,
                valoreStile = Testo.Corpo14.copy(fontWeight = FontWeight.Bold, color = if (ok == false) Colori.Pericolo else if (ok == true) Colori.Entrata else Colori.Giallo),
                accessorio = { Icon(Icone.Scudo, null, tint = if (ok == false) Colori.Pericolo else Colori.Entrata, modifier = Modifier.size(16.dp)) },
                divisore = d != null || !attestazione?.problems.isNullOrEmpty(),
            )
            attestazione?.problems?.takeIf { it.isNotEmpty() }?.let { p ->
                Text(p.joinToString("\n") { "· $it" }, style = Testo.Piccolo12.copy(color = Colori.Giallo), modifier = Modifier.padding(vertical = 6.dp))
            }
            if (d != null) {
                DatiSensibili(d)
            } else {
                Text(
                    "Posizione, reti Wi-Fi, giustificativo e note restano sul server: si vedono con l'impronta.",
                    style = Testo.Piccolo12, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
                BottoneSecondario(
                    if (lavoro == Lavoro.DETTAGLIO) "Leggo dal server…" else "Mostra posizione, reti e note",
                    { esegui(Lavoro.DETTAGLIO, { vm.dettaglio(t, firmatario) }) },
                    altezza = 48.dp, fondo = Colori.Rilievo, abilitato = gestibile,
                )
            }
        }
        val inviate = vm.contestazioniDi(t)
        if (inviate.isNotEmpty()) {
            Scheda(bordo = Colori.Giallo, padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp)) {
                TitoloSezione("Contestazioni della giornata")
                inviate.forEachIndexed { i, c ->
                    RigaContestazione(c)
                    if (i < inviate.lastIndex) androidx.compose.material3.HorizontalDivider(color = Colori.Divisore)
                }
            }
        }
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 16.dp)) {
            TitoloSezione("Gestione")
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                RigaSpunta(
                    "Tieni il video",
                    vm.conservazioneGiorni?.let { "Escluso dalla cancellazione a $it giorni" } ?: "Escluso dalla cancellazione automatica",
                    t.daTenere, { v -> esegui(Lavoro.FLAG, { vm.modifica(t, ModificaTimbratura(keep = v), firmatario) }) }, Colori.Azzurro,
                    abilitata = gestibile,
                )
                if (d != null) {
                    CampoTesto("Giustificativo presentato", giustificativo, { if (it.length <= Gestione.MAX_GIUSTIFICATIVO) giustificativo = it })
                    CampoTesto("Note", note, { if (it.length <= Gestione.MAX_NOTE) note = it }, righe = 3)
                    val cambiati = giustificativo.trim() != d.giustificativo.orEmpty() || note.trim() != d.notes.orEmpty()
                    BottonePrimario(
                        if (lavoro == Lavoro.NOTE) "Salvo…" else "Salva giustificativo e note",
                        {
                            esegui(Lavoro.NOTE, {
                                vm.modifica(t, ModificaTimbratura(giustificativo = giustificativo.trim(), notes = note.trim()), firmatario)
                            })
                        },
                        icona = Icone.Impronta, altezza = 48.dp, abilitato = cambiati && gestibile,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icone.Lucchetto, contentDescription = null, tint = Colori.Testo2, modifier = Modifier.size(14.dp))
                    Text(
                        when {
                            !t.sulServer -> "Si gestisce quando è arrivata al server"
                            lavoro == Lavoro.FLAG -> "Salvo…"
                            else -> "Ogni modifica chiede l'impronta digitale"
                        },
                        style = Testo.Piccolo12,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // la contestazione riguarda sempre tutta la giornata (link, ore valide), con questa timbratura evidenziata;
            // una volta partita la mail resta: l'esito è nella scheda "Contestazioni inviate" qui sopra
            // una giornata si contesta una volta sola: prima di aprire Contesta si controllano le segnalazioni già fatte
            // (GET /admin/notices, con l'impronta, una volta per sessione), anche quelle della giornata intera
            if (inviate.isEmpty()) {
                BottonePrimario(
                    if (lavoro == Lavoro.AVVISI) "Controllo le segnalazioni…" else "Contesta la giornata",
                    {
                        if (vm.avvisiLetti) {
                            onContesta(t.id)
                        } else {
                            esegui(Lavoro.AVVISI, { vm.caricaAvvisi(firmatario) }) {
                                if (!vm.giornataContestata(t.quando.toLocalDate())) onContesta(t.id)
                            }
                        }
                    },
                    altezza = 50.dp, abilitato = gestibile,
                )
                Text(
                    "Manda all'ente la mail di disallineamento con il link a tutta la giornata, evidenziando questa timbratura. " +
                        "Prima controlla che la giornata non sia già stata segnalata.",
                    style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            BottoneSecondario("Condividi con un link", { onCondividi(listOf(t.id)) }, altezza = 50.dp, abilitato = t.sulServer)
            BottoneSecondario(
                if (lavoro == Lavoro.PACCHETTO) "Preparo il pacchetto…" else "Scarica il pacchetto di prova",
                { esegui(Lavoro.PACCHETTO, { vm.scaricaPacchetto(t, firmatario) }) { condividiFile(contesto, it, "application/zip", "Pacchetto di prova") } },
                altezza = 50.dp, abilitato = gestibile,
            )
            BottonePericolo(
                if (lavoro == Lavoro.ELIMINA) "Elimino…" else "Elimina solo il video", { eliminare = true },
                abilitato = gestibile && !bloccata && !t.videoEliminato && t.videoRicevuto,
            )
            Text(
                when {
                    bloccata -> "Non eliminabile: è contestata o da tenere. Impronta, firme e marche restano comunque per sempre."
                    t.videoEliminato -> "Video già eliminato. Impronta, firme e marche restano per sempre."
                    t.sulServer && !t.videoRicevuto -> "Il video non è ancora arrivato al server."
                    else -> "Restano impronta, firme e marche: il video non si recupera più."
                },
                style = Testo.Piccolo12, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (eliminare) {
        AlertDialog(
            onDismissRequest = { eliminare = false },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Eliminare il video?", style = Testo.Titolo22) },
            text = {
                Text(
                    "Il file video si cancella dal server e non si recupera più. Impronta, firme, marche e catena restano: " +
                        "provano che la timbratura c'è stata, ma senza il video.",
                    style = Testo.Corpo14.copy(color = Colori.Testo3),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    eliminare = false
                    video?.let { vm.cancellaVideo(it) }
                    video = null
                    esegui(Lavoro.ELIMINA, { vm.eliminaVideo(t, firmatario) })
                }) { Text("Elimina il video", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo)) }
            },
            dismissButton = {
                TextButton(onClick = { eliminare = false }) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
    errore?.let { e ->
        AlertDialog(
            onDismissRequest = { errore = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Operazione non riuscita", style = Testo.Titolo22) },
            text = { Text(e, style = Testo.Corpo14.copy(color = Colori.Testo3)) },
            confirmButton = {
                TextButton(onClick = { errore = null }) { Text("OK", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold)) }
            },
        )
    }
}

/** Il riquadro del video: player quando è scaricato, altrimenti il perché non c'è o il tasto per vederlo. */
@Composable
private fun PannelloVideo(t: Timbratura, file: File?, scarico: Boolean, onRiproduci: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(if (file != null) 420.dp else 200.dp).clip(Forma22)
            .border(3.dp, t.tipo.colore, Forma22).background(Colori.CameraPannello, Forma22),
        contentAlignment = Alignment.Center,
    ) {
        val messaggio = when {
            t.videoEliminato -> "Video eliminato: restano impronta, firme e marche"
            !t.sulServer -> "Il video parte dal telefono con la timbratura"
            !t.videoRicevuto -> "Il video non è ancora arrivato al server"
            else -> null
        }
        when {
            file != null -> Lettore(file)
            messaggio != null -> Text(messaggio, style = Testo.Piccolo13, textAlign = TextAlign.Center, modifier = Modifier.padding(20.dp))
            scarico -> Text("Scarico il video…", style = Testo.Piccolo13.copy(color = Colori.Inchiostro, fontWeight = FontWeight.Bold))
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(60.dp).clip(CircleShape).background(Colori.Inchiostro, CircleShape).clickable(role = Role.Button, onClick = onRiproduci)
                        .semantics { contentDescription = "Riproduci il video (chiede l'impronta)" },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icone.Riproduci, contentDescription = null, tint = Colori.SuColore, modifier = Modifier.size(24.dp)) }
                Text("Con l'impronta", style = Testo.Piccolo12)
            }
        }
    }
}

/** VideoView di Android sul file scaricato: parte da solo, un tocco mette in pausa o riprende. */
@Composable
private fun Lettore(file: File) {
    AndroidView(
        factory = { ctx ->
            VideoView(ctx).apply {
                setVideoPath(file.path)
                setOnPreparedListener { start() }
                setOnTouchListener { v, e ->
                    if (e.action == android.view.MotionEvent.ACTION_UP) {
                        if (isPlaying) pause() else start()
                        v.performClick()
                    }
                    true
                }
                contentDescription = "Video della timbratura: tocca per mettere in pausa o riprendere"
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize(),
    )
}

/** Posizione, reti Wi-Fi e app attestata: solo dopo l'impronta. */
@Composable
private fun DatiSensibili(d: DettaglioTimbratura) {
    val a = d.attestazione
    a?.app?.forEach { RigaDato("App", it, mono = false, valoreStile = Testo.Corpo14) }
    a?.let {
        RigaDato(
            "Telefono", listOfNotNull(
                when (it.avvio) { "verified" -> "avvio verificato"; null -> null; else -> "avvio ${it.avvio}" },
                when (it.bloccato) { true -> "bootloader bloccato"; false -> "bootloader sbloccato"; null -> null },
            ).joinToString(" · ").ifEmpty { "—" },
            mono = false, valoreStile = Testo.Corpo14,
        )
    }
    val w = d.wifi
    RigaDato(
        "Reti Wi-Fi", w?.let { "${it.reti}, ${it.giaViste} già viste" } ?: "nessuna", mono = false,
        accessorio = { Icon(Icone.Wifi, null, tint = Colori.Testo2, modifier = Modifier.size(16.dp)) },
        divisore = w?.dettaglio.isNullOrEmpty(),
    )
    w?.dettaglio?.forEach { r ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(r.bssid, style = Testo.Mono12.copy(color = Colori.Inchiostro), modifier = Modifier.weight(1f))
            Text(
                listOfNotNull(r.rssi?.let { "$it dBm" }, r.mhz?.let { if (it >= 5000) "5 GHz" else "2,4 GHz" }, "vista ${r.visteAltrove}×").joinToString(" · "),
                style = Testo.Piccolo12,
            )
        }
    }
    val p = d.location
    RigaDato(
        "Posizione",
        p?.let { String.format(Locale.ROOT, "%.5f, %.5f", it.lat, it.lon) + (it.precisioneM?.let { m -> " ±${m.toInt()} m" } ?: "") } ?: "nessuna",
        divisore = false,
        accessorio = { if (p?.simulata == true) Badge("SIMULATA", Colori.SuColore, Colori.Pericolo) },
    )
    if (p != null) {
        val contesto = LocalContext.current
        BottoneSecondario(
            "Apri sulla mappa",
            {
                // geo: apre l'app delle mappe che c'è sul telefono, con il segnaposto sulle coordinate
                val c = String.format(Locale.ROOT, "%.6f,%.6f", p.lat, p.lon)
                val mappa = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$c?q=$c(Timbratura)"))
                runCatching { contesto.startActivity(mappa) }.onFailure {
                    contesto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=${p.lat}&mlon=${p.lon}#map=18/${p.lat}/${p.lon}")))
                }
            },
            altezza = 44.dp, fondo = Colori.Rilievo,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** Un file della cache (pacchetto di prova) al selettore di Android, tramite il FileProvider dell'app. */
internal fun condividiFile(contesto: Context, file: File, tipo: String, titolo: String) {
    val uri = FileProvider.getUriForFile(contesto, "${contesto.packageName}.file", file)
    val invio = Intent(Intent.ACTION_SEND).setType(tipo).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    invio.clipData = ClipData.newRawUri(file.name, uri)
    contesto.startActivity(Intent.createChooser(invio, titolo).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

@Composable
internal fun CampoTesto(
    etichetta: String,
    valore: String,
    onCambia: (String) -> Unit,
    righe: Int = 1,
    tastiera: KeyboardOptions = KeyboardOptions.Default,
    segnaposto: String? = null,
    errore: Boolean = false,
    modifier: Modifier = Modifier,
    allInvio: (() -> Unit)? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (etichetta.isNotEmpty()) Text(etichetta, style = Testo.Piccolo13.copy(color = Colori.Testo3, fontWeight = FontWeight.SemiBold))
        OutlinedTextField(
            value = valore,
            onValueChange = onCambia,
            minLines = righe,
            maxLines = righe.coerceAtLeast(1) + 2,
            singleLine = righe == 1,
            keyboardOptions = tastiera,
            keyboardActions = if (allInvio != null) KeyboardActions(onDone = { allInvio() }) else KeyboardActions.Default,
            isError = errore,
            placeholder = segnaposto?.let { { Text(it, style = Testo.Corpo15.copy(color = Colori.Disattivo)) } },
            textStyle = Testo.Corpo15,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Colori.BordoControllo, focusedBorderColor = Colori.Entrata,
                errorBorderColor = Colori.Pericolo, errorContainerColor = Colori.Fondo, errorCursorColor = Colori.Pericolo,
                errorTextColor = Colori.Inchiostro,
                unfocusedContainerColor = Colori.Fondo, focusedContainerColor = Colori.Fondo,
                cursorColor = Colori.Entrata, focusedTextColor = Colori.Inchiostro, unfocusedTextColor = Colori.Inchiostro,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 6 · Nuovo link: per chi, durata (15 giorni se non si sceglie, fino a 60), download sì o no. I link già creati e i
 * loro accessi si leggono dal server con l'impronta, solo quando servono.
 */
@Composable
fun CondividiScreen(vm: StatoApp, ids: List<Int>, onIndietro: () -> Unit) {
    val firmatario = LocalFirmatario.current
    val contesto = LocalContext.current
    // solo quelle che esistono davvero e sono arrivate al server: un elenco vuoto non crea link
    val tutte = remember(ids) { ids.mapNotNull { vm.timbratura(it) } }
    val scelte = tutte.filter { it.sulServer }
    var perChi by rememberSaveable { mutableStateOf("Per la Segreteria / Personale") }
    var giorni by rememberSaveable { mutableIntStateOf(Contestazioni.GIORNI_PREDEFINITI) }
    var download by rememberSaveable { mutableStateOf(true) }
    var lavoro by remember { mutableStateOf<String?>(null) }
    var errore by remember { mutableStateOf<String?>(null) }
    var daRevocare by remember { mutableStateOf<LinkCondivisione?>(null) }
    var accessiDi by remember { mutableStateOf<Pair<LinkCondivisione, List<AccessoLink>>?>(null) }
    val creato = vm.nuovoLink

    fun <T> esegui(quale: String, blocco: suspend () -> Risultato<T>, poi: (T) -> Unit = {}) {
        if (lavoro != null) return
        lavoro = quale
        errore = null
        vm.viewModelScope.launch {
            when (val r = blocco()) {
                is Risultato.Ok -> poi(r.valore)
                is Risultato.Errore -> errore = r.messaggio
            }
            lavoro = null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Indietro("Storico", onIndietro)
        Text("Nuovo link", style = Testo.Titolo30)
        Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
            Text(
                when (scelte.size) {
                    0 -> "Nessuna timbratura selezionata: scegline una dallo storico"
                    1 -> "1 timbratura selezionata"
                    else -> "${scelte.size} timbrature selezionate"
                },
                style = Testo.Piccolo13.copy(fontWeight = FontWeight.SemiBold),
            )
            scelte.forEach { t ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${t.quando.toLocalDate().settimana().replaceFirstChar { it.uppercase() }} ${t.quando.toLocalDate().giornoMese()} · ${t.tipo.etichetta}",
                        style = Testo.Corpo14, modifier = Modifier.weight(1f),
                    )
                    Text(t.quando.oraMinuti(), style = Testo.Mono14.copy(color = t.tipo.colore))
                }
            }
            if (scelte.isNotEmpty()) {
                Text(
                    "Il link mostra la giornata intera: anche le altre timbrature dello stesso giorno, per vedere se torna.",
                    style = Testo.Piccolo12, modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (tutte.size > scelte.size) {
                Text(
                    "${tutte.size - scelte.size} ancora in invio dal telefono: restano fuori dal link.",
                    style = Testo.Piccolo12.copy(color = Colori.Giallo), modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        CampoTesto("Per chi è (lo vede anche il destinatario)", perChi, { if (it.length <= Gestione.MAX_ETICHETTA) perChi = it })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Scade dopo", style = Testo.Piccolo13.copy(color = Colori.Testo3, fontWeight = FontWeight.SemiBold))
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Contestazioni.DURATE.forEach { n ->
                    Pillola("$n gg", n == giorni, { giorni = n }, Modifier.weight(1f), altezza = 44.dp, raggio = 14.dp)
                }
            }
        }
        RigaSpunta("Consenti il download", "Pacchetto di prova con video, firme e marche", download, { download = it })
        BottonePrimario(
            if (lavoro == "crea") "Creo il link…" else "Crea link",
            { esegui("crea", { vm.creaLink(scelte.mapNotNull { it.serverId }, perChi, giorni, download, firmatario) }) },
            icona = Icone.Impronta,
            abilitato = scelte.isNotEmpty() && perChi.isNotBlank() && lavoro == null,
        )
        creato?.let { (indirizzo, fino) ->
            Scheda(bordo = Colori.Entrata, padding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Link creato, valido fino al ${fino.breve()}", style = Testo.Piccolo13.copy(color = Colori.Entrata, fontWeight = FontWeight.ExtraBold))
                    Text(
                        indirizzo, style = Testo.Mono13,
                        modifier = Modifier.fillMaxWidth().background(Colori.Fondo, RoundedCornerShape(10.dp)).padding(10.dp),
                    )
                    Text("Viene mostrato solo ora: il server ne conserva solo l'impronta.", style = Testo.Piccolo12)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BottoneSecondario("Copia", { copiaTesto(contesto, indirizzo) }, Modifier.weight(1f), altezza = 44.dp, fondo = Colori.Rilievo)
                        BottoneSecondario("Invia…", { condividiTesto(contesto, indirizzo) }, Modifier.weight(1f), altezza = 44.dp, fondo = Colori.Rilievo)
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TitoloSezione("I tuoi link", Modifier.padding(top = 0.dp))
            val elenco = vm.link
            if (elenco == null) {
                Text("Si leggono dal server con l'impronta, con quante volte sono stati aperti.", style = Testo.Piccolo13)
                BottoneSecondario(
                    if (lavoro == "elenco") "Leggo dal server…" else "Mostra i link", { esegui("elenco", { vm.caricaLink(firmatario) }) },
                    altezza = 48.dp, abilitato = lavoro == null,
                )
            } else {
                val attivi = elenco.filter { it.active }
                attivi.forEach { l ->
                    RigaLink(
                        l,
                        onAccessi = { esegui("accessi", { vm.accessi(l.id, firmatario) }) { a -> accessiDi = l to a } },
                        onRevoca = { daRevocare = l },
                    )
                }
                if (attivi.isEmpty()) Text("Nessun link attivo.", style = Testo.Piccolo13)
                val vecchi = elenco.size - attivi.size
                if (vecchi > 0) Text("$vecchi scaduti o revocati: non si aprono più.", style = Testo.Piccolo12)
                Text("Tocca un link per vedere chi l'ha aperto (chiede l'impronta).", style = Testo.Piccolo12)
            }
        }
    }

    daRevocare?.let { l ->
        AlertDialog(
            onDismissRequest = { daRevocare = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Revocare il link?", style = Testo.Titolo22) },
            text = {
                Text(
                    "${giornateLink(l)}, ${l.label}. Chi ha il link non potrà più aprirlo. Le timbrature non cambiano.",
                    style = Testo.Corpo14.copy(color = Colori.Testo3),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    daRevocare = null
                    esegui("revoca", { vm.revocaLink(l.id, firmatario) })
                }) { Text("Revoca", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo)) }
            },
            dismissButton = {
                TextButton(onClick = { daRevocare = null }) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
    accessiDi?.let { (l, accessi) ->
        AlertDialog(
            onDismissRequest = { accessiDi = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Aperture del link", style = Testo.Titolo22) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${giornateLink(l)} · ${l.label}", style = Testo.Piccolo12)
                    if (accessi.isEmpty()) Text("Nessuno l'ha ancora aperto.", style = Testo.Corpo14.copy(color = Colori.Testo3))
                    accessi.asReversed().forEach { a ->
                        val quando = oraDi(a.at)
                        Column {
                            Text(
                                "${quando?.toLocalDate()?.giornoMese() ?: "?"} ${quando?.oraMinuti() ?: ""} · ${a.descrizione}",
                                style = Testo.Corpo14.copy(fontWeight = FontWeight.Bold),
                            )
                            Text(listOfNotNull(a.ip, a.userAgent?.take(80)).joinToString(" · ").ifEmpty { "—" }, style = Testo.Piccolo12)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { accessiDi = null }) { Text("Chiudi", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold)) }
            },
        )
    }
    errore?.let { e ->
        AlertDialog(
            onDismissRequest = { errore = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Operazione non riuscita", style = Testo.Titolo22) },
            text = { Text(e, style = Testo.Corpo14.copy(color = Colori.Testo3)) },
            confirmButton = {
                TextButton(onClick = { errore = null }) { Text("OK", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold)) }
            },
        )
    }
}

/**
 * Le giornate di un link, il modo in cui lo si riconosce: «Giovedì 5 novembre», «4 novembre e 5 novembre», «Dal 3
 * novembre al 7 novembre · 5 giornate». Con un server che non le manda (vecchio): la data di creazione.
 */
internal fun giornateLink(l: LinkCondivisione, oggi: LocalDate = LocalDate.now()): String {
    val giorni = l.days.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.distinct().sorted()
    fun giorno(d: LocalDate) = if (d.year == oggi.year) d.senzaAnno() else d.esteso()
    return when (giorni.size) {
        0 -> oraDi(l.creatoIl)?.toLocalDate()?.let { "Link del ${it.breve()}" } ?: "Link"
        1 -> giorno(giorni[0])
        2 -> "${giorni[0].giornoMeseParole()} e ${giorni[1].giornoMeseParole()}".replaceFirstChar { it.titlecase() }
        else -> "Dal ${giorni.first().giornoMeseParole()} al ${giorni.last().giornoMeseParole()} · ${giorni.size} giornate"
    }
}

/** Una riga dell'elenco: in grande le giornate, sotto in piccolo il destinatario (l'etichetta) e i dettagli. */
@Composable
private fun RigaLink(l: LinkCondivisione, onAccessi: () -> Unit, onRevoca: () -> Unit) {
    val scade = oraDi(l.scade)?.toLocalDate()
    val creato = oraDi(l.creatoIl)?.toLocalDate()
    Row(
        Modifier.fillMaxWidth().clip(Forma22).background(Colori.Superficie, Forma22).clickable(role = Role.Button, onClick = onAccessi)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(giornateLink(l), style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold))
            Text(l.label, style = Testo.Piccolo12.copy(color = Colori.Testo2), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "creato ${creato?.giornoMese() ?: "?"} · scade ${scade?.giornoMese() ?: "?"} · " +
                    "aperto ${l.accesses} ${if (l.accesses == 1) "volta" else "volte"}" + if (l.download) "" else " · senza download",
                style = Testo.Piccolo12,
            )
        }
        OutlinedButton(
            onClick = onRevoca,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(2.dp, Colori.Pericolo),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Colori.Pericolo, containerColor = Color.Transparent),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.minimumInteractiveComponentSize().height(36.dp),
        ) { Text("Revoca", style = Testo.Piccolo13.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold)) }
    }
}
