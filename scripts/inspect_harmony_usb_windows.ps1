<#
.SYNOPSIS
  Read-only Harmony USB inventory, independent of Concordance installation.

.DESCRIPTION
  Enumerates Windows Plug-and-Play device IDs and installed driver services
  for Logitech Harmony USB IDs. It never opens, writes or modifies the remote.
  This is a discovery experiment for a future standalone HID capture backend.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Write-Host 'IR Remote Studio | Windows native USB inventory'
Write-Host 'Read-only scan. No Harmony or Concordance commands are issued.'
Write-Host ''
$devices = @(Get-PnpDevice -PresentOnly | Where-Object {
    $_.InstanceId -match 'VID_046D&PID_C1[0-4][0-9A-F]' -or
    $_.InstanceId -match 'VID_0400&PID_C359'
})
if ($devices.Count -eq 0) {
    Write-Host 'No candidate Harmony USB device was found.'
    Write-Host 'Check the USB data cable, batteries, and USB port.'
    exit 0
}
foreach ($device in $devices) {
    Write-Host ('Name:     ' + $device.FriendlyName)
    Write-Host ('Status:   ' + $device.Status)
    Write-Host ('Class:    ' + $device.Class)
    if ($device.InstanceId -match 'VID_046D&PID_C124') {
        Write-Host 'Match:    Harmony 300/350 USB ID (verify physically)'
    } else {
        Write-Host 'Match:    Possible Harmony-family USB ID'
    }
    Write-Host ('Instance: ' + $device.InstanceId)
    foreach ($key in @('DEVPKEY_Device_Service','DEVPKEY_Device_DriverDesc')) {
        try {
            $value = (Get-PnpDeviceProperty -InstanceId $device.InstanceId -KeyName $key -ErrorAction Stop).Data
            Write-Host ($key + ': ' + $value)
        } catch { Write-Host ($key + ': unavailable') }
    }
    Write-Host ''
}
Write-Host 'Diagnostic only. USB enumeration does not prove native IR learning.'
