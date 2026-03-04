#!/bin/bash
# Junction LLM Gateway Stop Script
# Stops Caddy reverse proxy and Spring Boot application

# Default domain (matches Caddyfile)
DOMAIN="${GATEWAY_DOMAIN:-gateway.example.com}"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
GRAY='\033[0;37m'
NC='\033[0m' # No Color

echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Junction LLM Gateway Shutdown${NC}"
echo -e "${CYAN}  Domain: ${DOMAIN}${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

# Stop Caddy
echo -e "${YELLOW}Stopping Caddy...${NC}"
if docker-compose -f docker-compose.caddy.yml down 2>/dev/null; then
    echo -e "${GREEN}  [OK] Caddy stopped${NC}"
else
    echo -e "${GRAY}  [INFO] Caddy was not running${NC}"
fi

# Stop Spring Boot application
echo ""
echo -e "${YELLOW}Stopping Spring Boot application...${NC}"

# Try to read PID from file first
if [ -f /tmp/gateway.pid ]; then
    GATEWAY_PID=$(cat /tmp/gateway.pid)
    if kill -0 $GATEWAY_PID 2>/dev/null; then
        echo -e "${GRAY}  Found process with PID: $GATEWAY_PID${NC}"
        kill $GATEWAY_PID 2>/dev/null
        sleep 2
        
        # Force kill if still running
        if kill -0 $GATEWAY_PID 2>/dev/null; then
            echo -e "${YELLOW}  Process still running, forcing kill...${NC}"
            kill -9 $GATEWAY_PID 2>/dev/null
        fi
        
        echo -e "${GREEN}  [OK] Spring Boot application stopped${NC}"
        rm -f /tmp/gateway.pid
    else
        echo -e "${GRAY}  [INFO] Spring Boot process not found (may have already stopped)${NC}"
        rm -f /tmp/gateway.pid
    fi
else
    # Fallback: search for Spring Boot processes
    SPRING_PID=$(pgrep -f "spring-boot" || true)
    if [ -n "$SPRING_PID" ]; then
        echo -e "${GRAY}  Found Spring Boot process with PID: $SPRING_PID${NC}"
        kill $SPRING_PID 2>/dev/null
        sleep 2
        
        if kill -0 $SPRING_PID 2>/dev/null; then
            echo -e "${YELLOW}  Process still running, forcing kill...${NC}"
            kill -9 $SPRING_PID 2>/dev/null
        fi
        
        echo -e "${GREEN}  [OK] Spring Boot application stopped${NC}"
    else
        echo -e "${GRAY}  [INFO] No Spring Boot processes found${NC}"
    fi
fi

echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Gateway stopped${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""