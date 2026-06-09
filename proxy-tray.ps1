#Requires -Version 5
# proxy-tray.ps1 — AI Proxy con bandeja del sistema (Windows 10/11)
# Uso normal:      .\proxy-tray.ps1
# Modo diagnóstico (sin fragmentación TLS): .\proxy-tray.ps1 -NoSplit

param([switch]$NoSplit)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

$ScriptDir   = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProxyScript = Join-Path $ScriptDir "ai-proxy.js"

# ── Validaciones previas ──────────────────────────────────────
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    [System.Windows.Forms.MessageBox]::Show(
        "Node.js no está instalado.`n`nDescargalo desde: https://nodejs.org/",
        "AI Proxy", [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}
if (-not (Test-Path $ProxyScript)) {
    [System.Windows.Forms.MessageBox]::Show(
        "No se encontró ai-proxy.js`nCarpeta buscada: $ScriptDir",
        "AI Proxy", [System.Windows.Forms.MessageBoxButtons]::OK,
        [System.Windows.Forms.MessageBoxIcon]::Error) | Out-Null
    exit 1
}

# ── Estado global ─────────────────────────────────────────────
$script:proc    = $null
$script:domains = [System.Collections.Generic.SortedDictionary[string,hashtable]]::new()
$script:queue   = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()

# ── Íconos generados en memoria (sin archivos externos) ───────
function New-DotIcon([System.Drawing.Color]$fill) {
    $bmp = New-Object System.Drawing.Bitmap 16, 16
    $g   = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.Clear([System.Drawing.Color]::Transparent)
    $g.FillEllipse((New-Object System.Drawing.SolidBrush $fill), 2, 2, 12, 12)
    $g.Dispose()
    $icon = [System.Drawing.Icon]::FromHandle($bmp.GetHicon())
    $bmp.Dispose()
    return $icon
}
$iconOn  = New-DotIcon ([System.Drawing.Color]::FromArgb(50, 210, 50))
$iconOff = New-DotIcon ([System.Drawing.Color]::FromArgb(110, 110, 110))

# ════════════════════════════════════════════════════════════
#  FORM
# ════════════════════════════════════════════════════════════
$form = New-Object System.Windows.Forms.Form
$form.Text          = "AI Proxy"
$form.Size          = New-Object System.Drawing.Size(560, 620)
$form.MinimumSize   = New-Object System.Drawing.Size(420, 420)
$form.StartPosition = "CenterScreen"
$form.Icon          = $iconOff
$form.BackColor     = [System.Drawing.Color]::FromArgb(28, 28, 28)
$form.ForeColor     = [System.Drawing.Color]::White
$form.ShowInTaskbar = $true

# ── Header ────────────────────────────────────────────────────
$pHeader = New-Object System.Windows.Forms.Panel
$pHeader.Dock      = "Top"
$pHeader.Height    = 72
$pHeader.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 18)
$form.Controls.Add($pHeader)

$lblName = New-Object System.Windows.Forms.Label
$lblName.Text     = "AI Proxy"
$lblName.Font     = New-Object System.Drawing.Font("Segoe UI", 14, [System.Drawing.FontStyle]::Bold)
$lblName.Location = New-Object System.Drawing.Point(14, 7)
$lblName.AutoSize = $true
$pHeader.Controls.Add($lblName)

$lblDesc = New-Object System.Windows.Forms.Label
$modeText = if ($NoSplit) { "modo diagnóstico (sin fragmentación)" } else { "ChatGPT + Claude — 127.0.0.1:8889" }
$lblDesc.Text      = $modeText
$lblDesc.Font      = New-Object System.Drawing.Font("Segoe UI", 8)
$lblDesc.ForeColor = if ($NoSplit) { [System.Drawing.Color]::FromArgb(255,180,40) } else { [System.Drawing.Color]::FromArgb(120,120,120) }
$lblDesc.Location  = New-Object System.Drawing.Point(14, 40)
$lblDesc.AutoSize  = $true
$pHeader.Controls.Add($lblDesc)

$lblStatus = New-Object System.Windows.Forms.Label
$lblStatus.Text      = "● INACTIVO"
$lblStatus.Font      = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
$lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(110, 110, 110)
$lblStatus.Location  = New-Object System.Drawing.Point(240, 28)
$lblStatus.AutoSize  = $true
$pHeader.Controls.Add($lblStatus)

$btnToggle = New-Object System.Windows.Forms.Button
$btnToggle.Text      = "  Iniciar"
$btnToggle.Size      = New-Object System.Drawing.Size(90, 34)
$btnToggle.Anchor    = "Top,Right"
$btnToggle.Location  = New-Object System.Drawing.Point(455, 18)
$btnToggle.BackColor = [System.Drawing.Color]::FromArgb(40, 167, 69)
$btnToggle.ForeColor = [System.Drawing.Color]::White
$btnToggle.FlatStyle = "Flat"
$btnToggle.FlatAppearance.BorderSize = 0
$btnToggle.Font      = New-Object System.Drawing.Font("Segoe UI", 9, [System.Drawing.FontStyle]::Bold)
$pHeader.Controls.Add($btnToggle)

# ── Tabs ──────────────────────────────────────────────────────
$tabs = New-Object System.Windows.Forms.TabControl
$tabs.Dock = "Fill"
$tabs.Font = New-Object System.Drawing.Font("Segoe UI", 9)
$form.Controls.Add($tabs)

# Tab Logs
$tabLog = New-Object System.Windows.Forms.TabPage
$tabLog.Text      = "  Logs  "
$tabLog.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 18)
$tabs.Controls.Add($tabLog)

$rtb = New-Object System.Windows.Forms.RichTextBox
$rtb.Dock       = "Fill"
$rtb.BackColor  = [System.Drawing.Color]::FromArgb(12, 12, 12)
$rtb.ForeColor  = [System.Drawing.Color]::FromArgb(210, 210, 210)
$rtb.Font       = New-Object System.Drawing.Font("Consolas", 9)
$rtb.ReadOnly   = $true
$rtb.ScrollBars = "Vertical"
$rtb.WordWrap   = $false
$tabLog.Controls.Add($rtb)

# Tab Dominios
$tabDom = New-Object System.Windows.Forms.TabPage
$tabDom.Text      = "  Dominios  "
$tabDom.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 18)
$tabs.Controls.Add($tabDom)

$lv = New-Object System.Windows.Forms.ListView
$lv.Dock          = "Fill"
$lv.View          = "Details"
$lv.FullRowSelect = $true
$lv.BackColor     = [System.Drawing.Color]::FromArgb(12, 12, 12)
$lv.ForeColor     = [System.Drawing.Color]::FromArgb(210, 210, 210)
$lv.GridLines     = $true
$lv.Font          = New-Object System.Drawing.Font("Consolas", 9)
$lv.Columns.Add("Dominio",   225) | Out-Null
$lv.Columns.Add("IP destino",145) | Out-Null
$lv.Columns.Add("Conex.",     65) | Out-Null
$lv.Columns.Add("Tipo",       60) | Out-Null
$tabDom.Controls.Add($lv)

# StatusBar
$ss    = New-Object System.Windows.Forms.StatusStrip
$ss.BackColor = [System.Drawing.Color]::FromArgb(18, 18, 18)
$sslbl = New-Object System.Windows.Forms.ToolStripStatusLabel
$sslbl.ForeColor = [System.Drawing.Color]::FromArgb(120, 120, 120)
$sslbl.Text      = "Puerto 8889 | Listo"
$ss.Items.Add($sslbl) | Out-Null
$form.Controls.Add($ss)

# ════════════════════════════════════════════════════════════
#  TRAY
# ════════════════════════════════════════════════════════════
$tray = New-Object System.Windows.Forms.NotifyIcon
$tray.Icon    = $iconOff
$tray.Text    = "AI Proxy — Inactivo"
$tray.Visible = $true

$cm      = New-Object System.Windows.Forms.ContextMenuStrip
$miStart = $cm.Items.Add("▶  Iniciar proxy")
$miOpen  = $cm.Items.Add("📋  Abrir ventana")
$cm.Items.Add("-") | Out-Null
$miExit  = $cm.Items.Add("✕  Salir")
$tray.ContextMenuStrip = $cm

# ════════════════════════════════════════════════════════════
#  FUNCIONES
# ════════════════════════════════════════════════════════════
function Log-Append([string]$text, [System.Drawing.Color]$col) {
    if ($rtb.Lines.Count -gt 3500) {
        $cut = $rtb.Text.IndexOf("`n", 1500)
        if ($cut -gt 0) { $rtb.Select(0, $cut + 1); $rtb.SelectedText = "" }
    }
    $rtb.SelectionStart  = $rtb.TextLength
    $rtb.SelectionLength = 0
    $rtb.SelectionColor  = $col
    $rtb.AppendText($text + "`n")
    $rtb.ScrollToCaret()
}

function Dom-Refresh {
    $lv.BeginUpdate()
    $lv.Items.Clear()
    foreach ($key in $script:domains.Keys) {
        $d    = $script:domains[$key]
        $item = New-Object System.Windows.Forms.ListViewItem($key)
        $item.SubItems.Add($d.ip)               | Out-Null
        $item.SubItems.Add($d.count.ToString())  | Out-Null
        $item.SubItems.Add($d.type)              | Out-Null
        $item.ForeColor = if ($d.type -eq "AI") { [System.Drawing.Color]::FromArgb(80,200,255) } `
                          else                   { [System.Drawing.Color]::FromArgb(160,160,160) }
        $lv.Items.Add($item) | Out-Null
    }
    $lv.EndUpdate()
    $n     = $script:domains.Count
    $state = if ($script:proc -and -not $script:proc.HasExited) { "Activo" } else { "Detenido" }
    $sslbl.Text = "Puerto 8889 | $n dominio(s) | $state"
}

function Line-Parse([string]$raw) {
    $c = $raw -replace '\x1b\[[0-9;]*m', ''
    if ($c -match '\[(AI|PASS)\]\s+CONNECT\s+([^\s:]+):\d+\s+->\s+([^:\s]+):') {
        $t = $Matches[1]; $d = $Matches[2].Trim(); $ip = $Matches[3].Trim()
        if (-not $script:domains.ContainsKey($d)) {
            $script:domains[$d] = @{ ip = $ip; count = 0; type = $t }
        }
        $script:domains[$d].count++
        $script:domains[$d].ip = $ip
    }
}

function UI-SetActive([bool]$on) {
    if ($on) {
        $lblStatus.Text      = "● ACTIVO"
        $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(50, 210, 50)
        $btnToggle.Text      = "  Detener"
        $btnToggle.BackColor = [System.Drawing.Color]::FromArgb(180, 40, 40)
        $tray.Icon           = $iconOn
        $tray.Text           = "AI Proxy — Activo (8889)"
        $form.Icon           = $iconOn
        $miStart.Text        = "⬛  Detener proxy"
    } else {
        $lblStatus.Text      = "● INACTIVO"
        $lblStatus.ForeColor = [System.Drawing.Color]::FromArgb(110, 110, 110)
        $btnToggle.Text      = "  Iniciar"
        $btnToggle.BackColor = [System.Drawing.Color]::FromArgb(40, 167, 69)
        $tray.Icon           = $iconOff
        $tray.Text           = "AI Proxy — Inactivo"
        $form.Icon           = $iconOff
        $miStart.Text        = "▶  Iniciar proxy"
    }
}

function Proxy-Start {
    if ($script:proc -and -not $script:proc.HasExited) { return }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName               = "node"
    $psi.Arguments              = "`"$ProxyScript`""
    $psi.UseShellExecute        = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true
    $psi.CreateNoWindow         = $true
    if ($NoSplit) { $psi.EnvironmentVariables["NO_SPLIT"] = "1" }

    $script:proc = New-Object System.Diagnostics.Process
    $script:proc.StartInfo           = $psi
    $script:proc.EnableRaisingEvents = $true

    # Captura asíncrona → queue (thread-safe, sin tocar UI)
    $script:proc.add_OutputDataReceived({
        param($s, $e)
        if ($null -ne $e.Data) { $script:queue.Enqueue($e.Data) }
    })
    $script:proc.add_ErrorDataReceived({
        param($s, $e)
        if ($null -ne $e.Data) { $script:queue.Enqueue("_ERR_:$($e.Data)") }
    })

    $script:proc.Start()             | Out-Null
    $script:proc.BeginOutputReadLine()
    $script:proc.BeginErrorReadLine()

    UI-SetActive $true
    Log-Append "── Proxy iniciado ──────────────────────────────" ([System.Drawing.Color]::FromArgb(50, 210, 50))
}

function Proxy-Stop {
    if ($script:proc -and -not $script:proc.HasExited) {
        try { $script:proc.Kill() } catch {}
    }
    $script:proc = $null
    UI-SetActive $false
    Log-Append "── Proxy detenido ──────────────────────────────" ([System.Drawing.Color]::FromArgb(200, 70, 70))
    Dom-Refresh
}

# ════════════════════════════════════════════════════════════
#  TIMER — drena la queue en el UI thread cada 120ms
# ════════════════════════════════════════════════════════════
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 120
$timer.Add_Tick({
    $item    = $null
    $changed = $false

    while ($script:queue.TryDequeue([ref]$item)) {
        $isErr = $item.StartsWith("_ERR_:")
        $raw   = if ($isErr) { $item.Substring(6) } else { $item }
        $clean = $raw -replace '\x1b\[[0-9;]*m', ''

        Line-Parse $raw

        $col = switch -Regex ($clean) {
            '\[AI\]'           { [System.Drawing.Color]::FromArgb(80, 200, 255); break }
            '\[PASS\]'         { [System.Drawing.Color]::FromArgb(100, 100, 100); break }
            '\[FRAG\]'         { [System.Drawing.Color]::FromArgb(255, 185, 55); break }
            '\[ERROR\]'        { [System.Drawing.Color]::FromArgb(255, 80, 80); break }
            '\[TIMEOUT\]'      { [System.Drawing.Color]::FromArgb(255, 140, 40); break }
            '\[DoH\]'          { [System.Drawing.Color]::FromArgb(130, 225, 130); break }
            default            { if ($isErr) { [System.Drawing.Color]::FromArgb(255,80,80) }
                                 else        { [System.Drawing.Color]::FromArgb(190,190,190) } }
        }

        Log-Append $clean $col
        $changed = $true
    }

    # Detectar si el proceso terminó inesperadamente
    if ($script:proc -and $script:proc.HasExited) {
        $script:proc = $null
        UI-SetActive $false
        $changed = $true
    }

    if ($changed) { Dom-Refresh }
})
$timer.Start()

# ════════════════════════════════════════════════════════════
#  EVENTOS
# ════════════════════════════════════════════════════════════
$btnToggle.Add_Click({
    if ($script:proc -and -not $script:proc.HasExited) { Proxy-Stop } else { Proxy-Start }
})

$miStart.Add_Click({
    if ($script:proc -and -not $script:proc.HasExited) { Proxy-Stop } else { Proxy-Start }
})

$miOpen.Add_Click({
    $form.Show(); $form.WindowState = "Normal"; $form.Activate()
})

$tray.Add_DoubleClick({
    if ($form.Visible) { $form.Hide() }
    else { $form.Show(); $form.WindowState = "Normal"; $form.Activate() }
})

$miExit.Add_Click({
    $timer.Stop()
    Proxy-Stop
    $tray.Visible = $false
    [System.Windows.Forms.Application]::Exit()
})

# X → minimiza a bandeja (no cierra)
$form.Add_FormClosing({
    param($s, $e)
    $e.Cancel = $true
    $form.Hide()
    $tray.ShowBalloonTip(
        2500, "AI Proxy",
        "Minimizado a la bandeja del sistema.`nDoble click en el ícono para volver.",
        [System.Windows.Forms.ToolTipIcon]::Info)
})

# ════════════════════════════════════════════════════════════
#  ARRANQUE
# ════════════════════════════════════════════════════════════
$form.Show()
Proxy-Start
[System.Windows.Forms.Application]::Run()
