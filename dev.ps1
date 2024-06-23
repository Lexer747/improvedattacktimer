.\gradlew.bat build
if (-not $?) { exit $? }
.\gradlew.bat test
if (-not $?) { exit $? }
$repo = Get-Location
$devlocation = "$repo\..\runelite\runelite-client\src\main\java\net\runelite\client\plugins\improvedattacktimer"

$oldDevFiles = Get-ChildItem -Path $devlocation -Filter *.java -Recurse -File
foreach ($oldFile in $oldDevFiles) {
    Write-Output "Removing $devlocation\$oldFile"
    Remove-Item -Path "$devlocation\$oldFile"
}

$srcFilePath = "$repo\src\main\java\com\improvedattacktimer"
$srcFiles = Get-ChildItem -Path $srcFilePath -Filter *.java -Recurse -File
$oldPackage = "com.improvedattacktimer"
$newPackage = "net.runelite.client.plugins.improvedattacktimer"
$debugString = "LOCAL_DEBUGGING_20ad04d7 = false;" # should be committed to the repo off.
$debugOnString = "LOCAL_DEBUGGING_20ad04d7 = true;"
foreach ($srcFile in $srcFiles) {
    Write-Output "Copying file $srcFile -> $devlocation"
    Copy-Item -Path "$srcFilePath\$srcFile" "$devlocation\$srcFile"
    Write-Output "Updating package $oldPackage -> $newPackage | FILE: $devlocation\$srcFile"
    (Get-Content "$devlocation\$srcFile").Replace($oldPackage, $newPackage).Replace($debugString, $debugOnString) | Set-Content "$devlocation\$srcFile"
}
Write-Output "Done :)"
exit 0