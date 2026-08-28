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
  CollapsibleSection,
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

      {participants.map((p, i) => (
        <g key={p.name}>
          <rect x={laneX(i) - 75} y={38} width={150} height={56} rx={8} fill={p.fill} stroke={p.color} strokeWidth={1.5} />
          <circle cx={laneX(i) - 58} cy={56} r={4} fill={p.color} />
          <text x={laneX(i) + 2} y={62} textAnchor="middle" fill={tokens.text.primary} fontSize={13} fontWeight={650}>{p.name}</text>
          <text x={laneX(i)} y={80} textAnchor="middle" fill={tokens.text.tertiary} fontSize={10.5}>{p.role}</text>
          <path d={`M ${laneX(i)} 114 L ${laneX(i)} ${totalHeight - 20}`} stroke={p.color} strokeDasharray="4 7" opacity={0.5} />
        </g>
      ))}

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
const coreClasses = [
  { cls: "Launcher", role: "Activity 入口", desc: "桌面主 Activity，管理生命周期与窗口，持有 Workspace/AllApps 等视图" },
  { cls: "LauncherModel", role: "数据模型层", desc: "后台线程加载应用列表、Widget、文件夹，通过 Callbacks 接口回调 UI" },
  { cls: "LauncherProvider", role: "ContentProvider", desc: "基于 SQLite 的 favorites 表，持久化桌面布局（图标位置、文件夹、Widget）" },
  { cls: "Workspace", role: "主屏幕容器", desc: "CellLayout 网格容器，管理多页滑动、快捷方式/Widget 的放置" },
  { cls: "AllAppsContainerView", role: "应用抽屉", desc: "RecyclerView 列表展示所有已安装应用，支持搜索过滤" },
  { cls: "DragController", role: "拖拽引擎", desc: "处理长按拖拽、DropTarget 判定、动画反馈" },
  { cls: "WidgetHost", role: "AppWidget 宿主", desc: "实现 AppWidgetHost，管理远程视图的 inflate 与更新" },
  { cls: "InvariantDeviceProfile", role: "设备配置", desc: "根据屏幕尺寸计算行列数、图标大小、文件夹布局参数" },
];

const dataFlow = [
  { step: 1, phase: "启动", desc: "Launcher.onCreate() → 创建 LauncherModel" },
  { step: 2, phase: "加载", desc: "LauncherModel.startLoader() → 后台线程读取 DB + PackageManager" },
  { step: 3, phase: "绑定", desc: "Loader 完成后通过 Callbacks.bindWorkspace() 回调 UI 线程" },
  { step: 4, phase: "渲染", desc: "Workspace.addInScreenFromBinder() 将 ItemInfo 渲染为 BubbleTextView/Widget" },
  { step: 5, phase: "交互", desc: "用户点击/拖拽 → DragController → 更新 DB → 刷新 UI" },
];

const modules = [
  {
    name: "Workspace（主屏幕）",
    items: [
      "CellLayout 网格系统：自动对齐图标到单元格",
      "多页滑动：PagedView 实现水平翻页",
      "文件夹：FolderIcon 聚合多个应用，展开为 Folder 弹窗",
      "快捷方式：ShortcutInfo 封装 Intent + 图标 + 标题",
    ],
  },
  {
    name: "AllApps（应用抽屉）",
    items: [
      "AlphabeticalAppsList：按字母排序 + 预测排序",
      "搜索过滤：实时匹配应用名/包名",
      "Section Break：按首字母分组显示",
      "工作资料 Tab：多用户/Profile 切换",
    ],
  },
  {
    name: "Widget（小部件）",
    items: [
      "LauncherAppWidgetHost：管理 widgetId 分配",
      "PendingAddWidgetInfo：Widget 添加预配置",
      "WidgetResizeFrame：支持用户调整 Widget 尺寸",
      "Pin Widget API：第三方应用申请固定 Widget",
    ],
  },
  {
    name: "拖拽系统",
    items: [
      "DragSource 接口：定义拖拽源（Workspace/AllApps/Folder）",
      "DropTarget 接口：定义放置目标（Workspace/DeleteZone/Info）",
      "DragLayer：全局拖拽覆盖层，处理坐标转换",
      "SpringLoadedDragController：边缘触发翻页",
    ],
  },
  {
    name: "搜索",
    items: [
      "SearchDropTarget：拖拽到顶部触发搜索/删除",
      "AllApps 搜索栏：实时过滤应用列表",
      "QSB（Quick Search Box）：桌面顶部搜索栏 Widget",
      "Assist API：长按 Home 触发语音助手",
    ],
  },
  {
    name: "通知徽章",
    items: [
      "NotificationListener：监听系统通知",
      "NotificationInfo：聚合每个应用的未读数",
      "BadgeRenderer：在图标右上角绘制红点/数字",
      "dot-persisted：重启后恢复徽章状态",
    ],
  },
];

const ipcInterfaces = [
  { iface: "IActivityTaskManager", direction: "Launcher → system_server", desc: "startActivity()、moveTaskToBack()、getRecentTasks()" },
  { iface: "IStatusBar", direction: "system_server → SystemUI", desc: "expandNotificationsPanel()、disable2()、setSystemUiVisibility()" },
  { iface: "ILauncherPreview", direction: "Launcher ↔ system_server", desc: "预览模式下的窗口状态同步（Android 12+）" },
  { iface: "LauncherAppsService", direction: "Launcher → system_server", desc: "getActivityList()、registerCallback()、startSession()" },
  { iface: "AppWidgetService", direction: "Launcher → system_server", desc: "allocateAppWidgetId()、bindAppWidgetIdIfPossible()" },
  { iface: "LauncherProvider (CP)", direction: "跨进程查询", desc: "其他应用/系统通过 ContentResolver 查询桌面收藏数据" },
  { iface: "BroadcastReceiver", direction: "系统 → Launcher", desc: "PACKAGE_ADDED/REMOVED、LOCALE_CHANGED、THEME_CHANGED" },
];

const binderSeqParticipants: Participant[] = [
  { name: "Launcher", role: "桌面进程", color: "#4A90D9", fill: "#EBF3FC" },
  { name: "ActivityTask\nManagerService", role: "system_server", color: "#D97706", fill: "#FEF3E2" },
  { name: "PackageManager", role: "system_server", color: "#7C3AED", fill: "#F3EEFE" },
  { name: "SystemUI", role: "系统 UI", color: "#059669", fill: "#E6F7F0" },
];

const binderSeqMessages: Message[] = [
  { step: 1, from: 0, to: 1, label: "startActivity(Intent, options)", kind: "sync" },
  { step: 2, from: 1, to: 2, label: "resolveIntent() 解析目标 Activity", kind: "sync" },
  { step: 3, from: 2, to: 1, label: "返回 ResolveInfo", kind: "return" },
  { step: 4, from: 1, to: 1, label: "创建 ActivityRecord，权限检查", kind: "sync" },
  { step: 5, from: 1, to: 0, label: "返回启动结果 (START_SUCCESS)", kind: "return" },
  { step: 6, from: 1, to: 3, label: "onTaskStackChanged() 通知 SystemUI", kind: "sync" },
  { step: 7, from: 3, to: 3, label: "更新 Recents 列表 & 导航栏", kind: "sync" },
];

const binderSeqActivations: Activation[] = [
  { lane: 1, start: 1, end: 5 },
  { lane: 2, start: 2, end: 3 },
  { lane: 0, start: 1, end: 5 },
  { lane: 3, start: 6, end: 7 },
];

const customizationItems = [
  {
    area: "设备配置 (InvariantDeviceProfile)",
    points: [
      "grid_num_rows / grid_num_columns：桌面行列数",
      "icon_size：图标像素大小（dp）",
      "folder_columns / folder_rows：文件夹网格",
      "device_type 判定：phone / tablet / two-panel",
    ],
  },
  {
    area: "主题与外观",
    points: [
      "IconProvider：根据系统主题切换图标包",
      "ThemedIconCache：支持单色/自适应图标（Android 13+）",
      "all_apps_rv_corner_radius：应用抽屉圆角",
      "workspace_shadow_*：图标阴影参数",
    ],
  },
  {
    area: "布局定制",
    points: [
      "device_profiles.xml：定义多套屏幕配置",
      "invariant_device_profile.xml：默认网格参数",
      "default_workspace_*.xml：预置桌面布局",
      "partner_overlay.apk：厂商免修改覆盖资源",
    ],
  },
  {
    area: "功能扩展",
    points: [
      "Quickstep：与 SystemUI 共享最近任务（Android 10+）",
      "Plugin API：SystemUI 插件机制扩展 Launcher 行为",
      "ShortcutManager：动态快捷方式注册",
      "LauncherOverlay：负一屏/Google Discover 集成",
    ],
  },
];

export default function Launcher3DeepDive() {
  const { tokens } = useHostTheme();

  return (
    <Stack gap={32}>
      <H1>Android Launcher3 深度解析</H1>
      <Text tone="secondary">
        Launcher3 是 AOSP 的默认桌面启动器，包名 com.android.launcher3。它负责管理主屏幕布局、应用启动、Widget 托管、拖拽交互等核心桌面体验。以下从架构、功能模块、IPC 接口和定制开发四个维度进行深度解析。
      </Text>

      <Divider />

      {/* ── Section 1: Architecture ── */}
      <H2>一、源码架构分析</H2>

      <H3>1.1 核心类关系</H3>
      <Table
        headers={["类名", "角色", "职责说明"]}
        rows={coreClasses.map((c) => [c.cls, c.role, c.desc])}
      />

      <H3>1.2 数据加载流程</H3>
      <Callout tone="info">
        Launcher3 采用"后台加载 + 主线程绑定"的异步架构。LauncherModel 在 Worker 线程中读取 LauncherProvider（SQLite）和 PackageManager，构建 ItemInfo 列表后通过 Callbacks 接口回调到 UI 线程进行视图绑定。
      </Callout>
      <Stack gap={4}>
        {dataFlow.map((d) => (
          <Stack key={d.step} gap={2} direction="row" style={{ alignItems: "flex-start" }}>
            <Tag tone={d.step <= 2 ? "info" : d.step <= 4 ? "primary" : "success"}>{d.phase}</Tag>
            <Text size="small">{d.desc}</Text>
          </Stack>
        ))}
      </Stack>

      <Divider />

      {/* ── Section 2: Core Modules ── */}
      <H2>二、核心功能模块</H2>
      <Grid columns={2} gap={12}>
        {modules.map((mod) => (
          <Card key={mod.name}>
            <CardHeader>
              <Text weight="semibold" size="small">{mod.name}</Text>
            </CardHeader>
            <CardBody>
              <Stack gap={4}>
                {mod.items.map((item, i) => (
                  <Text key={i} tone="secondary" size="small">• {item}</Text>
                ))}
              </Stack>
            </CardBody>
          </Card>
        ))}
      </Grid>

      <Divider />

      {/* ── Section 3: IPC Interfaces ── */}
      <H2>三、关键接口 / IPC 通信</H2>

      <H3>3.1 Binder / AIDL 接口清单</H3>
      <Table
        headers={["接口", "方向", "说明"]}
        rows={ipcInterfaces.map((c) => [c.iface, c.direction, c.desc])}
      />

      <H3>3.2 应用启动 Binder 时序图</H3>
      <Text tone="secondary">
        展示从用户点击桌面图标到目标 Activity 启动完成的完整 Binder 事务流程：
      </Text>
      <SequenceDiagram
        participants={binderSeqParticipants}
        messages={binderSeqMessages}
        activations={binderSeqActivations}
        title="launcher3-binder"
      />

      <Callout tone="info">
        关键事务说明：步骤 1-5 为同步 Binder 调用（Launcher → ATMS → PackageManager → ATMS → Launcher），步骤 6-7 为 ATMS 向 SystemUI 的异步通知（oneway Binder），SystemUI 据此更新最近任务列表和导航栏状态。
      </Callout>

      <Divider />

      {/* ── Section 4: Customization ── */}
      <H2>四、定制与二次开发</H2>

      <Grid columns={2} gap={12}>
        {customizationItems.map((item) => (
          <Card key={item.area}>
            <CardHeader>
              <Text weight="semibold" size="small">{item.area}</Text>
            </CardHeader>
            <CardBody>
              <Stack gap={4}>
                {item.points.map((p, i) => (
                  <Text key={i} tone="secondary" size="small">• {p}</Text>
                ))}
              </Stack>
            </CardBody>
          </Card>
        ))}
      </Grid>

      <H3>4.1 厂商定制典型路径</H3>
      <Stack gap={8}>
        <CollapsibleSection title="修改桌面网格布局">
          <Stack gap={4}>
            <Text size="small">1. 编辑 res/xml/invariant_device_profile.xml 调整 rows/columns/iconSize</Text>
            <Text size="small">2. 在 device_profiles.xml 中为不同屏幕密度定义配置</Text>
            <Text size="small">3. 通过 partner overlay APK 覆盖资源，无需修改源码</Text>
          </Stack>
        </CollapsibleSection>
        <CollapsibleSection title="集成自定义 Widget">
          <Stack gap={4}>
            <Text size="small">1. 实现 AppWidgetProvider 子类并声明 metadata XML</Text>
            <Text size="small">2. 在 default_workspace.xml 中预置 Widget 位置和尺寸</Text>
            <Text size="small">3. 使用 PinWidget API 允许第三方应用动态添加</Text>
          </Stack>
        </CollapsibleSection>
        <CollapsibleSection title="Quickstep 最近任务集成">
          <Stack gap={4}>
            <Text size="small">1. Android 10+ 中 Launcher3 与 SystemUI 共享 RecentsView</Text>
            <Text size="small">2. Quickstep 模块在 SystemUI 进程中运行，提供手势导航动画</Text>
            <Text size="small">3. 通过 RecentsAnimationController 协调窗口转场</Text>
            <Text size="small">4. 厂商可替换 RecentsView 实现自定义最近任务样式</Text>
          </Stack>
        </CollapsibleSection>
        <CollapsibleSection title="主题与图标包适配">
          <Stack gap={4}>
            <Text size="small">1. Android 13+ 支持 Themed Icons（自适应图标单色模式）</Text>
            <Text size="small">2. IconProvider 根据系统主题动态切换图标资源</Text>
            <Text size="small">3. 第三方图标包通过 Intent Filter 注册，Launcher3 扫描并应用</Text>
            <Text size="small">4. 壁纸感知着色：WallpaperColorInfo 提取主色调应用到文件夹背景</Text>
          </Stack>
        </CollapsibleSection>
      </Stack>

      <Divider />

      <Text tone="tertiary" size="small">
        基于 AOSP Launcher3 (Android 14 / UP1A.231005.007)。不同厂商 ROM 的 Launcher3 分支可能存在较大差异。
      </Text>
    </Stack>
  );
}
