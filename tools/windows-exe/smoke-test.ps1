# Comprueba que un ProxyUCI.exe construido arranca de verdad. Lo usan el trabajo de CI y el de publicación.
#
# 1) --self-test, dos veces: arranca el grafo del motor, carga JNA y verifica wintun.dll con la Java recortada. La
#    segunda vez ya no extrae nada, y se ve en el tiempo.
# 2) --ready-file: abre la ventana de Compose y espera a que la propia app avise de que se compuso. No se lee el
#    título de la ventana desde fuera: los ejecutores de CI no tienen un escritorio interactivo. Sin tarjeta gráfica
#    se usa el dibujado por software.
param(
    [Parameter(Mandatory)][string]$Exe
)

$ErrorActionPreference = "Stop"
$Exe = (Resolve-Path $Exe).Path
$temp = if ($env:RUNNER_TEMP) { $env:RUNNER_TEMP } else { [IO.Path]::GetTempPath() }

$report = Join-Path $temp "autoprueba.txt"
foreach ($attempt in 1, 2) {
    Remove-Item $report -ErrorAction SilentlyContinue
    $clock = [Diagnostics.Stopwatch]::StartNew()
    $process = Start-Process $Exe -ArgumentList "--self-test", "`"$report`"" -Wait -PassThru
    "Ejecución ${attempt}: código $($process.ExitCode) en $([math]::Round($clock.Elapsed.TotalSeconds, 1)) s"
    if (Test-Path $report) { Get-Content $report | Where-Object { $_ -notlike "classpath=*" } } else { "(no se escribió el informe)" }
    if ($process.ExitCode -ne 0 -or (Get-Content $report -TotalCount 1) -ne "OK") { throw "La autoprueba falló" }
}
if (-not ((Get-Content $report -Raw) -match [regex]::Escape("ProxyUCI.exe"))) { throw "La app no supo cuál es su .exe" }
if (-not ((Get-Content $report -Raw) -match "tailscale=cargado")) { throw "La librería de Tailscale no viene en el .exe o no carga" }

$env:SKIKO_RENDER_API = "SOFTWARE_COMPAT"
$ready = Join-Path $temp "ventana-lista.txt"
Remove-Item $ready, "$ready.error" -ErrorAction SilentlyContinue
$process = Start-Process $Exe -ArgumentList "--ready-file", "`"$ready`"" -PassThru
$exited = $process.WaitForExit(90000)
if (-not $exited) {
    Get-Process javaw -ErrorAction SilentlyContinue | Format-Table Id, Responding
    Get-Process javaw -ErrorAction SilentlyContinue | Stop-Process -Force
    $process | Stop-Process -Force -ErrorAction SilentlyContinue
}
"Salió: $exited; código: $(if ($exited) { $process.ExitCode } else { 'n/a' })"
if (Test-Path "$ready.error") { "--- error no capturado en la app"; Get-Content "$ready.error" }
if (-not (Test-Path $ready) -or (Get-Content $ready -Raw) -ne "listo") { throw "La ventana no llegó a componerse en 90 s" }
"La ventana se compuso con la Java recortada"
