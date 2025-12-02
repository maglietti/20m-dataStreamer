#!/bin/bash
#
# Initialize GridGain cluster with license
#
# Usage: ./cluster-init.sh [license-file]
#
# The license file defaults to my-gridgain-9.1.license.conf
# License files should NOT be committed to git.
#

set -e

LICENSE_FILE="${1:-my-gridgain-9.1.license.conf}"
CLUSTER_NAME="datastreamer-test"
ENDPOINT="http://localhost:10300"

# Check if license file exists
if [ ! -f "$LICENSE_FILE" ]; then
    echo "Error: License file not found: $LICENSE_FILE"
    echo ""
    echo "To initialize the cluster, you need a valid GridGain license file."
    echo "Copy your license to: $LICENSE_FILE"
    echo "See license.example.conf for the expected format."
    exit 1
fi

# Check if httpie is installed
if ! command -v http &> /dev/null; then
    echo "Error: httpie (http) is not installed."
    echo "Install with: brew install httpie"
    exit 1
fi

# Read and minify the license JSON (remove whitespace)
LICENSE_JSON=$(cat "$LICENSE_FILE" | tr -d '\n' | tr -s ' ' | sed 's/: /:/g' | sed 's/, /,/g')

# Escape the JSON for embedding in the request body
LICENSE_ESCAPED=$(echo "$LICENSE_JSON" | sed 's/"/\\"/g')

echo "=== Initializing GridGain Cluster ==="
echo "    Cluster Name: $CLUSTER_NAME"
echo "    Endpoint:     $ENDPOINT"
echo "    License File: $LICENSE_FILE"
echo ""

# Initialize the cluster
http -v POST "${ENDPOINT}/management/v1/cluster/init" \
  Accept:'application/problem+json' \
  Content-Type:'application/json' \
  --raw="{
  \"metaStorageNodes\": [],
  \"cmgNodes\": [],
  \"clusterName\": \"${CLUSTER_NAME}\",
  \"license\": \"${LICENSE_ESCAPED}\"
}"

echo ""
echo "=== Checking Cluster State ==="

# Check cluster state
http -v GET "${ENDPOINT}/management/v1/cluster/state" \
  Accept:'application/json'
