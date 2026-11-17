# Construye build/exe/ProxyUCI.exe: un único ejecutable con la app y una Java recortada dentro.
#
# Requisitos (los pone el trabajo de CI): Windows x64, PowerShell 7 y un JDK 25 en JAVA_HOME
# (jdeps y jlink; el bytecode de la app es de Java 25, así que el runtime también).
#
# Pasos: 1) jar ejecutable con Amper; 2) averiguar qué módulos de Java usa; 3) jlink con solo esos;
# 4) comprimir runtime + jar; 5) compilar el arranque (Launcher.cs) con ese ZIP como recurso.
param(
    [string]$OutDir = "build/exe"
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Invoke-Native {
    param([string]$Description, [scriptblock]$Command)
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Description falló (código $LASTEXITCODE)" }
}

$repo = Resolve-Path (Join-Path $PSScriptRoot "../..")
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) { throw "Falta JAVA_HOME (hace falta un JDK 25 con jlink y jdeps)" }
    $jdkBin = Join-Path $env:JAVA_HOME "bin"

    $work = Join-Path $OutDir "work"
    if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    # 1. Jar ejecutable (con las librerías nativas de Compose de este sistema).
    Invoke-Native "El empaquetado con Amper" { ./kotlin.bat package -m desktopApp -f executable-jar }
    $jar = "build/tasks/_desktopApp_executableJarJvm/desktopApp-jvm-executable.jar"
    if (-not (Test-Path $jar)) { throw "No se generó $jar" }

    # 2. Módulos de Java. El jar ejecutable guarda las librerías como jar anidados, que jdeps no ve:
    #    se extrae y se analiza cada uno por separado.
    $unpacked = Join-Path $work "jar"
    [IO.Compression.ZipFile]::ExtractToDirectory((Resolve-Path $jar), $unpacked)
    $inputs = @((Join-Path $unpacked "BOOT-INF/classes"), (Join-Path $unpacked "org")) +
        (Get-ChildItem (Join-Path $unpacked "BOOT-INF/lib") -Filter *.jar | ForEach-Object FullName)
    $detected = & (Join-Path $jdkBin "jdeps.exe") --multi-release 25 --ignore-missing-deps --print-module-deps `
        -cp ((Join-Path $unpacked "BOOT-INF/lib") + "/*") @inputs
    if ($LASTEXITCODE -ne 0) { throw "jdeps falló" }
    # jdeps no ve lo que se carga por reflexión: jdk.crypto.ec hace falta para TLS con curvas elípticas.
    $modules = (($detected | Select-Object -Last 1).Trim() + ",jdk.crypto.ec")
    "Módulos de Java: $modules"

    # 3. Runtime mínimo.
    $payload = Join-Path $work "payload"
    Invoke-Native "jlink" {
        & (Join-Path $jdkBin "jlink.exe") --module-path (Join-Path $env:JAVA_HOME "jmods") --add-modules $modules `
            --strip-debug --no-header-files --no-man-pages --compress zip-6 --output (Join-Path $payload "runtime")
    }
    Copy-Item $jar (Join-Path $payload "app.jar")

    # 4. ZIP con el runtime y la app. Su huella identifica la versión: cambia el contenido, cambia la carpeta de caché.
    $zip = Join-Path $work "payload.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory($payload, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    $idFile = Join-Path $work "payload.id"
    [IO.File]::WriteAllText((Resolve-Path $work).Path + "\payload.id", $hash)

    # 5. El arranque, con el ZIP y la huella como recursos. Es el csc de .NET Framework, que ya viene con Windows.
    $csc = Join-Path $env:WINDIR "Microsoft.NET/Framework64/v4.0.30319/csc.exe"
    $exe = Join-Path $OutDir "ProxyUCI.exe"
    Invoke-Native "csc" {
        & $csc /nologo /target:winexe /platform:x64 /optimize+ "/out:$exe" `
            "/win32manifest:tools/windows-exe/app.manifest" `
            "/resource:$zip,payload.zip" "/resource:$idFile,payload.id" `
            /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
            tools/windows-exe/Launcher.cs
    }

    $size = (Get-Item $exe).Length / 1MB
    "ProxyUCI.exe: {0:N1} MB (contenido {1:N1} MB comprimido), huella {2}" -f $size, ((Get-Item $zip).Length / 1MB), $hash
    "SHA-256 del .exe: " + (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
    Remove-Item $work -Recurse -Force
}
finally {
    Pop-Location
}
