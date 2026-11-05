"""Prova end-to-end in locale con TSA e calendar OTS veri: un telefono simulato timbra e carica il video.

Uso: docker compose run --rm test python tests/scripts/e2e_local.py
Usa il database dei test (TEST_DATABASE_URL, svuotato a ogni avvio) e i file in /tmp/e2e del container.
"""

import os
import shutil
import time
from pathlib import Path

from fastapi.testclient import TestClient
from sqlalchemy import create_engine, text

from tests.helpers import Phone, body
from timbro import services
from timbro.app import create_app
from timbro.config import Settings

DATA = Path("/tmp/e2e")
shutil.rmtree(DATA, ignore_errors=True)
DB = os.environ["TEST_DATABASE_URL"]
with create_engine(DB, isolation_level="AUTOCOMMIT").connect() as c:
    c.execute(text("DROP SCHEMA public CASCADE"))
    c.execute(text("CREATE SCHEMA public"))
settings = Settings(database_url=DB, data_dir=DATA, worker_interval_s=1, periodic_checks_enabled=False, _env_file=None)

with TestClient(create_app(settings)) as app:
    with app.app.state.db.session() as s:
        code = services.new_pairing_code(s, services.create_user(s, "io"), settings)
    ph = Phone()
    r = app.post("/api/v1/devices/register", json=ph.registration(code, "sim")).json()
    ph.device_id, ph.token = r["device_id"], r["api_token"]

    video = os.urandom(1_500_000)
    raw, sig = ph.punch(video)
    t0 = time.monotonic()
    pid = app.post("/api/v1/punches", json=body(raw, sig), headers=ph.auth).json()["id"]
    app.put(f"/api/v1/punches/{pid}/video", content=video, headers=ph.auth)
    while time.monotonic() - t0 < 90:
        st = app.get(f"/api/v1/punches/{pid}", headers=ph.auth).json()
        if all(x["status"] != "pending" for x in st["stamps"]):
            break
        time.sleep(1)
    print(f"dopo {time.monotonic() - t0:.1f} s  video ricevuto={st['video_received']}")
    print(f"server_received_at: {st['server_received_at']}")
    for x in st["stamps"]:
        print(f"  {x["target"]:6} {x["provider"]:10} qualificata={x['qualified']!s:5} {x['status']:10} {x['gen_time']}")

    # OCSP/CRL conservati e verifica completa dell'archivio
    import json

    from timbro.models import Stamp
    from timbro.verify import verify_archive

    with app.app.state.db.session() as s:
        for st in s.query(Stamp).filter(Stamp.kind == "rfc3161", Stamp.status == "complete"):
            rev = json.loads(st.revinfo_json or "{}")
            print(f"  revinfo {st.target:6} {st.provider:10} ocsp={len(rev.get('ocsp', []))} crl={len(rev.get('crl', []))}")
        rep = verify_archive(s)
        print(f"verifica archivio: ok={rep.ok} anelli={rep.links} marche riverificate={rep.stamps_checked}")
        for e in rep.errors:
            print("  ERRORE", e)
        for w in rep.warnings:
            print("  avviso", w)

    # pacchetto di prova: si scarica e si verifica con openssl, come farebbe un perito
    import io
    import subprocess
    import zipfile

    pkg = DATA / "pacchetto"
    r = app.get(f"/api/v1/admin/punches/{pid}/evidence.zip", headers=ph.admin_headers("GET", f"/api/v1/admin/punches/{pid}/evidence.zip"))
    zipfile.ZipFile(io.BytesIO(r.content)).extractall(pkg)
    print(f"pacchetto: {sorted(p.name for p in pkg.rglob('*') if p.is_file())}")
    checks = [["openssl", "dgst", "-sha256", "-verify", "device_pubkey.pem", "-signature", "manifest.sig", "manifest.json"]]
    for tsr in sorted((pkg / "stamps").glob("*.tsr")):
        target, prov = tsr.stem.split("_", 1)
        src = ["-data", "video.mp4"] if target == "video" else ["-digest", json.loads((pkg / "record.json").read_text())["timbratura"]["chain_hash"]]
        checks.append(["openssl", "ts", "-verify", *src, "-in", f"stamps/{tsr.name}", "-CAfile", f"stamps/{prov}_trust.pem"])
    if shutil.which("openssl") is None:
        print("openssl non installato qui: verifica il pacchetto sul PC con i comandi di VERIFICA.md")
        checks = []
    for cmd in checks:
        out = subprocess.run(cmd, cwd=pkg, capture_output=True, text=True)
        result = next((l for l in (out.stdout + out.stderr).splitlines() if "Verif" in l), out.stderr.strip()[-200:])
        print(f"  {' '.join(cmd[1:3])} {cmd[-3] if 'ts' in cmd else ''}: {result}")
