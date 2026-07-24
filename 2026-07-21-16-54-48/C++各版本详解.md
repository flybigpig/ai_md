# C++ 各版本详解（C++98 → C++26）

> 适用范围：想搞清楚"每个标准到底加了什么、为什么重要、什么时候该用哪一版"的工程师。
> 与你的 AOSP 方向强相关：NDK 的 C++ 等级（目前 r27 默认 `gnustl` 已弃用，统一 `libc++`，支持到 C++17/20/23 视 NDK 版本）直接绑定你能用的语法。文末有"该用哪一版"的结论。

---

## 0. 版本速查表

| 标准 | 发布年 | 别名 / 代号 | 历史地位 | 你现在还用得到吗 |
|------|--------|-------------|----------|------------------|
| C++98 | 1998 | 第一个 ISO 标准 | 奠基 | 仅维护老代码 |
| C++03 | 2003 | 98 的缺陷修订 | 修正 Bug | 同 98 |
| C++11 | 2011 | C++0x | **现代 C++ 起点** | 必须掌握 |
| C++14 | 2014 | C++1y | 11 的修边幅 | 必须掌握 |
| C++17 | 2017 | C++1z | 库大爆发 | 必须掌握 |
| C++20 | 2020 | C++2a | **二战级更新** | 强烈建议 |
| C++23 | 2023 | C++2b | 收尾+实用化 | 看工具链 |
| C++26 | ~2026 | C++2c（草案） | 进行中 | 关注 |

> 命名规律：`C++0x`(x=年代, 0x 指 200x 年代但不准)、`C++1y`(2014)、`C++1z`(2017)、`C++2a`(2020)、`C++2b`(2023)、`C++2c`(2026)。现在官方直接叫年份。

---

## 1. C++98 / C++03 —— 奠基期

### 1.1 C++98（1998）
第一个 ISO 标准（ISO/IEC 14882:1998）。把"带类的 C"正式标准化。
- **模板**：函数模板、类模板、特化。
- **STL**（源自 Stepanov 的 HP STL）：`vector`/`list`/`map`/`set`、迭代器、算法、`iostream`。
- **异常处理**：`try/catch/throw`。
- **RTTI**：`dynamic_cast`/`typeid`。
- **`bool` 类型**正式加入。
- **名字空间** `namespace`。
- **`new`/`delete`、引用、const 成员函数** 等 OOP 基础。

### 1.2 C++03（2003）
纯缺陷修订，**语言特性几乎无变化**，主要修正标准文本矛盾、澄清库行为（如 `vector<bool>` 的诡异特化被"合法化"）。实践中"98/03"视为同一代。

```cpp
// C++98 风格：手写 RAII 雏形、裸指针为主
class File {
    FILE* f;
public:
    File(const char* p) : f(fopen(p, "r")) {}
    ~File() { if (f) fclose(f); }  // 98 已经有析构，RAII 思想可用
};
```

### 局限（今天看）
- 没有 `auto`、没有 `unique_ptr`、没有移动语义、没有 lambda、没有 `nullptr`（用 `0`/`NULL`）。
- 写现代代码极啰嗦且易泄内存。

---

## 2. C++11 —— 现代 C++ 的起点（最重要的分水岭）

> 如果说"学 C++"有唯一必学版本，就是 **C++11**。它把 C++ 从"带类的 C + STL"变成了另一门语言。

### 2.1 核心语言特性
- **`auto` 类型推导**：`auto x = 42;` 编译器推类型，泛型代码救星。
- **`nullptr`**：类型安全的空指针（不再是 `int` 0）。
- **范围 for**：`for (auto& x : container)`。
- **右值引用 `&&` + 移动语义**：`std::move`、移动构造/赋值，告别大对象拷贝。
- **`std::initializer_list` + 统一初始化** `{}`：`vector<int> v{1,2,3};`
- **lambda 表达式**：`[&](int x){ return x+1; }`，函数式编程进 C++。
- **`decltype`**：推导表达式类型。
- **`constexpr`**（初版，运行期也可）：编译期计算雏形。
- **强类型枚举 `enum class`**：不污染命名空间、不隐式转 int。
- **`override` / `final`**：显式标记虚函数重写/禁止继承。
- **可变参数模板（variadic templates）**：`template<typename... Ts>`，现代库基石。
- **`std::thread` / `std::atomic` / `std::mutex` / `std::condition_variable`**：**标准库自带并发**（不再依赖 pthread/Win32 原生 API）。
- **智能指针**：`std::unique_ptr` / `shared_ptr` / `weak_ptr`（出自 TR1/Boost，正式入标准）。
- **`=default` / `=delete`**：精确控制特殊成员函数。
- **右值引用 + 引用折叠**：完美转发的底座（`std::forward`）。

### 2.2 库新增
- `std::array`、`std::forward_list`、`std::unordered_map/set`（哈希容器）。
- `std::tuple`、`std::function`、`std::bind`。
- `std::chrono` 时间库、`std::regex`、`std::random`、`std::thread` 全家桶。
- `std::make_shared` / `make_unique`（C++14 才加 `make_unique`）。

```cpp
// C++11 现代写法
auto p = std::make_shared<Widget>();
std::vector<int> v = {1, 2, 3};
auto it = std::find_if(v.begin(), v.end(), [](int x){ return x > 1; });
std::thread t([p]{ /* 跨线程用 shared_ptr 安全共享 */ });
```

---

## 3. C++14 —— 修边幅（11 的完善版）

> 没有革命性特性，但把 11 的坑填平、把常用写法变顺手。**生产代码默认至少 C++14**。

- **`auto` 作函数返回类型**：`auto f() { return 42; }`（推导返回值）。
- **泛型 lambda**：`[](auto x, auto y){ return x+y; }`（参数也 `auto`）。
- **`std::make_unique`**：补齐 11 漏掉的智能指针工厂。
- **泛型变量模板**：`template<typename T> constexpr T pi = T(3.14159);`
- **`decltype(auto)`**：完美保留值类别（用于转发返回）。
- **`std::integer_sequence` / `std::index_sequence`**：编译期整数序列，TMP 基础设施。
- **`std::shared_timed_mutex`**、`std::quoted`（IO 友好）。
- **放宽 constexpr**：允许局部变量、循环（比 11 宽松）。

```cpp
// C++14：泛型 lambda + auto 返回
auto add = [](auto a, auto b) { return a + b; };
auto result = add(1, 2.5);   // double
```

---

## 4. C++17 —— 库大爆发 + 小语法糖

> 语言特性不多，但**标准库大幅增强**，日常可用轮子猛增。对工程落地性价比极高。

### 4.1 语言特性
- **结构化绑定（structured bindings）**：`auto [k, v] = *m.begin();` 解包 pair/tuple/struct。
- **`if constexpr`**：编译期分支，彻底干掉大量 SFINAE 模板技巧。
- **`inline` 变量**：头文件定义常量不再 ODR 踩雷（`inline constexpr int N = 10;`）。
- **折叠表达式（fold expressions）**：`(0 + ... + args)` 变参模板求和。
- **`std::string_view`**：零拷贝只读字符串视图，**性能利器**（不拥有内存）。
- **`[[nodiscard]]` / `[[maybe_unused]]` / `[[fallthrough]]`** 属性。
- **`if (init; cond)` / `switch (init; cond)`**：带初始化的选择语句。
- **类模板实参推导（CTAD）**：`std::pair p{1, "x"};` 不用写 `<int,const char*>`。
- **`auto` 在非类型模板参数**：`template<auto V> struct C {};`

### 4.2 库新增（重头戏）
- **`std::optional<T>`**：可能有/无的值，替代"哨兵值/-1/nullptr"陋习。
- **`std::variant<Ts...>`**：类型安全联合体（带visited）。
- **`std::any`**：类型擦除的任意值（慎用，有开销）。
- **`std::filesystem`**：跨平台文件系统操作（路径/目录/拷贝），终于不用 Boost。
- **`std::pmr`（多态分配器）**：`std::pmr::polymorphic_allocator` + `memory_resource`，热路径性能可控。
- **并行算法**：`std::sort(std::execution::par, ...)` 一键并行。
- **`std::string_view`**、`std::byte`、`std::invoke`、`std::apply`、`std::clamp`。

```cpp
// C++17 经典组合
std::optional<int> parse(const std::string_view s);   // 零拷贝 + 可选返回
if (auto [it, ok] = m.try_emplace(key, val); ok) {    // 结构化绑定 + if(init)
    use(*it);
}
auto sum = (0 + ... + args);                           // 折叠表达式
```

---

## 5. C++20 —— 二战级更新（Concepts / Modules / Ranges / Coroutines）

> 自 C++11 之后最大的一次语言变革。四大件：**模块、概念、范围、协程**。

### 5.1 四大语言特性
- **Concepts（概念）**：模板参数的编译期约束，报错从"天书"变人话。
  ```cpp
  template<typename T>
  concept Arithmetic = std::is_arithmetic_v<T>;
  template<Arithmetic T> T add(T a, T b) { return a + b; }
  ```
- **Modules（模块）**：`import <iostream>;` 替代 `#include`，**编译更快、隔离更好、无宏污染**。
  ```cpp
  // math.cppm
  export module math;
  export int square(int x) { return x * x; }
  // main.cpp
  import math;
  ```
- **Ranges（范围）**：惰性、可组合的算法管线，告别手写迭代器对。
  ```cpp
  using namespace std::views;
  auto r = v | filter([](int x){return x%2==0;}) | transform([](int x){return x*x;});
  ```
- **Coroutines（协程）**：`co_await`/`co_yield`/`co_return`，编译器生成状态机。
  ```cpp
  generator<int> range(int n) {
      for (int i = 0; i < n; co_yield i++);
  }
  ```

### 5.2 其他重要特性
- **`constinit` / `consteval`**：强制编译期初始化 / 编译期函数。
- **三路比较 `<=>`（spaceship operator）**：自动生成 `==`/`<`/`>` 全套比较。
- **`std::format`**：类型安全、快过 `iostream` 的格式化（终于有像样的 `printf` 替代）。
- **`std::span`**：零拷贝的连续序列视图（`T*`+长度），接口层神器。
- **`std::jthread`**：自动 `join` 的线程 + 可中断（`stop_token`）。
- **`std::latch` / `std::barrier` / `std::semaphore`**：并发原语入标准。
- **`std::atomic<T>` 支持浮点/智能指针原子**、`std::atomic_ref`。
- **`std::ranges::` 算法、`std::counted_iterator`** 等。
- **`[[likely]]` / `[[unlikely]]`** 分支提示（之前提过）。

### 5.3 注意
- 编译器支持是**渐进**的：GCC 10/11、Clang 10~13、MSVC 19.2x 才逐步齐活。
- Modules 在构建系统（CMake/Bazel）上仍有坑，落地需评估。

---

## 6. C++23 —— 实用化收尾

> 没有 20 那么炸裂，但补齐了大量"早就该有"的小东西。**被戏称 "C++20 完整版"**。

- **`std::print` / `std::println`**：`std::print("{} {}\n", a, b);` 直接打印（无需先 `format` 再 `cout`）。
- **`std::expected<T, E>`**：函数式错误处理，`T` 或错误 `E`，替代异常/optional 的"错误分支"。
- **`std::mdspan`**：多维视图，科学计算/HPC 友好。
- **`std::generator`**：标准协程生成器（20 只给语言机制，23 补库）。
- **`std::stacktrace`**：标准库直接拿调用栈（之前靠 `backtrace()`/`Boost`）。
- **`std::flat_map` / `std::flat_set`**：Sorted vector 实现的映射，缓存友好、查找快。
- **`std::ranges` 补全**：`range::contains`、`range::find_last`、`range::chunk`/`slide`/`adjacent` 等适配器。
- **`auto(x)` / `auto{x}`**：显式创建 decay 副本（与 `decay_copy` 等效）。
- **多维 `operator[]`**：`m[i][j][k]` 可一次写。
- **`std::basic_istream::view` / `std::istream_iterator` 改进** 等。
- **`if consteval`**：在编译期上下文分支。
- **`std::move_only_function`**：只移动的函数包装（比 `std::function` 灵活、可包移动-only lambda）。

```cpp
// C++23 错误处理新范式
std::expected<int, std::error_code> readConfig();
if (auto r = readConfig(); r) use(*r); else log(r.error());

std::println("answer = {}", 42);   // 标准库直接打印 + 换行
```

---

## 7. C++26 —— 进行中（草案，~2026 发布）

> 仍在委员会流程中，以下为已相对确定的方向（以最终发布为准）：

- **`std::execution` 独立执行库**（发送者-接收者模型，取代简单 `par` 策略）。
- **Contracts（契约）**：`pre`/`post`/`assert` 函数前后置条件（曾从 20 砍掉，26 回归）。
- **`std::simd`**：数据并行矢量类型入标准（SIMD 抽象）。
- **反射（静态反射）**：编译期自省类型结构（`std::meta`），元编程革命。
- **扩展 `std::format` / `std::print`** 能力。
- **`std::hazard_pointer`**：安全内存回收原语。
- **模式匹配（pattern matching）** 提案推进中（可能 26 或之后）。

> C++26 对你（AOSP native）短期影响小，但**静态反射 + Contracts** 长期会改变库设计方式，值得保持关注。

---

## 8. 该用哪一版？—— 决策建议

| 你的处境 | 推荐标准 | 理由 |
|----------|----------|------|
| 维护老系统 / 受限嵌入式 | C++11 / 14 | 工具链旧，但已够"现代" |
| **AOSP / Android NDK 常规开发** | **C++17**（最低）、**C++20**（新模块） | NDK r21+ 支持 17，r23+ 更好支持 20/23；libc++ 领跑 |
| 新项目 / 库开发 | C++20 | Concepts/Ranges/Modules 大幅提升可维护性 |
| 前沿实验 / 内部工具 | C++23 | `std::print`/`expected`/`stacktrace` 极好用 |

### 与 AOSP 的硬约束
- Android 系统自身编译用 **Clang + libc++**，近年来已支持到 C++17/20。但**跨 `.so` 边界传 STL 对象仍危险**（见上一文档的 ABI 节）——标准版本选高可以，但接口边界的 ABI 规则不变。
- NDK 的 `APP_CPPFLAGS` / `CMAKE_CXX_STANDARD` 设置决定你能用的语法。老 NDK 默认 C++14，请显式设 `17`/`20`。
- 内核态（你研究的 binder 驱动、内核模块）是 **C**（GNU C + 少量 C++ 受限），C++ 标准在此不适用——这是为什么 Binder 驱动用 C 写、用户态 lib 才用 C++。

---

## 9. 编译开关速记

```bash
# 常用标准指定
g++ -std=c++17 main.cpp
clang++ -std=c++20 main.cpp
# 校验工具（高级/专家必备）
g++ -std=c++17 -fsanitize=address,undefined -g main.cpp
# 看汇编（理解编译器真做了什么）
g++ -std=c++20 -O2 -S main.cpp        # 或丢进 https://godbolt.org
```

---

## 10. 一句话总结每个版本

- **C++98/03**：能写面向对象 + STL，但啰嗦、易泄。
- **C++11**：现代 C++ 诞生（移动、lambda、智能指针、线程）。**必学。**
- **C++14**：把 11 用顺手（auto 返回、泛型 lambda）。
- **C++17**：库爆发（optional/variant/string_view/filesystem/并行算法）。**日常性价比最高。**
- **C++20**：语言革命（Concepts/Modules/Ranges/Coroutines）。**新项目首选。**
- **C++23**：实用补全（print/expected/stacktrace/generator）。
- **C++26**：反射/契约/SIMD 的未来。

> 学习顺序建议：**11 → 14 → 17 打牢，再上 20/23**。不要跳，因为 11 的移动语义是后面一切的基础。
