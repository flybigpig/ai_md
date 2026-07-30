/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * aiagent — native 守护进程(agent "大脑"宿主)。
 *
 * 设计:系统服务 AIAgentManagerService 是"手"(特权执行),本 daemon 是"大脑":
 *   托管 LLM 推理 + Orchestrator + worker agents,经 Binder 调 IAIAgentManager 执行动作。
 *
 * 编译前提:本文件依赖 AIDL 生成的 native 头文件。需要把框架侧
 *   frameworks/base/core/java/android/os/aiagent/IAIAgentManager.aidl
 *   作为 aidl_interface(或 stable aidl)加入到 native 构建,生成:
 *   out/.../android/os/aiagent/IAIAgentManager.h
 * 然后用 android::os::aiagent::IAIAgentManager::fromBinder(...) 拿到代理。
 *
 * MVP 阶段也可不编本 daemon:系统服务内部已用 MockLlmClient 跑通回环,
 * 本 daemon 仅作为"接真实 LLM"的扩展点。
 */

#include <android/log.h>
#include <binder/IBinder.h>
#include <binder/IServiceManager.h>
#include <binder/ProcessState.h>

// 由 AIDL 生成的头(需先构建 aidl_interface)
// #include <android/os/aiagent/IAIAgentManager.h>

#include <unistd.h>

static const char* TAG = "aiagent";

using namespace android;

int main(int argc, char** argv) {
    (void)argc; (void)argv;

    // 初始化 Binder 线程池
    ProcessState::self()->setThreadPoolMaxThreadCount(4);
    ProcessState::self()->startThreadPool();

    ALOGI("%s: starting", TAG);

    // 获取系统服务代理(服务名 "aiagent",对应 Context.AI_AGENT_SERVICE)
    sp<IBinder> binder = defaultServiceManager()->getService(String16("aiagent"));
    if (binder == nullptr) {
        ALOGE("%s: cannot get IAIAgentManager service", TAG);
        return 1;
    }

    // 真实场景:android::os::aiagent::IAIAgentManager::fromBinder(binder)
    // 然后构造 AIAgentRequest(goal, baseUrl, model, useMock) 调 submitGoal()。
    // 此处仅作占位,示意 daemon 已连上"手"。
    ALOGI("%s: connected to IAIAgentManager, LLM runtime placeholder", TAG);

    // 主线程进入 loop,托管 LLM/orchestrator(此处省略)
    while (true) {
        sleep(60);
    }

    return 0;
}
