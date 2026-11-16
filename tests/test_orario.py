"""Calcolo delle ore valide: casi condivisi con l'app (tests/fixtures/contratto/orario.json)."""

import json
from datetime import date, datetime, time
from pathlib import Path

import pytest
from pydantic import ValidationError

from timbro import orario

DATA = json.loads((Path(__file__).parent / "fixtures" / "contratto" / "orario.json").read_text(encoding="utf-8"))
SCHEDULES = orario.Schedules.model_validate(DATA["schedule"])


def _at(day: str, hm: str) -> datetime:
    return datetime.combine(date.fromisoformat(day), time.fromisoformat(hm))


@pytest.mark.parametrize("case", DATA["cases"], ids=[c["name"] for c in DATA["cases"]])
def test_casi_condivisi(case):
    marks = [orario.Mark(k, _at(case["day"], hm), *reason) for k, hm, *reason in case["marks"]]
    now = _at(case["day"], case["now"]) if case.get("now") else None
    r = orario.compute(marks, SCHEDULES.at(date.fromisoformat(case["day"])), now)
    got = {
        "worked_min": r.worked_min, "due_min": r.due_min, "balance_min": r.balance_min, "pause_min": r.pause_min,
        "pause_counted_min": r.pause_counted_min, "open": r.open, "complete": r.complete,
        "adjustments": [{"code": n.code, "params": n.params} for n in r.adjustments],
        "anomalies": [{"code": n.code, "params": n.params} for n in r.anomalies],
    }
    assert got == {k: case[k] for k in got}
    for n in r.adjustments + r.anomalies:
        assert orario.describe(n) != n.code  # ogni codice ha il suo testo


def test_versioni_dell_orario():
    assert SCHEDULES.at(date(2025, 12, 31)) is None
    assert SCHEDULES.at(date(2026, 11, 4)).days[5].due_min == 240
    assert 5 not in SCHEDULES.at(date(2027, 1, 4)).days
    with pytest.raises(ValidationError):
        orario.Schedules.model_validate({"versions": [DATA["schedule"]["versions"][0]] * 2})  # stessa data
    with pytest.raises(ValidationError):
        orario.Schedule.model_validate({"valid_from": "2026-01-01", "days": {"8": {"due_min": 60}}})
    with pytest.raises(ValidationError):
        orario.Schedule.model_validate({"valid_from": "2026-01-01", "days": {}, "entry_from": "10:00", "entry_to": "09:00"})


def test_senza_orario_solo_ore_lorde():
    marks = [orario.Mark("in", _at("2026-11-04", "07:00")), orario.Mark("out", _at("2026-11-04", "15:00"))]
    r = orario.compute(marks, None)
    assert (r.worked_min, r.due_min, r.balance_min, r.adjustments) == (480, None, None, [])


def test_senza_orario_il_tempo_fuori_conta_lo_stesso():
    """La causale non è una regola dell'orario: senza orario il tempo fuori per servizio conta comunque."""
    marks = [orario.Mark("in", _at("2026-11-04", "08:00")), orario.Mark("out", _at("2026-11-04", "10:00"), "service"),
             orario.Mark("in", _at("2026-11-04", "11:00"), "service"), orario.Mark("out", _at("2026-11-04", "12:00"))]
    r = orario.compute(marks, None)
    assert (r.worked_min, r.due_min, r.complete) == (240, None, True)
    assert [n.code for n in r.adjustments] == ["fuori_in_presenza"]
    # senza causale le stesse timbrature valgono 3 ore
    assert orario.compute([orario.Mark(m.kind, m.at) for m in marks], None).worked_min == 180


def test_fuori_ieri_senza_rientro_non_conta_fino_a_adesso():
    """now di un altro giorno non apre niente: la giornata passata conta fino all'uscita, da verificare."""
    marks = [orario.Mark("in", _at("2026-11-04", "08:00")), orario.Mark("out", _at("2026-11-04", "10:00"), "service")]
    r = orario.compute(marks, SCHEDULES.at(date(2026, 11, 4)), _at("2026-11-05", "09:00"))
    assert (r.worked_min, r.open, r.complete) == (120, False, True)
    assert [n.code for n in r.anomalies] == ["senza_rientro"]


def test_testi():
    assert orario.duration(514) == "8 h 34" and orario.balance(34) == "+34′" and orario.balance(-90) == "−1 h 30"
    lines = orario.describe_schedule(SCHEDULES.versions[0])
    assert "venerdì 4 h 00 senza pausa" in lines[0] and "07:30" in lines[1]
    assert "servizio" in lines[-1] and "visita medica" in lines[-1]
    labels = [orario.punch_label(k, r) for r in (None, "service", "medical") for k in ("in", "out")]
    assert labels == ["entrata", "uscita", "entrata di servizio", "uscita di servizio", "entrata da visita medica",
                      "uscita per visita medica"]
    assert orario.describe(orario.Note("fuori_in_presenza", {"ora": "10:00", "durata": 60, "causale": "medical"})) == (
        "fuori per visita medica dalle 10:00: 60′ contati come presenza")
