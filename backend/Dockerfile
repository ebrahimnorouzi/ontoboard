FROM python:3.12-slim

# Install Java (required by ROBOT) and curl for downloading
RUN apt-get update && \
    apt-get install -y --no-install-recommends default-jre-headless curl git make && \
    rm -rf /var/lib/apt/lists/*

# Install ROBOT tool (OWL ontology automation)
ENV ROBOT_VERSION=1.9.6
RUN curl -sSL -o /usr/local/bin/robot.jar \
    "https://github.com/ontodev/robot/releases/download/v${ROBOT_VERSION}/robot.jar" && \
    printf '#!/bin/sh\njava -jar /usr/local/bin/robot.jar "$@"\n' > /usr/local/bin/robot && \
    chmod +x /usr/local/bin/robot

# Install Widoco (ontology HTML documentation generator)
ENV WIDOCO_VERSION=1.4.25
RUN curl -sSL -o /usr/local/bin/widoco.jar \
    "https://github.com/dgarijo/Widoco/releases/download/v${WIDOCO_VERSION}/widoco-${WIDOCO_VERSION}-jar-with-dependencies_JDK-17.jar"

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--reload"]
