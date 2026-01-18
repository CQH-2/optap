#!/bin/bash

# 设置 Java 21 环境
export JAVA_HOME=/home/codespace/java/21.0.9-ms
export PATH=$JAVA_HOME/bin:$PATH

# 进入项目目录
cd "$(dirname "$0")"

# 运行项目（统一走 Service 入口）
REQUEST_JSON=${1:-request.json}

if [[ "$REQUEST_JSON" != "-" && ! -f "$REQUEST_JSON" ]]; then
	echo "请求文件不存在：$REQUEST_JSON"
	echo "用法：./run.sh path/to/request.json    或  ./run.sh - < request.json"
	exit 2
fi

echo "启动 SchedulingSolveService 入口..."
mvn exec:java -Dexec.args="$REQUEST_JSON"
