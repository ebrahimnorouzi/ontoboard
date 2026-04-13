#!/usr/bin/env bash
# OntoBoard — build and run everything with one command.
#
# Usage:
#   ./run.sh              Start services (build only if images missing)
#   ./run.sh build        Force rebuild all images
#   ./run.sh up           Start services (no build)
#   ./run.sh down         Stop services
#   ./run.sh dev          Development mode (source mounted for hot-reload)
#   ./run.sh test         Run backend tests
#   ./run.sh logs         Show service logs
#   ./run.sh clean        Stop + remove data

set -e
cd "$(dirname "$0")"


# Check if an image exists
image_exists() {
    docker image inspect "$1" >/dev/null 2>&1
}

# Build a single image, retry once on failure
build_one() {
    local name="$1" dir="$2"
    echo "  Building $name from $dir..."
    if docker build --network host -t "$name" "$dir" 2>&1; then
        echo "  $name: OK"
    else
        echo "  $name: first attempt failed, retrying..."
        sleep 3
        docker build --network host -t "$name" "$dir" 2>&1 || {
            echo "  WARNING: $name build failed. Using cached image if available."
            if image_exists "$name"; then
                echo "  (cached image found — continuing)"
            else
                echo "  ERROR: No cached image for $name. Fix network and retry."
                exit 1
            fi
        }
    fi
}

# Build all images
build_all() {
    echo "=== Building images (--network host) ==="
    build_one ontoboard-backend  ./backend
    build_one ontoboard-frontend ./frontend
    build_one ontoboard-collab   ./collab
    build_one ontoboard-worker   ./worker
    echo "=== Build complete ==="
}

# Build only missing images
build_missing() {
    local need_build=false
    for img in ontoboard-backend ontoboard-frontend ontoboard-collab ontoboard-worker; do
        if ! image_exists "$img"; then
            need_build=true
            break
        fi
    done
    if [ "$need_build" = true ]; then
        echo "Some images missing — building..."
        build_all
    else
        echo "All images found. Use './run.sh build' to force rebuild."
    fi
}

start_services() {
    docker compose up -d
    echo ""
    echo "=== OntoBoard is running ==="
    echo "  Frontend: http://localhost:3000"
    echo "  Backend:  http://localhost:8000"
    echo "  API Docs: http://localhost:8000/docs"
    echo "  Admin:    admin / admin"
}

case "${1:-start}" in
  build)
    build_all
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
  dev)
    echo "=== Development mode (source mounted for hot-reload) ==="
    build_missing
    docker compose -f docker-compose.dev.yml up -d
    echo ""
    echo "  Frontend: http://localhost:3000 (hot-reload via Vite)"
    echo "  Backend:  http://localhost:8000 (auto-reload via uvicorn)"
    ;;
  test)
    cd backend && python3 -m pytest tests/ -v --tb=short
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
    echo "=== OntoBoard ==="
    build_missing
    start_services
    ;;
  *)
    echo "Usage: ./run.sh [build|up|down|dev|test|logs|clean]"
    ;;
esac
