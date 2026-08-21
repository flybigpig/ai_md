import {
  useHostTheme,
  Stack,
  H1,
  H2,
  H3,
  Text,
  Divider,
  Grid,
  Card,
  CardHeader,
  CardBody,
  Table,
  Tag,
  Callout,
} from "qoder/canvas";

type Participant = {
  name: string;
  role: string;
  color: string;
  fill: string;
};

type Message = {
  step: number;
  from: number;
  to: number;
  label: string;
  kind: "sync" | "return";
};

type Activation = {
  lane: number;
  start: number;
  end: number;
};

/* ── Binder IPC Sequence Diagram Data ── */
const binderParticipants: Participant[] = [
  { name: "Launcher", role: "Home 应用进程", color: "#4A90D9", fill: "#EBF3FC" },
  { name: "ActivityTaskManager", role: "system_server", color: "#D97706", fill: "#FEF3E2" },
  { name: "SystemUI", role: "系统 UI 进程", color: "#059669", fill: "#E6F7F0" },
  { name: "StatusBarManager", role: "system_server", color: "#7C3AED", fill: "#F3EEFE" },
];

const binderMessages: Message[] = [
  { step: 1, from: 0, to: 1, label: "startActivity(Intent)", kind: "sync" },
  { step: 2, from: 1, to: 1, label: "resolveActivity() / 权限检查", kind: "sync" },
  { step: 3, from: 1, to: 0, label: "返回 ActivityRecord / 启动结果", kind: "return" },
  { step: 4, from: 2, to: 3, label: "expandNotificationsPanel()", kind: "sync" },
  { step: 5, from: 3, to: 2, label: "onPanelExpanded()", kind: "return" },
  { step: 6, from: 0, to: 1, label: "setLauncherVisibility(true)", kind: "sync" },
  { step: 7, from: 1, to: 2, label: "onLauncherVisibilityChange()", kind: "sync" },
  { step: 8, from: 2, to: 2, label: "更新状态栏图标透明度", kind: "sync" },
];

const binderActivations: Activation[] = [
  { lane: 1, start: 1, end: 3 },
  { lane: 0, start: 1, end: 3 },
  { lane: 3, start: 4, end: 5 },
  { lane: 2, start: 4, end: 5 },
  { lane: 1, start: 6, end: 7 },
  { lane: 0, start: 6, end: 6 },
  { lane: 2, start: 7, end: 8 },
];

/* ── Data Exchange Flow Diagram Data ── */
const exchangeParticipants: Participant[] = [
  { name: "Launcher", role: "Home 应用", color: "#4A90D9", fill: "#EBF3FC" },
  { name: "WindowManager", role: "窗口管理", color: "#D97706", fill: "#FEF3E2" },
  { name: "SystemUI", role: "系统 UI", color: "#059669", fill: "#E6F7F0" },
];

const exchangeMessages: Message[] = [
  { step: 1, from: 0, to: 1, label: "WindowState (Launcher 窗口)", kind: "sync" },
  { step: 2, from: 1, to: 2, label: "focusedWindowChanged()", kind: "sync" },
  { step: 3, from: 2, to: 2, label: "判断是否显示导航栏高亮", kind: "sync" },
  { step: 4, from: 2, to: 1, label: "setSystemUiVisibility()", kind: "return" },
  { step: 5, from: 1, to: 0, label: "onSystemUiVisibilityChanged()", kind: "return" },
  { step: 6, from: 0, to: 0, label: "调整 Workspace insets", kind: "sync" },
];

const exchangeActivations: Activation[] = [
  { lane: 1, start: 1, end: 5 },
  { lane: 0, start: 1, end: 1 },
  { lane: 2, start: 2, end: 4 },
  { lane: 0, start: 5, end: 6 },
];

/* ── Layout helpers ── */
const laneLeft = 140;
const laneGap = 240;
const rowTop = 160;
const rowGap = 52;
const laneX = (i: number) => laneLeft + i * laneGap;
const rowY = (step: number) => rowTop + (step - 1) * rowGap;

function SequenceDiagram({
  participants,
  messages,
  activations,
  title,
}: {
  participants: Participant[];
  messages: Message[];
  activations: Activation[];
  title: string;
}) {
  const { tokens } = useHostTheme();
  const totalHeight = rowTop + messages.length * rowGap + 40;
  const totalWidth = laneLeft + (participants.length - 1) * laneGap + 140;

  return (
    <svg width={totalWidth} height={totalHeight} viewBox={`0 0 ${totalWidth} ${totalHeight}`}>
      <defs>
        <marker id={`sync-${title}`} markerWidth={7} markerHeight={7} refX={6.2} refY={3.5} orient="auto" markerUnits="strokeWidth">
          <path d="M 1 1 L 6 3.5 L 1 6" fill="none" stroke={tokens.text.secondary} strokeWidth={1.15} strokeLinecap="round" strokeLinejoin="round" />
        </marker>
        <marker id={`ret-${title}`} markerWidth={7} markerHeight={7} refX={6.2} refY={3.5} orient="auto" markerUnits="strokeWidth">
          <path d="M 1 1 L 6 3.5 L 1 6" fill="none" stroke={tokens.accent.control} strokeWidth={1.15} strokeLinecap="round" strokeLinejoin="round" />
        </marker>
      </defs>

      {/* Participants */}
      {participants.map((p, i) => (
        <g key={p.name}>
          <rect x={laneX(i) - 75} y={38} width={150} height={56} rx={8} fill={p.fill} stroke={p.color} strokeWidth={1.5} />
          <circle cx={laneX(i) - 58} cy={56} r={4} fill={p.color} />
          <text x={laneX(i) + 2} y={62} textAnchor="middle" fill={tokens.text.primary} fontSize={13} fontWeight={650}>{p.name}</text>
          <text x={laneX(i)} y={80} textAnchor="middle" fill={tokens.text.tertiary} fontSize={10.5}>{p.role}</text>
          <path d={`M ${laneX(i)} 114 L ${laneX(i)} ${totalHeight - 20}`} stroke={p.color} strokeDasharray="4 7" opacity={0.5} />
        </g>
      ))}

      {/* Activations */}
      {activations.map((a, i) => {
        const p = participants[a.lane];
        return (
          <rect
            key={`act-${i}`}
            x={laneX(a.lane) - 5}
            y={rowY(a.start) - 18}
            width={10}
            height={rowY(a.end) - rowY(a.start) + 36}
            rx={5}
            fill={p.fill}
            stroke={p.color}
            strokeWidth={1}
            opacity={0.8}
          />
        );
      })}

      {/* Messages */}
      {messages.map((m) => {
        const y = rowY(m.step);
        const fromX = laneX(m.from);
        const toX = laneX(m.to);
        const isReturn = m.kind === "return";
        const lineColor = isReturn ? tokens.accent.control : tokens.text.secondary;
        const markerEnd = isReturn ? `url(#ret-${title})` : `url(#sync-${title})`;

        if (m.from === m.to) {
          return (
            <g key={`msg-${m.step}`}>
              <path
                d={`M ${fromX + 13} ${y} C ${fromX + 60} ${y - 20}, ${fromX + 60} ${y + 20}, ${fromX + 13} ${y + 10}`}
                fill="none"
                stroke={lineColor}
                strokeWidth={1.25}
                strokeLinecap="round"
                strokeDasharray={isReturn ? "5 6" : undefined}
              />
              <text x={fromX + 68} y={y + 4} fill={tokens.text.primary} fontSize={11.5}>
                {m.step}. {m.label}
              </text>
            </g>
          );
        }

        const direction = toX > fromX ? 1 : -1;
        return (
          <g key={`msg-${m.step}`}>
            <path
              d={`M ${fromX + direction * 13} ${y} L ${toX - direction * 17} ${y}`}
              fill="none"
              stroke={lineColor}
              strokeWidth={1.25}
              strokeLinecap="round"
              strokeDasharray={isReturn ? "5 6" : undefined}
              markerEnd={markerEnd}
            />
            <text x={(fromX + toX) / 2 - 10} y={y - 10} fill={tokens.text.primary} fontSize={11.5}>
              {m.step}. {m.label}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/* ── Architecture Data ── */
const systemuiComponents = [
  { name: "StatusBar", desc: "状态栏：时间、电量、信号、通知图标" },
  { name: "NavigationBar", desc: "导航栏：返回、主页、最近任务" },
  { name: "NotificationPanel", desc: "通知面板/ shade：通知列表与快捷设置" },
  { name: "QuickSettings", desc: "快速设置面板：Wi-Fi、蓝牙、亮度等" },
  { name: "RecentsView", desc: "最近任务视图（部分 ROM 与 Launcher 共享）" },
  { name: "VolumeUI", desc: "音量覆盖层" },
  { name: "Keyguard", desc: "锁屏界面" },
  { name: "ScreenshotService", desc: "截图服务" },
];

const launcherComponents = [
  { name: "Workspace", desc: "主屏幕工作区：图标、文件夹布局" },
  { name: "AllAppsContainer", desc: "应用抽屉：全部应用列表" },
  { name: "WidgetHost", desc: "AppWidget 宿主：管理桌面小部件" },
  { name: "DragController", desc: "拖拽控制器：图标/Widget 拖放" },
  { name: "LauncherModel", desc: "数据模型：加载/缓存应用列表" },
  { name: "LauncherProvider", desc: "ContentProvider：收藏/桌面数据持久化" },
  { name: "SearchDropTarget", desc: "搜索/删除拖放目标区域" },
];

const commMethods = [
  { method: "Binder IPC", via: "AIDL / IInterface", direction: "双向", desc: "StatusBarManagerService、ActivityTaskManagerService 等系统服务" },
  { method: "Broadcast", via: "Intent 广播", direction: "单向/双向", desc: "ACTION_CLOSE_SYSTEM_DIALOGS、配置变更、时区变化等" },
  { method: "ContentProvider", via: "跨进程数据查询", direction: "双向", desc: "Launcher 通过 Provider 暴露桌面数据给 SystemUI 最近任务" },
  { method: "WindowManager", via: "窗口属性回调", direction: "系统→应用", desc: "focusedWindowChanged、systemUiVisibility 回调" },
  { method: "Handler/Message", via: "进程内消息", direction: "进程内", desc: "各自进程内的 UI 线程消息调度" },
];

export default function LauncherSystemUI() {
  const { tokens } = useHostTheme();

  return (
    <Stack gap={32}>
      <H1>Android Launcher 与 SystemUI 架构解析</H1>

      {/* ── Section 1: Architecture Overview ── */}
      <H2>一、架构与职责概览</H2>
      <Text tone="secondary">
        Launcher 和 SystemUI 是 Android 系统中两个核心的用户界面进程，它们共同构成了用户与设备交互的第一层界面。两者通过 Binder IPC 机制与系统服务协调工作。
      </Text>

      <Grid columns={2} gap={16}>
        <Card>
          <CardHeader>
            <Tag tone="info">SystemUI</Tag>
            <Text tone="tertiary" size="small">com.android.systemui</Text>
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              {systemuiComponents.map((c) => (
                <Stack key={c.name} gap={2}>
                  <Text weight="semibold" size="small">{c.name}</Text>
                  <Text tone="secondary" size="small">{c.desc}</Text>
                </Stack>
              ))}
            </Stack>
          </CardBody>
        </Card>

        <Card>
          <CardHeader>
            <Tag tone="primary">Launcher</Tag>
            <Text tone="tertiary" size="small">com.android.launcher3</Text>
          </CardHeader>
          <CardBody>
            <Stack gap={8}>
              {launcherComponents.map((c) => (
                <Stack key={c.name} gap={2}>
                  <Text weight="semibold" size="small">{c.name}</Text>
                  <Text tone="secondary" size="small">{c.desc}</Text>
                </Stack>
              ))}
            </Stack>
          </CardBody>
        </Card>
      </Grid>

      <Divider />

      {/* ── Section 2: Communication Methods ── */}
      <H2>二、通信方式</H2>
      <Table
        headers={["通信方式", "机制", "方向", "说明"]}
        rows={commMethods.map((c) => [c.method, c.via, c.direction, c.desc])}
      />

      <Divider />

      {/* ── Section 3: Data Exchange Flow ── */}
      <H2>三、数据交换格式与流程图</H2>
      <Text tone="secondary">
        下图展示 Launcher 与 SystemUI 通过 WindowManager 进行窗口焦点切换时的数据交换流程。当用户从 Launcher 切换到其他应用时，SystemUI 需要感知焦点变化并调整导航栏/状态栏的显示行为。
      </Text>

      <Callout tone="info">
        核心数据格式：WindowState 对象通过 Binder 传递，包含窗口 token、可见性标志、systemUiVisibility 位图等字段。SystemUI 根据 focusedWindow 的属性决定是否显示沉浸式模式。
      </Callout>

      <Stack gap={8}>
        <H3>窗口焦点切换数据交换流程</H3>
        <SequenceDiagram
          participants={exchangeParticipants}
          messages={exchangeMessages}
          activations={exchangeActivations}
          title="exchange"
        />
      </Stack>

      <Divider />

      {/* ── Section 4: Binder IPC Sequence ── */}
      <H2>四、Binder IPC 通信流程图</H2>
      <Text tone="secondary">
        下图展示 Launcher 与 SystemUI 通过 Binder 机制与 system_server 中的系统服务进行 IPC 通信的典型流程。包含应用启动、面板展开、可见性同步等关键交互。
      </Text>

      <Callout tone="info">
        Binder 事务流程：调用方线程将数据序列化到 Parcel → 通过 binder_driver 的 ioctl(BINDER_WRITE_READ) 将事务传递给目标进程 → 目标进程的 Binder 线程池接收并反序列化 → 调用 Stub 实现 → 返回值沿原路径返回。
      </Callout>

      <Stack gap={8}>
        <H3>Launcher ↔ SystemUI Binder IPC 时序</H3>
        <SequenceDiagram
          participants={binderParticipants}
          messages={binderMessages}
          activations={binderActivations}
          title="binder"
        />
      </Stack>

      <Divider />

      {/* ── Section 5: Key Interactions Summary ── */}
      <H2>五、关键交互场景总结</H2>
      <Grid columns={2} gap={12}>
        <Card>
          <CardHeader><Tag tone="primary">场景 1</Tag></CardHeader>
          <CardBody>
            <Text weight="semibold" size="small">应用启动</Text>
            <Text tone="secondary" size="small">
              Launcher 通过 ActivityTaskManagerService.startActivity() 发起启动请求，system_server 解析 Intent、检查权限后创建新的 ActivityRecord，并通知 SystemUI 更新最近任务栈。
            </Text>
          </CardBody>
        </Card>
        <Card>
          <CardHeader><Tag tone="info">场景 2</Tag></CardHeader>
          <CardBody>
            <Text weight="semibold" size="small">通知面板展开</Text>
            <Text tone="secondary" size="small">
              SystemUI 的 NotificationPanel 通过 StatusBarManagerService 与 WindowManager 协调，调整系统窗口层级，Launcher 收到 focus 变化后暂停动画以节省资源。
            </Text>
          </CardBody>
        </Card>
        <Card>
          <CardHeader><Tag tone="warning">场景 3</Tag></CardHeader>
          <CardBody>
            <Text weight="semibold" size="small">最近任务切换</Text>
            <Text tone="secondary" size="small">
              用户点击导航栏"最近任务"按钮，SystemUI 通过 RecentsAnimationController 请求 system_server 启动任务切换动画，Launcher 收到 onPause 回调并保存当前状态。
            </Text>
          </CardBody>
        </Card>
        <Card>
          <CardHeader><Tag tone="success">场景 4</Tag></CardHeader>
          <CardBody>
            <Text weight="semibold" size="small">Home 键返回</Text>
            <Text tone="secondary" size="small">
              SystemUI 的 NavigationBar 捕获 Home 键事件，通过 ActivityTaskManagerService.goToHomeIntent() 将前台切回 Launcher，Launcher onResume 恢复工作区状态。
            </Text>
          </CardBody>
        </Card>
      </Grid>

      <Text tone="tertiary" size="small">
        基于 AOSP Android 14 架构。不同厂商 ROM 可能在 SystemUI 和 Launcher 间增加额外的私有通信接口。
      </Text>
    </Stack>
  );
}
