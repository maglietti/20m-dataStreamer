#!/bin/bash
#
# Get GridGain cluster state
#

ENDPOINT="${1:-http://localhost:10300}"

echo "=== Cluster State ==="
echo "    Endpoint: $ENDPOINT"
echo ""

http -v GET "${ENDPOINT}/management/v1/cluster/state" \
  Accept:'application/json'
