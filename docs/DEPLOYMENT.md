# Junction Gateway Example Deployment Guide

This guide explains one way to deploy Junction Gateway with automatic HTTPS using Caddy as a reverse proxy.
It is an example deployment, not a turnkey production distribution.

## Architecture

```
Internet → Caddy (Docker) → Junction Gateway (Spring Boot)
                ↓
        Auto SSL via Let's Encrypt
```

- **Caddy**: Reverse proxy with automatic HTTPS certificate management
- **Junction Gateway**: Spring Boot application running on port 8080
- **Domain**: Configurable (default: `gateway.example.com`)

## Prerequisites

1. **Docker Desktop** installed and running on Windows 11
2. **Java 25+** installed
3. **Maven 3.9+** installed
4. **Ports 80 and 443** forwarded to your server
5. **Domain** pointing to your public IP (default: `gateway.example.com`)

## Domain Configuration

The gateway uses `gateway.example.com` as the default domain, which matches the `Caddyfile` configuration.

### Changing the Domain

You can configure a custom domain in three ways:

#### Option 1: Environment Variable (Recommended)

Set the `GATEWAY_DOMAIN` environment variable before running the scripts:

```bash
# Linux/Mac
export GATEWAY_DOMAIN="your-domain.com"
./start-gateway.sh

# Windows PowerShell
$env:GATEWAY_DOMAIN = "your-domain.com"
.\start-gateway.ps1
```

#### Option 2: Command-Line Flag

Use the domain flag when starting the gateway:

```bash
# Linux/Mac
./start-gateway.sh --domain your-domain.com

# Windows PowerShell
.\start-gateway.ps1 -Domain "your-domain.com"
```

#### Option 3: Edit Caddyfile

If you want to change the domain permanently:

1. Edit the `Caddyfile` to use your domain
2. Update the `reverse_proxy` directive if needed
3. Run the start script (scripts will use the domain from Caddyfile)

### DNS Requirements

Your domain must point to your server's public IP address:

```bash
# Test DNS resolution
nslookup your-domain.com
# or
dig your-domain.com
```

### SSL Certificate

Caddy automatically obtains and renews SSL certificates from Let's Encrypt for your configured domain.

## Quick Start

### 1. Start the Gateway

**Linux/Mac:**
```bash
./start-gateway.sh
```

**Windows:**
```powershell
.\start-gateway.ps1
```

This will:
- Start Caddy reverse proxy in Docker
- Build the Junction Gateway (if needed)
- Start the Spring Boot application in the background
- Configure automatic HTTPS
- Exit immediately, leaving services running

### 2. Access the Gateway

Once running, access your gateway at (default domain):

- **HTTPS**: https://gateway.example.com

### 3. Test the API

```bash
curl -X POST https://gateway.example.com/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{
    "model": "llama3.1",
    "messages": [{"role": "user", "content": "Hello"}],
    "stream": true
  }'
```

## Script Options

### PowerShell (Windows)

```powershell
# Skip building the project (use existing build)
.\start-gateway.ps1 -SkipBuild

# Skip starting Caddy (if already running)
.\start-gateway.ps1 -SkipCaddy

# Use Let's Encrypt staging server (for testing SSL setup)
.\start-gateway.ps1 -StagingSSL

# Use a custom domain
.\start-gateway.ps1 -Domain "your-domain.com"

# Combine options
.\start-gateway.ps1 -SkipBuild -StagingSSL
```

### Bash (Linux/Mac)

```bash
# Skip building the project
./start-gateway.sh --skip-build

# Skip starting Caddy
./start-gateway.sh --skip-caddy

# Use Let's Encrypt staging server
./start-gateway.sh --staging-ssl

# Use a custom domain
./start-gateway.sh --domain your-domain.com

# Combine options
./start-gateway.sh --skip-build --staging-ssl
```

## Stopping the Gateway

### PowerShell (Windows)
```powershell
.\stop-gateway.ps1
```

### Bash (Linux/Mac)
```bash
./stop-gateway.sh
```

Or manually:

```bash
# Stop Caddy
docker-compose -f docker-compose.caddy.yml down

# Stop Spring Boot (find and stop the background process)
pkill -f "spring-boot"
```

## Viewing Logs

### PowerShell (Windows)
```powershell
# View Caddy logs
docker logs -f junction-caddy

# View Spring Boot logs (if you started it manually)
# Or check the junction-samples/logs/ directory
```

### Bash (Linux/Mac)
```bash
# View Caddy logs
docker logs -f junction-caddy

# View Spring Boot logs
tail -f /tmp/gateway.log
```

## Configuration Files

### Caddyfile

Located at `./Caddyfile`. Key features:
- Automatic HTTPS with Let's Encrypt
- HTTP/2 and HTTP/3 support
- Gzip and Brotli compression
- Security headers (HSTS, XSS protection, etc.)

### docker-compose.caddy.yml

Docker Compose configuration for Caddy:
- Uses official `caddy:2-alpine` image
- Persists certificates in Docker volumes
- Exposes ports 80, 443 (TCP), and 443 (UDP for HTTP/3)

### application.yml

Spring Boot configuration:
- Binds to `0.0.0.0:8080` (all interfaces for Docker access)
- Virtual threads enabled
- Ollama provider configured

## Troubleshooting

### SSL Certificate Issues

If you're having trouble with certificates:

1. **Check domain DNS**: Ensure `gateway.example.com` resolves to your IP
   ```powershell
   nslookup gateway.example.com
   ```

2. **Check ports**: Verify ports 80 and 443 are accessible
   ```powershell
   # Test from another machine
   curl -I http://gateway.example.com
   ```

3. **Use staging server**: Test with Let's Encrypt staging to avoid rate limits
   ```powershell
   .\start-gateway.ps1 -StagingSSL
   ```

4. **Check Caddy logs**:
   ```powershell
   docker logs junction-caddy
   ```

5. **Clear certificate cache** (if needed):
   ```powershell
   docker-compose -f docker-compose.caddy.yml down
   docker volume rm gateway_caddy_data
   docker-compose -f docker-compose.caddy.yml up -d
   ```

### Connection Refused

If Caddy can't connect to the Spring Boot app:

1. **Check Spring Boot is running** on port 8080
2. **Verify Windows Firewall** allows port 8080
3. **Check Docker can reach host**:
   ```powershell
   docker run --rm alpine ping host.docker.internal
   ```

### Rate Limiting

Let's Encrypt has rate limits:
- 50 certificates per domain per week
- 5 duplicate certificates per week

Use `-StagingSSL` flag for testing to avoid hitting production limits.

## Security Features

The Caddy configuration includes:

- **Automatic HTTPS**: Let's Encrypt certificates with auto-renewal
- **HSTS**: HTTP Strict Transport Security headers
- **Security Headers**: XSS protection, content type options, frame options
- **Compression**: Gzip and Brotli for better performance
- **HTTP/3**: Latest protocol support for better performance

## Production Considerations

1. **Firewall**: Ensure only ports 80 and 443 are exposed publicly
2. **Logs**: Caddy logs are stored in Docker volume `caddy_data`
3. **Backups**: Backup the `caddy_data` volume for certificate persistence
4. **Monitoring**: Consider adding health check endpoints to the gateway
5. **Updates**: Regularly update Caddy image: `docker pull caddy:2-alpine`

## Support

For issues with:
- **Caddy**: https://caddyserver.com/docs/
- **Let's Encrypt**: https://letsencrypt.org/docs/
- **Junction Gateway**: See project README.md
