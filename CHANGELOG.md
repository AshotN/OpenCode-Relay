# Changelog

## [Unreleased]

## [1.1.0] - 2026-05-04

<p><strong>Added</strong></p>
<ul>
  <li>Alert users when OpenCode requests permission by requesting IDE attention while the project window is inactive and showing a notification when the permission UI is not visible.</li>
  <li>Brave Mode for automatically accepting OpenCode permission requests, with toggles in the tool window toolbar and settings UI.</li>
</ul>

<p><strong>Fixed</strong></p>
<ul>
  <li>Use <code>session.status</code> as the authoritative session state signal, dropping deprecated <code>session.idle</code> handling and fixing cases where the session list could stay stuck on <code>running...</code>.</li>
  <li>Refresh MCP server connection status immediately after connect and disconnect actions.</li>
  <li><code>RejectedExecutionException</code> when replacing the current project in the same IDE window while late OpenCode server callbacks are still being delivered.</li>
</ul>

## [1.0.0] - 2026-04-24

<p><strong>Added</strong></p>
<ul>
  <li>OpenCode server authentication support and expose the related IDE settings.</li>
  <li>OpenCode env variables support.</li>
  <li>Capture Ctrl-Z (<a href="https://github.com/ashotn/OpenCode-Companion/issues/3">#3</a>).</li>
</ul>

<p><strong>Breaking Changes</strong></p>
<ul>
  <li>Drop support for OpenCode versions earlier than 1.4.0.</li>
</ul>

<p><strong>Changed</strong></p>
<ul>
  <li>Improve compatibility with newer OpenCode 1.4+ patch payloads by updating patch parsing and diff handling.</li>
</ul>

## [0.4.1] - 2026-04-07

<p><strong>Fixed</strong></p>
<ul>
  <li>Force HTTP/1.1 for OpenCode 1.4 server communication to avoid HTTP/2 upgrade issues.</li>
</ul>
