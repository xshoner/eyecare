param([string[]]$Tasks = @('testDebugUnitTest','assembleDebug','lintDebug'))
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = (Get-ChildItem "$PSScriptRoot/.tools/jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$PSScriptRoot/.tools/android-sdk"
$env:GRADLE_USER_HOME = "$PSScriptRoot/.tools/gradle-cache"
$env:PATH = "$env:JAVA_HOME/bin;$env:ANDROID_HOME/platform-tools;$env:PATH"
Push-Location $PSScriptRoot
try {
    & "$PSScriptRoot/.tools/gradle/gradle-8.13/bin/gradle.bat" @Tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
