// 统计 lv3/lv4/lv5 节点分布
const fs = require('fs');
const html = fs.readFileSync('C:/Users/YTO-02231406/Documents/Qoder/2026-08-21/chat-4/framework_knowledge_tree1.html', 'utf8');
const lines = html.split(/\r?\n/);
let curMod = '';
let counts = {};
let total4 = 0, total5 = 0;
for (let i = 0; i < lines.length; i++) {
  const l = lines[i];
  let m = l.match(/===== (\d+)\./);
  if (m) { curMod = m[1]; counts[curMod] = counts[curMod] || { lv3: 0, lv4: 0, lv5: 0 }; }
  if (l.indexOf('class="node lv3"') >= 0) { if (counts[curMod]) counts[curMod].lv3++; }
  if (l.indexOf('class="node lv4"') >= 0) { if (counts[curMod]) counts[curMod].lv4++; total4++; }
  if (l.indexOf('class="node lv5"') >= 0) { if (counts[curMod]) counts[curMod].lv5++; total5++; }
}
console.log('模块 | lv3 | lv4 | lv5');
for (const k of Object.keys(counts)) {
  console.log(k.padStart(3) + ' | ' + String(counts[k].lv3).padStart(4) + ' | ' + String(counts[k].lv4).padStart(4) + ' | ' + String(counts[k].lv5).padStart(4));
}
console.log('总计 lv4: ' + total4 + ', lv5: ' + total5);
