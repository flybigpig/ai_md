//! 前后端共享的数据模型（struct）。
//! 后端用它序列化响应，前端用它反序列化请求结果，
//! 这样两侧字段保持一致，改一处即可。

use serde::{Deserialize, Serialize};

/// 用户实体：接口返回给前端的完整对象
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    pub id: u32,
    pub name: String,
    pub email: String,
}

/// 创建用户时的请求体（前端 POST 提交的内容）。
/// 没有 id —— id 由后端生成。
/// 同时 derive Serialize：客户端要把它序列化成 JSON 请求体。
#[derive(Debug, Serialize, Deserialize)]
pub struct CreateUser {
    pub name: String,
    pub email: String,
}

/// 更新用户时的请求体（全部可选，支持部分更新）
#[derive(Debug, Default, Deserialize)]
pub struct UpdateUser {
    pub name: Option<String>,
    pub email: Option<String>,
}
