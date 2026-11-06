"""Versione del codice in esecuzione e collegamenti ai sorgenti pubblici (docs/PROGETTO.md §25).

Sul server gitdeploy scrive il commit installato in `.gitdeploy-commit`, nella radice del codice (/app). Ogni commit
che va in produzione è prima pubblicato nel repository pubblico con il tag `timbro-<7 cifre>` e la sua release
(sorgenti marcati, attestazione di GitHub, impronte dell'app): da qui i collegamenti per la pagina e per il
pacchetto di prova. Sul PC il file non c'è: versione sconosciuta, nessun collegamento.
"""

import functools
import re

from timbro.config import BASE_DIR

COMMIT_FILE = BASE_DIR / ".gitdeploy-commit"
_COMMIT_RE = re.compile(r"^[0-9a-f]{40}$")
# versione dell'app nel manifest: "0.1.<numero>+<7 cifre del commit>", "-dirty" se compilata con modifiche locali
_APP_RE = re.compile(r"^(?P<nome>[0-9][0-9A-Za-z.\-]*)\+(?P<sha>[0-9a-f]{7,40})(?P<sporca>-dirty)?$")


@functools.cache
def server_commit() -> str | None:
    """Commit installato (letto una volta: senza riavvio cambiano solo file che l'app non usa)."""
    try:
        value = COMMIT_FILE.read_text(encoding="ascii").strip()
    except OSError:
        return None
    return value if _COMMIT_RE.match(value) else None


def tag(commit: str) -> str:
    return f"timbro-{commit[:7]}"


def release_url(base: str, commit: str | None) -> str | None:
    return f"{base.rstrip('/')}/releases/tag/{tag(commit)}" if commit else None


def tree_url(base: str, commit: str | None, path: str) -> str | None:
    """Cartella dei sorgenti in quella versione (android/ per l'app, timbro/ per il server)."""
    return f"{base.rstrip('/')}/tree/{tag(commit)}/{path}" if commit else None


def file_url(base: str, commit: str, path: str) -> str:
    """Indirizzo del file così com'è in quella versione (raw.githubusercontent.com per i repository GitHub)."""
    if base.startswith("https://github.com/"):
        return f"https://raw.githubusercontent.com/{base.removeprefix('https://github.com/').rstrip('/')}/{tag(commit)}/{path}"
    return f"{base.rstrip('/')}/blob/{tag(commit)}/{path}"


def app_source(app_version: str | None) -> tuple[str | None, bool]:
    """(commit dell'app, compilata in locale con modifiche) dalla versione scritta nel manifest."""
    m = _APP_RE.match(app_version or "")
    if not m:
        return None, False
    return m.group("sha"), bool(m.group("sporca"))
