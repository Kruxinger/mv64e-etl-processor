# mock_keycloak_service.py
from fastapi import FastAPI, Form
from fastapi.responses import JSONResponse
import uvicorn

app = FastAPI()

@app.post("/auth/realms/{realm}/protocol/openid-connect/token")
async def token(realm: str,
                client_id: str = Form(...),
                client_secret: str = Form(...),
                username: str = Form(...),
                password: str = Form(...),
                grant_type: str = Form("password")):
    """Simulierter Token-Endpunkt für Keycloak"""
    token = f"mock-token-for-{realm}-{username}"
    return JSONResponse({
        "access_token": token,
        "expires_in": 300,
        "token_type": "Bearer"
    })


def main():
    """Starte den Mock-Keycloak-Service auf Port 8090"""
    uvicorn.run("mock_service_keycloak:app", host="0.0.0.0", port=8090, reload=False)


if __name__ == "__main__":
    main()
