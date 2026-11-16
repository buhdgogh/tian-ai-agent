use std::{env, fmt, time::Duration};

const DEFAULT_PORT: u16 = 8080;

pub struct JavaConfig {
    port: u16,
}

#[derive(Debug)]
pub enum ConfigError {
    InvalidPort,
}

impl fmt::Display for ConfigError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidPort => write!(
                f,
                "TIAN_JAVA_PORT 必须是 1 到 65535 的整数。修改后请重启桌面端。"
            ),
        }
    }
}

impl std::error::Error for ConfigError {}

impl JavaConfig {
    pub const HEALTH_TIMEOUT: Duration = Duration::from_secs(3);

    pub fn from_env() -> Result<Self, ConfigError> {
        let port = match env::var("TIAN_JAVA_PORT") {
            Ok(value) => value
                .trim()
                .parse::<u16>()
                .ok()
                .filter(|port| *port != 0)
                .ok_or(ConfigError::InvalidPort)?,
            Err(env::VarError::NotPresent) => DEFAULT_PORT,
            Err(env::VarError::NotUnicode(_)) => return Err(ConfigError::InvalidPort),
        };
        Ok(Self { port })
    }

    pub fn port(&self) -> u16 {
        self.port
    }
}
