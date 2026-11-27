# =============================================================================
# Merqo - the sign out through the local Vite proxy, the way the browser does it
#
# The script walks the four requests the browser makes:
#
#   1. GET  /api/v1/auth/status   the page asks who am I
#   2. POST /api/v1/demo/login    the sign in
#   3. GET  /api/v1/auth/me       who am I now
#   4. POST /api/v1/auth/logout   the sign out
#   5. GET  /api/v1/auth/me       who am I now
#
# After the sign in the script builds a strict jar that holds only the cookies
# the sign in answer returned, and every later request travels with that jar
# and nothing else. A browser behaves the same way, so the script catches the
# bug that joins the two Set-Cookie headers into one.
#
# The script never prints a cookie value.
#
# Usage:
#   tools\local-signout-check.ps1 -BaseUrl http://localhost:5173
# =============================================================================

[CmdletBinding()]
param(
    [string] $BaseUrl = 'http://localhost:5173'
)

$ErrorActionPreference = 'Continue'

$BaseUrl = $BaseUrl.TrimEnd('/')
$ApiUrl = $BaseUrl + '/api/v1'

# The jar of the sign in. It is only used to read the CSRF token of the first
# answer.
$login = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# The jar that holds only the cookies of the sign in answer.
$script:Strict = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# The Set-Cookie headers of the sign in answer.
$script:LoginSetCookies = @()

# ---------------------------------------------------------------------------
# Reads every Set-Cookie header of an answer.
#
# <p><b>Why the indexer is not enough.</b> The indexer of the header
# collection joins the values of a repeated header into one string with a
# comma. The answer of the sign in carries two Set-Cookie headers, so the
# indexer hands back one string that holds both cookies. The values are read
# one by one with GetValues instead, so every cookie keeps its own line, the
# same way the browser reads them.
#
# @param $Response the answer of the request
# @returns the value of every Set-Cookie header
# ---------------------------------------------------------------------------
function Get-SetCookieHeaders {
    param($Response)

    $values = @()
    try {
        $values = @($Response.BaseResponse.Headers.GetValues('Set-Cookie'))
    } catch {
        $values = @()
    }
    if ($values.Count -eq 0) {
        try {
            $values = @($Response.Headers['Set-Cookie'])
        } catch {
            $values = @()
        }
    }
    return $values
}

# ---------------------------------------------------------------------------
# The value of one cookie in a jar.
#
# @param $Session the jar
# @param $Name the name of the cookie
# @returns the value, or an empty string when the jar does not carry it
# ---------------------------------------------------------------------------
function Get-CookieValue {
    param(
        $Session,
        [string] $Name
    )

    try {
        $cookie = $Session.Cookies.GetCookies($ApiUrl) |
            Where-Object { $_.Name -eq $Name } | Select-Object -First 1
        if ($cookie) { return $cookie.Value }
    } catch {
    }
    return ''
}

# ---------------------------------------------------------------------------
# Builds the strict jar: it holds only the cookies of the sign in answer.
#
# The name of every cookie is printed, never a value.
#
# @param $SetCookies the Set-Cookie headers of the sign in answer
# @returns the jar
# ---------------------------------------------------------------------------
function New-StrictSession {
    param([string[]] $SetCookies)

    $jar = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $cookieHost = ([Uri] $ApiUrl).Host

    foreach ($line in $SetCookies) {
        if (-not $line) { continue }
        # Only the name and the value of the cookie itself, never the flags.
        $first = ($line -split ';')[0]
        $parts = $first -split '=', 2
        if ($parts.Count -lt 2) { continue }
        try {
            $jar.Cookies.Add(
                (New-Object System.Net.Cookie($parts[0].Trim(), $parts[1], '/', $cookieHost)))
        } catch {
            # A cookie the jar cannot hold is skipped, and the step that needs
            # it fails with a clear message.
        }
    }
    return $jar
}

# ---------------------------------------------------------------------------
# One request.
#
# The cookies of the jar travel with the request and the cookies of the answer
# are kept, so the session cookie and the CSRF cookie behave exactly like they
# behave in the browser.
#
# @param $Label the name of the step, shown in the output
# @param $Uri the address of the request
# @param $Method the method of the request
# @param $Body the body of the request
# @param $Strict whether the strict jar is used instead of the sign in jar
# @returns nothing
# ---------------------------------------------------------------------------
function Invoke-Api {
    param(
        [string] $Label,
        [string] $Uri,
        [string] $Method = 'GET',
        [string] $Body = '',
        [switch] $Strict,
        [switch] $Capture
    )

    $session = if ($Strict) { $script:Strict } else { $login }
    $headers = @{ 'Accept' = 'application/json' }
    if ($Method -ne 'GET') {
        $token = Get-CookieValue -Session $session -Name 'XSRF-TOKEN'
        if ($token) { $headers['X-XSRF-TOKEN'] = $token }
    }

    $parameters = @{
        Uri             = $Uri
        Method          = $Method
        Headers         = $headers
        WebSession      = $session
        UseBasicParsing = $true
        TimeoutSec      = 30
        ErrorAction     = 'Stop'
    }
    if ($Method -ne 'GET') {
        $parameters['Body'] = $Body
        $parameters['ContentType'] = 'application/json'
    }

    $status = 0
    $setCookies = @()
    try {
        $response = Invoke-WebRequest @parameters
        $status = [int] $response.StatusCode
        $setCookies = Get-SetCookieHeaders -Response $response
    } catch {
        $status = 0
        if ($_.Exception.Response) {
            try { $status = [int] $_.Exception.Response.StatusCode } catch { $status = 0 }
        }
    }

    Write-Output ("[" + $Label + "] status=" + $status)
    foreach ($line in $setCookies) {
        if (-not $line) { continue }
        $parts = (($line -split ';')[0]) -split '=', 2
        Write-Output ("    Set-Cookie " + $parts[0].Trim() + " hasValue=" + (($parts.Count -ge 2) -and $parts[1].Length -gt 0))
    }
    if ($setCookies.Count -gt 0) {
        Write-Output ("    the answer carried " + $setCookies.Count + " Set-Cookie header(s)")
    }

    if ($Capture) {
        $script:LoginSetCookies = $setCookies
    }
}

Write-Output '=== the sign out through the local proxy ==='
Write-Output ('base: ' + $ApiUrl)
Write-Output ''

# The first call hands out the CSRF cookie.
Invoke-Api -Label '1 status' -Uri ($ApiUrl + '/auth/status')

Invoke-Api -Label '2 login' -Uri ($ApiUrl + '/demo/login') -Method 'POST' `
    -Body '{ "accountKey": "owner" }' -Capture

# The strict jar holds only the cookies the sign in answer returned.
$script:Strict = New-StrictSession -SetCookies $script:LoginSetCookies
$cookieNames = @()
try {
    foreach ($cookie in $script:Strict.Cookies.GetCookies($ApiUrl)) {
        $cookieNames += $cookie.Name
    }
} catch {
}
Write-Output ('    the strict jar holds: ' + ($cookieNames -join ', '))
Write-Output ''

Invoke-Api -Label '3 me' -Uri ($ApiUrl + '/auth/me') -Strict
Invoke-Api -Label '4 sign out' -Uri ($ApiUrl + '/auth/logout') -Method 'POST' -Body '{}' -Strict
Invoke-Api -Label '5 me after the sign out' -Uri ($ApiUrl + '/auth/me') -Strict
