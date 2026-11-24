# =============================================================================
# Merqo - check the live deployment.
#
# The script answers one question: does the deployed site work?
#
# It checks, in order:
#   1. the frontend answers and serves the app,
#   2. the backend answers the ping and the database is reachable,
#   3. the frontend proxy forwards /api to the backend (one origin),
#   4. demo mode is on and the demo accounts are there,
#   5. a demo login works: the session cookie is written and it is accepted.
#
# It never writes anything. It only reads and signs in as a demo account.
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

    # The address of the deployed backend. The default is the Render URL.
    [string] $BackendUrl = 'https://merqo-api.onrender.com',

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
# One request. The answer is the body, the status is written to $Status.
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
# ---------------------------------------------------------------------------
Write-Head 'Step 2 - the backend answers'

$backendHost = ([Uri] $BackendUrl).Host
$backendDns = Get-DnsName $backendHost
if (-not $backendDns) {
    Write-Fail ("the backend host " + $backendHost + " does not exist in DNS.")
    Write-Info 'The Render service is not created yet.'
} else {
    Write-Pass ("the backend host resolves (" + $backendDns + ")")

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
        Write-Fail ('the backend answered ' + $script:Status)
        Write-Info 'A 404 here usually means no service is bound to this name yet.'
        Write-Info $script:Body
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
# Step 5 - a demo login works.
# ---------------------------------------------------------------------------
Write-Head 'Step 5 - the demo login works'

# The first call gives the browser the CSRF cookie. The token then travels
# back in the header, exactly like the browser does it.
Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/status') -Session $FrontendSession
$csrfToken = Get-CookieValue -Session $FrontendSession -BaseUrl $FrontendUrl -Name 'XSRF-TOKEN'
if ($csrfToken) {
    Write-Pass 'the CSRF cookie was handed out'
} else {
    Write-Fail 'no CSRF cookie was handed out, so every request that changes something is refused'
}

$loginBody = '{ "accountKey": "' + $AccountKey + '" }'
Invoke-Check -Uri ($FrontendUrl + '/api/v1/demo/login') -Method 'POST' -Body $loginBody `
    -Headers @{ 'X-XSRF-TOKEN' = $csrfToken } -Session $FrontendSession

if ($script:Status -eq 200) {
    Write-Pass ('the demo login as ' + $AccountKey + ' worked')
    if ($script:Body -match '"accountType"\s*:\s*"([A-Z]+)"') {
        Write-Info ('the signed in account type: ' + $Matches[1])
    }

    $sessionCookie = Get-CookieValue -Session $FrontendSession -BaseUrl $FrontendUrl -Name 'MERQO_SESSION'
    if ($sessionCookie) {
        Write-Pass 'the session cookie was written'
    } else {
        Write-Fail 'no session cookie was written, so the browser stays signed out'
    }

    if ($sessionCookie) {
        Invoke-Check -Uri ($FrontendUrl + '/api/v1/auth/me') -Session $FrontendSession
        if ($script:Status -eq 200) {
            Write-Pass 'the session cookie is accepted on the next request'
        } else {
            Write-Fail ('the session cookie was refused (' + $script:Status + ')')
            Write-Info $script:Body
        }
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
