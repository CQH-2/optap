# Project Job Scheduling 官方示例

## 项目说明

本项目包含 OptaPlanner 官方的 Project Job Scheduling（项目任务调度）示例。
所有代码从 `projectjobscheduling` 目录移动而来，保持了官方示例的原始代码结构。

## 项目结构

```
com.iimsoft.scheduler/
├── pom.xml                                 # Maven 配置文件
├── run.sh                                  # 运行脚本
├── src/main/
│   ├── java/com/iimsoft/scheduler/        # 源代码目录
│   │   ├── app/                           # 应用程序主类（命令行版本）
│   │   ├── domain/                        # 领域模型
│   │   ├── persistence/                   # 数据持久化
│   │   └── score/                         # 评分计算
│   └── resources/
│       └── logback.xml                    # 日志配置
```

## 如何运行

### 方式 1：使用运行脚本（推荐）

```bash
cd /workspaces/optap/com.iimsoft.scheduler
./run.sh
```

### 方式 2：使用 Maven 命令

```bash
cd /workspaces/optap/com.iimsoft.scheduler

# 设置 Java 21 环境
export JAVA_HOME=/home/codespace/java/21.0.9-ms
export PATH=$JAVA_HOME/bin:$PATH

# 编译并运行
mvn clean compile
mvn exec:java
```

### 方式 3：只编译

```bash
cd /workspaces/optap/com.iimsoft.scheduler

# 设置 Java 21 环境
export JAVA_HOME=/home/codespace/java/21.0.9-ms
export PATH=$JAVA_HOME/bin:$PATH

# 只编译
mvn clean compile
```

## 依赖说明

项目使用以下主要依赖：

- **OptaPlanner Core** (9.44.0.Final) - OptaPlanner 核心库  
- **OptaPlanner Examples** (9.44.0.Final) - OptaPlanner 示例通用类库（已排除 JFreeChart/Swing UI 依赖）
- **Jackson** (2.15.2) - JSON 处理
- **Logback** (1.4.5) - 日志框架
- **Lombok** (1.18.24) - 代码简化工具

## 注意事项

1. **Java 版本要求**：本项目需要 Java 17 或更高版本，推荐使用 Java 21
2. **UI 界面**：此版本已移除 Swing UI 界面，改为命令行方式运行
3. **主类**：`com.iimsoft.schduler.app.ProjectJobSchedulingApp`
4. **领域模型**：项目主要使用 OptaPlanner Examples 的领域模型类

## 技术栈

- Java 17+
- Maven 4.x
- OptaPlanner 9.44.0.Final
