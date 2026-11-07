param(
    [Parameter(Mandatory=$true)][ValidateSet('Probe', 'Capture')][string]$Mode,
    [Parameter(Mandatory=$true)][string]$OutputPath,
    [Parameter(Mandatory=$true)][string]$LogPath
)

$ErrorActionPreference = 'Stop'
$initialized = $false
$signalPointer = [IntPtr]::Zero
$answer = [ordered]@{
    success = $false
    mode = $Mode
    stage = 'starting'
    message = ''
    model = ''
    carrier_hz = 0
    timings_us = @()
}

function Log-Step([string]$message) {
    $line = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff') + ' | ' + $message
    try { [System.IO.File]::AppendAllText($LogPath, $line + [Environment]::NewLine) } catch {}
}

try {
    [void](New-Item -ItemType Directory -Path (Split-Path -Parent $LogPath) -Force)
    Log-Step ('Worker started; mode=' + $Mode)
    if ([IntPtr]::Size -ne 4) { throw 'Expected 32-bit PowerShell.' }
    $installerDir = Join-Path ${env:ProgramFiles(x86)} 'Concordance'
    $dllFile = Join-Path $installerDir 'libconcord-6.dll'
    if (-not (Test-Path -LiteralPath $dllFile)) { throw "DLL missing: $dllFile" }

    $source = @'
using System;
using System.Runtime.InteropServices;
public static class HarmonyNative {
    [UnmanagedFunctionPointer(CallingConvention.Cdecl)]
    public delegate void ProgressCallback(uint stage, uint count, uint current, uint total,
                                          uint counterType, IntPtr arg, IntPtr stages);
    public static void ProgressSink(uint stage, uint count, uint current, uint total,
                                    uint counterType, IntPtr arg, IntPtr stages) { }
    public static readonly ProgressCallback Progress = new ProgressCallback(ProgressSink);

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static extern bool SetDllDirectory(string path);
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int init_concord();
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int deinit_concord();
    // get_identity ALWAYS invokes ProgressCallback (even before querying the remote).
    // Passing NULL here causes a native access violation.
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int get_identity(ProgressCallback callback, IntPtr callbackArgument);
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern IntPtr get_model();
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern int learn_from_remote(ref uint carrierHz, out IntPtr signal,
        out uint count, ProgressCallback callback, IntPtr callbackArgument);
    [DllImport("libconcord-6.dll", CallingConvention = CallingConvention.Cdecl)]
    public static extern void delete_ir_signal(IntPtr signal);
}
'@
    $answer.stage = 'load_library'
    Log-Step 'Compiling native bindings with non-null callback'
    Add-Type -TypeDefinition $source -Language CSharp
    if (-not [HarmonyNative]::SetDllDirectory($installerDir)) { throw 'Cannot set DLL search path.' }
    $answer.stage = 'init_concord'
    Log-Step 'Entering init_concord()'
    $rc = [HarmonyNative]::init_concord()
    Log-Step "init_concord returned $rc"
    if ($rc -ne 0) { throw "USB connection failed (libconcord error $rc)." }
    $initialized = $true

    $answer.stage = 'get_identity'
    Log-Step 'Entering get_identity() with valid progress callback'
    $rc = [HarmonyNative]::get_identity([HarmonyNative]::Progress, [IntPtr]::Zero)
    Log-Step "get_identity returned $rc"
    if ($rc -ne 0) { throw "Identity request failed (libconcord error $rc)." }
    $modelPointer = [HarmonyNative]::get_model()
    if ($modelPointer -eq [IntPtr]::Zero) { throw 'The model name was null.' }
    $model = [Runtime.InteropServices.Marshal]::PtrToStringAnsi($modelPointer)
    $answer.model = $model
    Log-Step "Connected to model: $model"
    # Harmony 300 and 350 share the same Logitech USB device family.
    # Other models require separate validation.
    if ($model -notmatch '(?i)\bHarmony\s*(?:300|350)\b') {
        throw "Connected to '$model'. This build supports Harmony 300 and Harmony 350."
    }

    if ($Mode -eq 'Capture') {
        $answer.stage = 'learn_from_remote'
        [uint32]$carrier = 0
        [uint32]$count = 0
        Log-Step 'Starting IR capture (press original remote now)'
        $rc = [HarmonyNative]::learn_from_remote([ref]$carrier, [ref]$signalPointer,
                                                 [ref]$count, [HarmonyNative]::Progress,
                                                 [IntPtr]::Zero)
        Log-Step "learn_from_remote returned $rc; carrier=$carrier; values=$count"
        if ($rc -eq 18) { throw 'IR receive timed out. Try again and press the original remote button immediately.' }
        if ($rc -eq 17) { throw 'IR receive buffer overflow; try a shorter button press.' }
        if ($rc -ne 0) { throw "IR capture returned libconcord error $rc." }
        if ($signalPointer -eq [IntPtr]::Zero -or $count -lt 2 -or $count -gt 10000 -or ($count % 2) -ne 0) {
            throw "Capture returned an invalid timing array (count $count)."
        }
        $timings = [uint32[]]::new([int]$count)
        for ($i = 0; $i -lt [int]$count; $i++) {
            $value = [Runtime.InteropServices.Marshal]::ReadInt32($signalPointer, 4*$i)
            if ($value -le 0) { throw "Invalid IR duration at index $i ($value)." }
            $timings[$i] = [uint32]$value
        }
        $answer.carrier_hz = $carrier
        $answer.timings_us = @($timings)
        if ($carrier -lt 1000 -or $carrier -gt 200000) { throw "Implausible carrier frequency: $carrier Hz." }
    }
    $answer.stage = 'completed'
    $answer.success = $true
    $answer.message = if ($Mode -eq 'Probe') { 'Harmony detected successfully.' } else { 'IR captured successfully.' }
} catch {
    $answer.message = $_.Exception.Message
    Log-Step ('ERROR at stage ' + $answer.stage + ': ' + $answer.message)
} finally {
    if ($signalPointer -ne [IntPtr]::Zero) {
        try { [HarmonyNative]::delete_ir_signal($signalPointer); Log-Step 'Released IR signal memory' }
        catch { Log-Step ('IR memory cleanup warning: ' + $_.Exception.Message) }
    }
    if ($initialized) {
        try { Log-Step 'Calling deinit_concord()'; [void][HarmonyNative]::deinit_concord(); Log-Step 'USB released' }
        catch { Log-Step ('USB cleanup warning: ' + $_.Exception.Message) }
    }
    try {
        $answer | ConvertTo-Json -Depth 8 -Compress |
            Set-Content -LiteralPath $OutputPath -Encoding UTF8
        Log-Step 'Wrote report'
    } catch { Log-Step ('Report write failed: ' + $_.Exception.Message) }
}
if (-not $answer.success) { exit 1 }
exit 0
