package com.expys.sdk

import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SSETest {
  private suspend fun events(vararg lines: String): List<String> = parseSseEvents(lines.toList().asFlow()).toList()

  @Test
  fun yieldsDataPayloadOfACompleteEvent() = runTest {
    assertEquals(listOf("""{"id":"m1"}"""), events("""data: {"id":"m1"}""", ""))
  }

  @Test
  fun stripsExactlyOneLeadingSpace() = runTest {
    assertEquals(listOf("no-space", " two"), events("data:no-space", "", "data:  two", ""))
  }

  @Test
  fun accumulatesMultiLineDataJoinedByNewlines() = runTest {
    assertEquals(listOf("line1\nline2"), events("data: line1", "data: line2", ""))
  }

  @Test
  fun ignoresHeartbeatCommentLines() = runTest {
    assertEquals(listOf("real"), events(": heartbeat", "", "data: real", "", ": heartbeat", ""))
  }

  @Test
  fun emitsNoEventForACommentOnlyBlock() = runTest {
    assertTrue(events(": just a comment", "").isEmpty())
  }

  @Test
  fun flushesATrailingEventWithNoFinalBlankLine() = runTest {
    assertEquals(listOf("tail"), events("data: tail"))
  }

  @Test
  fun ignoresUnknownFieldsAndYieldsOnlyData() = runTest {
    assertEquals(listOf("payload"), events("event: message", "id: 7", "data: payload", ""))
  }
}
