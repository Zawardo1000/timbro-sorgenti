package it.zawardo.timbro.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo

val Forma22 = RoundedCornerShape(22.dp)
private val forma16 = RoundedCornerShape(16.dp)

/** Riquadro scuro ad angoli larghi: il contenitore di tutte le schede (stile C, niente bordo). */
@Composable
fun Scheda(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    bordo: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Colori.Superficie, Forma22)
            .then(if (bordo != null) Modifier.border(2.dp, bordo, Forma22) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun TitoloSezione(testo: String, modifier: Modifier = Modifier) {
    Text(testo.uppercase(), style = Testo.Sezione13, modifier = modifier.padding(top = 10.dp, bottom = 4.dp))
}

/** Riga etichetta → valore, con il divisore sotto (tranne l'ultima). */
@Composable
fun RigaDato(
    etichetta: String,
    valore: String,
    mono: Boolean = true,
    divisore: Boolean = true,
    etichettaColore: Color = Colori.Testo2,
    valoreStile: TextStyle = if (mono) Testo.Mono14 else Testo.Corpo14.copy(fontWeight = FontWeight.SemiBold),
    accessorio: (@Composable RowScope.() -> Unit)? = null,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(etichetta, style = Testo.Corpo14.copy(color = etichettaColore))
                accessorio?.invoke(this)
            }
            Text(valore, style = valoreStile, textAlign = TextAlign.End)
        }
        if (divisore) HorizontalDivider(color = Colori.Divisore)
    }
}

/** Etichetta piena: testo scuro su colore (contestata, da tenere), o tenue per le note. */
@Composable
fun Badge(testo: String, colore: Color, fondo: Color, grassetto: Boolean = true) {
    Text(
        testo,
        style = Testo.Piccolo12.copy(color = colore, fontWeight = if (grassetto) FontWeight.ExtraBold else FontWeight.SemiBold),
        modifier = Modifier.background(fondo, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun BadgeQualificata() = Badge("Qualificata", Colori.Entrata, Colori.AccentoChiaro)

@Composable
fun BottonePrimario(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icona: ImageVector? = null,
    altezza: Dp = 52.dp,
    abilitato: Boolean = true,
    fondo: Color = Colori.Inchiostro,  // bianco; menta per confermare, rosso per scartare (testo scuro sopra)
) {
    Button(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.fillMaxWidth().height(altezza),
        shape = forma16,
        colors = ButtonDefaults.buttonColors(
            containerColor = fondo, contentColor = Colori.SuColore,
            disabledContainerColor = Colori.Rilievo, disabledContentColor = Colori.Disattivo,
        ),
    ) {
        if (icona != null) {
            Icon(icona, contentDescription = null, modifier = Modifier.size(20.dp))
            Box(Modifier.width(8.dp))
        }
        Text(testo, style = Testo.Corpo16.copy(fontWeight = FontWeight.ExtraBold, color = if (abilitato) Colori.SuColore else Colori.Disattivo))
    }
}

/** Bottone scuro; dentro una scheda (stesso fondo) va passato `fondo = Colori.Rilievo`, se no non si vede. */
@Composable
fun BottoneSecondario(
    testo: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    altezza: Dp = 52.dp,
    fondo: Color = Colori.Superficie,
    abilitato: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.fillMaxWidth().height(altezza),
        shape = forma16,
        colors = ButtonDefaults.buttonColors(
            containerColor = fondo, contentColor = Colori.Inchiostro,
            disabledContainerColor = fondo, disabledContentColor = Colori.Disattivo,
        ),
    ) {
        Text(testo, style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold, color = if (abilitato) Colori.Inchiostro else Colori.Disattivo))
    }
}

@Composable
fun BottonePericolo(testo: String, onClick: () -> Unit, abilitato: Boolean, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        enabled = abilitato,
        modifier = modifier.fillMaxWidth().height(50.dp),
        shape = forma16,
        border = BorderStroke(2.dp, if (abilitato) Colori.Pericolo else Colori.BordoControllo),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Colori.Pericolo,
            disabledContentColor = Colori.Disattivo,
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
        ),
    ) {
        Text(testo, style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold, color = if (abilitato) Colori.Pericolo else Colori.Disattivo))
    }
}

/**
 * Pillola selezionabile (filtri dello storico, durate del link): bianca se scelta, scura altrimenti. TalkBack
 * annuncia se è scelta; l'area di tocco arriva a 48 dp. Il contenitore va marcato con `selectableGroup()`.
 */
@Composable
fun Pillola(
    testo: String,
    scelta: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    altezza: Dp = 40.dp,
    raggio: Dp = 20.dp,
    ruolo: Role = Role.RadioButton,
    /** Fondo quando non è scelta: dentro una scheda (stesso fondo) va `Colori.Rilievo`, se no non si vede. */
    spenta: Color = Colori.Superficie,
) {
    val forma = RoundedCornerShape(raggio)
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .height(altezza)
            .clip(forma)
            .background(if (scelta) Colori.Inchiostro else spenta, forma)
            .selectable(selected = scelta, role = ruolo, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(testo, style = Testo.Corpo14.copy(color = if (scelta) Colori.SuColore else Colori.Inchiostro, fontWeight = FontWeight.ExtraBold))
    }
}

/** Interruttore a spunta con titolo e spiegazione, a tutta riga (area di tocco ≥ 48 dp, stato annunciato). */
@Composable
fun RigaSpunta(
    titolo: String,
    spiegazione: String,
    valore: Boolean,
    onCambia: (Boolean) -> Unit,
    coloreSpunta: Color = Colori.Entrata,
    abilitata: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = valore, enabled = abilitata, role = Role.Checkbox, onValueChange = onCambia),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(titolo, style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold))
            if (spiegazione.isNotEmpty()) Text(spiegazione, style = Testo.Piccolo12)
        }
        Checkbox(
            checked = valore,
            onCheckedChange = null,
            enabled = abilitata,
            colors = CheckboxDefaults.colors(
                checkedColor = coloreSpunta, checkmarkColor = Colori.SuColore, uncheckedColor = Colori.Disattivo,
                disabledCheckedColor = coloreSpunta.copy(alpha = 0.5f), disabledUncheckedColor = Colori.Disattivo,
            ),
        )
    }
}

/** Interruttore giallo del canvas (56×34): la sveglia del rientro. Lo stato lo annuncia la riga (role Switch). */
@Composable
fun Interruttore(acceso: Boolean) {
    Box(
        Modifier.size(56.dp, 34.dp).background(if (acceso) Colori.Giallo else Colori.BordoControllo, RoundedCornerShape(17.dp)),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.offset(x = if (acceso) 26.dp else 4.dp).size(26.dp)
                .background(if (acceso) Colori.SuColore else Colori.Testo2, CircleShape),
        )
    }
}

/** Scheda "Sveglia per il rientro" con la campanella e l'interruttore, a tutta riga. */
@Composable
fun SchedaSveglia(titolo: String, spiegazione: String, accesa: Boolean, onCambia: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(Forma22)
            .background(Colori.Superficie, Forma22)
            .toggleable(value = accesa, role = Role.Switch, onValueChange = onCambia)
            .padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icone.Campanella, contentDescription = null, tint = if (accesa) Colori.Giallo else Colori.Disattivo, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(titolo, style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold))
            Text(spiegazione, style = Testo.Piccolo12.copy(fontWeight = FontWeight.SemiBold))
        }
        Interruttore(accesa)
    }
}

enum class Esito { FATTO, ATTESA, PROBLEMA }

/** Un passo della catena di stato dell'ultima timbratura (firmata, marcata, video, integro, Bitcoin). */
data class Passo(val etichetta: String, val esito: Esito)

/** Catena di cerchi uniti da barre, come nel canvas: menta fatto, giallo in attesa, rosso se qualcosa non va. */
@Composable
fun CatenaStato(passi: List<Passo>, modifier: Modifier = Modifier) {
    fun colore(e: Esito) = when (e) {
        Esito.FATTO -> Colori.Entrata
        Esito.ATTESA -> Colori.Giallo
        Esito.PROBLEMA -> Colori.Pericolo
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            passi.forEachIndexed { i, p ->
                Box(Modifier.size(30.dp).background(colore(p.esito), CircleShape), contentAlignment = Alignment.Center) {
                    val icona = when (p.esito) {
                        Esito.FATTO -> Icone.FattoGrosso
                        Esito.ATTESA -> Icone.Orologio
                        Esito.PROBLEMA -> Icone.Avviso
                    }
                    Icon(icona, contentDescription = null, tint = Colori.SuColore, modifier = Modifier.size(16.dp))
                }
                if (i < passi.lastIndex) {
                    val pieno = passi[i + 1].esito == Esito.FATTO && p.esito == Esito.FATTO
                    Box(Modifier.weight(1f).height(4.dp).background(if (pieno) Colori.Entrata else Colori.BordoControllo))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            passi.forEach { p ->
                Text(
                    p.etichetta, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, maxLines = 1,
                    style = Testo.Piccolo12.copy(
                        fontWeight = FontWeight.SemiBold,
                        color = when (p.esito) {
                            Esito.FATTO -> Colori.Testo3
                            Esito.ATTESA -> Colori.Giallo
                            Esito.PROBLEMA -> Colori.Pericolo
                        },
                    ),
                )
            }
        }
    }
}

/** Link "‹ Storico" in alto nelle schermate di dettaglio. */
@Composable
fun Indietro(testo: String, onClick: () -> Unit) {
    Row(
        Modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icone.Indietro, contentDescription = null, tint = Colori.Inchiostro, modifier = Modifier.size(20.dp))
        Text(testo, style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold))
    }
}

enum class Sezione(val etichetta: String) { TIMBRA("Timbra"), STORICO("Storico"), IMPOSTAZIONI("Impostazioni") }

/** Le tre sezioni in basso: tre bottoni larghi, bianco quello attivo (canvas C). */
@Composable
fun BarraSezioni(attiva: Sezione, onScegli: (Sezione) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Colori.Fondo).navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Sezione.entries.forEach { s ->
            val scelta = s == attiva
            Box(
                Modifier.weight(1f).height(50.dp).clip(forma16)
                    .background(if (scelta) Colori.Inchiostro else Colori.Superficie, forma16)
                    .selectable(selected = scelta, role = Role.Tab) { onScegli(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    s.etichetta, maxLines = 1,
                    style = Testo.Corpo14.copy(color = if (scelta) Colori.SuColore else Colori.Inchiostro, fontWeight = if (scelta) FontWeight.ExtraBold else FontWeight.Bold, fontSize = 14.sp),
                )
            }
        }
    }
}

@Composable
fun Spazio(altezza: Dp) = Spacer(Modifier.height(altezza))
