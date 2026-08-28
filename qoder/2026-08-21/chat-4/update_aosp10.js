const fs = require('fs');
const path = require('path');

const filePath = 'C:\\Users\\YTO-02231406\\WorkBuddy\\Qoder\\2026-08-21\\chat-4\\framework_knowledge_tree1.html';
let content = fs.readFileSync(filePath, 'utf-8');

// Insert AOSP 10 startup diff lv4 node after the sequence diagram closing </div></li>
// Target: the unique closing tag of 时序图, just before </ul></li> that closes KeyguardService细化
const marker = '│ 锁屏界面已显示    │</div></li>';
const newContent = `│ 锁屏界面已显示    │</div></li>
      <li class="node lv4"><div class="row"><span class="toggle leaf">·</span><div class="label"><span class="title">AOSP 10 启动差异 <span class="tag tag-new">补全</span></span><div class="desc">AOSP 10 的 KeyguardService 是 exported bound Service（<span class="code">AndroidManifest.xml</span> L546-549），被 system_server 的 PhoneWindowManager 绑定，而非 CoreStartable 模式；视图管理器是 <span class="code">StatusBarKeyguardViewManager</span>（非 KeyguardViewControllerImpl）；<span class="code">StatusBar.startKeyguard()</span> 中创建 BiometricUnlockController 并注入；生命周期由独立 KeyguardLifecyclesDispatcher 派发；<span class="code">config_enableKeyguardService</span> 是最底层开关</div></div></div></li>`;

const idx = content.indexOf(marker);
if (idx < 0) { console.log('FAIL: marker not found'); process.exit(1); }
console.log('FOUND marker at', idx);

content = content.slice(0, idx) + newContent + content.slice(idx + marker.length);

fs.writeFileSync(filePath, content, 'utf-8');
console.log('UPDATE-OK: AOSP 10 diff node inserted');