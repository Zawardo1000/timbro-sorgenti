package it.zawardo.timbro.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.fragment.app.FragmentActivity
import it.zawardo.timbro.TimbroApp
import it.zawardo.timbro.acquisizione.Timbratrice
import it.zawardo.timbro.dati.coda.Invio
import it.zawardo.timbro.sicurezza.Chiavi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import it.zawardo.timbro.core.Sveglia
import it.zawardo.timbro.dati.FirmatarioAdmin
import it.zawardo.timbro.sicurezza.FirmaBiometrica
import it.zawardo.timbro.ui.screens.AssociazioneScreen
import it.zawardo.timbro.sveglia.SvegliaRientro
import it.zawardo.timbro.ui.components.BarraSezioni
import it.zawardo.timbro.ui.components.Sezione
import it.zawardo.timbro.ui.screens.ArticolazioneScreen
import it.zawardo.timbro.ui.screens.ArticolazioniScreen
import it.zawardo.timbro.ui.screens.AssegnazioniScreen
import it.zawardo.timbro.ui.screens.CondividiScreen
import it.zawardo.timbro.ui.screens.ConfermaScreen
import it.zawardo.timbro.ui.screens.ContestaScreen
import it.zawardo.timbro.ui.screens.DettaglioScreen
import it.zawardo.timbro.ui.screens.ImpostazioniScreen
import it.zawardo.timbro.ui.screens.NuovaArticolazioneScreen
import it.zawardo.timbro.ui.screens.NuovoUtenteScreen
import it.zawardo.timbro.ui.screens.OrarioScreen
import it.zawardo.timbro.ui.screens.RegistrazioneScreen
import it.zawardo.timbro.ui.screens.StoricoScreen
import it.zawardo.timbro.ui.screens.TimbraScreen
import it.zawardo.timbro.ui.screens.UtenteScreen
import it.zawardo.timbro.ui.screens.UtentiScreen
import it.zawardo.timbro.ui.theme.Colori
import java.time.LocalDate

/**
 * Le schermate dell'app e come ci si muove fra loro.
 *
 * Il tipo (entrata/uscita) e la sveglia del rientro si fissano al tocco di Registra e viaggiano nelle rotte: la
 * proposta che si ricalcola dopo (a mezzanotte, o dopo la conferma) non cambia la timbratura in corso. La sveglia
 * si programma solo alla conferma dell'uscita e si cancella al rientro.
 */
private object Rotte {
    const val TIMBRA = "timbra"
    const val REGISTRA = "registra/{kind}/{sveglia}"
    const val CONFERMA = "conferma/{kind}/{sveglia}"  // la ripresa sta nel ViewModel (file, impronta, sensori)
    const val STORICO = "storico"
    const val DETTAGLIO = "dettaglio/{id}"
    const val CONDIVIDI = "condividi/{ids}"
    const val CONTESTA = "contesta/{id}"
    const val SEGNALA = "segnala/{giorno}"  // giorno epoch: tutta la giornata
    const val ORARIO = "orario"
    const val IMPOSTAZIONI = "impostazioni"

    // amministrazione (solo per il ruolo admin)
    const val UTENTI = "utenti"
    const val NUOVO_UTENTE = "nuovo-utente"
    const val UTENTE = "utente/{id}"
    const val ASSEGNAZIONI = "assegnazioni/{id}"
    const val ARTICOLAZIONI = "articolazioni"
    const val NUOVA_ARTICOLAZIONE = "nuova-articolazione"
    const val ARTICOLAZIONE = "articolazione/{id}"
}

/**
 * Come [dropUnlessResumed], con un argomento: durante le transizioni (circa 700 ms) la schermata che esce resta
 * visibile e toccabile ma non è più RESUMED, e un secondo tocco non deve aprire due volte la stessa cosa.
 */
@Composable
private fun <T> soloSeAttiva(azione: (T) -> Unit): (T) -> Unit {
    val owner = LocalLifecycleOwner.current
    return { x -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) azione(x) }
}

/** Primo avvio senza associazione: la schermata del codice; poi l'app. */
@Composable
fun Radice(collegamento: Collegamento = viewModel()) {
    if (!collegamento.associato) {
        AssociazioneScreen(collegamento)
        return
    }
    val attivita = LocalActivity.current as FragmentActivity
    val firmatario = remember(attivita) { FirmaBiometrica(attivita, (attivita.application as TimbroApp).chiavi) }
    val vm: StatoApp = viewModel()
    // chi sono e il mio orario dal server (in cache nel ViewModel per aprire senza rete)
    LaunchedEffect(collegamento.io) { vm.usaIo(collegamento.io) }
    LaunchedEffect(collegamento.orario) { vm.usaOrario(collegamento.orario) }
    val app = attivita.application as TimbroApp
    val coda by remember { app.coda.coda().osserva() }.collectAsStateWithLifecycle(initialValue = null)
    LaunchedEffect(coda) { coda?.let { vm.usaCoda(it) } }
    // all'apertura: si manda quello che è rimasto in coda, si aggiornano marche e attestazione e si rilegge lo storico
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        Invio.pianifica(app)
        vm.aggiornaStorico()
        if (collegamento.io == null) collegamento.aggiorna()  // aperta senza rete: /me e /schedule appena si può
    }
    // tornata la rete: subito, senza aspettare il prossimo ritorno nell'app
    val connesso = app.rete.connesso
    var connessoPrima by remember { mutableStateOf(connesso) }
    LaunchedEffect(connesso) {
        if (connesso && !connessoPrima) {
            Invio.pianifica(app)
            vm.aggiornaStorico()
            collegamento.aggiorna()
        }
        connessoPrima = connesso
    }
    CompositionLocalProvider(LocalFirmatario provides firmatario) {
        App(vm, collegamento, firmatario)
    }
}

/** Chi firma le chiamate di gestione (impronta): lo usano le schermate che ne hanno bisogno. */
val LocalFirmatario = staticCompositionLocalOf<FirmatarioAdmin> { error("firmatario non impostato") }

@Composable
fun App(vm: StatoApp, collegamento: Collegamento, firmatario: FirmatarioAdmin) {
    val nav = rememberNavController()
    val voce by nav.currentBackStackEntryAsState()
    val contesto = LocalContext.current
    val sveglia = remember { SvegliaRientro(contesto) }
    // scelte a mano; senza, valgono la proposta automatica e la sveglia predefinita. Dopo ogni timbratura si riparte
    var scelta by rememberSaveable { mutableStateOf<Tipo?>(null) }
    var svegliaScelta by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // sveglia del rientro già programmata (sopravvive al processo: sta nelle preferenze)
    var svegliaAlle by remember { mutableStateOf(sveglia.programmata()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        svegliaAlle = sveglia.programmata()
        // aprendo l'app la sveglia che suona si spegne: si è già qui per timbrare
        sveglia.zittisci()
    }
    val permessoNotifiche = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun chiediNotificheSeServe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !sveglia.puoNotificare()) {
            permessoNotifiche.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val sezione = when (voce?.destination?.route) {
        Rotte.TIMBRA -> Sezione.TIMBRA
        Rotte.STORICO -> Sezione.STORICO
        Rotte.IMPOSTAZIONI -> Sezione.IMPOSTAZIONI
        else -> null
    }
    // icone scure sulla barra di stato solo sopra il blocco colorato di Timbra; chiare su tutte le schermate scure
    val activity = LocalActivity.current
    LaunchedEffect(sezione) {
        activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = sezione == Sezione.TIMBRA }
    }

    Column(Modifier.fillMaxSize().background(Colori.Fondo)) {
        Box(Modifier.weight(1f)) {
            NavHost(nav, startDestination = Rotte.TIMBRA) {
                composable(Rotte.TIMBRA) {
                    TimbraScreen(
                        vm, scelta, onTipo = { scelta = it },
                        sveglia = svegliaScelta, onSveglia = { svegliaScelta = it },
                        svegliaAlle = svegliaAlle,
                        onSvegliaPausa = { accesa ->
                            val uscita = vm.ultima()
                            if (accesa && uscita != null && uscita.tipo == Tipo.USCITA) {
                                sveglia.programma(Sveglia.alle(uscita.quando))
                                chiediNotificheSeServe()
                            } else {
                                sveglia.annulla()
                            }
                            svegliaAlle = sveglia.programmata()
                        },
                        onRegistra = soloSeAttivaDue { tipo, conSveglia -> nav.navigate("registra/${tipo.kind}/$conSveglia") },
                    )
                }
                composable(Rotte.REGISTRA, listOf(navArgument("sveglia") { type = NavType.BoolType })) { e ->
                    val tipo = Tipo.da(e.arguments?.getString("kind"))
                    val conSveglia = e.arguments?.getBoolean("sveglia") ?: false
                    RegistrazioneScreen(
                        tipo,
                        onRegistrata = { r ->
                            vm.ripresa?.cancella()  // una ripresa precedente mai confermata
                            vm.ripresa = r
                            nav.navigate("conferma/${tipo.kind}/$conSveglia") { popUpTo(Rotte.REGISTRA) { inclusive = true } }
                        },
                        onAnnulla = dropUnlessResumed { nav.popBackStack() },
                    )
                }
                composable(Rotte.CONFERMA, listOf(navArgument("sveglia") { type = NavType.BoolType })) { e ->
                    val tipo = Tipo.da(e.arguments?.getString("kind"))
                    val conSveglia = tipo == Tipo.USCITA && e.arguments?.getBoolean("sveglia") == true
                    val app = contesto.applicationContext as TimbroApp
                    val livello = remember { app.chiavi.livello(Chiavi.TIMBRATURE) }
                    var inCorso by remember { mutableStateOf(false) }
                    var errore by remember { mutableStateOf<String?>(null) }
                    ConfermaScreen(
                        tipo, vm.ripresa, conSveglia, livello, inCorso, errore,
                        onConferma = dropUnlessResumed {
                            val r = vm.ripresa
                            if (r == null || inCorso) return@dropUnlessResumed
                            inCorso = true
                            errore = null
                            // nello scope del ViewModel: firma, attestazione e coda finiscono anche se si esce dalla schermata
                            vm.viewModelScope.launch {
                                try {
                                    val voce = Timbratrice(app).timbra(r, tipo.kind)
                                    vm.ripresa = null
                                    val quando = LocalDateTime.ofInstant(Instant.ofEpochMilli(voce.creataIl), ZoneId.systemDefault())
                                    when {
                                        tipo == Tipo.USCITA && conSveglia -> {
                                            sveglia.programma(Sveglia.alle(quando))
                                            chiediNotificheSeServe()
                                        }
                                        else -> sveglia.annulla()  // rientro (o uscita senza sveglia): niente da suonare
                                    }
                                    svegliaAlle = sveglia.programmata()
                                    scelta = null
                                    svegliaScelta = null
                                    nav.popBackStack(Rotte.TIMBRA, inclusive = false)
                                } catch (ex: Exception) {
                                    errore = "Non sono riuscito a firmare la timbratura: ${ex.message}"
                                    inCorso = false
                                }
                            }
                        },
                        onAnnulla = dropUnlessResumed {
                            if (inCorso) return@dropUnlessResumed
                            vm.ripresa?.cancella()  // è entrato qualcuno: il video non resta sul telefono
                            vm.ripresa = null
                            nav.popBackStack(Rotte.TIMBRA, inclusive = false)
                        },
                    )
                }
                composable(Rotte.STORICO) {
                    StoricoScreen(
                        vm,
                        onApri = soloSeAttiva { id: Int -> nav.navigate("dettaglio/$id") },
                        onCondividi = soloSeAttiva { ids: List<Int> -> nav.condividi(vm, ids) },
                        onSegnala = soloSeAttiva { g: LocalDate -> nav.segnala(vm, g) },
                    )
                }
                composable(Rotte.DETTAGLIO, listOf(navArgument("id") { type = NavType.IntType })) { e ->
                    DettaglioScreen(
                        vm, e.arguments?.getInt("id") ?: 0,
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                        onCondividi = soloSeAttiva { ids: List<Int> -> nav.condividi(vm, ids) },
                        onContesta = soloSeAttiva { tid: Int ->
                            vm.esitoContestazione = null  // il link di una contestazione precedente non si mostra più
                            nav.navigate("contesta/$tid")
                        },
                        onSegnalaGiorno = soloSeAttiva { g: LocalDate -> nav.segnala(vm, g) },
                        // dopo la morte del processo la timbratura può non esserci più: si torna indietro senza chiedere
                        onSparita = { nav.popBackStack() },
                    )
                }
                composable(Rotte.CONTESTA, listOf(navArgument("id") { type = NavType.IntType })) { e ->
                    ContestaScreen(
                        vm, e.arguments?.getInt("id") ?: 0, null,
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                        onImpostazioni = dropUnlessResumed { nav.navigate(Rotte.IMPOSTAZIONI) { launchSingleTop = true } },
                        onSparita = { nav.popBackStack() },
                    )
                }
                composable(Rotte.SEGNALA, listOf(navArgument("giorno") { type = NavType.LongType })) { e ->
                    ContestaScreen(
                        vm, null, LocalDate.ofEpochDay(e.arguments?.getLong("giorno") ?: 0L),
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                        onImpostazioni = dropUnlessResumed { nav.navigate(Rotte.IMPOSTAZIONI) { launchSingleTop = true } },
                        onSparita = { nav.popBackStack() },
                    )
                }
                composable(Rotte.ORARIO) {
                    OrarioScreen(vm, onIndietro = dropUnlessResumed { nav.popBackStack() })
                }
                composable(Rotte.CONDIVIDI) { e ->
                    val ids = e.arguments?.getString("ids").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
                    CondividiScreen(vm, ids, onIndietro = dropUnlessResumed { nav.popBackStack() })
                }
                composable(Rotte.IMPOSTAZIONI) {
                    ImpostazioniScreen(
                        vm,
                        collegamento,
                        firmatario,
                        onOrario = dropUnlessResumed { nav.navigate(Rotte.ORARIO) },
                        onUtenti = dropUnlessResumed { nav.navigate(Rotte.UTENTI) },
                        onArticolazioni = dropUnlessResumed { nav.navigate(Rotte.ARTICOLAZIONI) },
                    ) {
                        chiediNotificheSeServe()
                        if (sveglia.prova()) "Sveglia di prova programmata: suona tra 10 secondi."
                        else "Sveglia di prova approssimativa: il telefono non permette le sveglie esatte."
                    }
                }
                composable(Rotte.UTENTI) {
                    UtentiScreen(
                        vm,
                        onUtente = soloSeAttiva { id: Long -> nav.navigate("utente/$id") },
                        onNuovo = dropUnlessResumed { nav.navigate(Rotte.NUOVO_UTENTE) },
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                    )
                }
                composable(Rotte.NUOVO_UTENTE) {
                    NuovoUtenteScreen(
                        vm,
                        // creato: al suo posto la pagina dell'utente (indietro si torna all'elenco)
                        onCreato = soloSeAttiva { id: Long -> nav.navigate("utente/$id") { popUpTo(Rotte.NUOVO_UTENTE) { inclusive = true } } },
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                    )
                }
                composable(Rotte.UTENTE, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    UtenteScreen(
                        vm, e.arguments?.getLong("id") ?: 0L,
                        onAssegnazioni = soloSeAttiva { id: Long -> nav.navigate("assegnazioni/$id") },
                        onIndietro = { nav.popBackStack() },
                    )
                }
                composable(Rotte.ASSEGNAZIONI, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    AssegnazioniScreen(vm, e.arguments?.getLong("id") ?: 0L, onIndietro = { nav.popBackStack() })
                }
                composable(Rotte.ARTICOLAZIONI) {
                    ArticolazioniScreen(
                        vm,
                        onArticolazione = soloSeAttiva { id: Long -> nav.navigate("articolazione/$id") },
                        onNuova = dropUnlessResumed { nav.navigate(Rotte.NUOVA_ARTICOLAZIONE) },
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                    )
                }
                composable(Rotte.NUOVA_ARTICOLAZIONE) {
                    NuovaArticolazioneScreen(
                        vm,
                        onCreata = soloSeAttiva { id: Long -> nav.navigate("articolazione/$id") { popUpTo(Rotte.NUOVA_ARTICOLAZIONE) { inclusive = true } } },
                        onIndietro = dropUnlessResumed { nav.popBackStack() },
                    )
                }
                composable(Rotte.ARTICOLAZIONE, listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    ArticolazioneScreen(vm, e.arguments?.getLong("id") ?: 0L, onIndietro = { nav.popBackStack() })
                }
            }
        }
        if (sezione != null) {
            BarraSezioni(sezione) { s ->
                val rotta = when (s) {
                    Sezione.TIMBRA -> Rotte.TIMBRA
                    Sezione.STORICO -> Rotte.STORICO
                    Sezione.IMPOSTAZIONI -> Rotte.IMPOSTAZIONI
                }
                nav.navigate(rotta) {
                    popUpTo(Rotte.TIMBRA) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }
}

/** [soloSeAttiva] con due argomenti (tipo e sveglia). */
@Composable
private fun <A, B> soloSeAttivaDue(azione: (A, B) -> Unit): (A, B) -> Unit {
    val owner = LocalLifecycleOwner.current
    return { a, b -> if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) azione(a, b) }
}

/** Segnalazione di tutta la giornata: l'esito di una contestazione precedente non si mostra più. */
private fun NavHostController.segnala(vm: StatoApp, giorno: LocalDate) {
    vm.esitoContestazione = null
    navigate("segnala/${giorno.toEpochDay()}")
}

/** Nuovo link: quello creato in precedenza non si mostra più (il token si vede una volta sola). */
private fun NavHostController.condividi(vm: StatoApp, ids: List<Int>) {
    vm.nuovoLink = null
    navigate("condividi/${ids.joinToString(",").ifEmpty { "-" }}")
}
