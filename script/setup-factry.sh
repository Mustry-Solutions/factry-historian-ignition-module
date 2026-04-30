#!/bin/bash

# Creates a collector in Factry Historian and saves the token.
#
# If the Factry API is not accessible (e.g. auth not available),
# falls back to asking the user to create the collector manually.

set -e
cd "$(dirname "$0")/.."

TOKEN_FILE="./script/.collector-token"
COLLECTOR_NAME="Ignition"
FACTRY_URL="http://localhost:8000"

# Try to create the collector via Factry API
# TODO: Update these endpoints once Factry API auth is confirmed.
#       The Swagger is at ${FACTRY_URL}/api/swagger/
#
# Expected flow:
#   1. POST /api/v1/auth/login → get session cookie
#   2. POST /api/v1/collectors → create collector
#   3. POST /api/v1/collectors/{uuid}/token → generate token
#
# For now, fall back to manual token entry.

echo "A Factry collector is needed for the Ignition module."
echo ""
echo "Please create a collector in the Factry UI:"
echo "  1. Open ${FACTRY_URL}"
echo "  2. Go to Collectors in the sidebar"
echo "  3. Click 'Create Collector'"
echo "  4. Select your time series database"
echo "  5. Name it: ${COLLECTOR_NAME}"
echo "  6. Click 'Generate Token' and copy the token"
echo ""
read -p "  Paste the collector token here: " TOKEN

if [ -z "$TOKEN" ]; then
    echo "ERROR: No token provided."
    exit 1
fi

# Validate it looks like a JWT
if ! echo "$TOKEN" | grep -qE '^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$'; then
    echo "WARNING: Token doesn't look like a valid JWT. Proceeding anyway."
fi

# Save token for other scripts
echo -n "$TOKEN" > "$TOKEN_FILE"
echo "Token saved."
