# mock_services_combined.py
from fastapi import FastAPI, Request, Form
from fastapi.responses import JSONResponse, Response
from datetime import datetime
import json
import random
import string
import os
import uvicorn

app = FastAPI(title="Combined Mock Services")

# =========================================
# 1️⃣ Consent Service
# =========================================
@app.get("/fhir/Consent")
async def get_consent(request: Request):
    patient_id = request.query_params.get("patient", "0000000000")
    bundle = {
        "resourceType": "Bundle",
        "id": "MOCKBUNDLE123",
        "type": "searchset",
        "total": 1,
        "link": [
            {"relation": "self", "url": f"http://localhost:8090/fhir/Consent?patient={patient_id}"}
        ],
        "entry": [
            {
                "fullUrl": f"http://localhost:8090/fhir/Consent/Consent-{patient_id}",
                "resource": {
                    "resourceType": "Consent",
                    "id": f"Consent-{patient_id}",
                    "meta": {
                        "versionId": "1",
                        "lastUpdated": "2025-01-01T00:00:00Z",
                        "profile": [
                            "https://www.medizininformatik-initiative.de/fhir/modul-consent/StructureDefinition/mii-pr-consent-einrichtung"
                        ]
                    },
                    "status": "active",
                    "scope": {
                        "coding": [
                            {"system": "http://terminology.hl7.org/CodeSystem/consentscope", "code": "research"}
                        ]
                    },
                    "category": [
                        {"coding": [{"system": "http://loinc.org", "code": "57016-8"}]},
                        {"coding": [{"system": "https://www.medizininformatik-initiative.de/fhir/modul-consent/CodeSystem/mii-cs-consent-category", "code": "MOCK-CATEGORY"}]}
                    ],
                    "patient": {"reference": f"Patient/{patient_id}"},
                    "dateTime": "2025-03-28T00:00:00Z",
                    "organization": [{"display": "MOCKORG"}],
                    "policy": [{"uri": "urn:oid:MOCK-POLICY"}],
                    "provision": {
                        "type": "permit",
                        "period": {"start": "2025-03-28T00:00:00Z", "end": "2030-03-27T00:00:00Z"},
                        "provision": [
                            {
                                "type": "permit",
                                "code": [{
                                    "coding": [{
                                        "system": "urn:oid:MOCK-SYSTEM",
                                        "code": "CONSENT_001",
                                        "display": "MOCK permission example"
                                    }]
                                }]
                            }
                        ]
                    }
                },
                "search": {"mode": "match"}
            }
        ]
    }
    return JSONResponse(content=bundle)


# =========================================
# 2️⃣ GPAS SOAP Service
# =========================================
def random_string(length=20):
    return ''.join(random.choices(string.ascii_uppercase + string.digits, k=length))

@app.post("/gpas/gpasService")
async def gpas_service(request: Request):
    xml = await request.body()
    xml = xml.decode("utf-8")
    print(xml)

    def soap_response(value: str):
        return f"""<?xml version="1.0" encoding="UTF-8"?>
<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
                  xmlns:psn="http://psn.ttp.ganimed.icmvc.emau.org/">
   <soapenv:Header/>
   <soapenv:Body>
      <psn>{value}</psn>
   </soapenv:Body>
</soapenv:Envelope>"""

    if "getOrCreatePseudonymFor" in xml or "createPseudonymFor" in xml:
        value = random_string()
        print(f"value: {value}")
        return Response(soap_response(value), media_type="text/xml")

    return Response("Invalid request", status_code=400)


# =========================================
# 3️⃣ Keycloak Token Service
# =========================================
@app.post("/auth/realms/{realm}/protocol/openid-connect/token")
async def token(
    realm: str,
    client_id: str = Form(...),
    client_secret: str = Form(...),
    username: str = Form(...),
    password: str = Form(...),
    grant_type: str = Form("password")
):
    token_value = f"mock-token-for-{realm}-{username}"
    return JSONResponse({
        "access_token": token_value,
        "expires_in": 300,
        "token_type": "Bearer"
    })


# =========================================
# 4️⃣ MTB File Receiver
# =========================================
SAVE_DIR = "received_files"
os.makedirs(SAVE_DIR, exist_ok=True)

@app.post("/receive/mtb/etl/patient-record:validate")
async def receive_file(request: Request):
    try:
        data = await request.json()
    except Exception:
        body = await request.body()
        data = {"raw_body": body.decode("utf-8")}

    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"{SAVE_DIR}/received_{timestamp}.json"

    with open(filename, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    print(f"✅ File received and saved as {filename}")
    return {"status": "SUCCESS", "message": f"Received {len(json.dumps(data))} bytes"}


# =========================================
# Start
# =========================================
def main():
    uvicorn.run("mock_services_combined:app", host="0.0.0.0", port=8090, reload=False)


if __name__ == "__main__":
    main()

# =========================================
# Verfügbare Endpoints:
# =========================================
# 1️⃣ Consent Service
#    GET  /fhir/Consent?patient={patient_id}
#
# 2️⃣ GPAS SOAP Service
#    POST /gpas/gpasService
#        - erwartet XML im Body
#        - reagiert auf "getOrCreatePseudonymFor" und "createPseudonymFor"
#
# 3️⃣ Keycloak Token Service
#    POST /auth/realms/{realm}/protocol/openid-connect/token
#        - Form-Parameter: client_id, client_secret, username, password, grant_type
#
# 4️⃣ MTB File Receiver
#    POST /receive/mtb/etl/patient-record:validate
#        - JSON oder Rohtext im Body wird gespeichert
#        - Dateien werden in 'received_files/' abgelegt

