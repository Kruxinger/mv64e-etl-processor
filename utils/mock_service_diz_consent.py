# mock_consent_service.py
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

app = FastAPI()

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
