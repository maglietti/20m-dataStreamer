#!/bin/bash
#
# Initialize GridGain cluster with license
#
# Usage: ./cluster-init.sh [-v] [license-file]
#
# Options:
#   -v    Verbose mode - show API responses for debugging
#
# The license file defaults to my-gridgain-9.1.license.conf
# License files should NOT be committed to git.
#

set -e

# Parse arguments
VERBOSE=false
LICENSE_FILE="my-gridgain-9.1.license.conf"

while [[ $# -gt 0 ]]; do
    case "$1" in
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        *)
            LICENSE_FILE="$1"
            shift
            ;;
    esac
done

CLUSTER_NAME="datastreamer-test"
ENDPOINT="http://localhost:10300"
MAX_RETRIES=10
STARTUP_RETRIES=5

# Fibonacci backoff: returns the nth Fibonacci number (capped at 21 seconds)
fib() {
    local n=$1
    local a=1 b=1 temp
    for ((i=2; i<=n; i++)); do
        temp=$((a + b))
        a=$b
        b=$temp
    done
    # Cap at 21 seconds
    echo $((b > 21 ? 21 : b))
}

# Check if node is reachable (responding to API calls)
# Returns 0 if node responds with STARTING or STARTED, 1 otherwise
# Sets NODE_STATE variable with current state for display
is_node_reachable() {
    local response
    response=$(http --ignore-stdin --timeout=2 -b GET "${ENDPOINT}/management/v1/node/state" 2>&1) || true

    if [[ "$VERBOSE" == "true" ]]; then
        echo "    [DEBUG] GET /management/v1/node/state"
        echo "    [DEBUG] Response: $response"
    fi

    # Extract current state for display
    NODE_STATE=$(echo "$response" | jq -r '.state // "UNREACHABLE"' 2>/dev/null) || NODE_STATE="UNREACHABLE"

    # Node is reachable if it returns STARTING or STARTED
    # Note: STARTING is expected before cluster initialization
    if [[ "$NODE_STATE" == "STARTING" || "$NODE_STATE" == "STARTED" ]]; then
        return 0
    fi
    return 1
}

# Check if node has completed initialization (state is STARTED)
# Returns 0 if node state is STARTED, 1 otherwise
is_node_started() {
    local response
    response=$(http --ignore-stdin --timeout=2 -b GET "${ENDPOINT}/management/v1/node/state" 2>&1) || true

    if [[ "$VERBOSE" == "true" ]]; then
        echo "    [DEBUG] GET /management/v1/node/state"
        echo "    [DEBUG] Response: $response"
    fi

    # Extract current state for display
    NODE_STATE=$(echo "$response" | jq -r '.state // "UNREACHABLE"' 2>/dev/null) || NODE_STATE="UNREACHABLE"

    if [[ "$NODE_STATE" == "STARTED" ]]; then
        return 0
    fi
    return 1
}

# Check if license file exists
if [ ! -f "$LICENSE_FILE" ]; then
    echo "!!! Error: License file not found: $LICENSE_FILE"
    echo ""
    echo "    To initialize the cluster, you need a valid GridGain license file."
    echo "    Copy your license to: $LICENSE_FILE"
    echo "    See license.example.conf for the expected format."
    exit 1
fi

# Check if httpie is installed
if ! command -v http &> /dev/null; then
    echo "!!! Error: httpie (http) is not installed."
    echo "    Install with: brew install httpie"
    exit 1
fi

# Check if jq is installed
if ! command -v jq &> /dev/null; then
    echo "!!! Error: jq is not installed."
    echo "    Install with: brew install jq"
    exit 1
fi

# Read and minify the license JSON (remove whitespace)
LICENSE_JSON=$(cat "$LICENSE_FILE" | tr -d '\n' | tr -s ' ' | sed 's/: /:/g' | sed 's/, /,/g')

# Escape the JSON for embedding in the request body
LICENSE_ESCAPED=$(echo "$LICENSE_JSON" | sed 's/"/\\"/g')

# Extract license info for display
LICENSE_EDITION=$(echo "$LICENSE_JSON" | jq -r '.edition // "unknown"')
LICENSE_MAX_CORES=$(echo "$LICENSE_JSON" | jq -r '.limits.maxCores // "unlimited"')
LICENSE_MAX_NODES=$(echo "$LICENSE_JSON" | jq -r '.limits.maxNodes // "unlimited"')
LICENSE_EXPIRE=$(echo "$LICENSE_JSON" | jq -r '.limits.expireDate // "unknown"')
LICENSE_COMPANY=$(echo "$LICENSE_JSON" | jq -r '.infos.companyName // "unknown"')

echo "=== [1/4] Cluster Configuration ==="
echo "    Cluster Name: $CLUSTER_NAME"
echo "    Endpoint:     $ENDPOINT"
echo ""
echo "--- License Details"
echo "    Edition:      $LICENSE_EDITION"
echo "    Company:      $LICENSE_COMPANY"
echo "    Max Cores:    $LICENSE_MAX_CORES"
echo "    Max Nodes:    $([[ "$LICENSE_MAX_NODES" == "0" ]] && echo "unlimited" || echo "$LICENSE_MAX_NODES")"
echo "    Expires:      $LICENSE_EXPIRE"
echo ""

echo "=== [2/4] Starting Cluster ==="
echo ">>> Checking if cluster is running"

# Check if node is already reachable (responds with STARTING or STARTED)
if is_node_reachable; then
    echo "<<< Node is reachable (state: $NODE_STATE)"
else
    echo "    Cluster not running, starting with docker compose..."
    echo ""

    # Start the cluster
    if ! docker compose up -d; then
        echo "!!! Failed to start cluster with docker compose"
        exit 1
    fi

    echo ""
    echo ">>> Waiting for nodes to become reachable"

    # Wait for at least one node to respond
    RETRY_COUNT=0
    NODE_UP=false

    while [[ $RETRY_COUNT -lt $STARTUP_RETRIES ]]; do
        # Check if node responds (STARTING or STARTED state)
        if is_node_reachable; then
            NODE_UP=true
            break
        fi

        RETRY_COUNT=$((RETRY_COUNT + 1))
        DELAY=$(fib $RETRY_COUNT)
        echo "    Attempt $RETRY_COUNT/$STARTUP_RETRIES: Node state is $NODE_STATE (retry in ${DELAY}s)"
        sleep $DELAY
    done

    if [[ "$NODE_UP" != "true" ]]; then
        echo "!!! Node not reachable after $STARTUP_RETRIES attempts (last state: $NODE_STATE)"
        echo "    Check container logs: docker compose logs"
        exit 1
    fi

    echo "<<< Node is reachable (state: $NODE_STATE)"
fi

echo ""

echo "=== [3/4] Initializing Cluster ==="
echo ">>> Sending initialization request"

# Initialize the cluster and capture response
INIT_RESPONSE=$(http --ignore-stdin -b POST "${ENDPOINT}/management/v1/cluster/init" \
  Accept:'application/problem+json' \
  Content-Type:'application/json' \
  --raw="{
  \"metaStorageNodes\": [],
  \"cmgNodes\": [],
  \"clusterName\": \"${CLUSTER_NAME}\",
  \"license\": \"${LICENSE_ESCAPED}\"
}" 2>&1) || true

INIT_STATUS=$?

# Check if response contains an error
if echo "$INIT_RESPONSE" | jq -e '.status' &>/dev/null; then
    ERROR_STATUS=$(echo "$INIT_RESPONSE" | jq -r '.status')
    ERROR_TITLE=$(echo "$INIT_RESPONSE" | jq -r '.title // "Unknown error"')
    ERROR_DETAIL=$(echo "$INIT_RESPONSE" | jq -r '.detail // "No details available"')

    if [[ "$ERROR_STATUS" == "409" && "$ERROR_TITLE" == *"already initialized"* ]]; then
        echo "<<< Cluster already initialized"
    else
        echo "!!! Initialization failed"
        echo "    Status: $ERROR_STATUS"
        echo "    Error:  $ERROR_TITLE"
        echo "    Detail: $ERROR_DETAIL"
        exit 1
    fi
elif [[ -z "$INIT_RESPONSE" ]]; then
    echo "<<< Initialization request accepted"
else
    echo "<<< Response: $INIT_RESPONSE"
fi

echo ""
echo "=== [4/4] Verifying Cluster State ==="
echo ">>> Waiting for cluster to become ready"

# Poll for cluster state with Fibonacci backoff
RETRY_COUNT=0
CLUSTER_READY=false

while [[ $RETRY_COUNT -lt $MAX_RETRIES ]]; do
    STATE_RESPONSE=$(http --ignore-stdin -b GET "${ENDPOINT}/management/v1/cluster/state" \
      Accept:'application/json' 2>&1) || true

    # Check if we got a valid state response (has cmgNodes or msNodes)
    if echo "$STATE_RESPONSE" | jq -e '.cmgNodes' &>/dev/null; then
        CLUSTER_READY=true
        break
    fi

    # Check for temporary unavailable (409)
    if echo "$STATE_RESPONSE" | jq -e '.status == 409' &>/dev/null; then
        RETRY_COUNT=$((RETRY_COUNT + 1))
        DELAY=$(fib $RETRY_COUNT)
        echo "    Attempt $RETRY_COUNT/$MAX_RETRIES: Cluster initializing... (retry in ${DELAY}s)"
        sleep $DELAY
        continue
    fi

    # Some other response, also retry with backoff
    RETRY_COUNT=$((RETRY_COUNT + 1))
    DELAY=$(fib $RETRY_COUNT)
    echo "    Attempt $RETRY_COUNT/$MAX_RETRIES: Waiting... (retry in ${DELAY}s)"
    sleep $DELAY
done

if [[ "$CLUSTER_READY" == "true" ]]; then
    echo "<<< Cluster is ready"
    echo ""

    # Parse and display cluster state
    CMG_NODES_COUNT=$(echo "$STATE_RESPONSE" | jq -r '.cmgNodes // [] | length')
    MS_NODES_COUNT=$(echo "$STATE_RESPONSE" | jq -r '.msNodes // [] | length')
    IGNITE_VERSION=$(echo "$STATE_RESPONSE" | jq -r '.igniteVersion // "unknown"')
    CLUSTER_TAG=$(echo "$STATE_RESPONSE" | jq -r '.clusterTag.clusterName // "unknown"')
    CLUSTER_ID=$(echo "$STATE_RESPONSE" | jq -r '.clusterTag.clusterId // "unknown"')

    echo "--- Cluster Status"
    echo "    Name:         $CLUSTER_TAG"
    echo "    ID:           $CLUSTER_ID"
    echo "    Version:      $IGNITE_VERSION"
    echo ""

    echo "--- Node Topology"
    echo "    Cluster Management:  $CMG_NODES_COUNT nodes"
    echo "$STATE_RESPONSE" | jq -r '.cmgNodes[]? // empty' | while read -r node; do
        echo "        - $node"
    done

    echo "    Metastorage:         $MS_NODES_COUNT nodes"
    echo "$STATE_RESPONSE" | jq -r '.msNodes[]? // empty' | while read -r node; do
        echo "        - $node"
    done

    echo ""
    echo "<<< Cluster initialization complete"
else
    echo "!!! Cluster did not become ready after $MAX_RETRIES attempts"
    echo "    Last response: $STATE_RESPONSE"
    exit 1
fi
