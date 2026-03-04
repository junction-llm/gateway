# Junction LLM Gateway Startup Script
# Starts Caddy reverse proxy and Spring Boot application in background

param(
    [switch]$SkipCaddy,
    [switch]$SkipBuild,
    [switch]$StagingSSL,
    [string]$Domain = "gateway.example.com"
)

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Junction LLM Gateway Startup" -ForegroundColor Cyan
Write-Host "  Domain: $Domain" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if Docker is running
if (-not $SkipCaddy) {
    Write-Host "Checking Docker..." -ForegroundColor Yellow
    try {
        $dockerInfo = docker info 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "Docker is not running"
        }
        Write-Host "  [OK] Docker is running" -ForegroundColor Green
    } catch {
        Write-Host "  [ERROR] Docker is not running. Please start Docker Desktop first." -ForegroundColor Red
        exit 1
    }

    # Start Caddy
    Write-Host ""
    Write-Host "Starting Caddy reverse proxy..." -ForegroundColor Yellow
    
    # Use staging server if requested
    if ($StagingSSL) {
        Write-Host "  Using Let's Encrypt STAGING server (for testing)" -ForegroundColor Yellow
        $env:CADDY_ACME_CA = "https://acme-staging-v02.api.letsencrypt.org/directory"
    }
    
    docker-compose -f docker-compose.caddy.yml up -d

    if ($LASTEXITCODE -eq 0) {
        Write-Host "  [OK] Caddy started successfully" -ForegroundColor Green
        Write-Host "  - HTTP:  http://$Domain" -ForegroundColor Gray
        Write-Host "  - HTTPS: https://$Domain" -ForegroundColor Gray
    } else {
        Write-Host "  [ERROR] Failed to start Caddy" -ForegroundColor Red
        exit 1
    }

    # Wait a moment for Caddy to initialize
    Write-Host ""
    Write-Host "Waiting for Caddy to initialize..." -ForegroundColor Yellow
    Start-Sleep -Seconds 3
}

# Build the project if not skipped
if (-not $SkipBuild) {
    Write-Host ""
    Write-Host "Building Junction Gateway..." -ForegroundColor Yellow
    mvn clean install -DskipTests -q

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  [ERROR] Build failed" -ForegroundColor Red
        exit 1
    }
    Write-Host "  [OK] Build successful" -ForegroundColor Green
}

# Start Spring Boot application in background
Write-Host ""
Write-Host "Starting Spring Boot application in background..." -ForegroundColor Yellow
Write-Host "  The application will bind to 0.0.0.0:8080 (accessible from Docker)" -ForegroundColor Gray
Write-Host ""

# Change to samples directory and start in background
$job = Start-Job -ScriptBlock {
    param($workingDir)
    Set-Location $workingDir
    mvn spring-boot:run -q 2>&1 | Out-Null
} -ArgumentList (Resolve-Path "./junction-samples").Path

# Give it a moment to start
Start-Sleep -Seconds 5

# Check if job is running
if ($job.State -eq "Running") {
    Write-Host "  [OK] Spring Boot application started successfully" -ForegroundColor Green
} else {
    Write-Host "  [WARNING] Spring Boot may not have started properly" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Gateway is running!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Access your gateway at:" -ForegroundColor White
Write-Host "  - HTTPS: https://$Domain" -ForegroundColor Green
Write-Host "  - HTTP:  http://$Domain" -ForegroundColor Green
Write-Host ""
Write-Host "To stop the services:" -ForegroundColor Yellow
Write-Host "  - Stop Caddy:    docker-compose -f docker-compose.caddy.yml down" -ForegroundColor Gray
Write-Host "  - Stop Gateway:  Stop-Job $job.Id" -ForegroundColor Gray
Write-Host ""
Write-Host "Goodbye!" -ForegroundColor Cyan
