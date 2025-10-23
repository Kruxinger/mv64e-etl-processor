# Dockerfile
FROM python:3.11-slim

# Arbeitsverzeichnis
WORKDIR /app

# Abhängigkeiten kopieren
COPY requirements.txt .

# Installiere Pakete
RUN pip install --no-cache-dir -r requirements.txt

# Code kopieren
COPY mock_services_combined.py .

# Port expose
EXPOSE 8090

# Startkommando
CMD ["python", "mock_services_combined.py"]
