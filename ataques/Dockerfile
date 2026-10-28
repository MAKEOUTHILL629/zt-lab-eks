# Imagen de los scripts de ataque (Python). Contexto de build: ataques/
# El pod comprometido la ejecuta con "sleep infinity" (definido en k8s/) y el operador
# lanza cada escenario con kubectl exec.
FROM python:3.12-slim
RUN useradd -r -u 1000 app && mkdir -p /trabajo && chown 1000 /trabajo && mkdir -p /app
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY escenarios.py alcance.py firmar.py ./
USER 1000
CMD ["sleep", "infinity"]
