#!/bin/bash
# Trigger Ignition resource scans (Ignition 8.3 API)
# Usage: ./script/scan.sh
#
# Environment variables:
#   GATEWAY_URL  - Gateway URL (default: http://localhost:8089)
#   API_KEY      - Ignition API token for authentication

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8089}"
API_KEY="${API_KEY:-cicd:sMwrq8GV0e16T6yzpY9_5iejY6wO_6ddHY__RKb-ksM}"

scan_gateway() {
    local name=$1
    local url=$2

    # Check if gateway is responding
    if ! curl -s -f "${url}/StatusPing" > /dev/null 2>&1; then
        echo "  x $name is not responding at $url"
        return 1
    fi

    echo "Scanning $name ($url)..."

    # Trigger config scan
    CONFIG_HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-Ignition-API-Token: $API_KEY" \
        -X POST "${url}/data/api/v1/scan/config")
    if [ "$CONFIG_HTTP_CODE" = "200" ]; then
        echo "  Config scan triggered"
    else
        echo "  Config scan failed (HTTP $CONFIG_HTTP_CODE)"
    fi

    # Trigger projects scan
    PROJECTS_HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
        -H "X-Ignition-API-Token: $API_KEY" \
        -X POST "${url}/data/api/v1/scan/projects")
    if [ "$PROJECTS_HTTP_CODE" = "200" ]; then
        echo "  Projects scan triggered"
    else
        echo "  Projects scan failed (HTTP $PROJECTS_HTTP_CODE)"
    fi

    echo ""
}

echo "Triggering Ignition resource scans..."
echo ""

scan_gateway "factry-historian" "$GATEWAY_URL"

echo "Done!"
