# mock_mtbfile_receiver.py
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from datetime import datetime
import json
import uvicorn
import os

app = FastAPI()
SAVE_DIR = "received_files"
os.makedirs(SAVE_DIR, exist_ok=True)


@app.post(
    "/receive/mtb/etl/patient-record:validate",
    response_class=JSONResponse,
)
async def receive_file(request: Request):
    # Inhalt als Text einlesen, falls JSON-Parser scheitert
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

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8092)
