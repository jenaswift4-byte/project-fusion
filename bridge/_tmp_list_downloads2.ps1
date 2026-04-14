Get-ChildItem 'C:\Users\wang\Downloads' -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 20 Name, Length, LastWriteTime |
    ForEach-Object { "$($_.Name) $([math]::Round($_.Length/1MB,1))MB $($_.LastWriteTime)" }
