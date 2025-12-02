#!/bin/bash
#
# Fetches logs from GridGain 9 Docker containers and saves them to local files.
# GridGain 9 writes logs to stdout, so we capture them via docker compose logs.
#
# Usage: ./scripts/fetch-node-logs.sh [--tail N] [--since TIME]
#
# Options:
#   --tail N      Only fetch the last N lines (default: all)
#   --since TIME  Fetch logs since timestamp (e.g., "2024-01-01T00:00:00", "1h", "30m")
#
# Output: logs/node{1-5}/docker-stdout.log
#

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
LOGS_DIR="$PROJECT_DIR/logs"

# Parse arguments
TAIL_ARG=""
SINCE_ARG=""

while [[ $# -gt 0 ]]; do
    case $1 in
        --tail)
            TAIL_ARG="--tail $2"
            shift 2
            ;;
        --since)
            SINCE_ARG="--since $2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

echo "=== Fetching GridGain Node Logs ==="
echo "    Project: $PROJECT_DIR"
echo "    Output:  $LOGS_DIR/node{1-5}/"

# Ensure log directories exist
for i in 1 2 3 4 5; do
    mkdir -p "$LOGS_DIR/node$i"
done

# Check if containers are running
cd "$PROJECT_DIR"
RUNNING=$(docker compose ps --status running --format "{{.Name}}" 2>/dev/null | wc -l)

if [ "$RUNNING" -eq 0 ]; then
    echo "!!! No running containers found. Fetching logs from stopped containers..."
fi

# Fetch logs for each node
for i in 1 2 3 4 5; do
    NODE="node$i"
    LOG_FILE="$LOGS_DIR/$NODE/docker-stdout.log"

    echo ">>> Fetching logs for $NODE"

    # Build docker compose logs command
    CMD="docker compose logs $TAIL_ARG $SINCE_ARG --no-color $NODE"

    if $CMD > "$LOG_FILE" 2>&1; then
        LINE_COUNT=$(wc -l < "$LOG_FILE" | tr -d ' ')
        SIZE=$(du -h "$LOG_FILE" | cut -f1)
        echo "<<< $NODE: $LINE_COUNT lines ($SIZE) -> $LOG_FILE"
    else
        echo "!!! Failed to fetch logs for $NODE"
    fi
done

echo ""
echo "=== Log Fetch Complete ==="
echo "    Logs saved to: $LOGS_DIR/node{1-5}/docker-stdout.log"
echo ""
echo "    View logs:"
echo "      cat $LOGS_DIR/node1/docker-stdout.log"
echo "      tail -f $LOGS_DIR/node1/docker-stdout.log"
