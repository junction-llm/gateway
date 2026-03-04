#!/bin/bash
# Junction LLM Gateway Startup Script
# Starts Caddy reverse proxy and Spring Boot application in background

set -e

# Default domain (matches Caddyfile)
DOMAIN="${GATEWAY_DOMAIN:-gateway.example.com}"

# Parse arguments
SKIP_CADDY=false
SKIP_BUILD=false
STAGING_SSL=false

for arg in "$@"; do
    case $arg in
        --skip-caddy)
            SKIP_CADDY=true
            shift
            ;;
        --skip-build)
            SKIP_BUILD=true
            shift
            ;;
        --staging-ssl)
            STAGING_SSL=true
            shift
            ;;
        --domain)
            DOMAIN="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $arg"
            echo "Usage: $0 [--skip-caddy] [--skip-build] [--staging-ssl] [--domain <domain>]"
            exit 1
            ;;
    esac
done

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Junction LLM Gateway Startup${NC}"
echo -e "${CYAN}  Domain: ${DOMAIN}${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Check if Docker is running
if [ "$SKIP_CADDY" = false ]; then
    echo -e "${YELLOW}Checking Docker...${NC}"
    if ! docker info > /dev/null 2>&1; then
        echo -e "${RED}  [ERROR] Docker is not running. Please start Docker Desktop first.${NC}"
        exit 1
    fi
    echo -e "${GREEN}  [OK] Docker is running${NC}"

    # Start Caddy
    echo ""
    echo -e "${YELLOW}Starting Caddy reverse proxy...${NC}"
    
    # Use staging server if requested
    if [ "$STAGING_SSL" = true ]; then
        echo -e "${YELLOW}  Using Let's Encrypt STAGING server (for testing)${NC}"
        export CADDY_ACME_CA="https://acme-staging-v02.api.letsencrypt.org/directory"
    fi
    
    docker-compose -f docker-compose.caddy.yml up -d

    if [ $? -eq 0 ]; then
        echo -e "${GREEN}  [OK] Caddy started successfully${NC}"
        echo -e "  - HTTP:  http://${DOMAIN}" -e "${GRAY}"
        echo -e "  - HTTPS: https://${DOMAIN}" -e "${GRAY}"
    else
        echo -e "${RED}  [ERROR] Failed to start Caddy${NC}"
        exit 1
    fi

    # Wait a moment for Caddy to initialize
    echo ""
    echo -e "${YELLOW}Waiting for Caddy to initialize...${NC}"
    sleep 3
fi

# Build the project if not skipped
if [ "$SKIP_BUILD" = false ]; then
    echo ""
    echo -e "${YELLOW}Building Junction Gateway...${NC}"
    if ! mvn clean install -DskipTests -q; then
        echo -e "${RED}  [ERROR] Build failed${NC}"
        exit 1
    fi
    echo -e "${GREEN}  [OK] Build successful${NC}"
fi

# Start Spring Boot application in background
echo ""
echo -e "${YELLOW}Starting Spring Boot application in background...${NC}"
echo -e "  The application will bind to 0.0.0.0:8080 (accessible from Docker)" -e "${GRAY}"
echo ""

# Change to samples directory and start in background
cd junction-samples

# Start the application in background and capture PID
nohup mvn spring-boot:run -q > /tmp/gateway.log 2>&1 &
GATEWAY_PID=$!

# Store PID in file for cleanup
echo $GATEWAY_PID > /tmp/gateway.pid

# Give it a moment to start
sleep 5

# Check if process is still running
if kill -0 $GATEWAY_PID 2>/dev/null; then
    echo -e "${GREEN}  [OK] Spring Boot application started successfully${NC}"
    echo -e "${GREEN}  PID: $GATEWAY_PID${NC}"
else
    echo -e "${YELLOW}  [WARNING] Spring Boot may not have started properly${NC}"
    echo -e "${YELLOW}  Check logs at: /tmp/gateway.log${NC}"
fi

# Go back to original directory
cd ..

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Gateway is running!${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""
echo -e "Access your gateway at:" -e "${WHITE}"
echo -e "  - HTTPS: https://${DOMAIN}" -e "${GREEN}"
echo -e "  - HTTP:  http://${DOMAIN}" -e "${GREEN}"
echo ""
echo -e "To stop the services:" -e "${YELLOW}"
echo -e "  - Stop Caddy:    docker-compose -f docker-compose.caddy.yml down" -e "${GRAY}"
echo -e "  - Stop Gateway:  ./stop-gateway.sh" -e "${GRAY}"
echo ""
echo -e "Gateway logs available at: /tmp/gateway.log" -e "${GRAY}"
echo ""
echo -e "Goodbye!" -e "${CYAN}"
