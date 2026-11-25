"""pytest 全局准备。

两件事：
1. 把项目根目录加进 sys.path，测试里才能 import app
2. 在导入 app.core.config 之前注入环境变量

pydantic-settings 的优先级是「环境变量 > .env 文件」，所以测试不依赖本地 .env，
换台机器、CI 里都能直接跑。
"""

import os
import sys
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_ROOT))

os.environ.setdefault("APP_NAME", "agent-service-tests")
os.environ.setdefault("DEEPSEEK_API_KEY", "test-key")
os.environ.setdefault("INTERNAL_TOKEN", "test-token")
