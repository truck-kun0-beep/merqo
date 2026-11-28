# =============================================================================
# Merqo - check the live deployment.
#
# The script answers one question: does the deployed site work, all the way
# through the requests that change something?
#
# It checks, in order:
#   1. the frontend answers and serves the app,
#   2. the backend answers the ping and the database is reachable,
#   3. the frontend proxy forwards /api to the backend (one origin),
#   4. demo mode is on and the demo accounts are there,
#   5. the demo login works and hands out both cookies,
#   6. GET /auth/me is accepted,
#   7. a user is created (the first POST after the login),
#   8. that user is deactivated (a second POST),
#   9. the sign out works (a third POST).
#
# Steps 7 to 9 are the ones that broke on the live site: the sign in worked,
# but every request that changes something was refused with a 403, because the
# answer to the sign in did not hand out the CSRF cookie.
#
# <b>The jar is strict.</b> After the sign in the script builds a fresh jar that
# holds only the cookies of the sign in answer, and every later request travels
# with that jar and nothing else. A browser behaves the same way when the
# cookie of the session is replaced, and the script therefore catches the bug
# even on a browser that still had an old CSRF cookie lying around.
#
# It never prints a secret: no cookie value, no token, no password. It only
# prints the names of the cookies it found.
#
# Usage:
#   scripts\check-live.ps1
#   scripts\check-live.ps1 -FrontendUrl https://my-app.pages.dev
#   scripts\check-live.ps1 -FrontendUrl https://my-app.pages.dev -BackendUrl https://my-api.onrender.com
#
# Exit code 0 means every check passed. Any other number means the site is not
# ready, and the output says which check failed and why.
#
# The cookies travel in a session container, exactly like they travel in the
# browser. That matters for the CSRF token: the backend compares the token in
# the header with the token in the cookie, so both have to be sent.
# =============================================================================

[CmdletBinding()]
param(
    # The address of the deployed frontend. The default is the Pages URL.
    [string] $FrontendUrl = 'https://merqo.pages.dev',

    # The address of the deployed backend. The default is the Render URL. The
    # direct check is only a diagnosis: the proxy check below is the one that
    # matters, because the browser only ever talks to the Pages URL.
    [string] $BackendUrl = 'https://merqo.onrender.com',

    # The demo account to sign in with. The default is the owner, who sees
    # every branch and every permission.
    [string] $AccountKey = 'owner',

    # How long a single request may take. The free backend sleeps after
    # fifteen idle minutes, so the first request can take about a minute.
    [int] $TimeoutSeconds = 90
)

$ErrorActionPreference = 'Stop'

$FrontendUrl = $FrontendUrl.TrimEnd('/')
$BackendUrl = $BackendUrl.TrimEnd('/')

# The number of failed checks. The exit code is this number, so a run that
# fails says how many things are wrong.
$script:Failed = 0

# The cookies of the browser. One container for the frontend origin, because
# that is the origin the browser uses. The backend is only called directly for
# a diagnosis, so it gets its own container.
$FrontendSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$BackendSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

# The jar that holds only the cookies of the sign in answer. It is built after
# the sign in and every later request travels with it.
$script:StrictSession = New-Object Microsoft.PowerShell.Commands.WebRequestSession

function Write-Head {
    Write-Output ''
    Write-Output '============================================================'
    Write-Output $1
    Write-Output '============================================================'
}

function Write-Pass {
    param([string] $Message)
    Write-Output ("  [PASS] " + $Message)
}

function Write-Fail {
    param([string] $Message)
    $script:Failed++
    Write-Output ("  [FAIL] " + $Message)
}

function Write-Info {
    param([string] $Message)
    Write-Output ("  [INFO] " + $Message)
}

# ---------------------------------------------------------------------------
# The name the host resolves to, or an empty string when it does not exist.
# ---------------------------------------------------------------------------
function Get-DnsName {
    param([string] $HostName)
    try {
        $records = Resolve-DnsName -Name $HostName -Type CNAME -ErrorAction Stop
        $cname = $records | Where-Object { $_.NameHost } | Select-Object -First 1
        if ($cname) {
            return $cname.NameHost
        }
    } catch {
        # The host has no CNAME. The address record is checked below.
    }
    try {
        $records = Resolve-DnsName -Name $HostName -ErrorAction Stop
        $address = $records | Where-Object { $_.IPAddress } | Select-Object -First 1
        if ($address) {
            return $address.IPAddress
        }
    } catch {
        return ''
    }
    return ''
}

# ---------------------------------------------------------------------------
# Reads every Set-Cookie header of an answer.
#
# <p><b>Why the indexer is not enough.</b> The indexer of the header
# collection joins the values of a repeated header into one string with a
# comma. The answer of the sign in carries two Set-Cookie headers, so the
# indexer hands back one string that holds both cookies and the jar built
# from it holds neither. The values are read one by one with GetValues
# instead, so every cookie keeps its own line.
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
# One request. The answer is the body, the status is written to $Status and the
# Set-Cookie headers are written to $SetCookies.
#
# The cookies of the session travel with the request and the cookies of the
# answer are kept in the session, so the session cookie and the CSRF cookie
# behave exactly like they behave in the browser.
# ---------------------------------------------------------------------------
function Invoke-Check {
    param(
        [string] $Uri,
        [string] $Method = 'GET',
        [string] $Body,
        [hashtable] $Headers = @{},
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session
    )

    $script:Status = 0
    $script:Body = ''
    $script:SetCookies = @()

    $requestHeaders = @{ 'Accept' = 'application/json, text/plain, */*' }
    foreach ($key in $Headers.Keys) {
        $requestHeaders[$key] = $Headers[$key]
    }

    $parameters = @{
        Uri             = $Uri
        Method          = $Method
        Headers         = $requestHeaders
        TimeoutSec      = $TimeoutSeconds
        UseBasicParsing = $true
        ErrorAction     = 'Stop'
    }
    if ($Session) {
        $parameters['WebSession'] = $Session
    }
    if ($Body) {
        $parameters['Body'] = $Body
        $parameters['ContentType'] = 'application/json'
    }

    try {
        $response = Invoke-WebRequest @parameters
        $script:Status = [int] $response.StatusCode
        $script:Body = $response.Content
        $script:SetCookies = Get-SetCookieHeaders -Response $response
    } catch {
        # Invoke-WebRequest throws for every status above 299. The answer is
        # still in the exception, so the body is read from there.
        $exception = $_.Exception
        if ($exception.Response) {
            try {
                $script:Status = [int] $exception.Response.StatusCode
            } catch {
                $script:Status = 0
            }
            try {
                $reader = New-Object System.IO.StreamReader($exception.Response.GetResponseStream())
                $script:Body = $reader.ReadToEnd()
                $reader.Close()
            } catch {
                $script:Body = ''
            }
        } else {
            $script:Status = 0
            $script:Body = $exception.Message
        }
    }
}

# ---------------------------------------------------------------------------
# The value of one cookie in a session, or an empty string when the session
# does not carry it.
# ---------------------------------------------------------------------------
function Get-CookieValue {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session,
        [string] $BaseUrl,
        [string] $Name
    )

    if (-not $Session) {
        return ''
    }
    try {
        $cookie = $Session.Cookies.GetCookies($BaseUrl) | Where-Object { $_.Name -eq $Name } |
            Select-Object -First 1
        if ($cookie) {
            return $cookie.Value
        }
    } catch {
        return ''
    }
    return ''
}

# ---------------------------------------------------------------------------
# The names of the cookies in a session. Only the names are printed, never the
# values, so the output of the script may be pasted into a ticket.
# ---------------------------------------------------------------------------
function Get-CookieNames {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session,
        [string] $BaseUrl
    )

    try {
        return (($Session.Cookies.GetCookies($BaseUrl) |
                Select-Object -ExpandProperty Name -Unique) -join ', ')
    } catch {
        return ''
    }
}

# ---------------------------------------------------------------------------
# The CSRF token of the session, sent back the way the browser sends it.
#
# The token is read from the cookie and put into the header. It is never
# printed.
# ---------------------------------------------------------------------------
function Get-CsrfHeader {
    param(
        [Microsoft.PowerShell.Commands.WebRequestSession] $Session,
        [string] $BaseUrl
    )

    $token = Get-CookieValue -Session $Session -BaseUrl $BaseUrl -Name 'XSRF-TOKEN'
    if (-not $token) {
        return @{}
    }
    return @{ 'X-XSRF-TOKEN' = $token }
}

# ---------------------------------------------------------------------------
# Builds the strict jar: it holds only the cookies of the sign in answer.
#
# The name of every cookie is printed, never a value.
# ---------------------------------------------------------------------------
function New-StrictSession {
    param([string[]] $SetCookies, [string] $BaseUrl)

    $jar = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $cookieHost = ([Uri] $BaseUrl).Host

    foreach ($line in $SetCookies) {
        if (-not $line) {
            continue
        }
        # Only the name and the value of the cookie itself, never the flags.
        $first = ($line -split ';')[0]
        $parts = $first -split '=', 2
        if ($parts.Count -lt 2) {
            continue
        }
        $name = $parts[0].Trim()
        $value = $parts[1]
        try {
            $jar.Cookies.Add((New-Object System.Net.Cookie($name, $value, '/', $cookieHost)))
        } catch {
            # A cookie the jar cannot hold is skipped, and the step that needs
            # it fails with a clear message.
        }
    }
    return $jar
}

Write-Head 'Merqo live check'
Write-Output ("  Frontend : " + $FrontendUrl)
Write-Output ("  Backend  : " + $BackendUrl)
Write-Output ("  Account  : $AccountKey")

# ---------------------------------------------------------------------------
# Step 1 - the frontend answers.
# ---------------------------------------------------------------------------
Write-Head 'Step 1 - the frontend answers'

$frontendHost = ([Uri] $FrontendUrl).Host
$frontendDns = Get-DnsName $frontendHost
if (-not $frontendDns) {
    Write-Fail ("the frontend host " + $frontendHost + " does not exist in DNS.")
    Write-Info ('The Cloudflare Pages project is not created yet, or the project name is not ' + $frontendHost + '.')
    Write-Info 'Create it in the Cloudflare dashboard, then run this script again.'
} else {
    Write-Pass ("the frontend host resolves (" + $frontendDns + ")")

    Invoke-Check -Uri ($FrontendUrl + '/') -Session $FrontendSession
    if ($script:Status -eq 200) {
        Write-Pass ('the frontend answers 200 (' + $script:Body.Length + ' bytes)')
        if ($script:Body -match '<div id="root">') {
            Write-Pass 'the page is the Merqo application'
        } else {
            Write-Fail 'the page has no root element, so it is not the Merqo application'
        }
    } else {
        Write-Fail ('the frontend answered ' + $script:Status)
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# Step 2 - the backend answers and the database is reachable.
#
# This call goes straight to the backend. It is a diagnosis, not a requirement:
# the browser only ever talks to the Pages URL. A wrong address here is
# therefore only a note, as long as the proxy check below works.
# ---------------------------------------------------------------------------
Write-Head 'Step 2 - the backend answers'

$backendHost = ([Uri] $BackendUrl).Host
$backendDns = Get-DnsName $backendHost
if (-not $backendDns) {
    Write-Info ("the backend host " + $backendHost + " does not exist in DNS, so it is skipped.")
    Write-Info 'The direct check is only a diagnosis. The proxy check in step 3 is the one that matters.'
} else {
    Write-Info ("the backend host resolves (" + $backendDns + ")")

    Invoke-Check -Uri ($BackendUrl + '/api/v1/health/ping') -Session $BackendSession
    if ($script:Status -eq 200) {
        Write-Pass 'the backend answers the ping'
        if ($script:Body -match '"pong"\s*:\s*true') {
            Write-Pass 'the ping says pong'
        } else {
            Write-Fail 'the ping does not say pong'
            Write-Info $script:Body
        }
        if ($script:Body -match '"database"\s*:\s*\{[^}]*"status"\s*:\s*"UP"') {
            Write-Pass 'the database is reachable'
        } else {
            Write-Fail 'the database is not reachable, so the backend cannot do anything'
            Write-Info $script:Body
        }
    } else {
        Write-Info ('the direct call answered ' + $script:Status + ', so it is skipped.')
        Write-Info 'The direct check is only a diagnosis. The proxy check in step 3 is the one that matters.'
        Write-Info 'Check API_BASE_URL in the Cloudflare dashboard if the proxy check fails too.'
    }
}

# ---------------------------------------------------------------------------
# Step 3 - the proxy forwards /api to the backend.
# ---------------------------------------------------------------------------
Write-Head 'Step 3 - the proxy forwards /api to the backend'

Invoke-Check -Uri ($FrontendUrl + '/api/v1/health/ping') -Session $FrontendSession
if ($script:Status -eq 200 -and $script:Body -match '"pong"\s*:\s*true') {
    Write-Pass 'the frontend proxy reaches the backend'
} elseif ($script:Status -eq 500 -and $script:Body -match 'PROXY_NOT_CONFIGURED') {
    Write-Fail 'the Pages project has no API_BASE_URL'
    Write-Info 'Set API_BASE_URL in the Cloudflare dashboard, then trigger a new deploy.'
} elseif ($script:Status -in @(502, 503, 504)) {
    Write-Fail ('the proxy could not reach the backend (' + $script:Status + ')')
    Write-Info 'The backend is probably asleep. Wait a minute and run this script again.'
    Write-Info $script:Body
} else {
    Write-Fail ('the proxy answered ' + $script:Status)
    Write-Info $script:Body
}

# ---------------------------------------------------------------------------
# Step 4 - demo mode is on and the demo accounts are there.
# ---------------------------------------------------------------------------
Write-Head 'Step 4 - demo mode is on'

Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/status') -Session $FrontendSession
if ($script:Status -eq 200) {
    Write-Pass 'the setup status answers'
    if ($script:Body -match '"demoMode"\s*:\s*true') {
        Write-Pass 'demo mode is on'
    } else {
        Write-Fail 'demo mode is off, so the one click demo login is not there'
        Write-Info 'Set DEMO_MODE=true in the Render environment, then let Render restart.'
        Write-Info $script:Body
    }
} else {
    Write-Fail ('the setup status answered ' + $script:Status)
    Write-Info $script:Body
}

Invoke-Check -Uri ($FrontendUrl + '/api/v1/demo/accounts') -Session $FrontendSession
if ($script:Status -eq 200) {
    Write-Pass 'the demo accounts answer'
    $accountCount = ([regex]::Matches($script:Body, '"accountKey"')).Count
    Write-Info ('the demo accounts found: ' + $accountCount)
} else {
    Write-Fail ('the demo accounts answered ' + $script:Status)
    Write-Info $script:Body
}

# ---------------------------------------------------------------------------
# Step 5 - the demo login works and hands out both cookies.
# ---------------------------------------------------------------------------
Write-Head 'Step 5 - the demo login works'

# The first call gives the browser the CSRF cookie. The token then travels
# back in the header, exactly like the browser does it.
Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/status') -Session $FrontendSession
if (Get-CookieValue -Session $FrontendSession -BaseUrl $FrontendUrl -Name 'XSRF-TOKEN') {
    Write-Pass 'the CSRF cookie was handed out'
} else {
    Write-Fail 'no CSRF cookie was handed out, so every request that changes something is refused'
}

$loginBody = '{ "accountKey": "' + $AccountKey + '" }'
Invoke-Check -Uri ($FrontendUrl + '/api/v1/demo/login') -Method 'POST' -Body $loginBody `
    -Headers (Get-CsrfHeader -Session $FrontendSession -BaseUrl $FrontendUrl) -Session $FrontendSession

$script:SignedIn = $false
$script:ProbeUserId = ''

if ($script:Status -eq 200) {
    Write-Pass ('the demo login as ' + $AccountKey + ' worked')
    $script:SignedIn = $true
    if ($script:Body -match '"accountType"\s*:\s*"([A-Z]+)"') {
        Write-Info ('the signed in account type: ' + $Matches[1])
    }

    # The strict jar holds only the cookies of this answer. Every later request
    # travels with it and with nothing else, so the script proves that the
    # answer of the login is enough.
    $script:StrictSession = New-StrictSession -SetCookies $script:SetCookies -BaseUrl $FrontendUrl
    $strictNames = Get-CookieNames -Session $script:StrictSession -BaseUrl $FrontendUrl
    Write-Info ('the cookies of the login answer: ' + $strictNames)

    if (Get-CookieValue -Session $script:StrictSession -BaseUrl $FrontendUrl -Name 'MERQO_SESSION') {
        Write-Pass 'the session cookie was written'
    } else {
        Write-Fail 'no session cookie was written, so the browser stays signed out'
        $script:SignedIn = $false
    }

    # The CSRF cookie has to be in the answer of the login as well. The browser
    # has no other way to learn the token of the session it is in.
    if (Get-CookieValue -Session $script:StrictSession -BaseUrl $FrontendUrl -Name 'XSRF-TOKEN') {
        Write-Pass 'the CSRF cookie was handed out with the login'
    } else {
        Write-Fail 'the login did not hand out the CSRF cookie, so the next POST is refused with a 403'
        Write-Info 'This is the bug that broke the live site. The answer of the login has to carry both cookies.'
        $script:SignedIn = $false
    }
} elseif ($script:Status -eq 403 -and $script:Body -match 'CSRF_TOKEN_INVALID') {
    Write-Fail 'the login was refused because the CSRF token was missing'
    Write-Info 'Hard reload the page with Ctrl+F5 and try again.'
} elseif ($script:Status -eq 404) {
    Write-Fail 'the demo login is not there'
    Write-Info 'Set DEMO_MODE=true in the Render environment, then let Render restart.'
    Write-Info $script:Body
} else {
    Write-Fail ('the demo login answered ' + $script:Status)
    Write-Info $script:Body
}

# ---------------------------------------------------------------------------
# Step 6 - GET /auth/me is accepted.
# ---------------------------------------------------------------------------
Write-Head 'Step 6 - the session is accepted'

if (-not $script:SignedIn) {
    Write-Fail 'the session could not be checked, because the login did not work'
} else {
    Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/me') -Session $script:StrictSession
    if ($script:Status -eq 200) {
        Write-Pass 'GET /auth/me is accepted'
    } else {
        Write-Fail ('GET /auth/me was refused (' + $script:Status + ')')
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# Step 7 - a user is created. This is the first POST after the login.
# ---------------------------------------------------------------------------
Write-Head 'Step 7 - a user is created'

# The name of the probe account. It has to be unique, so the time is part of
# it. It is not a secret: it is the name of an account the script is about to
# create and to deactivate again.
$probeName = 'livecheck' + (Get-Date -Format 'MMddHHmmss')

if (-not $script:SignedIn) {
    Write-Fail 'the account could not be created, because the login did not work'
} else {
    $createBody = '{ "username": "' + $probeName + '", "fullName": "Live Check", "accountType": "STAFF" }'
    Invoke-Check -Uri ($FrontendUrl + '/api/v1/users/create') -Method 'POST' -Body $createBody `
        -Headers (Get-CsrfHeader -Session $script:StrictSession -BaseUrl $FrontendUrl) `
        -Session $script:StrictSession

    if ($script:Status -eq 201) {
        Write-Pass 'POST /users/create worked (201)'

        if ($script:Body -match '"id"\s*:\s*"([0-9a-fA-F-]{36})"') {
            $script:ProbeUserId = $Matches[1]
            Write-Info ('the new account has the id ' + $script:ProbeUserId)
        } else {
            Write-Fail 'the answer of the create carries no account id, so the account cannot be deactivated'
        }
    } elseif ($script:Status -eq 403 -and $script:Body -match 'CSRF_TOKEN_INVALID') {
        Write-Fail 'POST /users/create was refused because the CSRF token was missing or wrong'
        Write-Info 'This is the bug that broke the live site. The login has to hand out the CSRF cookie.'
    } else {
        Write-Fail ('POST /users/create answered ' + $script:Status)
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# Step 8 - the new account is deactivated. This is the second POST.
# ---------------------------------------------------------------------------
Write-Head 'Step 8 - the account is deactivated'

if (-not $script:ProbeUserId) {
    Write-Fail 'the account could not be deactivated, because it was not created'
} else {
    Invoke-Check -Uri ($FrontendUrl + '/api/v1/users/' + $script:ProbeUserId + '/deactivate') `
        -Method 'POST' -Body '{}' `
        -Headers (Get-CsrfHeader -Session $script:StrictSession -BaseUrl $FrontendUrl) `
        -Session $script:StrictSession

    if ($script:Status -eq 200) {
        Write-Pass 'POST /users/{id}/deactivate worked (200)'
        if ($script:Body -match '"isActive"\s*:\s*false') {
            Write-Pass 'the account is inactive'
        } else {
            Write-Fail 'the answer does not say the account is inactive'
            Write-Info $script:Body
        }
    } elseif ($script:Status -eq 403 -and $script:Body -match 'CSRF_TOKEN_INVALID') {
        Write-Fail 'the deactivation was refused because the CSRF token was missing or wrong'
    } else {
        Write-Fail ('the deactivation answered ' + $script:Status)
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# Step 8b - Read-only Phase 5 payments, verification queue, and COD endpoints
# ---------------------------------------------------------------------------
Write-Head 'Step 8b - Phase 5 payments endpoints (read-only)'

if (-not $script:SignedIn) {
    Write-Fail 'cannot check Phase 5 payments endpoints because login did not succeed'
} else {
    Invoke-Check -Uri ($FrontendUrl + '/api/v1/payments/history?page=0&size=5') -Session $script:StrictSession
    if ($script:Status -eq 200) {
        Write-Pass 'GET /payments/history worked (200)'
    } else {
        Write-Fail ('GET /payments/history answered ' + $script:Status)
        Write-Info $script:Body
    }

    Invoke-Check -Uri ($FrontendUrl + '/api/v1/payments/pending') -Session $script:StrictSession
    if ($script:Status -eq 200) {
        Write-Pass 'GET /payments/pending worked (200)'
    } else {
        Write-Fail ('GET /payments/pending answered ' + $script:Status)
        Write-Info $script:Body
    }

    Invoke-Check -Uri ($FrontendUrl + '/api/v1/cod/outstanding') -Session $script:StrictSession
    if ($script:Status -eq 200) {
        Write-Pass 'GET /cod/outstanding worked (200)'
    } else {
        Write-Fail ('GET /cod/outstanding answered ' + $script:Status)
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# Step 9 - the sign out works. This is the third POST.
# ---------------------------------------------------------------------------
Write-Head 'Step 9 - the sign out works'

if (-not $script:SignedIn) {
    Write-Fail 'the sign out could not be checked, because the login did not work'
} else {
    Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/logout') -Method 'POST' -Body '{}' `
        -Headers (Get-CsrfHeader -Session $script:StrictSession -BaseUrl $FrontendUrl) `
        -Session $script:StrictSession

    if ($script:Status -eq 204) {
        Write-Pass 'POST /auth/logout worked (204)'
    } elseif ($script:Status -eq 403 -and $script:Body -match 'CSRF_TOKEN_INVALID') {
        Write-Fail 'the sign out was refused because the CSRF token was missing or wrong'
        Write-Info 'This is the bug that broke the live site. The login has to hand out the CSRF cookie.'
    } else {
        Write-Fail ('the sign out answered ' + $script:Status)
        Write-Info $script:Body
    }
}

# ---------------------------------------------------------------------------
# The result.
# ---------------------------------------------------------------------------
Write-Head 'The result'
if ($script:Failed -eq 0) {
    Write-Output '  Everything passed. The deployed site works and the demo login works.'
    exit 0
} else {
    Write-Output ('  ' + $script:Failed + ' check(s) failed. The deployed site is not ready yet.')
    exit 1
}
