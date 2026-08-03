"""
Kleiner Test-Client + Mock-DNPM:DIP fuer den mv64e-etl-processor.

Drei Rollen in einer kleinen Flask-App:

1. Sender: schickt auf Knopfdruck ein komplexes, echtes Mtb-Beispiel
   (fixtures/mv64e-mtb-fake-patient.json, aus den eigenen Testressourcen
   des ETL-Repos) per POST an den ETL, so wie es sonst das
   Onkostar-Export-Plugin tun wuerde.
2. Empfaenger: simuliert DNPM:DIP - nimmt entgegen, was der ETL nach
   erfolgreicher Verarbeitung weiterleitet (APP_REST_URI muss auf diese
   App zeigen), zeigt es im Frontend an.
3. Mock fuer DIZ (LMU-Fork, Consent-Service "diz_keycloak"): beantwortet
   Keycloak-Token-Requests und die Consent-Abfrage (GET /Consent?patient=...)
   des ETL, damit sich der diz_keycloak-Codepfad testen laesst, ohne dass
   echtes DIZ/Keycloak erreichbar sein muss. Welche Patient-IDs einen
   (Mock-)Consent haben, wird ueber das Sende-Formular gesteuert (siehe /send).

Nur fuer lokale Entwicklung/Tests gedacht, keine Auth-Haertung noetig.
"""

from __future__ import annotations

import copy
import json
import logging
import os
import threading
import uuid
from datetime import datetime, timedelta, timezone
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

# must match app.consent.diz.broad-consent-policy-code/-system in the ETL's DizConsentConfigProperties
# (defaults shown here - override via env if the ETL side was reconfigured to non-default values)
CONSENT_POLICY_CODE = os.environ.get("CONSENT_POLICY_CODE", "2.16.840.1.113883.3.1937.777.24.5.3.6")
CONSENT_POLICY_SYSTEM = os.environ.get(
    "CONSENT_POLICY_SYSTEM", "urn:oid:2.16.840.1.113883.3.1937.777.24.5.3"
)

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
    "last_consent_check": None,
    "last_token_request": None,
    "consented_patient_ids": [],  # patient IDs the mock currently has a Consent on file for
    "history": [],  # newest first, {time, kind, summary}
}

MAX_HISTORY = 25


def _now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _record_history(kind: str, summary: str) -> None:
    state["history"].insert(0, {"time": _now(), "kind": kind, "summary": summary})
    del state["history"][MAX_HISTORY:]


def _build_payload(randomize_patient_id: bool, explicit_patient_id: str | None) -> tuple[dict, str]:
    """Returns (parsed payload, patient id used). An explicit id (typed into the frontend) wins
    over randomization, so you can pick an id you also marked as having a Consent. Otherwise a
    random id avoids duplicate-detection kicking in when you just want to see a fresh file go
    through; keep the same id to deliberately test duplicate detection instead."""
    if explicit_patient_id:
        # simple text substitution: the fixture references the same patient id in many
        # nested "patient": {"id": "..."} spots, a plain string replace keeps them consistent
        # without having to walk the whole nested structure by hand
        randomized_raw = FIXTURE_RAW.replace(ORIGINAL_PATIENT_ID, explicit_patient_id)
        return json.loads(randomized_raw), explicit_patient_id

    if not randomize_patient_id:
        return json.loads(FIXTURE_RAW), ORIGINAL_PATIENT_ID

    new_id = str(uuid.uuid4())
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
    body = request.json if request.is_json else {}
    randomize = body.get("randomize_patient_id", True)
    explicit_patient_id = (body.get("patient_id") or "").strip() or None
    has_consent = bool(body.get("has_consent", False))

    payload, patient_id = _build_payload(randomize, explicit_patient_id)

    with state_lock:
        already_consented = patient_id in state["consented_patient_ids"]
        if has_consent and not already_consented:
            state["consented_patient_ids"].append(patient_id)
        elif not has_consent and already_consented:
            state["consented_patient_ids"].remove(patient_id)

    url = f"{ETL_BASE_URL}/mtb"
    log.info(
        "Sende MTB-Datei fuer Patient %s an %s (Mock-Consent: %s)", patient_id, url, has_consent
    )

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
            "has_consent": has_consent,
            "time": _now(),
        }
    except requests.RequestException as e:
        log.error("Senden an ETL fehlgeschlagen: %s", e)
        result = {
            "ok": False,
            "error": str(e),
            "patient_id": patient_id,
            "has_consent": has_consent,
            "time": _now(),
        }

    with state_lock:
        state["last_sent"] = result
        _record_history(
            "sent",
            f"Patient {patient_id[:8]}... (Consent: {'ja' if has_consent else 'nein'}) "
            f"-> HTTP {result.get('status_code', 'ERR')}",
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


def _build_permit_consent_bundle(patient_id: str) -> dict:
    """Minimal-but-valid MII Broad Consent Bundle: one active Consent resource with a single
    'permit' provision matching CONSENT_POLICY_CODE/-SYSTEM, which is all ConsentProcessor's
    getProvisionTypeByPolicyCode() actually looks for. Period is anchored to "now" so it never
    expires during local testing."""
    now = datetime.now(timezone.utc)
    start = (now - timedelta(days=1)).strftime("%Y-%m-%dT%H:%M:%S+00:00")
    end = (now + timedelta(days=3650)).strftime("%Y-%m-%dT%H:%M:%S+00:00")
    return {
        "resourceType": "Bundle",
        "type": "searchset",
        "total": 1,
        "entry": [
            {
                "resource": {
                    "resourceType": "Consent",
                    "id": f"mock-consent-{patient_id}",
                    "status": "active",
                    "scope": {
                        "coding": [
                            {
                                "system": "http://terminology.hl7.org/CodeSystem/consentscope",
                                "code": "research",
                            }
                        ]
                    },
                    "patient": {"display": f"Patienten-ID {patient_id}"},
                    "dateTime": now.strftime("%Y-%m-%dT%H:%M:%S+00:00"),
                    "policy": [{"uri": "urn:oid:2.16.840.1.113883.3.1937.777.24.2.1790"}],
                    "provision": {
                        "type": "deny",
                        "period": {"start": start, "end": end},
                        "provision": [
                            {
                                "type": "permit",
                                "period": {"start": start, "end": end},
                                "code": [
                                    {
                                        "coding": [
                                            {
                                                "system": CONSENT_POLICY_SYSTEM,
                                                "code": CONSENT_POLICY_CODE,
                                                "display": "Mock: MDAT erheben",
                                            }
                                        ]
                                    }
                                ],
                            }
                        ],
                    },
                }
            }
        ],
    }


def _build_empty_consent_bundle() -> dict:
    return {"resourceType": "Bundle", "type": "searchset", "total": 0, "entry": []}


@app.route("/Consent", methods=["GET"])
def consent_search():
    """Mocks DIZ's Consent endpoint that KeycloakDizConsentService queries - verified against
    the real system to be a plain "GET [uri]<patientId>" with the id appended directly (not a
    FHIR search with separate query params, which was the original, unverified assumption - see
    temp.txt/LMU-README.md). APP_CONSENT_DIZ_URI must therefore end in "/Consent?patient=" so
    the id lands in the "patient" query param read here. Returns a permit Bundle for patient ids
    marked via /send's "has_consent" checkbox, an empty Bundle (no entries -> "not asked") for
    everything else."""
    patient_id = request.args.get("patient") or None
    had_auth_header = request.headers.get("Authorization", "").lower().startswith("bearer ")

    with state_lock:
        has_consent = bool(patient_id) and patient_id in state["consented_patient_ids"]
        check = {
            "time": _now(),
            "patient_id": patient_id,
            "had_auth_header": had_auth_header,
            "consent_found": has_consent,
            "query": dict(request.args),
        }
        state["last_consent_check"] = check
        _record_history(
            "consent",
            f"Consent-Abfrage Patient {patient_id}: {'gefunden' if has_consent else 'kein Consent'}",
        )

    bundle = _build_permit_consent_bundle(patient_id) if has_consent else _build_empty_consent_bundle()
    log.info(
        "DIZ-Consent-Abfrage (Mock) fuer Patient %s -> %s",
        patient_id,
        "PERMIT" if has_consent else "leer",
    )
    return jsonify(bundle), 200


@app.route("/mock-keycloak/token", methods=["POST"])
def keycloak_token():
    """Mocks the Keycloak client-credentials token endpoint KeycloakTokenProvider calls before
    every DIZ request. Accepts any client id/secret and hands back a fixed, non-expiring-enough
    fake token - nothing here validates it, KeycloakDizConsentService just needs any
    'Authorization: Bearer ...' header to attach."""
    with state_lock:
        state["last_token_request"] = {"time": _now(), "form": dict(request.form)}
        _record_history("token", "Keycloak-Token angefragt (Mock)")
    return jsonify({"access_token": "mock-access-token", "token_type": "bearer", "expires_in": 300}), 200


@app.route("/consent/clear", methods=["POST"])
def clear_consented_patient_ids():
    with state_lock:
        state["consented_patient_ids"] = []
        _record_history("consent", "Consent-Liste geleert")
    return jsonify({"ok": True, "consented_patient_ids": []})


@app.route("/state")
def get_state():
    with state_lock:
        return jsonify(copy.deepcopy(state))


if __name__ == "__main__":
    port = int(os.environ.get("PORT", "5000"))
    log.info("Testservice startet auf Port %d, ETL_BASE_URL=%s", port, ETL_BASE_URL)
    app.run(host="0.0.0.0", port=port, debug=True)
