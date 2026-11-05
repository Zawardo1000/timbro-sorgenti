package it.zawardo.timbro.ui

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val IT = Locale.ITALIAN
private val giornoEsteso = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", IT)
private val giornoMeseEsteso = DateTimeFormatter.ofPattern("EEEE d MMMM", IT)
private val giornoSettimana = DateTimeFormatter.ofPattern("EEE", IT)
private val giornoSettimanaEsteso = DateTimeFormatter.ofPattern("EEEE", IT)
private val soloMese = DateTimeFormatter.ofPattern("MMMM", IT)
private val giornoMeseParole = DateTimeFormatter.ofPattern("d MMMM", IT)
private val meseAnno = DateTimeFormatter.ofPattern("MMMM yyyy", IT)
private val oraMinuti = DateTimeFormatter.ofPattern("HH:mm")
private val oraSecondi = DateTimeFormatter.ofPattern("HH:mm:ss")
private val dataBreve = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val giornoMese = DateTimeFormatter.ofPattern("dd/MM")

private fun String.maiuscola() = replaceFirstChar { it.titlecase(IT) }

fun LocalDate.esteso(): String = format(giornoEsteso).maiuscola()           // Giovedì 5 novembre 2026
fun LocalDate.senzaAnno(): String = format(giornoMeseEsteso).maiuscola()    // Giovedì 5 novembre
fun LocalDate.settimana(): String = format(giornoSettimana).take(3)        // gio
fun LocalDate.nomeGiorno(): String = format(giornoSettimanaEsteso).maiuscola() // Giovedì
fun LocalDate.nomeMese(): String = format(soloMese)                        // novembre
fun LocalDate.giornoMeseParole(): String = format(giornoMeseParole)        // 26 ottobre
fun LocalDate.mese(): String = format(meseAnno).uppercase(IT)              // NOVEMBRE 2026
fun LocalDate.breve(): String = format(dataBreve)                          // 05/11/2026
fun LocalDate.giornoMese(): String = format(giornoMese)                    // 05/11
fun LocalDateTime.oraMinuti(): String = format(oraMinuti)                  // 07:58
fun LocalDateTime.oraSecondi(): String = format(oraSecondi)                // 07:58:12
fun LocalTime.oraMinuti(): String = format(oraMinuti)                      // 12:15

fun Duration.oreMinuti(): String = String.format(Locale.ROOT, "%d h %02d", toHours(), toMinutesPart())  // 8 h 16
fun Duration.primi(): String = "${toMinutes()}′"                                                        // 29′
fun Duration.contoAllaRovescia(): String = String.format(Locale.ROOT, "%02d:%02d", toMinutes(), toSecondsPart())  // 09:42
