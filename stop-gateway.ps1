# Junction LLM Gateway Stop Script
# Stops Caddy reverse proxy and Spring Boot application

$ErrorActionPreference = "Stop"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Junction LLM Gateway Shutdown" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Stop Caddy
Write-Host "Stopping Caddy..." -ForegroundColor Yellow
docker-compose -f docker-compose.caddy.yml down 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "  [OK] Caddy stopped" -ForegroundColor Green
} else {
    Write-Host "  [INFO] Caddy was not running" -ForegroundColor Gray
}

# Find and stop Spring Boot jobs
Write-Host ""
Write-Host "Stopping Spring Boot application..." -ForegroundColor Yellow
$jobs = Get-Job | Where-Object { $_.Name -like "*spring*" -or $_.Command -like "*mvn*spring-boot*" }
if ($jobs) {
    $jobs | Stop-Job -ErrorAction SilentlyContinue
    $jobs | Remove-Job -ErrorAction SilentlyContinue
    Write-Host "  [OK] Spring Boot application stopped" -ForegroundColor Green
} else {
    Write-Host "  [INFO] No Spring Boot jobs found" -ForegroundColor Gray
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Gateway stopped" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
