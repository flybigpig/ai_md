package com.fly.agent.tools

import com.fly.agent.llm.FunctionDef
import com.fly.agent.llm.ToolDef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/** 构造工具参数 schema 的小工具（OpenAI function calling 协议） */

fun param(type: String, desc: String, enumValues: List<String>? = null): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive(type))
        put("description", JsonPrimitive(desc))
        if (enumValues != null) {
            put("enum", buildJsonArray { enumValues.forEach { add(JsonPrimitive(it)) } })
        }
    }

fun paramsObject(vararg props: Pair<String, JsonObject>): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("object"))
        put(
            "properties",
            buildJsonObject { props.forEach { (k, v) -> put(k, v) } }
        )
        put(
            "required",
            buildJsonArray { props.forEach { (k, _) -> add(JsonPrimitive(k)) } }
        )
    }

fun toolDef(name: String, description: String, params: JsonObject): ToolDef =
    ToolDef(function = FunctionDef(name = name, description = description, parameters = params))
