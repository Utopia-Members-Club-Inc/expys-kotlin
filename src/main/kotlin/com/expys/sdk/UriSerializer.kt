package com.expys.sdk

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import java.net.URI

/**
 * Serializes a [URI] as its string form. The OpenAPI generator emits
 * `@Contextual` `java.net.URI` fields (e.g. `CreateWebhookRequest.url`), so this
 * must be registered in the SDK's [kotlinx.serialization.json.Json] serializers
 * module for those models to encode/decode. Surfaced from the generated models -
 * not a modification of them.
 */
internal object UriSerializer : KSerializer<URI> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("java.net.URI", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: URI) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): URI = URI(decoder.decodeString())
}

/** The serializers module registering [UriSerializer] for the SDK's `Json`. */
internal val expysSerializersModule: SerializersModule = SerializersModule {
  contextual(URI::class, UriSerializer)
}
