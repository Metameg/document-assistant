# syntax=docker/dockerfile:1
FROM eclipse-temurin:26-jdk-noble AS build
WORKDIR /build
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
COPY src/ src/
RUN ./mvnw -B -ntp -DskipTests package

FROM eclipse-temurin:26-jre-noble
WORKDIR /app
# Docling runs on CPU alongside Java; no CUDA runtime is needed.
RUN apt-get update && apt-get install -y --no-install-recommends python3 python3-venv libglib2.0-0 libgl1 libgomp1 \
    && rm -rf /var/lib/apt/lists/*
COPY requirements-docling.txt ./
RUN python3 -m venv /opt/venv \
    && /opt/venv/bin/pip install --no-cache-dir torch==2.14.0 torchvision==0.29.0 --index-url https://download.pytorch.org/whl/cpu \
    && /opt/venv/bin/pip install --no-cache-dir -r requirements-docling.txt \
    && /opt/venv/bin/pip check \
    && /opt/venv/bin/python -c "from docling.document_converter import DocumentConverter; from docling_core.transforms.chunker.hybrid_chunker import HybridChunker; import tiktoken, matplotlib"
ENV PATH="/opt/venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    MPLBACKEND=Agg \
    OMP_NUM_THREADS=2 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=40.0 -Djava.awt.headless=true"
COPY --from=build /build/target/document-assistant-0.0.1-SNAPSHOT.jar ./app.jar
COPY config/application-railway.properties ./config/application-railway.properties
COPY config/graph-ontology.yaml ./config/graph-ontology.yaml
COPY data/ ./data/
COPY scripts/ ./scripts/
COPY evaluation/ ./evaluation/
# Research evaluation records the exact answer instructions used by this build.
COPY src/main/java/com/example/documentassistant/rag/HybridAnswerService.java ./src/main/java/com/example/documentassistant/rag/HybridAnswerService.java
# Fail clearly if the Git deployment omitted the nine demo documents.
RUN /opt/venv/bin/python -c "from pathlib import Path; import json; raw=Path('data/raw/specsheets'); docs=list(Path('data/processed/specsheets').glob('*.json')); assert len(list(raw.glob('*.pdf')))==9, 'Bundle exactly nine PDFs in data/raw/specsheets'; assert len(docs)==9, 'Bundle nine processed JSON files in data/processed/specsheets'; names=[json.loads(p.read_text())['sourceFile'] for p in docs]; assert all(Path(n).name==n and (raw/n).is_file() for n in names), 'Processed sourceFile values must match bundled PDF filenames'"
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home app \
    && mkdir -p target evaluation/results /app/data/corpora /home/app \
    && chown -R app:app /app /home/app
ENV HOME=/home/app
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=file:/app/config/application-railway.properties"]
