//! 后端接口（REST API），用 axum 实现。
//!
//! 运行：cargo run --bin server
//! 默认监听 http://127.0.0.1:3000
//!
//! 接口清单（CRUD）：
//!   GET    /users          -> 列出全部用户
//!   POST   /users          -> 新建用户
//!   GET    /users/:id      -> 查询单个用户
//!   PUT    /users/:id      -> 更新用户
//!   DELETE /users/:id      -> 删除用户

use axum::{
    extract::{Path, State},
    http::StatusCode,
    routing::get,
    Json, Router,
};
use rust_http_demo::{CreateUser, UpdateUser, User};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

/// 应用状态：这里用「内存 HashMap」当数据库。
/// Arc<Mutex<...>> 让多个请求handler可以共享且安全地并发读写。
/// 真实项目会换成 sqlx / sea-orm 等数据库。
type Db = Arc<Mutex<HashMap<u32, User>>>;

#[tokio::main]
async fn main() {
    // 初始化一个空的“数据库”
    let db: Db = Arc::new(Mutex::new(HashMap::new()));

    // 把 db 注入到 Router 的 state 里，handler 通过 State(db) 取出
    let app = Router::new()
        .route("/users", get(list_users).post(create_user))
        .route(
            "/users/:id",
            get(get_user).put(update_user).delete(delete_user),
        )
        .with_state(db);

    let listener = tokio::net::TcpListener::bind("127.0.0.1:3000")
        .await
        .unwrap();
    println!("🚀 后端已启动: http://127.0.0.1:3000");
    // axum::serve 是 axum 0.7 推荐的高层启动方式
    axum::serve(listener, app).await.unwrap();
}

// -------------------- handlers --------------------

/// GET /users
async fn list_users(State(db): State<Db>) -> Json<Vec<User>> {
    let db = db.lock().unwrap();
    Json(db.values().cloned().collect())
}

/// POST /users
/// 返回 201 风格：这里简单返回创建出的对象（含后端生成的 id）
async fn create_user(
    State(db): State<Db>,
    Json(payload): Json<CreateUser>,
) -> (StatusCode, Json<User>) {
    let mut db = db.lock().unwrap();
    let id = db.keys().max().copied().unwrap_or(0) + 1;
    let user = User {
        id,
        name: payload.name,
        email: payload.email,
    };
    db.insert(id, user.clone());
    (StatusCode::CREATED, Json(user))
}

/// GET /users/:id
/// 用 Result<_, StatusCode> 表达“找不到就返回 404”
async fn get_user(
    State(db): State<Db>,
    Path(id): Path<u32>,
) -> Result<Json<User>, StatusCode> {
    let db = db.lock().unwrap();
    db.get(&id)
        .cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND) // 不存在 -> 404
}

/// PUT /users/:id
async fn update_user(
    State(db): State<Db>,
    Path(id): Path<u32>,
    Json(payload): Json<UpdateUser>,
) -> Result<Json<User>, StatusCode> {
    let mut db = db.lock().unwrap();
    let user = db.get_mut(&id).ok_or(StatusCode::NOT_FOUND)?;
    if let Some(name) = payload.name {
        user.name = name;
    }
    if let Some(email) = payload.email {
        user.email = email;
    }
    Ok(Json(user.clone()))
}

/// DELETE /users/:id
async fn delete_user(
    State(db): State<Db>,
    Path(id): Path<u32>,
) -> StatusCode {
    let mut db = db.lock().unwrap();
    if db.remove(&id).is_some() {
        StatusCode::NO_CONTENT // 204 删除成功无内容
    } else {
        StatusCode::NOT_FOUND
    }
}
