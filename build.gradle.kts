import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java`
    // 打胖 jar（把数据库驱动合入插件 jar）
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "com.starinn"
version = "1.2.0"

java {
    // Velocity 3.4.x 运行于 Java 21
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
    // velocity-api 所在仓库
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Velocity 运行时自带 velocity-api（含 Adventure / MiniMessage / snakeyaml / configurate），
    // 必须是 compileOnly，绝不能打进 jar、也绝不能 relocate。
    compileOnly("com.velocitypowered:velocity-api:3.4.0")

    // 数据库驱动：Velocity 不自带，必须用 shadowJar 打进插件 jar
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    implementation("com.mysql:mysql-connector-j:9.0.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<ShadowJar>().configureEach {
    // 合并两个驱动各自的 META-INF/services/java.sql.Driver，
    // 否则 DriverManager 发现不了驱动，会 ClassNotFoundException。
    mergeServiceFiles()
    // 胖 jar 去掉 -all 后缀，产物即 build/libs/StarChat-<版本>.jar
    archiveClassifier.set("")
    // 说明：不做 relocate。Velocity 为每个插件使用独立类加载器，
    // 驱动不会与其他插件互相污染；而 sqlite-jdbc 内部以硬编码字符串
    // "/org/sqlite/native/..." 查找内置本地库，relocate 反而会破坏它。
    // velocity-api 为 compileOnly，本就不在 jar 内，天然满足"不 relocate api"的要求。
}

tasks.jar {
    // 只要胖 jar，避免 build/libs 出现两个 jar 造成误装
    enabled = false
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
