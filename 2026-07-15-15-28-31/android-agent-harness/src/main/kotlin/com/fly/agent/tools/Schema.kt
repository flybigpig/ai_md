package com.fly.agent.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 描述一个参数属性。 */
data class Prop(
    val name: String,
    val type: String,          // "string" | "integer" | "number" | "boolean"
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/** 从属性列表构建 JSON Schema(type=object)。 */
fun schemaOf(vararg props: Prop): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { p ->
            put(p.name, buildJsonObject {
                put("type", p.type)
                put("description", p.description)
                p.enumValues?.let { ev ->
                    put("enum", buildJsonArray { ev.forEach { add(JsonPrimitive(it)) } })
                }
            })
        }
    })
    put("required", buildJsonArray {
        props.filter { it.required }.forEach { add(JsonPrimitive(it.name)) }
    })
}

/** 空参数 schema。 */
fun emptySchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
}
