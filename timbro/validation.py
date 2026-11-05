"""Tipi per gli input dell'API e del manifest: valori che Postgres rifiuterebbe diventano 422, non 500."""

from datetime import datetime
from typing import Annotated

from pydantic import AfterValidator, AwareDatetime


def _no_nul(v: str | None) -> str | None:
    # Postgres non accetta il carattere NUL nei campi di testo: l'inserimento fallirebbe con un 500
    if v is not None and "\x00" in v:
        raise ValueError("carattere NUL non ammesso")
    return v


def _plausible(v: datetime | None) -> datetime | None:
    # anni estremi (0001, 9999) danno OverflowError nella conversione in UTC
    if v is not None and not 2000 <= v.year <= 2200:
        raise ValueError("data fuori dall'intervallo ammesso (2000-2200)")
    return v


NoNulStr = Annotated[str, AfterValidator(_no_nul)]
PlausibleDatetime = Annotated[AwareDatetime, AfterValidator(_plausible)]
