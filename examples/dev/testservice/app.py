"""
Kleiner Test-Client + Mock-DNPM:DIP fuer den mv64e-etl-processor.

Zwei Rollen in einer kleinen Flask-App:

1. Sender: schickt auf Knopfdruck ein komplexes, echtes Mtb-Beispiel
   (fixtures/mv64e-mtb-fake-patient.json, aus den eigenen Testressourcen
   des ETL-Repos) per POST an den ETL, so wie es sonst das
   Onkostar-Export-Plugin tun wuerde.
2. Empfaenger: simuliert DNPM:DIP - nimmt entgegen, was der ETL nach
   erfolgreicher Verarbeitung weiterleitet (APP_REST_URI muss auf diese
   App zeigen), zeigt es im Frontend an.

Nur fuer lokale Entwicklung/Tests gedacht, keine Auth-Haertung noetig.
"""

from __future__ import annotations

import copy
import json
import logging
import os
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path

import requests
from flask import Flask, jsonify, request, render_template

logging.basicConfig(level=logging.INFO, format="%(asctime)s [testservice] %(message)s")
log = logging.getLogger("testservice")

app = Flask(__name__)

FIXTURE_PATH = Path(__file__).parent / "fixtures" / "mv64e-mtb-fake-patient.json"
FIXTURE_RAW = FIXTURE_PATH.read_text(encoding="utf-8")
ORIGINAL_PATIENT_ID = json.loads(FIXTURE_RAW)["patient"]["id"]

ETL_BASE_URL = os.environ.get("ETL_BASE_URL", "http://localhost:8000").rstrip("/")
ETL_USERNAME = os.environ.get("ETL_USERNAME", "admin")
ETL_PASSWORD = os.environ.get("ETL_PASSWORD", "very-secret")

# what the /mtb/etl/patient-record receiver replies with on the *next* call,
# selectable from the frontend so you can test SUCCESS/WARNING/ERROR/DUPLICATION
# handling in the ETL's own monitoring UI without touching real gPAS/gICS/DIP.
SEVERITY_ISSUES = {
    "success": [],
    "warning": [{"severity": "warning", "message": "Testservice: simulierte Warnung"}],
    "error": [{"severity": "error", "message": "Testservice: simulierter Fehler"}],
    "fatal": [{"severity": "fatal", "message": "Testservice: simulierter fataler Fehler"}],
}

state_lock = threading.Lock()
state = {
    "next_severity": "success",
    "last_sent": None,
    "last_received": None,
    "history": [],  # newest first, {time, kind, summary}
}

MAX_HISTORY = 25


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _record_history(kind: str, summary: str) -> None:
    state["history"].insert(0, {"time": _now(), "kind": kind, "summary": summary})
    del state["history"][MAX_HISTORY:]


def _build_payload(randomize_patient_id: bool) -> tuple[dict, str]:
    """Returns (parsed payload, patient id used). Random id avoids duplicate-detection
    kicking in when you just want to see a fresh file go through; keep the same id to
    deliberately test duplicate detection instead."""
    if not randomize_patient_id:
        return json.loads(FIXTURE_RAW), ORIGINAL_PATIENT_ID

    new_id = str(uuid.uuid4())
    # simple text substitution: the fixture references the same patient id in many
    # nested "patient": {"id": "..."} spots, a plain string replace keeps them consistent
    # without having to walk the whole nested structure by hand
    randomized_raw = FIXTURE_RAW.replace(ORIGINAL_PATIENT_ID, new_id)
    return json.loads(randomized_raw), new_id


@app.route("/")
def index():
    with state_lock:
        return render_template(
            "index.html",
            etl_base_url=ETL_BASE_URL,
            state=copy.deepcopy(state),
        )


@app.route("/send", methods=["POST"])
def send():
    randomize = request.json.get("randomize_patient_id", True) if request.is_json else True
    payload, patient_id = _build_payload(randomize)

    url = f"{ETL_BASE_URL}/mtb"
    log.info("Sende MTB-Datei fuer Patient %s an %s", patient_id, url)

    try:
        response = requests.post(
            url,
            json=payload,
            auth=(ETL_USERNAME, ETL_PASSWORD),
            headers={"Content-Type": "application/json"},
            timeout=30,
        )
        result = {
            "ok": True,
            "status_code": response.status_code,
            "body": response.text,
            "patient_id": patient_id,
            "time": _now(),
        }
    except requests.RequestException as e:
        log.error("Senden an ETL fehlgeschlagen: %s", e)
        result = {
            "ok": False,
            "error": str(e),
            "patient_id": patient_id,
            "time": _now(),
        }

    with state_lock:
        state["last_sent"] = result
        _record_history(
            "sent",
            f"Patient {patient_id[:8]}... -> HTTP {result.get('status_code', 'ERR')}",
        )

    return jsonify(result)


def _extract_patient_id(mtb_body: dict) -> str | None:
    try:
        return mtb_body.get("patient", {}).get("id")
    except AttributeError:
        return None


@app.route("/mtb/etl/patient-record", methods=["POST"])
def receive_patient_record():
    """Mimics DNPM:DIP's ingest endpoint - this is what the ETL calls after it has
    pseudonymized/consent-checked/duplicate-checked the file itself."""
    raw_body = request.get_data(as_text=True)
    try:
        parsed = json.loads(raw_body) if raw_body else {}
    except json.JSONDecodeError:
        parsed = None

    with state_lock:
        severity = state["next_severity"]
        received = {
            "time": _now(),
            "content_type": request.content_type,
            "had_auth_header": "Authorization" in request.headers,
            "patient_id": _extract_patient_id(parsed) if parsed else None,
            "body_preview": raw_body[:4000],
            "body_size": len(raw_body),
            "replied_severity": severity,
        }
        state["last_received"] = received
        _record_history(
            "received",
            f"POST patient-record, Pseudonym {received['patient_id']}, Antwort={severity}",
        )

    log.info(
        "MTB-Datei vom ETL empfangen (Pseudonym=%s, %d bytes), antworte mit '%s'",
        received["patient_id"],
        received["body_size"],
        severity,
    )
    return jsonify({"issues": SEVERITY_ISSUES[severity]}), 200


@app.route("/mtb/etl/patient/<patient_id>", methods=["DELETE"])
def receive_delete(patient_id: str):
    with state_lock:
        state["last_received"] = {
            "time": _now(),
            "content_type": request.content_type,
            "had_auth_header": "Authorization" in request.headers,
            "patient_id": patient_id,
            "body_preview": "(DELETE request, kein Body)",
            "body_size": 0,
            "replied_severity": "success",
        }
        _record_history("received", f"DELETE patient {patient_id}")
    log.info("Loeschanfrage vom ETL fuer Pseudonym %s empfangen", patient_id)
    return "", 200


@app.route("/set-severity", methods=["POST"])
def set_severity():
    severity = request.json.get("severity", "success")
    if severity not in SEVERITY_ISSUES:
        return jsonify({"ok": False, "error": f"unknown severity '{severity}'"}), 400
    with state_lock:
        state["next_severity"] = severity
    return jsonify({"ok": True, "severity": severity})


@app.route("/state")
def get_state():
    with state_lock:
        return jsonify(copy.deepcopy(state))


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    log.info("Testservice startet auf Port %d, ETL_BASE_URL=%s", port, ETL_BASE_URL)
    app.run(host="0.0.0.0", port=port, debug=True)
