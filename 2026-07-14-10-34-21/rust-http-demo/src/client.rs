//! 前端请求（HTTP 客户端），用 reqwest 实现。
//!
//! 运行前请先启动后端：cargo run --bin server
//! 然后另开终端：      cargo run --bin client
//!
//! 演示：GET 列表、POST 创建、GET 按 id 查询、错误处理。

use rust_http_demo::{CreateUser, User};

/// 把所有错误统一成 `Box<dyn std::error::Error>`，方便 main 直接 `?`
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // 复用同一个 Client（带连接池，比每次 new 高效）
    let client = reqwest::Client::new();
    let base = "http://127.0.0.1:3000";

    // ---------- 1) POST 创建用户 ----------
    let new_user = CreateUser {
        name: "Alice".to_string(),
        email: "alice@example.com".to_string(),
    };
    // .json(&body) 会自动把结构体序列化成 JSON，并加上 Content-Type: application/json
    let created: User = client
        .post(format!("{base}/users"))
        .json(&new_user)
        .send()
        .await?
        .error_for_status()? // 非 2xx 直接返回 Err，省去手写判断
        .json()
        .await?;
    println!("✅ 创建成功: {:?}", created);

    // ---------- 2) GET 查询刚创建的用户 ----------
    let fetched: User = client
        .get(format!("{base}/users/{}", created.id))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    println!("🔍 查询到: {:?}", fetched);

    // ---------- 3) GET 列出全部用户 ----------
    let users: Vec<User> = client
        .get(format!("{base}/users"))
        .send()
        .await?
        .error_for_status()?
        .json()
        .await?;
    println!("📋 当前用户数: {}", users.len());

    // ---------- 4) 演示错误处理：查询不存在的 id ----------
    let bad_resp = client.get(format!("{base}/users/999")).send().await?;
    match bad_resp.status() {
        reqwest::StatusCode::NOT_FOUND => {
            println!("⚠️  按预期：id=999 不存在，服务端返回 404");
        }
        _ => {
            println!("❓ 意外的状态码: {}", bad_resp.status());
        }
    }

    Ok(())
}
