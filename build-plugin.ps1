<#
.SYNOPSIS
    Поднимает версию плагина и собирает zip для установки.

.DESCRIPTION
    При неизменной версии установленный плагин неотличим от новой сборки: имя zip то же,
    в списке плагинов та же версия — так уже вышло, что правки проверялись на старом билде.
    Поэтому версия поднимается всегда, до сборки.

.PARAMETER Version
    Задать версию явно (например 0.1.0). По умолчанию patch увеличивается на единицу.

.PARAMETER SkipBump
    Собрать текущую версию, ничего не поднимая.

.EXAMPLE
    .\build-plugin.ps1
    .\build-plugin.ps1 -Version 0.1.0
#>
[CmdletBinding()]
param(
    [string]$Version,
    [switch]$SkipBump
)

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$propsPath = Join-Path $PSScriptRoot 'gradle.properties'
$props = Get-Content $propsPath
$versionLine = $props | Where-Object { $_ -match '^\s*version\s*=' } | Select-Object -First 1
if (-not $versionLine) { throw "В gradle.properties нет строки version =" }
$current = ($versionLine -split '=', 2)[1].Trim()

if ($SkipBump) {
    $next = $current
} elseif ($Version) {
    $next = $Version.Trim()
} else {
    # Поднимаем последний числовой сегмент: 0.0.9 -> 0.0.10, суффиксы вида -SNAPSHOT сохраняем.
    if ($current -notmatch '^(?<head>.*?)(?<patch>\d+)(?<tail>[^\d]*)$') {
        throw "Не удалось разобрать версию '$current' — задайте -Version явно"
    }
    $next = '{0}{1}{2}' -f $Matches.head, ([int]$Matches.patch + 1), $Matches.tail
}

if ($next -ne $current) {
    ($props -replace '^\s*version\s*=.*$', "version = $next") | Set-Content $propsPath -Encoding UTF8
    Write-Host "version: $current -> $next"
} else {
    Write-Host "version: $next (без изменений)"
}

& (Join-Path $PSScriptRoot 'gradlew.bat') buildPlugin -q
if ($LASTEXITCODE -ne 0) { throw "buildPlugin завершился с кодом $LASTEXITCODE" }

$zip = Join-Path $PSScriptRoot "build\distributions\hgrider-$next.zip"
if (-not (Test-Path $zip)) { throw "Ожидался $zip, но его нет" }
$info = Get-Item $zip
Write-Host ''
Write-Host "Готово: $($info.FullName)"
Write-Host ("  {0:N0} байт, {1:HH:mm:ss}" -f $info.Length, $info.LastWriteTime)
Write-Host '  Settings -> Plugins -> gear -> Install Plugin from Disk... -> перезапуск IDE'
