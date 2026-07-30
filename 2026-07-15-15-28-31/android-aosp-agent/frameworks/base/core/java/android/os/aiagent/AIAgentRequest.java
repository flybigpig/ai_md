/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * AIAgentRequest — submitGoal 的入参 Parcelable。
 */

package android.os.aiagent;

import android.os.Parcel;
import android.os.Parcelable;

public final class AIAgentRequest implements Parcelable {
    private final String goal;
    private final String baseUrl;   // OpenAI 兼容端点,如 http://127.0.0.1:8081/v1
    private final String model;     // 模型名
    private final boolean useMock;  // true: 用 MockLlmClient 跑通链路,不联网

    public AIAgentRequest(String goal, String baseUrl, String model, boolean useMock) {
        this.goal = goal;
        this.baseUrl = baseUrl;
        this.model = model;
        this.useMock = useMock;
    }

    public String getGoal() { return goal; }
    public String getBaseUrl() { return baseUrl; }
    public String getModel() { return model; }
    public boolean isUseMock() { return useMock; }

    // ---- Parcelable ----
    protected AIAgentRequest(Parcel in) {
        goal = in.readString();
        baseUrl = in.readString();
        model = in.readString();
        useMock = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(goal);
        dest.writeString(baseUrl);
        dest.writeString(model);
        dest.writeByte((byte) (useMock ? 1 : 0));
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<AIAgentRequest> CREATOR = new Creator<AIAgentRequest>() {
        @Override
        public AIAgentRequest createFromParcel(Parcel in) { return new AIAgentRequest(in); }
        @Override
        public AIAgentRequest[] newArray(int size) { return new AIAgentRequest[size]; }
    };
}
