package it.zawardo.timbro.ui.screens

import it.zawardo.timbro.acquisizione.LetturaOrologi
import android.os.SystemClock
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.repeatOnLifecycle
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.acquisizione.EventoRipresa
import it.zawardo.timbro.acquisizione.RaccoltaSensori
import it.zawardo.timbro.acquisizione.Registratore
import it.zawardo.timbro.acquisizione.Ripresa
import it.zawardo.timbro.acquisizione.durataMs
import it.zawardo.timbro.acquisizione.impronta
import it.zawardo.timbro.core.Ore
import it.zawardo.timbro.core.PausaMinima
import it.zawardo.timbro.core.Sveglia
import it.zawardo.timbro.ui.StatoApp
import it.zawardo.timbro.dati.StatoRete
import it.zawardo.timbro.ui.StatoInvio
import it.zawardo.timbro.ui.Timbratura
import it.zawardo.timbro.ui.Tipo
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.CatenaStato
import it.zawardo.timbro.ui.components.Esito
import it.zawardo.timbro.ui.components.Forma22
import it.zawardo.timbro.ui.components.Icone
import it.zawardo.timbro.ui.components.Passo
import it.zawardo.timbro.ui.components.RigaDato
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.SchedaSveglia
import it.zawardo.timbro.ui.components.Spazio
import it.zawardo.timbro.ui.contoAllaRovescia
import it.zawardo.timbro.ui.oraMinuti
import it.zawardo.timbro.ui.senzaAnno
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.sicurezza.Chiavi
import it.zawardo.timbro.ui.theme.Testo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale

const val DURATA_MASSIMA_S = 15

/** L'ora che scorre mentre la schermata è attiva: data, proposta e conto alla rovescia si aggiornano da soli. */
@Composable
private fun adesso(): LocalDateTime {
    var ora by remember { mutableStateOf(LocalDateTime.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                ora = LocalDateTime.now()
                delay(1000L - ora.nano / 1_000_000)
            }
        }
    }
    return ora
}

/**
 * 1 · Timbra (stile C): metà alta nel colore del tipo con la parola enorme, Registra sul bordo, sotto la sveglia
 * (sulle uscite) e lo stato dell'ultima timbratura.
 *
 * Dopo la prima uscita di un giorno con la pausa diventa la Pausa: RIENTRO, conto alla rovescia dei 15 minuti al
 * posto di Registra e la sveglia programmata. Prima della fine un tocco chiede conferma, non blocca (orologi diversi
 * fra telefono e timbratrice). Nei giorni senza pausa dell'orario (il venerdì a mezza giornata) la prima uscita chiude
 * la giornata: niente Pausa e sveglia spenta di default. Il tipo si fissa al tocco di Registra; la data e la proposta
 * seguono l'ora, anche a mezzanotte.
 */
@Composable
fun TimbraScreen(
    vm: StatoApp,
    scelta: Tipo?,
    onTipo: (Tipo) -> Unit,
    sveglia: Boolean?,
    onSveglia: (Boolean) -> Unit,
    svegliaAlle: LocalDateTime?,
    onSvegliaPausa: (Boolean) -> Unit,
    onRegistra: (Tipo, Boolean) -> Unit,
) {
    val ora = adesso()
    val oggi = ora.toLocalDate()
    val giornata = vm.giornata(oggi)
    val proposta = vm.tipoProposto(oggi)
    val tipo = scelta ?: proposta
    val ultimaDiOggi = giornata.segni.lastOrNull()
    // l'ultima uscita di oggi apre la pausa solo se è nella fascia della pausa di un giorno che ce l'ha
    val ultimaApreLaPausa = ultimaDiOggi?.tipo == Tipo.USCITA && vm.uscitaDiPausa(oggi, ultimaDiOggi.quando.toLocalTime())
    val pausa = giornata.inPausa && tipo == Tipo.ENTRATA && ultimaApreLaPausa
    val uscitaPausa = if (pausa) ultimaDiOggi else null
    val mancante = uscitaPausa?.let { PausaMinima.mancante(it.quando, ora) } ?: Duration.ZERO
    val svegliaUscita = sveglia ?: vm.svegliaPredefinita(ora)
    // giornata chiusa: dopo la seconda uscita, o dopo un'uscita che non apre la pausa (fuori fascia, venerdì)
    val finita = ultimaDiOggi?.tipo == Tipo.USCITA && (giornata.uscite >= 2 || !ultimaApreLaPausa)

    var conferma by rememberSaveable { mutableStateOf<Long?>(null) }  // minuti al rientro, se il dialogo è aperto
    fun svegliaPer(t: Tipo) = t == Tipo.USCITA && (sveglia ?: vm.svegliaPredefinita(LocalDateTime.now()))
    val registra = dropUnlessResumed {
        val adesso = LocalDateTime.now()
        val t = scelta ?: vm.tipoProposto(adesso.toLocalDate())
        val manca = PausaMinima.daConfermare(t.kind, vm.ultima(), adesso)
        if (manca != null) conferma = PausaMinima.minutiArrotondati(manca) else onRegistra(t, svegliaPer(t))
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Colori.Fondo)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)) {
            val intestazione = if (uscitaPausa != null) "${oggi.senzaAnno()} · in pausa dalle ${uscitaPausa.quando.oraMinuti()}"
            else "${oggi.senzaAnno()} · ${ora.oraMinuti()}"
            val didascalia = when {
                uscitaPausa != null && !mancante.isZero -> "Pausa minima 15 min: puoi rientrare dalle ${PausaMinima.fine(uscitaPausa.quando).oraMinuti()}"
                uscitaPausa != null -> "Pausa minima finita: puoi timbrare il rientro"
                scelta != null && scelta != proposta -> "Scelta a mano · la proposta era ${proposta.etichetta}"
                finita -> vm.ore(oggi).let { o ->
                    "Giornata finita: ${Ore.durata(o.lavoratiMin)} valide" + (o.saldoMin?.let { ", saldo ${Ore.saldo(it)}" } ?: "")
                }
                ultimaDiOggi == null -> "Proposta: prima timbratura di oggi"
                else -> "Proposta: l'ultima è un'${ultimaDiOggi.tipo.etichetta.lowercase()} delle ${ultimaDiOggi.quando.oraMinuti()}"
            }
            val (testoRete, coloreRete) = when (vm.statoRete()) {
                StatoRete.ONLINE -> "Online" to Colori.Entrata
                StatoRete.OFFLINE -> (if (vm.inCoda > 0) "Offline · ${vm.inCoda} in coda" else "Offline") to Colori.Disattivo
                StatoRete.SERVER_GIU -> "Server non raggiungibile" to Colori.Giallo
            }
            Blocco(
                colore = tipo.colore,
                rete = testoRete,
                coloreRete = coloreRete,
                intestazione = intestazione,
                parola = if (uscitaPausa != null) "RIENTRO" else tipo.etichetta.uppercase(Locale.ITALIAN),
                didascalia = didascalia,
                tipo = tipo,
                onTipo = onTipo,
            ) {
                if (uscitaPausa != null && !mancante.isZero) {
                    AnelloPausa(mancante, PausaMinima.fine(uscitaPausa.quando), registra)
                } else {
                    PulsanteRegistra(tipo, registra)
                }
            }
            Spazio(66.dp + 18.dp)
            val bloccoPausa = uscitaPausa != null && !mancante.isZero
            Text(
                if (bloccoPausa) "Prima delle ${PausaMinima.fine(uscitaPausa!!.quando).oraMinuti()} chiede conferma"
                else "Display e badge · senza audio · massimo $DURATA_MASSIMA_S s",
                style = Testo.Corpo14.copy(color = if (bloccoPausa) Colori.Giallo else Colori.Testo2, fontWeight = if (bloccoPausa) FontWeight.SemiBold else FontWeight.Normal),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            Spacer(Modifier.weight(1f).heightIn(min = 18.dp))
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    bloccoPausa -> SchedaSveglia(
                        titolo = if (svegliaAlle != null) "Sveglia alle ${svegliaAlle.oraMinuti()}" else "Sveglia spenta",
                        spiegazione = if (svegliaAlle != null) "Suona alla fine della pausa minima" else "Nessun avviso a fine pausa",
                        accesa = svegliaAlle != null,
                        onCambia = onSvegliaPausa,
                    )
                    tipo == Tipo.USCITA && uscitaPausa == null -> SchedaSveglia(
                        titolo = "Sveglia per il rientro",
                        spiegazione = when {
                            svegliaUscita && giornata.uscite == 0 -> "Suona alle ${Sveglia.alle(ora).oraMinuti()}, 15 min dopo questa uscita · prima uscita di oggi"
                            svegliaUscita -> "Suona alle ${Sveglia.alle(ora).oraMinuti()}, 15 min dopo questa uscita"
                            giornata.uscite > 0 -> "Spenta · alla seconda uscita del giorno è spenta di default"
                            !vm.conPausa(oggi) -> "Spenta · oggi l'orario non prevede la pausa"
                            !vm.uscitaDiPausa(oggi, ora.toLocalTime()) -> "Spenta · fuori dalla fascia della pausa: è l'uscita di fine giornata"
                            else -> "Spenta"
                        },
                        accesa = svegliaUscita,
                        onCambia = onSveglia,
                    )
                }
                vm.ultima()?.let { u -> if (uscitaPausa != null) UltimaBreve(u) else UltimaConCatena(u) }
            }
        }
    }

    conferma?.let { minuti ->
        AlertDialog(
            onDismissRequest = { conferma = null },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Pausa minima non finita", style = Testo.Titolo22) },
            text = {
                Text(
                    "Mancano $minuti min alla fine della pausa minima: registrare lo stesso?",
                    style = Testo.Corpo15.copy(color = Colori.Testo3),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    conferma = null
                    val t = scelta ?: vm.tipoProposto(LocalDateTime.now().toLocalDate())
                    onRegistra(t, svegliaPer(t))
                }) { Text("Registra lo stesso", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Giallo)) }
            },
            dismissButton = {
                TextButton(onClick = { conferma = null }) { Text("Aspetto", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
}

/** La metà alta nel colore del tipo, con il pulsante a cavallo del bordo (lo spazio sotto lo aggiunge chi la usa). */
@Composable
private fun Blocco(
    colore: Color,
    rete: String,
    coloreRete: Color,
    intestazione: String,
    parola: String,
    didascalia: String,
    tipo: Tipo,
    onTipo: (Tipo) -> Unit,
    pulsante: @Composable () -> Unit,
) {
    val scuro = Colori.SuColore
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .background(colore, RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp))
                .statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 90.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("timbro", style = Testo.Marchio18.copy(color = scuro), modifier = Modifier.weight(1f))
                Row(
                    Modifier.background(scuro, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Box(Modifier.size(8.dp).background(coloreRete, CircleShape))
                    Text(rete, style = Testo.Piccolo13.copy(color = Colori.Inchiostro, fontWeight = FontWeight.Bold))
                }
            }
            Spazio(26.dp)
            Text(intestazione, style = Testo.Corpo15.copy(color = scuro, fontWeight = FontWeight.Bold))
            Spazio(6.dp)
            BasicText(
                parola,
                style = Testo.Parola64.copy(color = scuro),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 34.sp, maxFontSize = 64.sp, stepSize = 2.sp),
                modifier = Modifier.semantics { contentDescription = parola.lowercase() },
            )
            Spazio(10.dp)
            Text(didascalia, style = Testo.Corpo15.copy(color = scuro, fontWeight = FontWeight.SemiBold))
            Spazio(18.dp)
            Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tipo.entries.forEach { t ->
                    val scelto = t == tipo
                    val forma = RoundedCornerShape(22.dp)
                    Box(
                        Modifier.minimumInteractiveComponentSize().height(44.dp).clip(forma)
                            .then(if (scelto) Modifier.background(scuro, forma) else Modifier.border(2.dp, scuro, forma))
                            .selectable(selected = scelto, role = Role.RadioButton) { onTipo(t) }
                            .padding(horizontal = 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(t.etichetta, style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = if (scelto) Colori.Inchiostro else scuro))
                    }
                }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).offset(y = 66.dp)) { pulsante() }
    }
}

@Composable
private fun PulsanteRegistra(tipo: Tipo, onClick: () -> Unit) {
    Box(Modifier.size(132.dp).background(Colori.Fondo, CircleShape), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(112.dp).clip(CircleShape).background(Colori.Inchiostro, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Registra la timbratura (${tipo.etichetta.lowercase()})" },
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(26.dp).background(Colori.Rec, CircleShape))
                Text("REGISTRA", style = Testo.Bottone13.copy(color = Colori.SuColore))
            }
        }
    }
}

/** Al posto di Registra durante la pausa minima: anello che si riempie e conto alla rovescia. */
@Composable
private fun AnelloPausa(mancante: Duration, fine: LocalDateTime, onClick: () -> Unit) {
    val totale = PausaMinima.DURATA.seconds.toFloat()
    val fatto = ((totale - mancante.seconds) / totale).coerceIn(0f, 1f)
    Box(
        Modifier.size(132.dp).clip(CircleShape).background(Colori.Fondo, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = "Registra il rientro: pausa minima fino alle ${fine.oraMinuti()}, " +
                    "mancano ${PausaMinima.minutiArrotondati(mancante)} minuti, chiede conferma"
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(132.dp)) {
            val tratto = 7.dp.toPx()
            val r = 58.dp.toPx()
            val angolo = Offset(center.x - r, center.y - r)
            drawCircle(Color(0xFF2A2A33), radius = r, style = Stroke(tratto))
            drawArc(
                Colori.Entrata, startAngle = -90f, sweepAngle = 360f * fatto, useCenter = false,
                topLeft = angolo, size = Size(2 * r, 2 * r), style = Stroke(tratto, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(mancante.contoAllaRovescia(), style = Testo.Mono21.copy(fontSize = 26.sp, lineHeight = 30.sp))
            Text("al rientro", style = Testo.Piccolo12.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@Composable
private fun UltimaConCatena(u: Timbratura) {
    Scheda(padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Ultima · ${u.tipo.etichetta}", style = Testo.Titolo15, modifier = Modifier.weight(1f))
            Text(u.quando.oraMinuti(), style = Testo.Mono16.copy(color = u.tipo.colore))
        }
        Spazio(14.dp)
        val c = u.catena
        if (c == null) {
            // dati finti della modalità dimostrativa
            val marcata = if (u.stato == StatoInvio.MARCATA) Esito.FATTO else Esito.ATTESA
            CatenaStato(
                listOf(
                    Passo("Firmata", Esito.FATTO),
                    Passo("Marcata", marcata),
                    Passo("Video", marcata),
                    Passo("Integro", if (u.attestazioneOk == true) Esito.FATTO else Esito.PROBLEMA),
                    Passo("Bitcoin", Esito.ATTESA),
                ),
            )
            return@Scheda
        }
        // timbratura vera: coda del telefono e stato letto dal server
        fun esito(fatto: Boolean) = when {
            fatto -> Esito.FATTO
            c.rifiutata -> Esito.PROBLEMA
            else -> Esito.ATTESA
        }
        CatenaStato(
            listOf(
                Passo("Firmata", Esito.FATTO),
                Passo("Marcata", esito(c.marcata)),
                Passo("Video", esito(c.video)),
                Passo(
                    "Integro",
                    when {
                        !c.conAttestazione -> Esito.PROBLEMA
                        c.integro == true -> Esito.FATTO
                        c.integro == false -> Esito.PROBLEMA
                        else -> Esito.ATTESA
                    },
                ),
                Passo("Bitcoin", if (c.bitcoin) Esito.FATTO else Esito.ATTESA),
            ),
        )
        val nota = when {
            c.rifiutata -> "Rifiutata dal server: ${c.errore.orEmpty()}" to Colori.Pericolo
            !c.inviata && c.errore != null -> "In coda, nuovo tentativo a breve (${c.errore})" to Colori.Giallo
            !c.inviata -> "In coda: parte appena c'è rete" to Colori.Giallo
            !c.video && c.errore != null -> "Video in coda (${c.errore})" to Colori.Giallo
            !c.conAttestazione -> "Il telefono non ha saputo attestare la timbratura" to Colori.Giallo
            c.integro == false -> "Attestazione: ${c.problemi.orEmpty().split("; ").take(2).joinToString("; ")}" to Colori.Giallo
            else -> null
        }
        nota?.let { (testo, colore) ->
            Spazio(10.dp)
            Text(testo, style = Testo.Piccolo12.copy(color = colore, fontWeight = FontWeight.SemiBold), maxLines = 3)
        }
    }
}

@Composable
private fun UltimaBreve(u: Timbratura) {
    Row(
        Modifier.fillMaxWidth().background(Colori.Superficie, Forma22).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Ultima · ${u.tipo.etichetta}", style = Testo.Titolo15, modifier = Modifier.weight(1f))
        val (stato, colore) = when (u.stato) {
            StatoInvio.MARCATA -> "marcata" to Colori.Entrata
            StatoInvio.RICEVUTA -> "ricevuta" to Colori.Inchiostro
            StatoInvio.IN_INVIO -> "in invio" to Colori.Giallo
        }
        Text(stato, style = Testo.Piccolo13.copy(color = colore, fontWeight = FontWeight.Bold))
        Text(u.quando.oraMinuti(), style = Testo.Mono16.copy(color = u.tipo.colore), modifier = Modifier.padding(start = 10.dp))
    }
}

/**
 * 2 · Registrazione: nero, cornice nel colore del tipo, anteprima della fotocamera, timer grande e barra; si ferma da
 * sola a 15 secondi (CameraX, senza audio). Partono insieme posizione e reti Wi-Fi ([RaccoltaSensori]); allo stop si
 * calcola subito l'impronta SHA-256 del file e la ripresa passa alla Conferma.
 *
 * Fotocamera e posizione si chiedono qui, spiegandole: senza fotocamera non si registra, senza posizione la timbratura
 * parte lo stesso (niente posizione né reti nel manifest). Uscendo prima della fine (Indietro, app in background) la
 * registrazione si ferma e il file si cancella: si ricomincia.
 */
@Composable
fun RegistrazioneScreen(tipo: Tipo, onRegistrata: (Ripresa) -> Unit, onAnnulla: () -> Unit) {
    val contesto = LocalContext.current
    val app = contesto.applicationContext as TimbroApp
    val proprietario = LocalLifecycleOwner.current
    fun concessa(p: String) = ContextCompat.checkSelfPermission(contesto, p) == PackageManager.PERMISSION_GRANTED
    var fotocamera by remember { mutableStateOf(concessa(Manifest.permission.CAMERA)) }
    var negata by remember { mutableStateOf(false) }
    val chiedi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        fotocamera = concessa(Manifest.permission.CAMERA)
        negata = !fotocamera
    }
    var chiesto by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val mancano = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .filterNot(::concessa)
        if (mancano.isNotEmpty() && !chiesto) {
            chiesto = true
            chiedi.launch(mancano.toTypedArray())
        }
    }

    val registratore = remember { Registratore.crea(contesto) }
    var sensori by remember { mutableStateOf<RaccoltaSensori?>(null) }
    val anteprima = remember {
        PreviewView(contesto).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val livello = remember { app.chiavi.livello(Chiavi.TIMBRATURE) }
    var millis by remember { mutableLongStateOf(0L) }
    var registrando by remember { mutableStateOf(false) }
    var calcolo by remember { mutableStateOf(false) }
    var errore by remember { mutableStateOf<String?>(null) }
    var tentativo by remember { mutableIntStateOf(0) }
    var precisione by remember { mutableStateOf<Float?>(null) }
    var fonte by remember { mutableStateOf<String?>(null) }
    var reti by remember { mutableIntStateOf(0) }
    val file = remember { mutableStateOf<File?>(null) }
    val consegnata = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(fotocamera, tentativo) {
        if (!fotocamera) return@LaunchedEffect
        errore = null
        millis = 0
        val s = RaccoltaSensori(contesto).also { sensori = it }
        val inizio = ZonedDateTime.now()
        val inizioMonotono = SystemClock.elapsedRealtime() // per riportare gli orologi di rete e GNSS a questo istante
        try {
            registratore.collega(proprietario, anteprima)
            s.avvia()
            file.value = registratore.avvia { e ->
                when (e) {
                    is EventoRipresa.Durata -> millis = e.ms
                    is EventoRipresa.Fine -> {
                        registrando = false
                        val f = file.value
                        if (e.riuscita && f != null && f.length() > 0) {
                            calcolo = true
                            scope.launch {
                                val sha = impronta(f)
                                val (posizione, wifi) = s.concludi()
                                val orologi = LetturaOrologi.leggi(contesto, inizioMonotono)
                                consegnata.value = true
                                onRegistrata(
                                    Ripresa(
                                        f.absolutePath, sha, f.length(), durataMs(f, millis), inizio, posizione, wifi, orologi,
                                        codifica = registratore.descrizione,
                                    ),
                                )
                            }
                        } else {
                            f?.delete()
                            runCatching { s.concludi() }
                            errore = "Registrazione interrotta (${e.errore}). Riprova."
                        }
                    }
                }
            }
            registrando = true
        } catch (e: Exception) {
            runCatching { s.concludi() }
            errore = "Fotocamera non disponibile: ${e.message}"
        }
    }
    // pastiglie: precisione della posizione e reti viste, aggiornate ogni secondo durante la ripresa
    LaunchedEffect(registrando) {
        while (registrando) {
            sensori?.let { precisione = it.precisioneM; fonte = it.fonte; reti = it.retiViste() }
            delay(1000)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (!consegnata.value) {
                registratore.ferma()
                file.value?.delete()
                sensori?.let { runCatching { it.concludi() } }
            }
            registratore.chiudi()
        }
    }

    val ferma = dropUnlessResumed { if (registrando) registratore.ferma() }
    val secondi = (millis / 1000).toInt().coerceAtMost(DURATA_MASSIMA_S)
    Column(Modifier.fillMaxSize().background(Colori.CameraFondo).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tipo.etichetta.uppercase(Locale.ITALIAN), style = Testo.Titolo26.copy(color = tipo.colore), modifier = Modifier.weight(1f))
            if (registrando) {
                Row(
                    Modifier.background(Colori.Rec, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(8.dp).background(Colori.Inchiostro, CircleShape))
                    Text("REC", style = Testo.Corpo14.copy(fontWeight = FontWeight.ExtraBold))
                }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 14.dp)
                .border(5.dp, tipo.colore, RoundedCornerShape(28.dp))
                .clip(RoundedCornerShape(28.dp))
                .background(Colori.CameraPannello),
        ) {
            if (fotocamera) {
                AndroidView({ anteprima }, Modifier.fillMaxSize())
            }
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // si aggiorna ogni secondo mentre il fix migliora: "Rete" (o "Posizione", fused) finché il GPS non fa meglio
                Pastiglia(precisione?.let { "${when (fonte) { "gps" -> "GPS"; "network" -> "Rete"; else -> "Posizione" }} ±${it.roundToInt()} m" } ?: "GPS …")
                Pastiglia(if (reti > 0) "Wi-Fi $reti" else "Wi-Fi …")
                val sicuro = livello != "software" && livello != "?"
                Pastiglia(
                    if (sicuro) "Chip sicuro" else "Chiave software",
                    fondo = if (sicuro) Colori.Entrata else Colori.Giallo, colore = Colori.SuColore,
                )
            }
            when {
                !fotocamera -> Messaggio(
                    "Serve la fotocamera",
                    "Il video inquadra solo il display del terminale e il badge, senza audio: resta sul telefono finché non lo confermi.",
                ) {
                    BottonePrimario(if (negata) "Apri le impostazioni" else "Consenti", onClick = {
                        if (negata) {
                            contesto.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", contesto.packageName, null)),
                            )
                        } else {
                            chiedi.launch(arrayOf(Manifest.permission.CAMERA))
                        }
                    })
                    BottoneSecondario("Annulla", onAnnulla)
                }
                errore != null -> Messaggio("Registrazione non riuscita", errore.orEmpty()) {
                    BottonePrimario("Riprova", onClick = { tentativo++ })
                    BottoneSecondario("Annulla", onAnnulla)
                }
                calcolo -> Messaggio("Calcolo l'impronta…", "SHA-256 del video: da qui in poi il file non cambia più.") {}
                else -> Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(String.format(Locale.ROOT, "%02d", secondi), style = Testo.Parola64.copy(fontSize = 44.sp, lineHeight = 46.sp, color = Colori.Inchiostro))
                        Text(" s", style = Testo.Titolo22.copy(color = Colori.Testo2), modifier = Modifier.padding(bottom = 4.dp).weight(1f))
                        Text("su $DURATA_MASSIMA_S · senza audio", style = Testo.Piccolo13, modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Box(Modifier.fillMaxWidth().height(10.dp).background(Color(0xFF2A2A33), RoundedCornerShape(5.dp))) {
                        Box(
                            Modifier.fillMaxWidth((millis / Registratore.DURATA_MASSIMA_MS.toFloat()).coerceIn(0f, 1f)).height(10.dp)
                                .background(tipo.colore, RoundedCornerShape(5.dp)),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (registrando) "Inquadra display e badge · se entra qualcuno, annulla dopo lo stop" else "Prepara l'inquadratura del terminale",
                style = Testo.Piccolo13, modifier = Modifier.weight(1f),
            )
            Row(
                Modifier.height(64.dp).clip(RoundedCornerShape(32.dp))
                    .background(if (registrando) Colori.Inchiostro else Colori.Rilievo, RoundedCornerShape(32.dp))
                    .clickable(enabled = registrando, role = Role.Button, onClick = ferma)
                    .semantics { contentDescription = "Ferma la registrazione" }
                    .padding(horizontal = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(18.dp).background(Colori.Rec, RoundedCornerShape(4.dp)))
                Text("STOP", style = Testo.Titolo22.copy(fontSize = 16.sp, color = if (registrando) Colori.SuColore else Colori.Testo2))
            }
        }
    }
}

/** Riquadro al centro dell'inquadratura: permessi, errori, calcolo dell'impronta. */
@Composable
private fun BoxScope.Messaggio(titolo: String, testo: String, azioni: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.align(Alignment.Center).padding(20.dp).background(Colori.Superficie, Forma22).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(titolo, style = Testo.Titolo22)
        Text(testo, style = Testo.Corpo15.copy(color = Colori.Testo3))
        azioni()
    }
}

@Composable
private fun Pastiglia(testo: String, fondo: Color = Colori.CameraChip, colore: Color = Colori.Inchiostro) {
    Text(
        testo, maxLines = 1,
        style = Testo.Piccolo13.copy(color = colore, fontWeight = FontWeight.Bold),
        modifier = Modifier.background(fondo, RoundedCornerShape(50)).padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/**
 * 3 · Conferma: il video appena registrato (tocca per rivederlo) e i dati veri prima di firmare e mettere in coda; si
 * può annullare se è entrato qualcuno (il file si cancella). [ripresa] null: l'app è stata chiusa nel frattempo e la
 * registrazione non c'è più.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConfermaScreen(
    tipo: Tipo,
    ripresa: Ripresa?,
    sveglia: Boolean,
    livello: String,
    inCorso: Boolean,
    errore: String?,
    onConferma: () -> Unit,
    onAnnulla: () -> Unit,
) {
    // pulsanti sempre visibili in basso: si scorre solo il contenuto sopra (sui telefoni bassi, o con caratteri grandi)
    Column(Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().navigationBarsPadding()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (ripresa == null) {
                Text("Controlla il video", style = Testo.Titolo28)
                Text("La registrazione non c'è più (l'app è stata chiusa nel frattempo): registra di nuovo.", style = Testo.Corpo15.copy(color = Colori.Testo3))
                return@Column
            }
            Text(
                "${tipo.etichetta} delle ${ripresa.inizio.toLocalDateTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}",
                style = Testo.Titolo28.copy(color = tipo.colore),
            )
            Anteprima(ripresa, tipo)
            val mb = String.format(Locale.ITALIAN, "%.1f", ripresa.dimensione / 1_000_000.0)
            // bitrate effettivo: per confrontare le due codifiche (impostazioni → video compatto)
            val mbit = String.format(Locale.ITALIAN, "%.1f", ripresa.dimensione * 8.0 / 1000.0 / ripresa.durataMs.coerceAtLeast(1))
            val p = ripresa.posizione
            val reti = ripresa.wifi?.aps?.size ?: 0
            val sicuro = livello != "software" && livello != "?"
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Pastiglia("${(ripresa.durataMs + 500) / 1000} s · $mb MB · $mbit Mbit/s", fondo = Colori.Rilievo, colore = Colori.Inchiostro)
                ripresa.codifica?.let { Pastiglia(it, fondo = Colori.Rilievo, colore = Colori.Inchiostro) }
                when {
                    p == null -> Pastiglia("Posizione non disponibile", fondo = Colori.Giallo, colore = Colori.SuColore)
                    p.isMock == true -> Pastiglia("Posizione simulata!", fondo = Colori.Giallo, colore = Colori.SuColore)
                    p.accuracyM != null -> Pastiglia("GPS ±${p.accuracyM!!.roundToInt()} m", fondo = Colori.Rilievo, colore = Colori.Inchiostro)
                    else -> Pastiglia("GPS senza precisione", fondo = Colori.Rilievo, colore = Colori.Inchiostro)
                }
                Pastiglia(if (reti > 0) "Wi-Fi $reti" else "Nessuna rete Wi-Fi", fondo = Colori.Rilievo, colore = Colori.Inchiostro)
                Pastiglia(
                    if (sicuro) "Chip sicuro ${livello.uppercase(Locale.ROOT)}" else "Chiave software",
                    fondo = if (sicuro) Colori.Entrata else Colori.Giallo, colore = Colori.SuColore,
                )
                if (tipo == Tipo.USCITA) {
                    Pastiglia(if (sveglia) "Sveglia tra 15 min" else "Sveglia spenta", fondo = if (sveglia) Colori.Giallo else Colori.Rilievo,
                        colore = if (sveglia) Colori.SuColore else Colori.Testo2)
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            errore?.let { Text(it, style = Testo.Corpo14.copy(color = Colori.Pericolo, fontWeight = FontWeight.Bold)) }
            if (ripresa == null) {
                BottonePrimario("Torna a Timbra", onAnnulla)
            } else {
                BottonePrimario(if (inCorso) "Firmo e metto in coda…" else "Conferma e invia", onConferma, fondo = Colori.Entrata)
                BottonePrimario("Scarta il video", onAnnulla, fondo = Colori.Pericolo, altezza = 48.dp)
            }
        }
    }
}

/** Il video appena registrato: fermo sul primo fotogramma, si avvia e si ferma toccandolo. */
@Composable
private fun Anteprima(ripresa: Ripresa, tipo: Tipo) {
    var inRiproduzione by remember { mutableStateOf(false) }
    val video = remember { mutableStateOf<VideoView?>(null) }
    Box(
        Modifier.fillMaxWidth().height(260.dp).border(4.dp, tipo.colore, Forma22).clip(Forma22).background(Colori.CameraPannello)
            .clickable(role = Role.Button) {
                video.value?.let { v -> if (v.isPlaying) v.pause() else v.start() }
                inRiproduzione = video.value?.isPlaying == true
            }
            .semantics { contentDescription = "Riproduci l'anteprima" },
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { c ->
                VideoView(c).apply {
                    setVideoPath(ripresa.percorso)
                    setOnPreparedListener { mp -> mp.setVolume(0f, 0f); seekTo(1) }
                    setOnCompletionListener { inRiproduzione = false }
                    video.value = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (!inRiproduzione) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(Colori.Inchiostro, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icone.Riproduci, contentDescription = null, tint = Colori.SuColore, modifier = Modifier.size(26.dp))
            }
        }
    }
}
