"""Articolazione dell'orario per utente e calcolo delle ore valide di una giornata.

L'orario è configurabile per utente, con versioni datate ("valido dal"): quando l'ente cambia articolazione si
aggiunge una versione nuova e i giorni passati restano calcolati con quella di allora. Si salva nella tabella
settings (chiave orario:<utente>) e lo imposta l'app.

Lo stesso calcolo c'è nell'app (android/core): i casi di tests/fixtures/contratto/orario.json li controllano tutti e
due, così i due conti non divergono. Le regole producono codici con parametri (non frasi): ognuno li scrive a modo suo.

Regole (valori dell'orario attuale dell'utente come predefiniti):
- l'entrata prima di entry_from vale da entry_from; dopo entry_to vale l'ora vera, ma è un'anomalia;
- è pausa la prima uscita tra pause_from e pause_to seguita da un rientro, nei giorni con la pausa: conta almeno
  pause_counted_min (sotto pause_terminal_min la timbratrice non accetterebbe il rientro: anomalia), oltre conta
  la durata vera; le altre uscite contano per il tempo effettivo;
- senza pausa e oltre no_pause_after_min di lavoro si tolgono no_pause_deduct_min;
- saldo = ore valide - dovute del giorno (0 nei giorni senza orario), solo per giornate complete.
I minuti si contano sulle ore e minuti delle timbrature (i secondi non contano), come sul terminale.
"""

from dataclasses import dataclass, field
from datetime import date, datetime, time

from pydantic import BaseModel, Field, field_validator, model_validator


class DayRule(BaseModel):
    due_min: int = Field(ge=0, le=1440)  # minuti dovuti
    pause: bool = True  # giorno con la pausa (e il rientro)


class Schedule(BaseModel):
    valid_from: date
    days: dict[int, DayRule]  # 1 = lunedì ... 7 = domenica; un giorno assente vale 0 dovute, niente pausa
    entry_from: time = time(7, 30)
    entry_to: time = time(9, 30)
    pause_from: time = time(12, 15)
    pause_to: time = time(13, 15)
    pause_counted_min: int = Field(default=30, ge=0, le=240)
    pause_terminal_min: int = Field(default=15, ge=0, le=240)
    no_pause_after_min: int = Field(default=360, ge=0, le=1440)
    no_pause_deduct_min: int = Field(default=30, ge=0, le=240)

    @field_validator("days")
    @classmethod
    def _weekdays(cls, v: dict[int, DayRule]) -> dict[int, DayRule]:
        if any(not 1 <= k <= 7 for k in v):
            raise ValueError("giorni della settimana da 1 (lunedì) a 7 (domenica)")
        return v

    @model_validator(mode="after")
    def _order(self):
        if self.entry_from > self.entry_to or self.pause_from > self.pause_to:
            raise ValueError("fasce orarie con l'inizio dopo la fine")
        return self


class Schedules(BaseModel):
    versions: list[Schedule] = Field(default_factory=list, max_length=50)

    @field_validator("versions")
    @classmethod
    def _sorted_unique(cls, v: list[Schedule]) -> list[Schedule]:
        v = sorted(v, key=lambda s: s.valid_from)
        if len({s.valid_from for s in v}) != len(v):
            raise ValueError("due versioni con la stessa data di inizio")
        return v

    def at(self, day: date) -> Schedule | None:
        """La versione in vigore quel giorno (l'ultima iniziata), o None."""
        current = None
        for s in self.versions:
            if s.valid_from <= day:
                current = s
        return current


@dataclass(frozen=True)
class Mark:
    kind: str  # "in" | "out"
    at: datetime  # ora locale della timbratura


@dataclass
class Note:
    code: str
    params: dict


@dataclass
class DayResult:
    worked_min: int
    due_min: int | None
    balance_min: int | None
    pause_min: int | None  # durata vera della pausa
    pause_counted_min: int | None  # come la conta l'ente
    open: bool  # giornata in corso (contata fino a "now")
    complete: bool  # tutte le entrate hanno la loro uscita
    adjustments: list[Note] = field(default_factory=list)  # correzioni applicate dalle regole
    anomalies: list[Note] = field(default_factory=list)  # cose da guardare


def _m(t: time) -> int:
    return t.hour * 60 + t.minute


def _hm(m: int) -> str:
    return f"{m // 60:02d}:{m % 60:02d}"


def compute(marks: list[Mark], schedule: Schedule | None, now: datetime | None = None) -> DayResult:
    """Ore valide di una giornata. now (stesso giorno) conta la coppia aperta fino a quel momento."""
    ms = sorted(marks, key=lambda x: x.at)
    day = ms[0].at.date() if ms else (now.date() if now else None)
    adj: list[Note] = []
    anom: list[Note] = []
    pairs: list[list[int | None]] = []  # [inizio, fine] in minuti del giorno
    open_start = None
    for mk in ms:
        m = _m(mk.at.time())
        if mk.kind == "in":
            if open_start is not None:
                anom.append(Note("uscita_mancante", {"dopo": _hm(open_start)}))
            open_start = m
        else:
            if open_start is None:
                anom.append(Note("entrata_mancante", {"prima": _hm(m)}))
                continue
            pairs.append([open_start, m])
            open_start = None
    is_open = False
    if open_start is not None:
        if now is not None and now.date() == day:
            pairs.append([open_start, _m(now.time())])
            is_open = True
        else:
            anom.append(Note("uscita_mancante", {"dopo": _hm(open_start)}))
    complete = not anom and not is_open

    rule = schedule.days.get(day.isoweekday()) if schedule and day else None
    due = (rule.due_min if rule else 0) if schedule else None
    if schedule and pairs:
        first = pairs[0]
        start, lim = first[0], _m(schedule.entry_from)
        if start < lim:
            first[0] = min(lim, first[1])
            adj.append(Note("entrata_anticipata", {"ora": _hm(start), "conteggiata_da": _hm(lim)}))
        elif start > _m(schedule.entry_to):
            anom.append(Note("entrata_tardiva", {"ora": _hm(start), "limite": _hm(_m(schedule.entry_to))}))

    worked = sum(max(b - a, 0) for a, b in pairs)
    pause = pause_counted = None
    if schedule:
        for (_, out), (back, _) in zip(pairs, pairs[1:]):
            gap = max(back - out, 0)
            in_window = _m(schedule.pause_from) <= out <= _m(schedule.pause_to)
            if pause is None and rule is not None and rule.pause and in_window:
                pause = gap
                pause_counted = max(gap, schedule.pause_counted_min)
                if gap < schedule.pause_terminal_min:
                    anom.append(Note("rientro_troppo_presto", {"durata": gap, "minimo": schedule.pause_terminal_min}))
                if pause_counted > gap:
                    worked -= pause_counted - gap
                    adj.append(Note("pausa_minima", {"durata": gap, "conteggiata": pause_counted}))
            else:
                adj.append(Note("uscita_non_pausa", {"ora": _hm(out), "durata": gap}))
        if pause is None and worked > schedule.no_pause_after_min:
            worked -= schedule.no_pause_deduct_min
            adj.append(Note("pausa_non_fatta", {"tolti": schedule.no_pause_deduct_min, "oltre": schedule.no_pause_after_min}))
    worked = max(worked, 0)
    return DayResult(
        worked_min=worked,
        due_min=due,
        balance_min=worked - due if due is not None and complete else None,
        pause_min=pause,
        pause_counted_min=pause_counted,
        open=is_open,
        complete=complete,
        adjustments=adj,
        anomalies=anom,
    )


# --- testi in italiano (pagina pubblica e mail) -------------------------------------------------------------------


def duration(m: int) -> str:
    return f"{m // 60} h {m % 60:02d}"


def balance(m: int) -> str:
    sign = "+" if m >= 0 else "−"
    m = abs(m)
    return f"{sign}{m // 60} h {m % 60:02d}" if m >= 60 else f"{sign}{m}′"


def describe(n: Note) -> str:
    p = n.params
    return {
        "entrata_anticipata": lambda: f"entrata delle {p['ora']} conteggiata dalle {p['conteggiata_da']}",
        "pausa_minima": lambda: f"pausa di {p['durata']}′ conteggiata {p['conteggiata']}′",
        "uscita_non_pausa": lambda: f"uscita delle {p['ora']} (non è la pausa): conta il tempo effettivo, {p['durata']}′",
        "pausa_non_fatta": lambda: f"nessuna pausa con più di {duration(p['oltre'])} di lavoro: tolti {p['tolti']}′",
        "entrata_tardiva": lambda: f"entrata delle {p['ora']}, dopo il limite delle {p['limite']}",
        "rientro_troppo_presto": lambda: f"rientro dopo {p['durata']}′, prima dei {p['minimo']}′ che la timbratrice "
                                         "accetta",
        "uscita_mancante": lambda: f"manca l'uscita dopo l'entrata delle {p['dopo']}",
        "entrata_mancante": lambda: f"manca l'entrata prima dell'uscita delle {p['prima']}",
    }.get(n.code, lambda: n.code)()


GIORNI_BREVI = {1: "lunedì", 2: "martedì", 3: "mercoledì", 4: "giovedì", 5: "venerdì", 6: "sabato", 7: "domenica"}


def describe_schedule(s: Schedule) -> list[str]:
    """L'orario in vigore, in frasi (scheda della pagina pubblica)."""
    days = ", ".join(
        f"{GIORNI_BREVI[d]} {duration(r.due_min)}{'' if r.pause else ' senza pausa'}"
        for d, r in sorted(s.days.items()) if r.due_min
    )
    return [
        f"In vigore dal {s.valid_from:%d/%m/%Y}. Ore dovute: {days or 'nessuna'}.",
        f"Ingresso dalle {s.entry_from:%H:%M} alle {s.entry_to:%H:%M}: un'entrata precedente vale dalle "
        f"{s.entry_from:%H:%M}.",
        f"La pausa inizia tra le {s.pause_from:%H:%M} e le {s.pause_to:%H:%M} e conta almeno {s.pause_counted_min} "
        f"minuti; il rientro è possibile dopo {s.pause_terminal_min} minuti; oltre i {s.pause_counted_min} minuti conta "
        "la durata effettiva.",
        f"Senza pausa, oltre {s.no_pause_after_min // 60} ore di lavoro si tolgono {s.no_pause_deduct_min} minuti.",
        "Le altre uscite contano per il tempo effettivo.",
    ]
