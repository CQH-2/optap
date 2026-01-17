#!/bin/bash

# 设置 Java 21 环境
export JAVA_HOME=/home/codespace/java/21.0.9-ms
export PATH=$JAVA_HOME/bin:$PATH

# 进入项目目录
cd "$(dirname "$0")"

# 运行项目
echo "启动 ProjectJobScheduling 示例..."
mvn exec:java
