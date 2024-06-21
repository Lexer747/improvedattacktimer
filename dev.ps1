$devlocation = "C:\Users\Lexer\Repos\runelite\runelite-client\src\main\java\net\runelite\client\plugins\improvedattacktimer"

$oldDevFiles = Get-ChildItem -Path $devlocation -Filter *.java -Recurse -File
foreach ($oldFile in $oldDevFiles) {
    echo "Removing $devlocation\$oldFile"
    Remove-Item -Path "$devlocation\$oldFile"
}

$srcFilePath = "C:\Users\Lexer\Repos\improvedattacktimer\src\main\java\com\improvedattacktimer"
$srcFiles = Get-ChildItem -Path $srcFilePath -Filter *.java -Recurse -File
$oldPackage = "com.improvedattacktimer"
$newPackage = "net.runelite.client.plugins.improvedattacktimer"
foreach ($srcFile in $srcFiles) {
    echo "Copying file $srcFile -> $devlocation"
    Copy-Item -Path "$srcFilePath\$srcFile" "$devlocation\$srcFile"
    echo "Updating package $oldPackage -> $newPackage | FILE: $devlocation\$srcFile"
    (Get-Content "$devlocation\$srcFile").Replace($oldPackage, $newPackage ) | Set-Content "$devlocation\$srcFile"
}
echo "Done :)"
exit 0