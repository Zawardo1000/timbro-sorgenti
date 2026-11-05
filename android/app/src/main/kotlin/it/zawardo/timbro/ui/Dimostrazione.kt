package it.zawardo.timbro.ui

import it.zawardo.timbro.core.Orario
import it.zawardo.timbro.core.RegolaGiorno
import it.zawardo.timbro.core.VersioneOrario
import it.zawardo.timbro.core.api.Amministrazione
import it.zawardo.timbro.core.api.Articolazione
import it.zawardo.timbro.core.api.Assegnazione
import it.zawardo.timbro.core.api.DatiProfilo
import it.zawardo.timbro.core.api.Io
import it.zawardo.timbro.core.api.LinkCondivisione
import it.zawardo.timbro.core.api.TelefonoUtente
import it.zawardo.timbro.core.api.UtenteSistema
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Dati finti della modalità dimostrativa (solo debug, Impostazioni → Questo telefono): circa sei settimane di
 * giornate con i casi da vedere nello Storico, due utenti, due articolazioni e un link. Niente server: si guardano
 * le schermate, le modifiche restano in memoria.
 */
internal object Dimostrazione {
    private data class Modello(val ore: List<Pair<Int, Int>>, val segni: Map<Int, (Timbratura) -> Timbratura> = emptyMap())

    const val TELEFONO = "5b0f6c1e-8d7a-4c39-9a51-2f7e4b1d0c33"
    private val DAL = LocalDate.of(2026, 1, 1)

    val articolazioni: List<Articolazione> = listOf(
        Articolazione(1, "36 h su 5 giorni, ven 4 h", Orario.predefinito().versions),
        Articolazione(2, "4 giorni da 9 ore", listOf(VersioneOrario(DAL, (1..4).associateWith { RegolaGiorno(540, true) }))),
    )

    private val profilo = DatiProfilo(
        "Mario Rossi", "012345",
        listOf("ufficio.personale@ente.example.it", "responsabile@ente.example.it"), "mario.rossi@example.it",
    )

    val utenti: List<UtenteSistema> = listOf(
        UtenteSistema(
            2, "rossi", Amministrazione.ADMIN, profilo, listOf(Assegnazione(1, articolazioni[0].name, DAL)),
            listOf(TelefonoUtente(TELEFONO, "Pixel di Mario", "2026-09-26T08:12:00+00:00")),
        ),
        UtenteSistema(
            1, "prova", Amministrazione.UTENTE, DatiProfilo("Utente di prova", "000000"), listOf(Assegnazione(1, articolazioni[0].name, DAL)),
            listOf(TelefonoUtente("0a1b2c3d-0000-4000-8000-0000000000aa", "Telefono di prova", "2026-11-05T12:30:00+00:00")),
        ),
    )

    val io = Io(2, "rossi", Amministrazione.ADMIN, TELEFONO, profilo, utenti[0].assignments)

    val orario: Orario get() = Amministrazione.risolvi(io.assignments, articolazioni)

    fun link(oggi: LocalDate): List<LinkCondivisione> = listOf(
        LinkCondivisione(
            31, "Per il sindacato", listOf(101, 102, 103), true,
            OffsetDateTime.now().minusDays(6).toString(), oggi.plusDays(9).atTime(12, 0).atOffset(OffsetDateTime.now().offset).toString(),
            accesses = 4,
        ),
    )

    /** Dal lunedì al giovedì due entrate e due uscite, il venerdì mezza giornata; due giorni di ferie, oggi in corso. */
    fun timbrature(): List<Timbratura> {
        val oggi = LocalDate.now()
        val giorni = generateSequence(oggi.minusDays(1)) { it.minusDays(1) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .take(30).toList()
        val ieri = giorni[0]
        val interessanti = listOf(
            Modello(
                listOf(8 to 1, 12 to 30, 12 to 58, 17 to 2),
                mapOf(0 to { t -> t.copy(contestata = true, daTenere = true, giustificativo = "Prot. 4521 del ${ieri.dayOfMonth}/${ieri.monthValue}", note = "Terminale lento, badge passato due volte.") }),
            ),
            Modello(listOf(7 to 20, 12 to 30, 12 to 58, 16 to 40)),  // entrata prima delle 7:30: vale dalle 7:30
            Modello(listOf(7 to 55, 12 to 28, 12 to 50, 16 to 58), mapOf(2 to { t -> t.copy(stato = StatoInvio.RICEVUTA, retiGiaViste = 3) }, 3 to { t -> t.copy(daTenere = true) })),
            Modello(listOf(7 to 45, 15 to 10)),  // senza pausa oltre 6 ore: tolti 30 minuti
            Modello(listOf(8 to 0, 12 to 30, 13 to 0), mapOf(2 to { t -> t.copy(contestata = true) })),  // uscita dimenticata
            Modello(listOf(8 to 3, 12 to 35, 13 to 4, 17 to 6), mapOf(3 to { t -> t.copy(contestata = true) })),
            Modello(listOf(7 to 59, 12 to 30, 12 to 59, 16 to 30), mapOf(0 to { t -> t.copy(videoEliminato = true, videoRicevuto = false) })),
            Modello(listOf(7 to 57, 12 to 32, 12 to 58, 17 to 1), mapOf(0 to { t -> t.copy(videoEliminato = true, videoRicevuto = false) })),
        )
        fun normale(i: Int): Modello {
            val entrata = 7 * 60 + 55 + (i * 7) % 9
            val uscita = 12 * 60 + 28 + (i * 5) % 8
            val rientro = uscita + 25 + (i * 3) % 6
            val fine = 16 * 60 + 30 + (i * 11) % 37
            return Modello(
                listOf(entrata, uscita, rientro, fine).map { it / 60 to it % 60 },
                buildMap<Int, (Timbratura) -> Timbratura> {
                    if (i % 9 == 0) put(3) { t -> t.copy(contestata = true) }
                    if (i % 13 == 0) put(0) { t -> t.copy(daTenere = true) }
                },
            )
        }
        fun venerdi(i: Int): Modello {
            val entrata = 7 * 60 + 40 + (i * 4) % 14
            return Modello(listOf(entrata, entrata + 238 + (i * 5) % 9).map { it / 60 to it % 60 })
        }
        val ferie = setOf(14, 15)
        var k = 0
        val modelli = giorni.mapIndexedNotNull { i, g ->
            when {
                i in ferie -> null
                g.dayOfWeek == DayOfWeek.FRIDAY -> g to venerdi(i)
                else -> g to (interessanti.getOrNull(k) ?: normale(k)).also { k++ }
            }
        }
        var id = 100
        val righe = mutableListOf<Timbratura>()
        modelli.reversed().forEach { (giorno, m) ->
            m.ore.forEachIndexed { i, (h, min) ->
                val tipo = if (i % 2 == 0) Tipo.ENTRATA else Tipo.USCITA
                val t = Timbratura(id++, giorno.atTime(h, min, (id * 7) % 60), tipo)
                righe += m.segni[i]?.invoke(t) ?: t
            }
        }
        val entrataOggi = oggi.atTime(LocalTime.of(7, 58, 12))
        if (LocalDateTime.now().isAfter(entrataOggi.plusMinutes(1))) righe += Timbratura(id, entrataOggi, Tipo.ENTRATA)
        return righe.sortedByDescending { it.quando }
    }
}
