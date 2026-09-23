FROM python:3.12-slim

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY sidecar.py .

# Reachable from the bot container on the compose network.
ENV OBSERVER_SIDECAR_HOST=0.0.0.0
EXPOSE 8787

CMD ["python", "sidecar.py"]
