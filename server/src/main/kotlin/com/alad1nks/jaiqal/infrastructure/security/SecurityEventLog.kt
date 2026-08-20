package com.alad1nks.jaiqal.infrastructure.security

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val SECURITY_EVENT_SCHEMA_VERSION = 1

internal fun securityEventMessage(
    eventType: String,
    fields: Map<String, Any?>,
): String = JsonObject(
    buildMap {
        put("eventType", JsonPrimitive(eventType))
        put("schemaVersion", JsonPrimitive(SECURITY_EVENT_SCHEMA_VERSION))
        fields.forEach { (name, value) ->
            val primitive = when (value) {
                null -> null
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> error("Unsupported structured security event field type: ${value::class.simpleName}")
            }
            if (primitive != null) put(name, primitive)
        }
    },
).toString()
