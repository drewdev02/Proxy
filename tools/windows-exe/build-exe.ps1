# Construye build/exe/ProxyUCI.exe: un único ejecutable con la app y una Java recortada dentro.
#
# Requisitos (los pone el trabajo de CI): Windows x64, PowerShell 7 y un JDK 25 en JAVA_HOME
# (jar, jdeps y jlink; el bytecode de la app es de Java 25, así que el runtime también).
#
# Pasos: 1) obtener de Amper el classpath con el que ejecuta la app; 2) copiar esas librerías con nombres únicos y
# enlazarlas desde un jar de manifiesto; 3) averiguar qué módulos de Java usan; 4) jlink con solo esos;
# 5) comprimir runtime + librerías; 6) compilar el arranque (Launcher.cs) con ese ZIP como recurso.
#
# Por qué no se usa el jar ejecutable de Amper: guarda todas las librerías en una carpeta plana por nombre de
# archivo, y varias se llaman igual en grupos distintos (androidx.compose.runtime:runtime-desktop, de 1,9 MB, y
# org.jetbrains.compose.runtime:runtime-desktop, un stub de 4,8 KB). El segundo pisa al primero y a la app le falta
# la clase `Composer`: la ventana no abre. El classpath de `./kotlin run` conserva las rutas completas, que son únicas.
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

# Un manifiesto no admite líneas de más de 72 bytes: se parten, y cada continuación empieza con un espacio.
function Format-ManifestLine {
    param([string]$Line)
    $out = $Line.Substring(0, [Math]::Min(70, $Line.Length))
    $rest = $Line.Substring($out.Length)
    while ($rest.Length -gt 0) {
        $take = [Math]::Min(69, $rest.Length)
        $out += "`r`n " + $rest.Substring(0, $take)
        $rest = $rest.Substring($take)
    }
    return $out
}

$repo = Resolve-Path (Join-Path $PSScriptRoot "../..")
Push-Location $repo
try {
    if (-not $env:JAVA_HOME) { throw "Falta JAVA_HOME (hace falta un JDK 25 con jar, jlink y jdeps)" }
    $jdkBin = Join-Path $env:JAVA_HOME "bin"

    # Rutas absolutas y con las barras de Windows: csc interpreta mal las relativas con «/».
    $OutDir = [IO.Path]::GetFullPath((Join-Path $repo $OutDir))
    $work = Join-Path $OutDir "work"
    if (Test-Path $OutDir) { Remove-Item $OutDir -Recurse -Force }
    New-Item -ItemType Directory -Path $work | Out-Null

    # 1. El classpath con el que Amper ejecuta la app. La autoprueba lo escribe en su informe, y de paso comprueba
    #    que la app arranca sin empaquetar.
    $report = Join-Path $work "informe-sin-empaquetar.txt"
    Invoke-Native "La autoprueba de la app sin empaquetar" {
        ./kotlin.bat run -m desktopApp --no-compose-hot-reload -- --self-test $report
    }
    if ((Get-Content $report -TotalCount 1) -ne "OK") { throw "La autoprueba sin empaquetar falló:`n$(Get-Content $report -Raw)" }
    $classpathLine = Get-Content $report | Where-Object { $_ -like "classpath=*" } | Select-Object -First 1
    if (-not $classpathLine) { throw "El informe no trae el classpath" }
    $entries = $classpathLine.Substring("classpath=".Length).Split([IO.Path]::PathSeparator) | Where-Object { $_ }

    # 2. Cada librería con un prefijo numérico: los nombres no se repiten y el orden del classpath se conserva.
    $payload = Join-Path $work "payload"
    $lib = Join-Path $payload "lib"
    New-Item -ItemType Directory -Path $lib | Out-Null
    $linked = @()
    $index = 0
    foreach ($entry in $entries) {
        if (-not (Test-Path $entry -PathType Leaf)) { throw "El classpath trae algo que no es un archivo: $entry" }
        $index++
        $name = "{0:000}-{1}" -f $index, (Split-Path $entry -Leaf)
        Copy-Item $entry (Join-Path $lib $name)
        $linked += "lib/$name"
    }
    "Librerías: $index"

    # app.jar solo trae un manifiesto: la clase principal y el classpath, en orden y con rutas relativas.
    $manifest = Join-Path $work "manifest.txt"
    $manifestText = "Manifest-Version: 1.0`r`nMain-Class: org.uci.proxy.MainKt`r`n" +
        (Format-ManifestLine ("Class-Path: " + ($linked -join " "))) + "`r`n"
    [IO.File]::WriteAllText($manifest, $manifestText, [Text.Encoding]::ASCII)
    $stub = Join-Path $work "stub"
    New-Item -ItemType Directory -Path $stub | Out-Null
    Set-Content (Join-Path $stub "LEEME.txt") "ProxyUCI: las librerías están en lib/, enlazadas desde el manifiesto de este jar."
    Invoke-Native "jar" { & (Join-Path $jdkBin "jar.exe") --create --file (Join-Path $payload "app.jar") --manifest $manifest -C $stub . }

    # 3. Módulos de Java: se analizan todas las librerías juntas.
    $jars = Get-ChildItem $lib -Filter *.jar | ForEach-Object FullName
    $detected = & (Join-Path $jdkBin "jdeps.exe") --multi-release 25 --ignore-missing-deps --print-module-deps `
        -cp ($lib + "\*") @jars
    if ($LASTEXITCODE -ne 0) { throw "jdeps falló" }
    # jdeps no ve lo que se carga por reflexión: jdk.crypto.ec hace falta para TLS con curvas elípticas.
    $modules = (($detected | Select-Object -Last 1).Trim() + ",jdk.crypto.ec")
    "Módulos de Java: $modules"

    # 4. Runtime mínimo.
    Invoke-Native "jlink" {
        & (Join-Path $jdkBin "jlink.exe") --module-path (Join-Path $env:JAVA_HOME "jmods") --add-modules $modules `
            --strip-debug --no-header-files --no-man-pages --compress zip-6 --output (Join-Path $payload "runtime")
    }

    # 5. ZIP con el runtime y la app. Su huella identifica la versión: cambia el contenido, cambia la carpeta de caché.
    $zip = Join-Path $work "payload.zip"
    [IO.Compression.ZipFile]::CreateFromDirectory($payload, $zip, [IO.Compression.CompressionLevel]::Optimal, $false)
    $hash = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLower()
    $idFile = Join-Path $work "payload.id"
    [IO.File]::WriteAllText($idFile, $hash)

    # 6. El arranque, con el ZIP y la huella como recursos. Es el csc de .NET Framework, que ya viene con Windows.
    $csc = Join-Path $env:WINDIR "Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    $exe = Join-Path $OutDir "ProxyUCI.exe"
    $source = (Resolve-Path (Join-Path $repo "tools\windows-exe\Launcher.cs")).Path
    $manifestCs = (Resolve-Path (Join-Path $repo "tools\windows-exe\app.manifest")).Path
    Invoke-Native "csc" {
        & $csc /nologo /target:winexe /platform:x64 /optimize+ "/out:$exe" `
            "/win32manifest:$manifestCs" `
            "/resource:$zip,payload.zip" "/resource:$idFile,payload.id" `
            /reference:System.IO.Compression.dll /reference:System.IO.Compression.FileSystem.dll `
            $source
    }

    $size = (Get-Item $exe).Length / 1MB
    "ProxyUCI.exe: {0:N1} MB (contenido {1:N1} MB comprimido), huella {2}" -f $size, ((Get-Item $zip).Length / 1MB), $hash
    "SHA-256 del .exe: " + (Get-FileHash $exe -Algorithm SHA256).Hash.ToLower()
    Remove-Item $work -Recurse -Force
}
finally {
    Pop-Location
}
