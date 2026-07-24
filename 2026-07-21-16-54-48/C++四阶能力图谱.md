# C++ 四阶能力图谱：基础 → 进阶 → 高级 → 专家

> 适用对象：有编程基础、想在 C++ 上从会写到能写底层/高性能代码的工程师。
> 路线设计原则：**每一阶都要能独立交付产物**，而不是“看过书”。
> 与你的 AOSP 方向强相关：Binder / HAL / ART / libcutils / SurfaceFlinger 全是 C++，专家阶能力直接对应 native 层 debug 与设计。

---

## 0. 总览（一图速查）

| 阶段 | 核心目标 | 能交付的东西 | 典型卡点 |
|------|----------|--------------|----------|
| 基础 | 写出“能编译、能跑、不出明显 bug”的 C++ | 控制台程序、简单类封装 | 指针/引用混淆、内存泄漏不自知 |
| 进阶 | 写出“正确且现代”的 C++（RAII、move、智能指针） | 可用的工具库、健壮组件 | 生命周期、悬挂引用、异常安全 |
| 高级 | 写出“高效且可控”的 C++（模板元、并发、UB） | 高性能模块、并发库 | 模板报错、数据竞争、ABI |
| 专家 | 写出“贴近编译器/硬件”的 C++ | 底层库、性能天花板优化、救火 | UB 根因、优化器误解、ABI 破坏 |

---

## 1. 基础（Fundamentals）

**目标**：理解 C++ 与 C/Java 的本质差异，能写出符合直觉且内存安全的朴素代码。

### 1.1 必会清单
- **类型系统**：`int/unsigned` 陷阱、`char` 符号性、`bool`、`enum` vs `enum class`、大小与对齐（`sizeof`/`alignof`）。
- **引用 vs 指针**：引用是别名（不可空、不可重绑），指针可为空、可算术。
  ```cpp
  void f(int& r);   // 调用方必须传左值，r 始终是同一对象
  void g(int* p);   // p 可为 nullptr，需判空
  ```
- **const 正确性**：`const T*`、`T* const`、`const T&` 参数（大型对象传参默认 `const T&`）。
- **函数与重载**：默认参数、重载决议、`inline` 的语义（非“内联展开”而是“允许多定义”）。
- **面向对象基础**：`class`/`struct` 默认访问差异、构造函数/析构函数、`=default`/`=delete`、访问控制。
- **堆与栈**：栈上对象自动析构；`new/delete` 必须配对（进阶用智能指针替代）。
- **STL 入门**：`vector`、`string`、`map`/`unordered_map`、`algorithm`（`sort`/`find`/`count`）。
- **编译模型**：`.h` 声明、`.cpp` 定义、#include 展开、`#pragma once`、预处理宏风险。

### 1.2 自检（能答 = 过关）
- [ ] `int a[10]; int* p = a;` 中 `a` 和 `p` 在类型/衰变(数组衰变成指针)上有何不同？
- [ ] 为什么 `vector<int> v; for(int i=0;i<v.size();i++)` 在某些场景有符号/无符号比较告警？
- [ ] `struct` 与 `class` 唯一区别是什么？

---

## 2. 进阶（Intermediate / Modern C++）

**目标**：用现代 C++（C++11/14/17）写出“异常安全、零泄漏、可维护”的代码，这是工程落地的分水岭。

### 2.1 RAII 与资源所有权（最重要）
- **RAII**：资源获取即初始化，析构即释放。任何资源（内存、锁、文件、socket）都包进对象。
- **智能指针**：
  - `std::unique_ptr<T>`：独占所有权，零开销，默认首选。
  - `std::shared_ptr<T>`：引用计数共享，`make_shared` 一次分配控制块+对象。
  - `std::weak_ptr<T>`：打破 `shared_ptr` 循环引用。
  ```cpp
  auto p = std::make_unique<Foo>(args...);   // 优于 new
  std::vector<std::unique_ptr<Base>> items;   // 多态容器
  ```
- **禁止裸 `new/delete`** 作为常规写法（专家阶才在 allocator 内部碰）。

### 2.2 移动语义与值类别
- **值类别**：左值 / 右值 / 纯右值 / 将亡值（xvalue）。
- **`std::move`**：把左值**强制当成右值**（不移动任何东西，只是 cast）。
- **移动构造 / 移动赋值**：`T(T&&)`；使“拷贝大对象”变“指针交换”。
- **Rule of Five/ZerO**：有自定义析构/拷贝/移动其一，就要审视五个特殊成员；优先用 `=default`，避免手写。
- **返回值优化 (RVO/NRVO)**：`return local;` 通常零拷贝，不要画蛇添足 `std::move(ret)`。

### 2.3 模板与泛型（基础版）
- 函数模板、类模板、`typename` vs `class`。
- `auto` 类型推导、`decltype`、`range-based for`。
- `std::initializer_list`、统一初始化 `{}`。

### 2.4 Lambda 与函数对象
- `[captures](args) -> ret { body }`；捕获方式 `[=]` `[&]` `[this]` `[x=std::move(y)]`。
- `std::function`（类型擦除，有开销）、模板 + 泛型 lambda（零开销）。

### 2.5 并发入门
- `std::thread`、`std::mutex`、`std::lock_guard`/`std::unique_lock`（RAII 加锁）。
- `std::condition_variable`、`std::future`/`std::async`。

### 2.6 自检
- [ ] `std::unique_ptr` 能放进 `std::vector` 吗？`std::shared_ptr` 呢？为什么？
- [ ] 以下函数返回时会发生什么拷贝/移动：`Widget make() { Widget w; return w; }`
- [ ] 为什么 `std::mutex` 不可拷贝、不可移动？

---

## 3. 高级（Advanced）

**目标**：理解“代码在编译期与运行期到底发生了什么”，能写出高性能、无数据竞争、可扩展的库。

### 3.1 模板元编程（TMP）
- **编译期计算**：`constexpr` 函数（运行期/编译期皆可）、`if constexpr`（编译期分支，避免 SFINAE 地狱）。
- **类型萃取**：`std::enable_if`、`std::void_t`、traits（`std::is_integral_v` 等）。
- **SFINAE**：替换失败不是错误，用于重载选择。
- **C++20 Concepts**：`template<typename T> requires Integral<T>` 或 `template<Integral T>`，报错可读性质变。
  ```cpp
  template<typename T>
  concept Addable = requires(T a, T b) { a + b; };
  template<Addable T> T sum(T a, T b) { return a + b; }
  ```
- **变参模板**：`template<typename... Ts>`，包展开 `f(args...)`、折叠表达式 `(0 + ... + args)`。
- **CRTP**（奇异递归模板模式）：静态多态，避免虚函数开销。

### 3.2 内存模型与并发（硬核）
- **happens-before**、`std::atomic` 的 6 种 memory_order（`relaxed`/`acquire`/`release`/`acq_rel`/`seq_cst`/`consume`）。
- **无锁编程**：CAS（`compare_exchange_weak/strong`）、ABA 问题、`std::atomic<T*>`。
- **false sharing**：缓存行（64B）伪共享，用 `alignas(64)` 或 padding 隔离热变量。
- **数据竞争 vs 竞态条件**：前者是 UB，后者是逻辑错误。
- **线程池、无锁队列、读写锁** 的实现要点。

### 3.3 未定义行为（UB）—— 高级必须懂
- 常见 UB：有符号溢出、解引用空/悬挂指针、越界、重复释放、违反严格别名、在构造前/析构后访问对象、整数移位越界。
- **严格别名规则**：`char*` 可别名任意类型，但 `int*` 不能别名 `float*`（可用 `memcpy`/`std::bit_cast` 规避）。
- 工具：`-fsanitize=address,undefined`、Clang ThreadSanitizer。

### 3.4 自定义分配器与容器
- `std::allocator` 接口、`std::pmr::memory_resource`（C++17 多态分配器）。
- 池分配器、栈分配器（`std::pmr::monotonic_buffer_resource`）在热路径降分配开销。

### 3.5 constexpr / 编译期反射雏形
- `constexpr` 容器、`consteval`、`std::embed`（C++26 草案）、编译期字符串处理。

### 3.6 协程（C++20，选学但加分）
- `co_await`/`co_yield`/`co_return`、`promise_type`、`std::coroutine_handle`。
- 理解它本质是编译器生成的**状态机 + 堆分配帧**，并非 OS 线程。

### 3.7 自检
- [ ] `memory_order::relaxed` 保证什么、不保证什么？
- [ ] 为什么 `std::vector<bool>` 是坑（它不是存 `bool` 的连续数组）？
- [ ] 用 Concepts 写一个只接受“可加且可比较”类型的 `max()`。

---

## 4. 专家（Expert）

**目标**：能看透编译器和 ABI，能定位“只有专家能解”的崩溃/性能问题，能设计底层库。

### 4.1 编译与链接的真相
- **翻译单元**：每个 `.cpp` 独立编译成 `.o`，`#include` 把声明搬进来。
- **ODR（单一定义规则）**：跨 TU 的 inline/模板/变量定义规则，违反 = UB 或链接错误。
- **符号与可见性**：`__attribute__((visibility("default/hidden")))`；`.so` 导出控制，避免符号冲突。
- **Name mangling**：`c++filt` 还原，理解 C 链接 `extern "C"`。
- **链接顺序**：静态库 `-l` 顺序、弱符号、`--whole-archive`。

### 4.2 ABI 稳定性（对 Android 极其重要）
- **Itanium C++ ABI**：vtable 布局、`this` 调整、RTTI/`dynamic_cast` 实现。
- **破坏 ABI 的改动**：增删虚函数、改类布局、改 `std::string` 实现（libstdc++ vs libc++ 不兼容！）。
- **Android 现实**：NDK 用 libc++，系统用（老版本）不同 STL；跨 so 传 `std::string`/`std::vector` 是经典 crash 源 → 用 C 接口或 `android::String8` 等稳定类型桥接。

### 4.3 编译期优化与性能
- **内联决策**：`inline` 只是建议；LTO（链接期优化）跨 TU 内联。
- **PGO（Profile Guided Optimization）**：训练后重排热点，提升 IPC。
- **SIMD 与 intrinsics**：`_mm_*`（SSE/AVX），`#pragma omp simd`，auto-vectorization 阅读。
- **冷/热路径**：`[[likely]]`/`[[unlikely]]`（C++20）、分支预测提示。
- **虚假依赖与停顿**：CPU 流水、store buffer、memory barrier 与原子对应。

### 4.4 未定义行为的根因挖掘
- 用 UBSan/ASan/TSan/MSan 组合定位；理解“为什么优化器会把 UB 代码整段删掉”。
- 案例：`if (p) { ... }` 之后优化器假设 `p != nullptr`，删掉后续空判。

### 4.5 标准库实现与贡献
- 读 `libc++` / `libstdc++` 源码：`std::vector` 增长策略、`std::sort` introsort、`std::shared_ptr` 控制块。
- 理解 `abi::__cxx11` 命名空间（GCC5 的 ABI 切换）。

### 4.6 底层调试与工具链
- **GDB/LLDB**：看 vtable、看 corrupted heap、看 backtrace 中 mangled name。
- **perf / simpleperf（Android）**：火焰图、cache-miss、branch-miss 热点。
- **nm / objdump / readelf**：符号表、段、重定位。
- **core dump 分析**：`bt`、`info registers`、`x/` 内存查看。

### 4.7 专家自检
- [ ] 两个 `.so` 各自静态链了不同版本的同一个模板类，运行时会怎样？
- [ ] 为什么 `std::string` 不能安全地跨 module boundary（不同编译器/STL）传递？
- [ ] 用 `perf` 你发现某函数 L1-dcache-miss 极高，可能的原因与对策？

---

## 5. 与 Android / AOSP native 层映射

| C++ 能力 | AOSP 落点 |
|----------|-----------|
| RAII / 智能指针 | `sp<>`/`wp<>`（Android 强/弱指针，RefBase）、`base::ScopedFD` |
| 移动语义 | `android::base::unique_fd`、binder 事务数据转移 |
| 模板元 / Concepts | `libbase`、`libutils` 泛型封装 |
| 并发 / atomics | Binder 线程池、SurfaceFlinger 合成、HAL 回调 |
| ABI / 符号可见性 | NDK `libc++` vs 系统 STL 隔离、`__attribute__((visibility))` |
| UB / sanitizers | `SANITIZE` 编译选项、`hwaddress` 在 Android 10+ |
| 调试 | `simpleperf`、`tombstone`、`ndk-stack` 还原 native crash |

> 你的 Binder 内核 / HAL 方向：专家阶的 **ABI + 内存模型 + 调试** 是日常。建议把第 4 阶作为重点投入。

---

## 6. 推荐资源（按阶取用）

- **基础/进阶**：《C++ Primer》(第5版)、《Effective Modern C++》(Meyers, C++11/14)、cppreference.com（权威参考，常翻）。
- **高级**：《C++ Templates: The Complete Guide》(2nd)、《C++ Concurrency in Action》(2nd, Williams)、《Effective C++》三卷。
- **专家**：Itanium C++ ABI 规范、LLVM/Clang 源码、`libc++` 实现、Agner Fog 的《Optimizing C++》、Godbolt Compiler Explorer（看汇编）。
- **练习场**：LeetCode（算法练手）、GitHub 读 `fmt`/`abseil`/`boost` 源码、自己写一个 `vector`/`shared_ptr`/`thread_pool`。

---

## 7. 一条可执行的学习路径（建议顺序）

1. 基础：写完 10 个小程序（含类、vector、文件 IO）。
2. 进阶：把一个旧项目用 `unique_ptr` + `=default` 重构，跑通 ASan。
3. 高级：手写 `thread_pool` + 一个无锁环形队列，用 TSan 验证。
4. 专家：用 Godbolt 对比 `-O0/-O2` 下自己函数的汇编；用 simpleperf 给一个 native 模块做火焰图。

> 记住 C++ 的真理：**你写的每一行都在和编译器/硬件签契约。理解契约，才配谈性能。**
