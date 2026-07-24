package com.fly.agent.util

import kotlinx.serialization.json.Json

/**
 * 全局 JSON 实例：
 * - ignoreUnknownKeys：LLM 返回字段不稳定时容忍多余字段
 * - isLenient：容忍非严格 JSON（如裸字符串）
 * - encodeDefaults：保留默认值，便于调试
 */
val Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    prettyPrint = false
}
