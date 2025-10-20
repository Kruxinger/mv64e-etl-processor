from fastapi import FastAPI, Form
from fastapi.responses import JSONResponse
import secrets
import uvicorn

app = FastAPI(title="Mock Keycloak", version="1.0")

@app.post("/auth/realms/mv64/protocol/openid-connect/token")
@app.post("/auth/realms/broadconsent/protocol/openid-connect/token")
def get_token(
    client_id: str = Form(...),
    client_secret: str = Form(None),
    username: str = Form(None),
    password: str = Form(None),
    grant_type: str = Form(...)
):
    access_token = secrets.token_urlsafe(32)
    return JSONResponse({
        "access_token": access_token,
        "token_type": "Bearer",
        "expires_in": 3600,
        "scope": "openid"
    })

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8090)
