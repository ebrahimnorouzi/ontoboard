#!/usr/bin/env bash
# Build all OntoBoard images with --network host to bypass Docker Desktop proxy.
set -e

echo "Building backend..."
docker build --network host -t ontoboard-backend  ./backend

echo "Building worker..."
docker build --network host -t ontoboard-worker   ./worker

echo "Building frontend..."
docker build --network host -t ontoboard-frontend ./frontend

echo "Building collab..."
docker build --network host -t ontoboard-collab   ./collab

echo ""
echo "All images built. Run:  docker compose up"
