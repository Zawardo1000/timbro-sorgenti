"""Carica un app bundle nel test interno di Google Play (Google Play Developer API, account di servizio).

    PLAY_SERVICE_ACCOUNT_JSON='{...}' python carica_su_play.py <bundle.aab> "<nome della release>"

La release va ai tester del test interno subito ("completed"). Una versione già caricata non è un errore: il flusso
si può rilanciare. Se Google Play accetta solo bozze (app non ancora pubblicata), la release resta in bozza e va
avviata dalla Play Console.
"""

import json
import os
import sys

from google.auth.transport.requests import AuthorizedSession
from google.oauth2 import service_account

PACCHETTO = "it.zawardo.timbro"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACCHETTO}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACCHETTO}"
BINARIO = "internal"


def risposta(r):
    if r.status_code >= 400:
        raise SystemExit(f"Google Play: HTTP {r.status_code}: {r.text[:800]}")
    return r.json() if r.content else {}


def main(aab: str, nome: str) -> None:
    cred = service_account.Credentials.from_service_account_info(
        json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]), scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    s = AuthorizedSession(cred)
    edit = risposta(s.post(f"{API}/edits", json={}, timeout=60))["id"]
    with open(aab, "rb") as f:
        r = s.post(f"{UPLOAD}/edits/{edit}/bundles", params={"uploadType": "media"}, data=f,
                   headers={"Content-Type": "application/octet-stream"}, timeout=900)
    if 400 <= r.status_code < 500 and "already been used" in r.text:
        print("questa versione è già su Google Play: niente da caricare")
        return
    versione = risposta(r)["versionCode"]
    for stato in ("completed", "draft"):
        r = s.put(f"{API}/edits/{edit}/tracks/{BINARIO}", timeout=60, json={
            "track": BINARIO, "releases": [{"name": nome, "versionCodes": [str(versione)], "status": stato}]})
        if stato == "completed" and r.status_code == 400 and "draft" in r.text.lower():
            print("Google Play accetta solo bozze per questa app: la release resta in bozza")
            continue
        risposta(r)
        break
    risposta(s.post(f"{API}/edits/{edit}:commit", timeout=60))
    print(f"caricata su Google Play: versionCode {versione}, test interno, stato {stato}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
