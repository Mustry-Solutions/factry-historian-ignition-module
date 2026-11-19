#!/bin/bash

# Creates a collector in Factry Historian via REST API and saves the token.
#
# Requires: Factry setup wizard completed, valid user credentials.
# The token is saved to script/.collector-token for use by setup-historians.sh.
# Checked against Factry Historian v8.2.3.
#
# Environment overrides: FACTRY_URL, COLLECTOR_NAME, FACTRY_ORG (organization name or UUID;
# default: the first one that is not the root organization).

set -e
cd "$(dirname "$0")/.."

TOKEN_FILE="./script/.collector-token"
COOKIE_FILE="/tmp/factry-setup-cookies.txt"
COLLECTOR_NAME="${COLLECTOR_NAME:-Ignition}"
FACTRY_URL="${FACTRY_URL:-http://localhost:8000}"

# ── Factry credentials ────────────────────────────────────────────
echo "Factry Historian login required."
read -p "  Username: " FACTRY_USER
read -s -p "  Password: " FACTRY_PASS
echo ""

# ── Login ─────────────────────────────────────────────────────────
HTTP_CODE=$(curl -s -o /tmp/factry-login-resp.txt -w "%{http_code}" \
    -c "$COOKIE_FILE" \
    -H "Content-Type: application/json" \
    -d "{\"Name\":\"${FACTRY_USER}\",\"Password\":\"${FACTRY_PASS}\"}" \
    "${FACTRY_URL}/api/auth/local/login") || true

if [ "$HTTP_CODE" != "200" ]; then
    echo "ERROR: Login to $FACTRY_URL failed (HTTP $HTTP_CODE)"
    cat /tmp/factry-login-resp.txt 2>/dev/null
    echo ""
    exit 1
fi

XSRF=$(grep XSRF "$COOKIE_FILE" | awk '{print $NF}')
echo "Logged in to Factry."

# ── Helper: authenticated API call ────────────────────────────────
factry_api() {
    local method="$1"
    local path="$2"
    local data="$3"

    local args=(-s -b "$COOKIE_FILE" -H "x-xsrf-token: $XSRF")
    if [ -n "$ORG_UUID" ]; then
        args+=(-H "x-organization-uuid: $ORG_UUID")
    fi
    if [ -n "$data" ]; then
        args+=(-H "Content-Type: application/json" -d "$data")
    fi
    if [ "$method" != "GET" ]; then
        args+=(-X "$method")
    fi

    # Never fail here: under set -e that would end the script without a message.
    curl "${args[@]}" "${FACTRY_URL}/api${path}" || true
}

# ── Get organization UUID ─────────────────────────────────────────
# Factry 8.2 refuses most API calls without an x-organization-uuid header.
echo "Looking up organization..."
ORG_RESP=$(factry_api GET "/current-user/organizations")
ORG_UUID=$(echo "$ORG_RESP" | FACTRY_ORG="${FACTRY_ORG:-}" python3 -c "
import json, os, sys
try:
    orgs = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
if not isinstance(orgs, list):
    sys.exit(0)
wanted = os.environ['FACTRY_ORG']
if wanted:
    orgs = [o for o in orgs if wanted in (o.get('UUID'), o.get('Name'))]
else:
    orgs = sorted(orgs, key=lambda o: bool(o.get('IsRoot')))
if orgs:
    print(orgs[0].get('UUID', ''))
" 2>/dev/null) || true

if [ -z "$ORG_UUID" ]; then
    echo "ERROR: No organization found${FACTRY_ORG:+ matching '$FACTRY_ORG'}."
    echo "$ORG_RESP"
    exit 1
fi
echo "  Using organization: $ORG_UUID"

# ── Get time series database UUID ─────────────────────────────────
echo "Looking up time series database..."
TSDB_RESP=$(factry_api GET "/timeseries-databases")
TSDB_UUID=$(echo "$TSDB_RESP" | python3 -c "
import json, sys
try:
    dbs = json.load(sys.stdin)
except ValueError:
    sys.exit(0)
if not isinstance(dbs, list):
    sys.exit(0)
# Pick the first non-internal database, or the first one
for db in dbs:
    name = db.get('Name', '') or db.get('name', '')
    if not name.startswith('_internal'):
        print(db.get('UUID', '') or db.get('uuid', ''))
        sys.exit(0)
if dbs:
    print(dbs[0].get('UUID', '') or dbs[0].get('uuid', ''))
" 2>/dev/null) || true

if [ -z "$TSDB_UUID" ]; then
    echo "ERROR: No time series database found. Create one in Factry first."
    echo "$TSDB_RESP"
    exit 1
fi
echo "  Using TSDB: $TSDB_UUID"

# ── Create collector ──────────────────────────────────────────────
# Without a default database Factry leaves new measurements Discovered and stores nothing.
echo "Creating collector '$COLLECTOR_NAME'..."
COLLECTOR_RESP=$(factry_api POST "/collectors" \
    "{\"Name\":\"${COLLECTOR_NAME}\",\"Description\":\"Ignition historian module\",\"DefaultDatabaseUUID\":\"${TSDB_UUID}\",\"Status\":\"Active\"}")

COLLECTOR_UUID=$(echo "$COLLECTOR_RESP" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    print(d.get('UUID', ''))
except:
    pass
" 2>/dev/null) || true

if [ -z "$COLLECTOR_UUID" ]; then
    echo "ERROR: Failed to create collector."
    echo "$COLLECTOR_RESP"
    exit 1
fi
echo "  Collector UUID: $COLLECTOR_UUID"

# ── Generate token ────────────────────────────────────────────────
echo "Generating collector token..."
TOKEN_RESP=$(factry_api GET "/collectors/${COLLECTOR_UUID}/token")

COLLECTOR_TOKEN=$(echo "$TOKEN_RESP" | python3 -c "
import json, sys
try:
    d = json.load(sys.stdin)
    # The response might be the token string directly or wrapped in an object
    if isinstance(d, str):
        print(d)
    elif 'Token' in d:
        print(d['Token'])
    elif 'token' in d:
        print(d['token'])
    else:
        print(d)
except:
    # Maybe it's a plain string response
    print(sys.stdin.read().strip())
" 2>/dev/null) || true

if [ -z "$COLLECTOR_TOKEN" ] || ! echo "$COLLECTOR_TOKEN" | grep -qE '^eyJ'; then
    echo "ERROR: Failed to generate token."
    echo "$TOKEN_RESP"
    exit 1
fi

# Save token
echo -n "$COLLECTOR_TOKEN" > "$TOKEN_FILE"
echo "  Token saved to $TOKEN_FILE"

# Cleanup
rm -f "$COOKIE_FILE" /tmp/factry-login-resp.txt
