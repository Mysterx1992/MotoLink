# MotoLink Supabase deletion: MANUAL test of ONE NEW disposable Auth identity.
# Never runs from CI. Never accepts an existing user ID or a user-provided JWT.
# It does not read the service-role key, store tokens on disk, or touch the live
# motolink-assistant function. Requires deployed motolink-delete-session with
# verify_jwt=true. Does NOT run automatically on build.
#
# Usage in Windows PowerShell from the repository root:
#   powershell -ExecutionPolicy Bypass -File scripts/test_supabase_delete_disposable.ps1
#
# PowerShell 7 alternative:
#   pwsh -File scripts/test_supabase_delete_disposable.ps1

param(
    [string]$ProjectRef = "yjklfhhchswnvqvgfrqk"
)
$ErrorActionPreference = "Stop"
$uriBase = "https://$ProjectRef.supabase.co"
$sessionToken = $null
$testUserId = $null
$created = $false
$deleted = $false

function Get-StatusCode($err) {
    if ($null -eq $err.Exception.Response) { return 0 }
    try { return [int]$err.Exception.Response.StatusCode } catch { return 0 }
}

Write-Host "MotoLink: collaudo manuale su account anonimo APPENA CREATO."
Write-Host "Endpoint: $uriBase/functions/v1/motolink-delete-session"
Write-Host "Non utilizzare token o ID di account esistenti."
$key = (Read-Host "Inserisci solo la PUBLIC/PUBLISHABLE API KEY Supabase (mai service_role)").Trim()
if ([string]::IsNullOrWhiteSpace($key) -or $key.Length -lt 15) {
    throw "Publishable key mancante"
}
$publicHeaders = @{ apikey = $key; "Content-Type" = "application/json" }

# Preflight BEFORE creating the disposable identity: the endpoint must exist.
$preflightStatus = 0
try {
    $null = Invoke-WebRequest -Method Post -Uri "$uriBase/functions/v1/motolink-delete-session" -Headers $publicHeaders -Body "{}" -UseBasicParsing
    throw "L'endpoint ha accettato una richiesta senza JWT: NON SICURO. Stop."
} catch {
    if ($_.Exception.Message -like "*NON SICURO*") { throw }
    $preflightStatus = Get-StatusCode $_
}
if ($preflightStatus -ne 401 -and $preflightStatus -ne 403) {
    throw "Endpoint non pronto o controllo JWT non verificato (HTTP $preflightStatus). Nessun account creato."
}
Write-Host "PASS: richiesta senza JWT rifiutata dal server (HTTP $preflightStatus)."

$answer = (Read-Host "Digita esattamente CREA_E_ELIMINA_TEST per creare e cancellare UNA identita nuova").Trim()
if ($answer -cne "CREA_E_ELIMINA_TEST") {
    Write-Host "Annullato, nessuna identita di prova creata."
    exit 0
}

try {
    $signup = Invoke-RestMethod -Method Post -Uri "$uriBase/auth/v1/signup" -Headers $publicHeaders -Body "{}"
    if ($null -eq $signup.user -or $signup.user.is_anonymous -ne $true -or
        [string]::IsNullOrWhiteSpace($signup.access_token)) {
        throw "Signup non ha confermato identita anonima e JWT validi. STOP."
    }
    $created = $true
    $testUserId = [string]$signup.user.id
    $sessionToken = [string]$signup.access_token
    Write-Host "PASS: creata un'identita anonima NUOVA, isolata dalla V1.8."

    # Do not send user_id in the request body. Backend must derive user.id from JWT.
    $userHeaders = @{ apikey = $key; Authorization = "Bearer $sessionToken"; "Content-Type" = "application/json" }
    $result = Invoke-RestMethod -Method Post -Uri "$uriBase/functions/v1/motolink-delete-session" -Headers $userHeaders -Body "{}"
    if ($result.deleted -ne $true) {
        throw "Il backend non ha confermato deleted=true. NON considerare il test passato."
    }
    $deleted = $true

    # Probe the deleted identity using only its now-stale token; no admin key.
    $stillExists = $false
    try {
        $null = Invoke-RestMethod -Method Get -Uri "$uriBase/auth/v1/user" -Headers $userHeaders
        $stillExists = $true
    } catch {
        $status = Get-StatusCode $_
        if ($status -ne 401 -and $status -ne 403 -and $status -ne 404) {
            throw "Impossibile verificare assenza identita (HTTP $status)."
        }
    }
    if ($stillExists) { throw "L'identita di prova risulta ancora presente. STOP." }
    Write-Host "PASS: il server ha confermato deleted=true e Auth non trova piu l'identita di prova."
    Write-Host "PASS FINALE: prova end-to-end con account disposable. Nessuna identita preesistente selezionata."
} catch {
    Write-Host "FAIL: $($_.Exception.Message)"
    if ($created -and -not $deleted) {
        Write-Host "ATTENZIONE: un account ANONIMO DI PROVA potrebbe essere rimasto su Supabase."
        Write-Host "Riferimento esclusivamente per verifica amministrativa del test: $testUserId"
        Write-Host "Non eliminare altri account, neppure per ripulire automaticamente."
    }
    throw
} finally {
    $sessionToken = $null
    $key = $null
    $publicHeaders = $null
}
