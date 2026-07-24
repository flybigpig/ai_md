package com.fly.agent.util

import kotlinx.serialization.json.Json

/** 全局宽松 JSON:忽略未知字段、允许默认值缺省、输出更可读。 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    prettyPrint = false
    explicitNulls = false
}
