# One-time setup for repeatable Android APK signatures on GitHub Actions.
# Run on the developer's trusted Windows PC, NEVER commit the .p12 or password.
# Requires GitHub CLI (gh) authenticated as a repo admin and Java keytool.
[CmdletBinding()]
param(
    [string]$Repository = 'MixterVee/IR-Remote-Studio',
    [string]$KeystorePath = "$env:USERPROFILE\Documents\IR Remote Studio\Signing\ir-studio-android-test.p12"
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-KeytoolPath {
    $found = Get-Command 'keytool.exe' -ErrorAction SilentlyContinue
    if ($null -ne $found) { return $found.Source }
    $possible = @(
        "$env:JAVA_HOME\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe",
        "$env:ProgramFiles\Android\Android Studio\jre\bin\keytool.exe"
    )
    foreach ($candidate in $possible) {
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    throw "Java keytool.exe not found. Install Android Studio's JDK or set JAVA_HOME."
}

function Set-PrivateGhSecret([string]$Name, [string]$Value, [string]$TargetRepo) {
    # Pass the key/password only through stdin, never on the command line.
    $gh = (Get-Command 'gh.exe' -ErrorAction Stop).Source
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $gh
    $info.Arguments = "secret set $Name --repo $TargetRepo"
    $info.RedirectStandardInput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardOutput = $true
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    if (-not $process.Start()) { throw "Could not launch GitHub CLI" }
    $process.StandardInput.Write($Value)
    $process.StandardInput.Close()
    $stderr = $process.StandardError.ReadToEnd()
    $stdout = $process.StandardOutput.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) {
        throw "GitHub CLI could not save secret '$Name': $stderr $stdout"
    }
    Write-Host "Registered private GitHub Actions secret: $Name" -ForegroundColor Green
}

if (-not (Get-Command 'gh.exe' -ErrorAction SilentlyContinue)) {
    throw "GitHub CLI is missing. Run: winget install --id GitHub.cli -e, reopen PowerShell, then run this script again."
}
& gh auth status 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "GitHub CLI is not authenticated. Run: gh auth login, then retry."
}

$keytool = Get-KeytoolPath
$folder = Split-Path -Parent $KeystorePath
if (-not (Test-Path -LiteralPath $folder)) {
    New-Item -Path $folder -ItemType Directory -Force | Out-Null
}
Write-Host ""
Write-Host "Permanent signing keystore location: $KeystorePath"
Write-Host "Save its password in your password manager and back up the keystore itself."
Write-Host "Without the SAME keystore, future updates cannot install over existing APKs."
Write-Host ""
$secure = Read-Host 'Enter a strong (12+ character) permanent APK signing password' -AsSecureString
$ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
try {
    $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
} finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
}
if ([string]::IsNullOrEmpty($password) -or $password.Length -lt 12) {
    throw 'Password must be at least 12 characters.'
}

try {
    if (Test-Path -LiteralPath $KeystorePath) {
        Write-Host "Existing keystore found: REUSING it, not replacing it."
        & $keytool -list -keystore $KeystorePath -storetype PKCS12 -alias irstudio -storepass $password | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Existing signing key could not be opened. Check your password.' }
    } else {
        Write-Host 'Generating your first permanent IR Remote Studio signing key...'
        & $keytool -genkeypair -keystore $KeystorePath -storetype PKCS12 `
            -alias irstudio -keyalg RSA -keysize 3072 -validity 10000 `
            -dname 'CN=IR Remote Studio Android Test, OU=Development, O=MixterVee, C=CA' `
            -storepass $password -keypass $password
        if ($LASTEXITCODE -ne 0) {
            throw 'Key generation failed.'
        }
    }

    $encodedKeystore = [Convert]::ToBase64String(
        [System.IO.File]::ReadAllBytes($KeystorePath))
    Set-PrivateGhSecret 'IR_STUDIO_ANDROID_KEYSTORE_BASE64' $encodedKeystore $Repository
    Set-PrivateGhSecret 'IR_STUDIO_ANDROID_KEYSTORE_PASSWORD' $password $Repository

    Write-Host ''
    Write-Host 'SUCCESS: Signing material stored in encrypted GitHub Actions secrets.' -ForegroundColor Green
    Write-Host 'Future GitHub Actions APKs will use this exact key and can update in place.'
    Write-Host 'IMPORTANT: The next transition from an old disposable debug signature'
    Write-Host 'still requires ONE final uninstall/reinstall. Export your app library first!'
    Write-Host 'Keep the .p12 file and password backed up securely. Never post them online.'
    Write-Host 'Certificate fingerprint:'
    & $keytool -list -v -keystore $KeystorePath -storetype PKCS12 `
        -alias irstudio -storepass $password |
        Select-String -Pattern 'SHA256:|SHA-256:'
} finally {
    # Do not echo this value or put it in any repository file.
    $password = $null
}
