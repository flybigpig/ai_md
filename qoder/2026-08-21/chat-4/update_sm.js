const fs = require('fs');
const filePath = 'C:\\Users\\YTO-02231406\\WorkBuddy\\Qoder\\2026-08-21\\chat-4\\framework_knowledge_tree1.html';
let content = fs.readFileSync(filePath, 'utf-8');

// Insert "状态机深度解析" lv4 node after "AOSP 10 启动差异" node
// The marker is the closing </li> of the AOSP 10 node, right before </ul></li> of KeyguardService细化
const marker = '<span class="tag tag-new">补全</span></span><div class="desc">AOSP 10 的 KeyguardService 是 exported bound Service（<span class="code">AndroidManifest.xml</span> L546-549），被 system_server 的 PhoneWindowManager 绑定，而非 CoreStartable 模式；视图管理器是 <span class="code">StatusBarKeyguardViewManager</span>（非 KeyguardViewControllerImpl）；<span class="code">StatusBar.startKeyguard()</span> 中创建 BiometricUnlockController 并注入；生命周期由独立 KeyguardLifecyclesDispatcher 派发；<span class="code">config_enableKeyguardService</span> 是最底层开关</div></div></div></li>';

const closeTag = '</ul></li>';
const idx = content.indexOf(marker);
if (idx < 0) { console.log('FAIL: marker not found'); process.exit(1); }
console.log('FOUND marker at', idx);

const idxEnd = content.indexOf(closeTag, idx);
if (idxEnd < 0) { console.log('FAIL: closeTag not found after marker'); process.exit(1); }
console.log('FOUND closeTag at', idxEnd);

const newNode = `</div></div></div></li>
      <li class="node lv4"><div class="row"><span class="toggle leaf">·</span><div class="label"><span class="title">状态机深度解析 <span class="tag tag-new">补全</span></span><div class="desc">KVM 维护 6-8 个 boolean 状态位（<span class="code">mShowing/mOccluded/mScreenOn/mInputRestricted/mDeviceLocked/mSwitchingUser</span>），所有外部事件经 Handler 串行化后按状态组合决策输出；解锁分三阶段：handleDone → handleGoingAway（动画）→ handleHide（移除窗口）；睡眠唤醒四阶段钩子驱动状态迁移；三重竞态防护：Handler 串行 + synchronized + 前置检查</div></div></div></li>
    </ul></li>`;

content = content.slice(0, idxEnd) + newNode + content.slice(idxEnd + closeTag.length);

fs.writeFileSync(filePath, content, 'utf-8');
console.log('UPDATE-OK: state machine depth node inserted');