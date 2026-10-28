# Imagen del generador de tráfico legítimo (Python). Contexto de build: portal/
FROM python:3.12-slim
RUN useradd -r -u 1000 app && mkdir -p /app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY portal.py firmar.py ./
USER 1000
CMD ["python", "-u", "portal.py"]
