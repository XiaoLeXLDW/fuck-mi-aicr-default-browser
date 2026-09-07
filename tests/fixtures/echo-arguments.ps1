# Harmless child-process fixture. Does not read files, connect to ADB, or mutate devices.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
ConvertTo-Json -InputObject @($args) -Compress
