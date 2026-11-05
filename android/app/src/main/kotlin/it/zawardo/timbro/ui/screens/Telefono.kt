package it.zawardo.timbro.ui.screens

import android.content.Intent
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.zawardo.timbro.BuildConfig
import it.zawardo.timbro.dati.FirmatarioAdmin
import it.zawardo.timbro.sicurezza.Ambiente
import it.zawardo.timbro.ui.Collegamento
import it.zawardo.timbro.ui.components.BottonePericolo
import it.zawardo.timbro.ui.components.BottonePrimario
import it.zawardo.timbro.ui.components.BottoneSecondario
import it.zawardo.timbro.ui.components.Forma22
import it.zawardo.timbro.ui.components.RigaDato
import it.zawardo.timbro.ui.components.Scheda
import it.zawardo.timbro.ui.components.TitoloSezione
import it.zawardo.timbro.ui.theme.Colori
import it.zawardo.timbro.ui.theme.Testo

/**
 * Primo avvio: il telefono non è associato. Il codice lo genera l'amministratore (Amministrazione → utente →
 * "Associa un telefono"); il resto lo fa l'app (docs/PROGETTO.md §22).
 */
@Composable
fun AssociazioneScreen(c: Collegamento) {
    var codice by rememberSaveable { mutableStateOf("") }
    var nome by rememberSaveable { mutableStateOf(Build.MODEL.orEmpty().ifBlank { "Telefono" }) }
    var server by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(Colori.Fondo).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 24.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("timbro", style = Testo.Marchio18)
        Text("Associa questo telefono", style = Testo.Titolo30)
        Text(
            "Inserisci il codice che ti ha dato l'amministratore (vale 15 minuti, una volta sola). Il telefono crea le " +
                "sue chiavi nel chip sicuro e riceve dal server il certificato di accesso: niente file da copiare.",
            style = Testo.Piccolo13,
        )
        Scheda(padding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CampoTesto(
                    "Codice di associazione", codice, { codice = it }, segnaposto = "es. Ab3x-K9_qZ7p",
                    tastiera = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                )
                CampoTesto(
                    "Nome del telefono", nome, { nome = it.take(100) },
                    tastiera = KeyboardOptions(imeAction = ImeAction.Done), allInvio = { if (!c.lavoro) c.associa(codice, nome) },
                )
            }
        }
        BottonePrimario(
            if (c.lavoro) "Associazione in corso…" else "Associa",
            { c.associa(codice, nome) },
            abilitato = !c.lavoro && codice.isNotBlank() && nome.isNotBlank(),
        )
        c.errore?.let { Text(it, style = Testo.Corpo14.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold)) }
        Text(
            "Serve un blocco schermo (PIN o impronta): protegge la chiave delle operazioni di gestione.",
            style = Testo.Piccolo12,
        )
        if (BuildConfig.DEBUG) {
            BottoneSecondario(if (server) "Nascondi server" else "Server (debug): ${c.ambiente.apiBase}", { server = !server }, altezza = 44.dp)
            if (server) EditorAmbiente(c)
        }
    }
}

/**
 * Questo telefono, dal server: utente, ruolo, associazione, certificato, chiavi. Prova della firma admin.
 * Dissociazione: [daInviare] timbrature ancora in coda chiedono una seconda conferma; [prima] ferma il lavoro in corso
 * dell'app prima di cancellare.
 */
@Composable
fun SchedaTelefono(
    c: Collegamento,
    firmatario: FirmatarioAdmin,
    daInviare: Int = 0,
    prima: () -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    var server by remember { mutableStateOf(false) }
    // 0 niente, 1 prima conferma, 2 seconda conferma (timbrature in coda che andrebbero perse)
    var conferma by remember { mutableIntStateOf(0) }
    val attivita = LocalActivity.current
    val dissocia = {
        conferma = 0
        prima()
        c.dissocia {
            // l'app riparte da capo, sulla schermata dell'associazione (ViewModel nuovi, niente dati della sessione)
            attivita?.let { it.startActivity(Intent(it, it.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) }
        }
    }
    val io = c.io
    Scheda(padding = PaddingValues(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp)) {
        TitoloSezione("Questo telefono")
        RigaDato("Utente", io?.let { "${it.username} · ${if (it.admin) "amministratore" else "utente"}" } ?: "—", mono = false, valoreStile = Testo.Corpo14.copy(fontWeight = FontWeight.Bold))
        RigaDato("Nome", c.nomeTelefono ?: "—", mono = false, valoreStile = Testo.Corpo14)
        RigaDato("Id", c.telefonoId?.take(8)?.plus("…") ?: "—")
        RigaDato("Server", c.ambiente.apiBase.removePrefix("https://").removePrefix("http://"), valoreStile = Testo.Mono12.copy(color = Colori.Inchiostro))
        RigaDato(
            "Certificato di accesso", if (c.conCertificato) "rilasciato dal server" else "nessuno (server di sviluppo)", mono = false,
            valoreStile = Testo.Corpo14.copy(color = if (c.conCertificato) Colori.Entrata else Colori.Giallo, fontWeight = FontWeight.Bold),
        )
        val livello = c.livelloChiavi()
        RigaDato(
            "Chiavi", if (livello == "software") "software: senza chip sicuro" else "nel chip sicuro · $livello", mono = false,
            valoreStile = Testo.Corpo14.copy(color = if (livello == "software") Colori.Giallo else Colori.Inchiostro),
        )
        RigaDato("Versione dell'app", "${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHA}", valoreStile = Testo.Mono12.copy(color = Colori.Inchiostro))
        extra()
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BottoneSecondario("Aggiorna dal server", { c.aggiorna() }, altezza = 44.dp, fondo = Colori.Rilievo)
            BottoneSecondario("Prova la firma con l'impronta", { c.provaAdmin(firmatario) }, altezza = 44.dp, fondo = Colori.Rilievo)
            c.messaggio?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold)) }
            c.errore?.let { Text(it, style = Testo.Piccolo12.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold)) }
            if (BuildConfig.DEBUG) {
                BottoneSecondario(if (server) "Nascondi server" else "Server (debug)", { server = !server }, altezza = 44.dp, fondo = Colori.Rilievo)
                if (server) EditorAmbiente(c)
            }
            BottonePericolo(
                if (c.lavoro) "Dissociazione in corso…" else "Dissocia questo telefono", { conferma = 1 }, abilitato = !c.lavoro,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "Cancella da questo telefono chiavi, accesso e dati dell'app. Sul server il telefono resta registrato: " +
                    "si revoca da Amministrazione.",
                style = Testo.Piccolo12,
            )
        }
    }
    if (conferma == 1) {
        AlertDialog(
            onDismissRequest = { conferma = 0 },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Dissociare questo telefono?", style = Testo.Titolo22) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Dal telefono si cancellano le chiavi del chip sicuro, il token di accesso, lo storico salvato, la " +
                            "coda delle timbrature, i video rimasti e la sveglia del rientro. Le timbrature già arrivate al " +
                            "server restano lì.",
                        style = Testo.Corpo14.copy(color = Colori.Testo3),
                    )
                    Text(
                        "Il telefono resta registrato sul server: per revocarlo, Impostazioni → Amministrazione → Utenti → " +
                            "il tuo utente → Revoca (o chiedilo all'amministratore). Per usare di nuovo l'app serve un codice " +
                            "di associazione nuovo.",
                        style = Testo.Corpo14.copy(color = Colori.Testo3),
                    )
                    if (daInviare > 0) {
                        Text(
                            "${perse(daInviare)} non ${if (daInviare == 1) "è ancora arrivata" else "sono ancora arrivate"} " +
                                "del tutto al server: dissociando ${if (daInviare == 1) "andrebbe persa" else "andrebbero perse"}.",
                            style = Testo.Corpo14.copy(color = Colori.Giallo, fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { if (daInviare > 0) conferma = 2 else dissocia() }) {
                    Text(if (daInviare > 0) "Continua" else "Dissocia", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo))
                }
            },
            dismissButton = {
                TextButton(onClick = { conferma = 0 }) { Text("Annulla", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
    if (conferma == 2) {
        AlertDialog(
            onDismissRequest = { conferma = 0 },
            containerColor = Colori.Superficie,
            shape = Forma22,
            title = { Text("Perdere ${perse(daInviare)}?", style = Testo.Titolo22) },
            text = {
                Text(
                    if (daInviare == 1) {
                        "Questa timbratura (o il suo video) esiste solo su questo telefono: se dissoci adesso non arriverà " +
                            "mai al server. Meglio aspettare che la coda si svuoti (serve la rete)."
                    } else {
                        "Queste timbrature (o i loro video) esistono solo su questo telefono: se dissoci adesso non " +
                            "arriveranno mai al server. Meglio aspettare che la coda si svuoti (serve la rete)."
                    },
                    style = Testo.Corpo14.copy(color = Colori.Testo3),
                )
            },
            confirmButton = {
                TextButton(onClick = dissocia) {
                    Text(if (daInviare == 1) "Dissocia e perdila" else "Dissocia e perdile", style = Testo.Corpo15.copy(fontWeight = FontWeight.ExtraBold, color = Colori.Pericolo))
                }
            },
            dismissButton = {
                TextButton(onClick = { conferma = 0 }) { Text("Aspetta", style = Testo.Corpo15.copy(fontWeight = FontWeight.Bold)) }
            },
        )
    }
}

private fun perse(n: Int) = if (n == 1) "1 timbratura" else "$n timbrature"

/** Solo debug: indirizzi del backend (l'emulatore vede il PC su 10.0.2.2). */
@Composable
private fun EditorAmbiente(c: Collegamento) {
    var api by remember { mutableStateOf(c.ambiente.apiBase) }
    var enroll by remember { mutableStateOf(c.ambiente.enrollBase) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CampoTesto("API", api, { api = it.trim() }, tastiera = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        CampoTesto("Associazione (/enroll)", enroll, { enroll = it.trim() }, tastiera = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        BottoneSecondario("Usa questi indirizzi", { c.cambiaAmbiente(Ambiente(api, enroll, c.ambiente.mtls)) }, altezza = 44.dp, fondo = Colori.Rilievo)
        BottoneSecondario("Torna ai predefiniti", {
            api = Ambiente.PREDEFINITO.apiBase
            enroll = Ambiente.PREDEFINITO.enrollBase
            c.cambiaAmbiente(Ambiente.PREDEFINITO)
        }, altezza = 44.dp, fondo = Colori.Rilievo)
    }
}
