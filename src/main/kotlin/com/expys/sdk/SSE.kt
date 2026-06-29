package com.expys.sdk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A minimal Server-Sent Events parser, kept pure (a transform over a line flow)
 * so the data-accumulation rules are testable without a network. Implements only
 * the slice of the SSE wire format the stream endpoint uses: `data:` lines
 * (accumulated, newline-joined) terminated by a blank line, with comment lines
 * (`:`-prefixed heartbeats) ignored. `event:`/`id:` and other fields are accepted
 * but unused.
 *
 * Input is a flow of already-split lines (CRLF/LF splitting is the byte layer's
 * concern); this only strips a single optional leading space after the field
 * colon. A non-blank trailing event (no final blank line before the flow ends) is
 * flushed when the source completes.
 */
internal fun parseSseEvents(lines: Flow<String>): Flow<String> = flow {
  val dataLines = mutableListOf<String>()
  var sawData = false

  suspend fun flush() {
    if (sawData) emit(dataLines.joinToString("\n"))
    dataLines.clear()
    sawData = false
  }

  lines.collect { line ->
    when {
      line.isEmpty() -> flush()
      line.startsWith(":") -> Unit // comment / heartbeat
      else -> {
        val colon = line.indexOf(':')
        val field = if (colon == -1) line else line.substring(0, colon)
        if (field == "data") {
          val raw = if (colon == -1) "" else line.substring(colon + 1)
          val value = if (raw.startsWith(" ")) raw.substring(1) else raw
          dataLines.add(value)
          sawData = true
        }
      }
    }
  }
  flush()
}
