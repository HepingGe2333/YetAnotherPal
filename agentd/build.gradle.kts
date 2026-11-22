plugins {
    kotlin("jvm")
    application
}

// `agentd` 是一个「零第三方依赖」的独立 JVM 服务：
// 只用 JDK 自带的 `com.sun.net.httpserver`（JDK 17 的 jdk.httpserver 模块）。
//
// 0.11.0 起它**不再用 `kotlin.srcDir(...)` + exclude 复用 Android 源码**，而是直接依赖 `:agentcore`：
// 两端（Android / JVM 服务）编译同一份 class，不存在「排除列表漂了就会少一个文件」的问题。
// 决策的实现只有一份：`InProcessAgentCore`（在 :agentcore），本模块只负责 HTTP 路由与校验。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.yap.agentd.MainKt")
}

dependencies {
    // 运行时零第三方依赖；唯一依赖是仓库内的 :agentcore（纯 Kotlin/JVM，自己也是零第三方）。
    implementation(project(":agentcore"))
    // JUnit 只进测试源集（而且它已经在本机 Gradle 缓存里）。
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    /**
     * 0.12.1 的「备份不进内存」是硬要求，测试用 **256 MiB 堆**跑：`BackupStreamMemoryTest`
     * 在 `-Xmx256m` 下导出/导入 1 万条消息 + 50 张图，只要实现又退回「整份 JsonValue/字符串」
     * 就会 OOM（这是断言，不是口号）。整套 `:agentd:test` 都很小，256m 足够。
     */
    maxHeapSize = "256m"
    testLogging {
        events("passed", "failed", "skipped")
    }
}

/**
 * 自包含可执行 jar：`java -jar agentd/build/libs/agentd-all.jar --port 8788`。
 *
 * 为什么需要它：普通 `jar` 任务只打自己的 class，Kotlin 标准库不在里面，`java -jar` 会
 * `NoClassDefFoundError: kotlin/...`。这里不用任何第三方打包插件（保持零依赖），
 * 直接把 runtimeClasspath 解进同一个 jar，并写上 Main-Class。
 */
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Build a self-contained runnable jar (Main-Class + Kotlin stdlib)."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "com.yap.agentd.MainKt"
    }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}


/**
 * 桌面端真人测试工具（无 UI）：复用 agentd 的存储/提示词/工具/记忆/心情/日记/进化逻辑，
 * 只在终端里对话。`./gradlew :agentd:desktop --args="--data /tmp/yap-desktop ..."`。
 */
tasks.register<JavaExec>("desktop") {
    group = "application"
    description = "Run the terminal-only prompt-engineering test console."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.yap.agentd.desktop.DesktopMainKt")
    standardInput = System.`in`
}

tasks.register<Jar>("desktopJar") {
    group = "distribution"
    description = "Self-contained terminal console jar."
    archiveClassifier.set("desktop")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.yap.agentd.desktop.DesktopMainKt" }
    from(sourceSets.main.get().output)
    from({
        configurations.runtimeClasspath.get().map { file ->
            if (file.isDirectory) file else zipTree(file)
        }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }
}
