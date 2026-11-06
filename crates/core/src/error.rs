use thiserror::Error;

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum CoreError {
    #[error("Shizuku 未就绪: {0}")]
    ShizukuNotReady(String),

    #[error("平台桥接调用失败: {0}")]
    Bridge(String),

    #[error("会话创建失败: {0}")]
    Session(String),

    #[error("写入安装会话失败: {0}")]
    Write(String),

    #[error("归档无效: {0}")]
    Archive(String),

    #[error("参数无效: {0}")]
    InvalidArgument(String),

    #[error("操作被拒绝: {0}")]
    Rejected(String),

    #[error("JSON 契约错误: {0}")]
    Json(String),
}

pub type Result<T> = std::result::Result<T, CoreError>;

impl From<serde_json::Error> for CoreError {
    fn from(e: serde_json::Error) -> Self {
        CoreError::Json(e.to_string())
    }
}
