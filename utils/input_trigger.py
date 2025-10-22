from http.client import responses

import requests
from requests import Session
import json

url = 'http://localhost:8080/mtb'

def get_session():
    session = Session()
    session.verify = False
    return session


with open("valid_input.json", "r", encoding="utf-8") as f:
    payload = json.load(f)

session = get_session()

response = session.get(url)
print(response.status_code)
print(response.text)

response = session.post(url, json=payload)
print(response.status_code)
print(response.text[:500])