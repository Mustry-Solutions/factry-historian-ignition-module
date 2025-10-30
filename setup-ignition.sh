#!/bin/bash

set -e

echo "=========================================="
echo "Ignition Docker Setup Script"
echo "=========================================="
echo ""

IGNITION_DATA_DIR="./ignition/data"

# Check if data directory already exists
if [ -d "$IGNITION_DATA_DIR" ] && [ "$(ls -A $IGNITION_DATA_DIR)" ]; then
    echo "⚠️  Ignition data directory already exists: $IGNITION_DATA_DIR"
    read -p "Do you want to DELETE it and start fresh? (y/N): " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo "🗑️  Removing existing data directory..."
        rm -rf "$IGNITION_DATA_DIR"
    else
        echo "✅ Keeping existing data. Starting Ignition..."
        docker compose up -d
        exit 0
    fi
fi

echo "📦 Step 1: Starting Ignition WITHOUT mounted volumes to initialize..."
echo ""

# Create temporary docker-compose file without volumes
cat > docker-compose.temp.yml << 'EOF'

services:
  ignition:
    image: inductiveautomation/ignition:8.3.1
    container_name: ignition-init-temp
    ports:
      - "8088:8088"
      - "8043:8043"
      - "8060:8060"
    environment:
      ACCEPT_IGNITION_EULA: "Y"
      GATEWAY_ADMIN_USERNAME: "admin"
      GATEWAY_ADMIN_PASSWORD: "password"
      GATEWAY_NETWORK_0_HOST: "ignition-dev"
      IGNITION_OPTS: "-Xmx2g"
    restart: "no"
EOF

echo "Starting temporary container..."
docker compose -f docker-compose.temp.yml up -d

echo ""
echo "⏳ Waiting for Ignition to initialize (this takes ~30-60 seconds)..."
echo ""

# Wait for Ignition to be ready
MAX_WAIT=120
COUNTER=0
while [ $COUNTER -lt $MAX_WAIT ]; do
    if docker exec ignition-init-temp curl -sf http://localhost:8088/StatusPing > /dev/null 2>&1; then
        echo "✅ Ignition is ready!"
        break
    fi

    # Show progress
    if [ $((COUNTER % 10)) -eq 0 ]; then
        echo "   ... still waiting ($COUNTER seconds elapsed)"
    fi

    sleep 1
    COUNTER=$((COUNTER + 1))
done

if [ $COUNTER -eq $MAX_WAIT ]; then
    echo "❌ ERROR: Ignition failed to start within $MAX_WAIT seconds"
    echo "Check logs with: docker logs ignition-init-temp"
    docker compose -f docker-compose.temp.yml down
    rm docker-compose.temp.yml
    exit 1
fi

echo ""
echo "📋 Step 2: Copying Ignition data from container to local folder..."
echo ""

# Create local directory
mkdir -p "$IGNITION_DATA_DIR"

# Copy data from container
echo "Copying files (this may take a minute)..."
docker cp ignition-init-temp:/usr/local/bin/ignition/data/. "$IGNITION_DATA_DIR/"

# Check if copy was successful
if [ -d "$IGNITION_DATA_DIR/db" ]; then
    echo "✅ Data copied successfully!"
    echo "   Data location: $IGNITION_DATA_DIR"
    echo "   Size: $(du -sh $IGNITION_DATA_DIR | cut -f1)"
else
    echo "❌ ERROR: Data copy failed"
    docker compose -f docker-compose.temp.yml down
    rm docker-compose.temp.yml
    exit 1
fi

echo ""
echo "🛑 Step 3: Stopping temporary container..."
docker compose -f docker-compose.temp.yml down
rm docker-compose.temp.yml

echo ""
echo "=========================================="
echo "✅ Setup Complete!"
echo "=========================================="
echo ""
echo "Ignition data has been initialized in:"
echo "  📁 $IGNITION_DATA_DIR"
echo "  📊 Size: $(du -sh $IGNITION_DATA_DIR | cut -f1)"
echo ""
echo "Next steps:"
echo ""
echo "1. Start Ignition and proxy:"
echo "   docker compose up -d"
echo ""
echo "2. Wait ~30 seconds for startup, then access:"
echo "   🌐 Ignition Gateway: http://localhost:8088"
echo "   🔌 Proxy Health:     http://localhost:8111/health"
echo ""
echo "3. Login with default credentials:"
echo "   👤 Username: admin"
echo "   🔑 Password: password"
echo ""
echo "4. Install the module:"
echo "   - Go to Config → System → Modules"
echo "   - Upload: build/Factry-Historian.modl"
echo ""
echo "Useful commands:"
echo "  docker compose logs -f          # View logs"
echo "  docker compose down             # Stop services"
echo "  docker compose restart ignition # Restart Ignition"
echo ""
