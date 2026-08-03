<#
.SYNOPSIS
    Generate the public client configuration file for the download-workbench
    layout preference survey (PostHog API Survey integration).

.DESCRIPTION
    Writes a browser-readable static JavaScript file that exposes
    window.PixivLayoutFeedbackPublicConfig as an Object.freeze() object.

    Three-state build semantics (all values are trimmed before validation):
      - all four values empty -> enabled=false configuration (no PostHog
        access; this is the default for source builds and ordinary forks);
      - all four values present and valid -> enabled=true configuration
        (official releases injected through GitHub Actions Repository
        Variables);
      - only one to three values present -> hard failure that lists the
        missing fields (a half-configured plugin must never be built).

    All four values are PUBLIC client configuration. They end up in browser
    JavaScript, browser dev tools, network requests, plugin jars, official
    packages, GitHub Actions Repository Variables, and generated static
    configuration files. They are not secrets and must never be treated as
    credentials. Genuinely secret PostHog management credentials (Personal
    API Key / Service Account Token / Organization keys) must never be passed
    to this script.

    The generated file is written as UTF-8 without BOM and uses JSON escaping.
    The script itself is ASCII-only and must stay that way (it runs under
    Windows PowerShell 5.1 which decodes BOM-less non-ASCII scripts with the
    system ANSI code page).

    Values may be supplied either as named parameters or through the
    PIXIV_LAYOUT_SURVEY_* environment variables (the Maven integration and the
    plugin release publisher pass values through environment variables to
    avoid command-line quoting issues). Explicit parameters win over the
    environment.

.PARAMETER ProjectToken
    PostHog browser Project token. Public client configuration.

.PARAMETER SurveyId
    PostHog API Survey id. Public resource identifier.

.PARAMETER ApiHost
    PostHog data-receiving host (or first-party reverse proxy). Must be an
    absolute URL; HTTPS is required for production builds, plain HTTP is only
    allowed for localhost / 127.0.0.1 / [::1] development hosts.

.PARAMETER UiHost
    PostHog console host for the actual region (e.g. US or EU Cloud). Same URL
    rules as ApiHost.

.PARAMETER PropertiesFile
    Optional path to a local packaging-only properties file (for example
    scripts/properties/posthog.properties). When present, the four values are
    read from that file instead of the parameters / environment variables, and
    the PIXIV_LAYOUT_SURVEY_* environment variables are ignored. The file must
    contain exactly the four keys
      pixiv.layout-survey.project-token
      pixiv.layout-survey.survey-id
      pixiv.layout-survey.api-host
      pixiv.layout-survey.ui-host
    with non-empty values; unknown, duplicate, misspelled, missing or
    placeholder keys fail the build. Values are trimmed, the first '=' on each
    line splits key and value (later '=' characters stay in the value), blank
    lines and '#' / '!' comment lines are ignored, and multi-line continuation
    is not supported. This mode is for LOCAL packaging only: it must never be
    used by GitHub Actions, which continues to use the Repository Variables.

.PARAMETER OutputPath
    Destination path of the generated public-config.js file.

.PARAMETER RequireConfig
    When set, an empty configuration is also a failure (all four values must
    be present). Used by official upstream release flows so that an official
    build can never ship without the survey configuration. Not a user-facing
    feature switch.

.EXAMPLE
    powershell -NoProfile -NonInteractive -File scripts/generate-layout-survey-public-config.ps1 `
        -OutputPath build/public-config.js

    Generates the default enabled=false configuration.
#>
[CmdletBinding()]
param(
    [string]$ProjectToken,
    [string]$SurveyId,
    [string]$ApiHost,
    [string]$UiHost,
    [string]$PropertiesFile,
    [string]$OutputPath,
    [switch]$RequireConfig
)

$ErrorActionPreference = "Stop"
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
# Set to $true while values come from a PropertiesFile: validation errors then
# never echo configuration values, only field/key names.
$PropertiesMode = $false

function Assert-NoControlCharacters {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory = $true)][string]$FieldName
    )
    foreach ($ch in $Value.ToCharArray()) {
        $code = [int]$ch
        if ($code -lt 0x20 -or $code -eq 0x7F) {
            throw "$FieldName contains a control character (U+$($code.ToString('X4'))); it cannot be used in the layout survey public configuration."
        }
    }
}

function Test-ProjectToken {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value.Length -gt 512) {
        throw "ProjectToken is longer than 512 characters; refusing to build the layout survey public configuration with it."
    }
    Assert-NoControlCharacters $Value "ProjectToken"
    if ($Value -match "[\r\n]") {
        throw "ProjectToken must not contain newlines."
    }
}

function Test-SurveyId {
    param([Parameter(Mandatory = $true)][string]$Value)
    if ($Value.Length -gt 128) {
        throw "SurveyId is longer than 128 characters; refusing to build the layout survey public configuration with it."
    }
    Assert-NoControlCharacters $Value "SurveyId"
    $uuidPattern = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    $loosePattern = "^[A-Za-z0-9_-]{8,128}$"
    if ($Value -notmatch $uuidPattern -and $Value -notmatch $loosePattern) {
        $valueContext = ""
        if (-not $PropertiesMode) { $valueContext = " '$Value'" }
        throw "SurveyId$valueContext does not match the PostHog survey id shape (UUID or a safe alphanumeric token); refusing to build the layout survey public configuration with it."
    }
}

function Test-WebHost {
    param(
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value,
        [Parameter(Mandatory = $true)][string]$FieldName
    )
    $valueContext = ""
    if (-not $PropertiesMode) { $valueContext = " '$Value'" }
    if ($Value.Length -gt 1024) {
        throw "$FieldName is longer than 1024 characters."
    }
    Assert-NoControlCharacters $Value $FieldName
    $uri = $null
    if (-not [System.Uri]::TryCreate($Value, [System.UriKind]::Absolute, [ref]$uri)) {
        throw "$FieldName$valueContext is not an absolute URL."
    }
    if (-not [string]::IsNullOrEmpty($uri.UserInfo)) {
        throw "$FieldName must not contain a username or password (userinfo)."
    }
    if (-not [string]::IsNullOrEmpty($uri.Fragment)) {
        throw "$FieldName must not contain a URL fragment."
    }
    $scheme = $uri.Scheme.ToLowerInvariant()
    if ($scheme -eq "http") {
        # Only loopback hosts (localhost / 127.0.0.1 / [::1] and equivalents) may
        # use plain http, and only for local development.
        if (-not $uri.IsLoopback) {
            $hostContext = ""
            if (-not $PropertiesMode) { $hostContext = " for non-local host '$($uri.Host)'" }
            throw "$FieldName uses plain http$hostContext; only http://localhost, http://127.0.0.1 and http://[::1] are allowed for local development. Production builds must use https."
        }
    } elseif ($scheme -ne "https") {
        throw "$FieldName has unsupported scheme '$scheme'; only https (and local http://localhost / http://127.0.0.1 / http://[::1]) are allowed."
    }
    return $uri
}

function ConvertTo-JsStringLiteral {
    param([Parameter(Mandatory = $true)][AllowEmptyString()][string]$Value)
    # JSON/JavaScript string escaping implemented with explicit characters so
    # the output never depends on the ambient ANSI code page.
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.Append('"')
    foreach ($ch in $Value.ToCharArray()) {
        $code = [int]$ch
        if ($ch -eq '"') { [void]$builder.Append('\"') }
        elseif ($ch -eq '\') { [void]$builder.Append('\\') }
        elseif ($code -eq 0x08) { [void]$builder.Append('\b') }
        elseif ($code -eq 0x0C) { [void]$builder.Append('\f') }
        elseif ($code -eq 0x0A) { [void]$builder.Append('\n') }
        elseif ($code -eq 0x0D) { [void]$builder.Append('\r') }
        elseif ($code -eq 0x09) { [void]$builder.Append('\t') }
        elseif ($code -lt 0x20 -or $code -eq 0x7F) { [void]$builder.Append('\u').Append($code.ToString('X4')) }
        else { [void]$builder.Append($ch) }
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function Write-JsConfig {
    param(
        [Parameter(Mandatory = $true)][bool]$Enabled,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ProjectToken,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$SurveyId,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$ApiHost,
        [Parameter(Mandatory = $true)][AllowEmptyString()][string]$UiHost
    )
    # "This is a public client configuration file, not a secret." rendered from
    # Base64 so this ASCII-only script can emit the Chinese note without
    # relying on the ANSI code page.
    $noteZh = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("6L+Z5piv5YWs5byA5a6i5oi356uv6YWN572u77yM5LiN5pivIFNlY3JldOOAgg=="))
    $enabledLiteral = if ($Enabled) { "true" } else { "false" }
    # Evaluate the string literals before building the array: PowerShell 5.1
    # mis-parses function calls inside @() literals combined with the +
    # operator, so each value is interpolated as a plain variable instead.
    $projectTokenLiteral = ConvertTo-JsStringLiteral $ProjectToken
    $surveyIdLiteral = ConvertTo-JsStringLiteral $SurveyId
    $apiHostLiteral = ConvertTo-JsStringLiteral $ApiHost
    $uiHostLiteral = ConvertTo-JsStringLiteral $UiHost
    $lines = @(
        "// Generated by scripts/generate-layout-survey-public-config.ps1; do not edit.",
        "// This is a PUBLIC client configuration file. $noteZh",
        "// It is not a secret: the values appear in browser JavaScript, browser network",
        "// requests, and browser developer tools by design. Never put PostHog Personal",
        "// API Keys, Service Account Tokens, or Organization management credentials here.",
        "//",
        "// The official build injects these four values through the GitHub Actions",
        "// Repository Variables PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN, PIXIV_LAYOUT_SURVEY_ID,",
        "// PIXIV_LAYOUT_SURVEY_API_HOST and PIXIV_LAYOUT_SURVEY_UI_HOST. Source builds and",
        "// ordinary forks keep all four empty and ship enabled=false.",
        "window.PixivLayoutFeedbackPublicConfig = Object.freeze({",
        "    enabled: $enabledLiteral,",
        "    projectToken: $projectTokenLiteral,",
        "    surveyId: $surveyIdLiteral,",
        "    apiHost: $apiHostLiteral,",
        "    uiHost: $uiHostLiteral",
        "});"
    )
    $parent = Split-Path -Parent $OutputPath
    if (-not [string]::IsNullOrWhiteSpace($parent)) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    [System.IO.File]::WriteAllText($OutputPath, (($lines -join "`n") + "`n"), $Utf8NoBom)
    Write-Host "Layout survey public config generated: $OutputPath (enabled=$Enabled)"
}

# ---- main flow ----

# Prefer explicit parameters; fall back to the environment for each value.
if ([string]::IsNullOrWhiteSpace($PropertiesFile)) {
    if ([string]::IsNullOrWhiteSpace($ProjectToken)) { $ProjectToken = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN") }
    if ([string]::IsNullOrWhiteSpace($SurveyId)) { $SurveyId = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_ID") }
    if ([string]::IsNullOrWhiteSpace($ApiHost)) { $ApiHost = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_API_HOST") }
    if ([string]::IsNullOrWhiteSpace($UiHost)) { $UiHost = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_UI_HOST") }
} else {
    # PropertiesFile mode: explicit parameters must not be mixed in, and the
    # residual PIXIV_LAYOUT_SURVEY_* environment variables are deliberately
    # ignored so a stale environment can never leak values into the file mode.
    if (-not [string]::IsNullOrWhiteSpace($ProjectToken) -or
        -not [string]::IsNullOrWhiteSpace($SurveyId) -or
        -not [string]::IsNullOrWhiteSpace($ApiHost) -or
        -not [string]::IsNullOrWhiteSpace($UiHost)) {
        throw "PropertiesFile cannot be combined with the explicit ProjectToken / SurveyId / ApiHost / UiHost parameters. Pass exactly one configuration source."
    }
    $allowedKeys = @(
        "pixiv.layout-survey.project-token",
        "pixiv.layout-survey.survey-id",
        "pixiv.layout-survey.api-host",
        "pixiv.layout-survey.ui-host"
    )
    $PropertiesMode = $true
    $filePath = $PropertiesFile.Trim()
    if (-not (Test-Path -LiteralPath $filePath -PathType Leaf)) {
        throw "PropertiesFile does not exist or is not a regular file: $filePath"
    }
    $lines = [System.IO.File]::ReadAllLines(
        $filePath,
        (New-Object System.Text.UTF8Encoding($false, $true)))
    $values = @{}
    for ($lineIndex = 0; $lineIndex -lt $lines.Length; $lineIndex++) {
        $line = $lines[$lineIndex]
        $trimmed = $line.Trim()
        if ($trimmed.Length -eq 0) { continue }
        if ($trimmed.StartsWith("#") -or $trimmed.StartsWith("!")) { continue }
        if ($trimmed.EndsWith("\")) {
            throw "posthog.properties line $($lineIndex + 1) uses unsupported multi-line continuation (trailing backslash); only single-line key=value entries are supported."
        }
        $separatorIndex = $trimmed.IndexOf("=")
        if ($separatorIndex -lt 1) {
            throw "posthog.properties line $($lineIndex + 1) is not a key=value entry."
        }
        $key = $trimmed.Substring(0, $separatorIndex).Trim()
        $value = $trimmed.Substring($separatorIndex + 1).Trim()
        if ($allowedKeys -notcontains $key) {
            throw "posthog.properties contains unknown key '$key'; only pixiv.layout-survey.project-token / survey-id / api-host / ui-host are accepted."
        }
        if ($values.ContainsKey($key)) {
            throw "posthog.properties contains duplicate key '$key'."
        }
        if ([string]::IsNullOrWhiteSpace($value)) {
            throw "posthog.properties key '$key' has an empty value; all four keys must be filled."
        }
        $values[$key] = $value
    }
    $missingKeys = @($allowedKeys | Where-Object { -not $values.ContainsKey($_) })
    if ($missingKeys.Count -gt 0) {
        throw "posthog.properties is missing required key(s): $($missingKeys -join ', ')."
    }
    foreach ($key in $allowedKeys) {
        if ($values[$key] -in @("project-token", "survey-id", "api-host", "ui-host")) {
            $placeholderNote = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("cG9zdGhvZy5wcm9wZXJ0aWVzIOS7jeWMheWQq+WNoOS9jeWAvO+8jOivt+Whq+WGmeWunumZheWFrOW8gOWuouaIt+err+mFjee9ruOAgg=="))
            throw "posthog.properties key '$key' still contains a placeholder value. $placeholderNote"
        }
    }
    $ProjectToken = $values["pixiv.layout-survey.project-token"]
    $SurveyId = $values["pixiv.layout-survey.survey-id"]
    $ApiHost = $values["pixiv.layout-survey.api-host"]
    $UiHost = $values["pixiv.layout-survey.ui-host"]
}
if ([string]::IsNullOrWhiteSpace($OutputPath)) { $OutputPath = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_OUTPUT_PATH") }
if (-not $RequireConfig) {
    $RequireValue = [Environment]::GetEnvironmentVariable("PIXIV_LAYOUT_SURVEY_REQUIRE_CONFIG")
    if ($RequireValue -match "^(?i:true|1|yes)$") { $RequireConfig = $true }
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    throw "OutputPath is required (parameter or PIXIV_LAYOUT_SURVEY_OUTPUT_PATH)."
}
$OutputPath = $OutputPath.Trim()

$ProjectToken = if ($ProjectToken) { $ProjectToken.Trim() } else { "" }
$SurveyId = if ($SurveyId) { $SurveyId.Trim() } else { "" }
$ApiHost = if ($ApiHost) { $ApiHost.Trim() } else { "" }
$UiHost = if ($UiHost) { $UiHost.Trim() } else { "" }

$present = @(
    @{ Name = "ProjectToken (PIXIV_LAYOUT_SURVEY_PROJECT_TOKEN)"; Value = $ProjectToken },
    @{ Name = "SurveyId (PIXIV_LAYOUT_SURVEY_ID)"; Value = $SurveyId },
    @{ Name = "ApiHost (PIXIV_LAYOUT_SURVEY_API_HOST)"; Value = $ApiHost },
    @{ Name = "UiHost (PIXIV_LAYOUT_SURVEY_UI_HOST)"; Value = $UiHost }
)
$provided = @($present | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Value) })
$missing = @($present | Where-Object { [string]::IsNullOrWhiteSpace($_.Value) })

if ($provided.Count -eq 0) {
    if ($RequireConfig) {
        throw "Layout survey configuration is required for this build, but all four PIXIV_LAYOUT_SURVEY_* values are empty. Missing: $((($missing | ForEach-Object { $_.Name }) -join ', '))."
    }
    Write-JsConfig -Enabled $false -ProjectToken "" -SurveyId "" -ApiHost "" -UiHost ""
    exit 0
}

if ($provided.Count -lt 4) {
    throw "Layout survey configuration is incomplete: missing $((($missing | ForEach-Object { $_.Name }) -join ', ')). Provide all four values or none of them (all empty means disabled)."
}

Test-ProjectToken $ProjectToken
Test-SurveyId $SurveyId
$apiUri = Test-WebHost $ApiHost "ApiHost"
$uiUri = Test-WebHost $UiHost "UiHost"

Write-JsConfig -Enabled $true -ProjectToken $ProjectToken -SurveyId $SurveyId `
    -ApiHost $apiUri.AbsoluteUri.TrimEnd("/") -UiHost $uiUri.AbsoluteUri.TrimEnd("/")
exit 0
