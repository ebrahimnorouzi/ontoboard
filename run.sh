#!/usr/bin/env bash
# OntoBoard — build and run everything with one command.
#
# Usage:
#   ./run.sh              Build + start all services
#   ./run.sh build        Build images only
#   ./run.sh up           Start services (no build)
#   ./run.sh down         Stop services
#   ./run.sh test         Run backend tests
#   ./run.sh logs         Show service logs
#   ./run.sh clean        Stop + remove data

set -e
cd "$(dirname "$0")"

case "${1:-start}" in
  build)
    echo "=== Building all images ==="
    docker build --network host -t ontoboard-backend  ./backend
    docker build --network host -t ontoboard-worker   ./worker
    docker build --network host -t ontoboard-frontend ./frontend
    docker build --network host -t ontoboard-collab   ./collab
    echo "=== Build complete ==="
    ;;
  up)
    docker compose up -d
    echo "Services started:"
    echo "  Frontend: http://localhost:3000"
    echo "  Backend:  http://localhost:8000"
    echo "  API Docs: http://localhost:8000/docs"
    ;;
  down)
    docker compose down
    ;;
  test)
    cd backend && python -m pytest tests/ -v --tb=short
    ;;
  logs)
    docker compose logs -f --tail=50
    ;;
  clean)
    docker compose down
    rm -rf data
    echo "Cleaned all data."
    ;;
  start|"")
    echo "=== OntoBoard — Building and starting ==="
    docker build --network host -t ontoboard-backend  ./backend
    docker build --network host -t ontoboard-worker   ./worker
    docker build --network host -t ontoboard-frontend ./frontend
    docker build --network host -t ontoboard-collab   ./collab
    docker compose up -d
    echo ""
    echo "=== OntoBoard is running ==="
    echo "  Frontend: http://localhost:3000"
    echo "  Backend:  http://localhost:8000"
    echo "  API Docs: http://localhost:8000/docs"
    echo "  Admin:    admin / admin"
    ;;
  *)
    echo "Usage: ./run.sh [build|up|down|test|logs|clean]"
    ;;
esac
