# Changelog

## [Unreleased]

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
