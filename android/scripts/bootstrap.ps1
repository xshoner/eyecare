$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$root = Split-Path $PSScriptRoot -Parent
$toolsDir = Join-Path $root '.tools'
New-Item -ItemType Directory -Force $toolsDir | Out-Null
function Fetch-Checked($url, $file, $hash, $algorithm='SHA256') {
    if (!(Test-Path -LiteralPath $file)) { Invoke-WebRequest -Uri $url -OutFile $file }
    if ((Get-FileHash -LiteralPath $file -Algorithm $algorithm).Hash -ne $hash) { throw "Checksum mismatch: $file" }
}
if (!(Test-Path "$toolsDir/jdk")) {
    $asset = (Invoke-RestMethod 'https://api.adoptium.net/v3/assets/latest/17/hotspot?architecture=x64&image_type=jdk&os=windows')[0].binary.package
    Fetch-Checked $asset.link "$toolsDir/jdk.zip" $asset.checksum
    Expand-Archive "$toolsDir/jdk.zip" "$toolsDir/jdk"
}
if (!(Test-Path "$toolsDir/gradle/gradle-8.13/bin/gradle.bat")) {
    $hashResponse = (Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256').Content
    $hash = if ($hashResponse -is [byte[]]) { [Text.Encoding]::UTF8.GetString($hashResponse).Trim() } else { $hashResponse.Trim() }
    Fetch-Checked 'https://services.gradle.org/distributions/gradle-8.13-bin.zip' "$toolsDir/gradle.zip" $hash
    Expand-Archive "$toolsDir/gradle.zip" "$toolsDir/gradle"
}
if (!(Test-Path "$toolsDir/sdk-tools19/cmdline-tools/bin/sdkmanager.bat")) {
    $xml = [xml](Invoke-WebRequest 'https://dl.google.com/android/repository/repository2-1.xml').Content
    $pkg = $xml.SelectSingleNode("//*[local-name()='remotePackage' and @path='cmdline-tools;19.0']")
    $archive = $pkg.archives.archive | Where-Object { $_.'host-os' -eq 'windows' }
    Fetch-Checked ("https://dl.google.com/android/repository/" + $archive.complete.url) "$toolsDir/sdk19.zip" ([string]$archive.complete.checksum) 'SHA1'
    Expand-Archive "$toolsDir/sdk19.zip" "$toolsDir/sdk-tools19"
}
$env:JAVA_HOME = (Get-ChildItem "$toolsDir/jdk" -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = "$toolsDir/android-sdk"
$env:PATH = "$env:JAVA_HOME/bin;$env:PATH"
$sdkManager = "$toolsDir/sdk-tools19/cmdline-tools/bin/sdkmanager.bat"
1..20 | ForEach-Object { 'y' } | & $sdkManager --sdk_root=$env:ANDROID_HOME --licenses
& $sdkManager --sdk_root=$env:ANDROID_HOME 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0'
if ($LASTEXITCODE -ne 0) { throw 'Android SDK installation failed' }
if (!(Test-Path "$env:ANDROID_HOME/platforms/android-36/android.jar")) { throw 'Android platform missing' }
[IO.File]::WriteAllText("$root/local.properties", ('sdk.dir=' + $env:ANDROID_HOME.Replace('\','/').Replace(':','\:') + "`n"), [Text.UTF8Encoding]::new($false))
Write-Output 'Android build tools ready.'
