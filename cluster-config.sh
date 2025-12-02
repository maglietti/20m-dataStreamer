#!/bin/bash
#
# Get GridGain cluster configuration
#

ENDPOINT="${1:-http://localhost:10300}"

echo "=== Cluster Configuration ==="
echo "    Endpoint: $ENDPOINT"
echo ""

http -v GET "${ENDPOINT}/management/v1/configuration/cluster" \
  Accept:'application/problem+json'
