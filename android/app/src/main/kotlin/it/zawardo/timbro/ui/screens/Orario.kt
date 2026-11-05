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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.RegolaGiorno
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.ui.breve
import it.zawardo.timbro.ui.components.Badge
import it.zawardo.timbro.ui.components.Forma22
import it.zawardo.timbro.ui.components.Indietro
import it.zawardo.timbro.ui.components.RigaDato
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.oraMinuti
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale

/**
 * Il mio orario di lavoro, in sola lettura: l'articolazione assegnata (con il nome e il giorno da cui vale) e le
 * versioni in vigore, giorno per giorno e con le regole. Le ore valide dello Storico, della pagina per l'ente e della
 * mail si calcolano con questo (core `Ore`, uguale al server). Lo modifica solo l'amministratore (Amministrazione →
 * Articolazioni orarie e assegnazioni): nell'app vera arriva già risolto da `GET /api/v1/schedule`.
 */
@Composable
fun OrarioScreen(vm: StatoApp, onIndietro: () -> Unit) {
    val oggi = LocalDate.now()
    val orario = vm.orario.ordinato()
    val inVigore = orario.inVigore(oggi)
    val assegnazioni = vm.assegnazioni().sortedByDescending { it.validoDal }

    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Indietro("Impostazioni", onIndietro)
        Text("Il mio orario", style = Testo.Titolo30)
        Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 12.dp)) {
            TitoloSezione("Articolazione assegnata")
            if (assegnazioni.isEmpty()) {
                Text("Nessuna: lo Storico mostra le ore fra le timbrature, senza saldo.", style = Testo.Piccolo13.copy(color = Colori.Giallo))
            }
            assegnazioni.forEachIndexed { i, a ->
                RigaDato(
                    a.nomeArticolazione ?: "Articolazione ${a.articolazioneId}", "dal ${a.validoDal.breve()}",
                    divisore = i < assegnazioni.lastIndex, etichettaColore = Colori.Inchiostro,
                )
            }
            Text(
                "Lo modifica l'amministratore. Quando l'ente cambia l'orario si aggiunge una versione datata: i giorni " +
                    "passati restano calcolati con quella di allora.",
                style = Testo.Piccolo12, modifier = Modifier.padding(top = 6.dp),
            )
        }
        orario.versions.sortedByDescending { it.validoDal }.forEach { v ->
            SchedaVersioneLettura(v, statoVersione(v, inVigore, oggi))
        }
    }
}

internal enum class StatoVersione { IN_VIGORE, FUTURA, PASSATA }

internal fun statoVersione(v: VersioneOrario, inVigore: VersioneOrario?, oggi: LocalDate): StatoVersione = when {
    v.validoDal.isAfter(oggi) -> StatoVersione.FUTURA
    v == inVigore -> StatoVersione.IN_VIGORE
    else -> StatoVersione.PASSATA
}

@Composable
internal fun BadgeVersione(stato: StatoVersione) = when (stato) {
    StatoVersione.IN_VIGORE -> Badge("IN VIGORE", Colori.SuColore, Colori.Entrata)
    StatoVersione.FUTURA -> Badge("FUTURA", Colori.SuColore, Colori.Azzurro)
    StatoVersione.PASSATA -> Badge("PASSATA", Colori.Testo3, Colori.Rilievo, grassetto = false)
}

/** Una versione in sola lettura: ore dovute dei giorni e le regole in frasi (come nella pagina per l'ente). */
@Composable
internal fun SchedaVersioneLettura(v: VersioneOrario, stato: StatoVersione) {
    Scheda(
        bordo = if (stato == StatoVersione.IN_VIGORE) Colori.Entrata else null,
        padding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dal ${v.validoDal.breve()}", style = Testo.Titolo22, modifier = Modifier.weight(1f))
            BadgeVersione(stato)
        }
        TitoloSezione("Ore dovute")
        val giorni = (1..7).filter { (v.days[it]?.dovutiMin ?: 0) > 0 }
        giorni.forEachIndexed { i, g ->
            val r = v.days.getValue(g)
            RigaDato(
                Ore.nomeGiorno(g).replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                Ore.durata(r.dovutiMin) + if (r.pause) "" else " · senza pausa",
                divisore = i < giorni.lastIndex,
            )
        }
        if (giorni.isEmpty()) Text("Nessun giorno con ore dovute.", style = Testo.Piccolo13)
        TitoloSezione("Regole")
        Ore.descriviOrario(v).drop(1).forEach {
            Text("· $it", style = Testo.Piccolo13.copy(color = Colori.Testo3), modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

/**
 * Editor di una versione (amministratore): ore dovute giorno per giorno con la pausa, e le regole dell'ente (fasce di
 * ingresso e di pausa, minimi, trattenuta senza pausa).
 */
@Composable
internal fun EditorVersione(
    v: VersioneOrario,
    stato: StatoVersione,
    eliminabile: Boolean,
    onCambia: (VersioneOrario) -> Unit,
    onElimina: () -> Unit,
) {
    Scheda(
        bordo = if (stato == StatoVersione.IN_VIGORE) Colori.Entrata else null,
        padding = PaddingValues(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Dal ${v.validoDal.breve()}", style = Testo.Titolo22, modifier = Modifier.weight(1f))
            BadgeVersione(stato)
        }
        TitoloSezione("Ore dovute")
        (1..7).forEach { g ->
            val r = v.days[g]
            val minuti = r?.dovutiMin ?: 0
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Ore.nomeGiorno(g).replaceFirstChar { it.titlecase(Locale.ITALIAN) },
                    style = Testo.Corpo14.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.width(92.dp),
                )
                Regolatore(
                    if (minuti == 0) "libero" else Ore.durata(minuti),
                    descrizione = Ore.nomeGiorno(g),
                    onMeno = { onCambia(v.conGiorno(g, minuti - 15, r?.pause ?: true)) },
                    onPiu = { onCambia(v.conGiorno(g, minuti + 15, r?.pause ?: true)) },
                    modifier = Modifier.weight(1f),
                )
                if (minuti > 0) {
                    ChipPausa(r?.pause == true, Ore.nomeGiorno(g)) { onCambia(v.conGiorno(g, minuti, it)) }
                } else {
                    Box(Modifier.width(96.dp))
                }
            }
        }
        TitoloSezione("Regole")
        RigaRegola("Ingresso dalle", v.ingressoDa.oraMinuti(), { onCambia(v.copy(ingressoDa = v.ingressoDa.passo(-15))) }, { onCambia(v.copy(ingressoDa = v.ingressoDa.passo(15))) })
        RigaRegola("fino alle", v.ingressoA.oraMinuti(), { onCambia(v.copy(ingressoA = v.ingressoA.passo(-15))) }, { onCambia(v.copy(ingressoA = v.ingressoA.passo(15))) })
        RigaRegola("Pausa che inizia dalle", v.pausaDa.oraMinuti(), { onCambia(v.copy(pausaDa = v.pausaDa.passo(-15))) }, { onCambia(v.copy(pausaDa = v.pausaDa.passo(15))) })
        RigaRegola("alle", v.pausaA.oraMinuti(), { onCambia(v.copy(pausaA = v.pausaA.passo(-15))) }, { onCambia(v.copy(pausaA = v.pausaA.passo(15))) })
        RigaRegola(
            "Pausa conteggiata almeno", "${v.pausaConteggiataMin}′",
            { onCambia(v.copy(pausaConteggiataMin = (v.pausaConteggiataMin - 5).coerceIn(0, 240))) },
            { onCambia(v.copy(pausaConteggiataMin = (v.pausaConteggiataMin + 5).coerceIn(0, 240))) },
        )
        RigaRegola(
            "Rientro possibile dopo", "${v.rientroMinimoMin}′",
            { onCambia(v.copy(rientroMinimoMin = (v.rientroMinimoMin - 5).coerceIn(0, 240))) },
            { onCambia(v.copy(rientroMinimoMin = (v.rientroMinimoMin + 5).coerceIn(0, 240))) },
        )
        RigaRegola(
            "Senza pausa, oltre", Ore.durata(v.sogliaSenzaPausaMin),
            { onCambia(v.copy(sogliaSenzaPausaMin = (v.sogliaSenzaPausaMin - 30).coerceIn(0, 1440))) },
            { onCambia(v.copy(sogliaSenzaPausaMin = (v.sogliaSenzaPausaMin + 30).coerceIn(0, 1440))) },
        )
        RigaRegola(
            "si tolgono", "${v.trattenutaSenzaPausaMin}′",
            { onCambia(v.copy(trattenutaSenzaPausaMin = (v.trattenutaSenzaPausaMin - 5).coerceIn(0, 240))) },
            { onCambia(v.copy(trattenutaSenzaPausaMin = (v.trattenutaSenzaPausaMin + 5).coerceIn(0, 240))) },
            divisore = false,
        )
        if (eliminabile) {
            TextButton(onClick = onElimina, modifier = Modifier.padding(top = 4.dp)) {
                Text("Elimina questa versione", style = Testo.Corpo14.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold))
            }
        }
    }
}

/** Il giorno con i minuti dovuti (0 = libero, tolto dall'orario) e la pausa. */
private fun VersioneOrario.conGiorno(g: Int, minuti: Int, pausa: Boolean): VersioneOrario {
    val m = minuti.coerceIn(0, 12 * 60)
    return copy(days = if (m == 0) days - g else days + (g to RegolaGiorno(m, pausa)))
}

/** ±15 minuti, senza passare la mezzanotte. */
private fun LocalTime.passo(minuti: Long): LocalTime {
    val nuovo = plusMinutes(minuti)
    return if ((minuti > 0 && nuovo.isBefore(this)) || (minuti < 0 && nuovo.isAfter(this))) this else nuovo
}

@Composable
private fun RigaRegola(etichetta: String, valore: String, onMeno: () -> Unit, onPiu: () -> Unit, divisore: Boolean = true) {
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(etichetta, style = Testo.Corpo14.copy(color = Colori.Testo2), modifier = Modifier.weight(1f))
            Regolatore(valore, etichetta, onMeno, onPiu)
        }
        if (divisore) HorizontalDivider(color = Colori.Divisore)
    }
}

/** [−] valore [+], tasti da 48 dp. */
@Composable
private fun Regolatore(valore: String, descrizione: String, onMeno: () -> Unit, onPiu: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TastoPasso("−", "Meno: $descrizione", onMeno)
        Text(valore, style = Testo.Mono15, textAlign = TextAlign.Center, modifier = Modifier.width(72.dp))
        TastoPasso("+", "Più: $descrizione", onPiu)
    }
}

@Composable
private fun TastoPasso(segno: String, descrizione: String, onClick: () -> Unit) {
    val forma = RoundedCornerShape(12.dp)
    Box(
        Modifier.minimumInteractiveComponentSize().size(36.dp).clip(forma).background(Colori.Rilievo, forma)
            .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = descrizione },
        contentAlignment = Alignment.Center,
    ) { Text(segno, style = Testo.Corpo16.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)) }
}

/** "pausa" / "no pausa": interruttore compatto, annunciato come tale. */
@Composable
private fun ChipPausa(conPausa: Boolean, giorno: String, onCambia: (Boolean) -> Unit) {
    val forma = RoundedCornerShape(10.dp)
    Box(
        Modifier.width(96.dp).minimumInteractiveComponentSize().clip(forma)
            .background(if (conPausa) Colori.AccentoChiaro else Colori.Rilievo, forma)
            .toggleable(value = conPausa, role = Role.Switch, onValueChange = onCambia)
            .semantics { contentDescription = "Pausa il $giorno" }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (conPausa) "con pausa" else "senza pausa",
            style = Testo.Piccolo12.copy(fontWeight = FontWeight.Bold, color = if (conPausa) Colori.Entrata else Colori.Testo2),
        )
    }
}

/**
 * Scelta di una data (calendario scuro, giorno scelto in menta): [selezionabile] dice quali date si possono scegliere
 * (per esempio non quelle di versioni o assegnazioni che ci sono già).
 */
@Composable
internal fun SceltaData(
    titolo: String,
    conferma: String,
    iniziale: LocalDate,
    anni: IntRange,
    selezionabile: (LocalDate) -> Boolean,
    onScelta: (LocalDate) -> Unit,
    onAnnulla: () -> Unit,
) {
    fun millis(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()  // il DatePicker ragiona in UTC
    fun data(millis: Long) = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
    val stato = rememberDatePickerState(
        initialSelectedDateMillis = millis(iniziale),
        initialDisplayedMonthMillis = millis(iniziale.withDayOfMonth(1)),
        yearRange = anni,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = selezionabile(data(utcTimeMillis))
            override fun isSelectableYear(year: Int) = year in anni
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
    val scelta = stato.selectedDateMillis?.let(::data)
    DatePickerDialog(
        onDismissRequest = onAnnulla,
        colors = colori,
        shape = Forma22,
        confirmButton = {
            TextButton(onClick = { scelta?.let(onScelta) }, enabled = scelta != null && selezionabile(scelta)) {
                Text(conferma, style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Entrata))
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
            title = { Text(titolo, style = Testo.Sezione13, modifier = Modifier.padding(start = 24.dp, top = 20.dp)) },
        )
    }
}
