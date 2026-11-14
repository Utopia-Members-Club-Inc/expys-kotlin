package com.expys.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Serializes an [OffsetDateTime] as an ISO 8601 string (`2026-11-01T00:00:00Z`).
 * The OpenAPI generator emits `@Contextual` `java.time.OffsetDateTime` fields for
 * every `format: date-time` property (e.g. `MemberSummary.vipStatusAt`,
 * `SetMemberRequest.asOf`, `MemberSignalSnapshot.observedAt`), so this must be
 * registered in [expysSerializersModule] for those models to encode/decode.
 */
internal object OffsetDateTimeSerializer : KSerializer<OffsetDateTime> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("java.time.OffsetDateTime", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: OffsetDateTime) {
    encoder.encodeString(value.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
  }

  override fun deserialize(decoder: Decoder): OffsetDateTime = OffsetDateTime.parse(decoder.decodeString())
}
