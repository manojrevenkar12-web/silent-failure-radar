dependencies {
    implementation(project(":core"))
    implementation(project(":world"))
    implementation("tools.jackson.core:jackson-databind")
}

fun evalTask(name: String, arg: String, text: String) = tasks.register<JavaExec>(name) {
    description = text
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("radar.eval.Evaluate")
    args(arg)
    workingDir = rootDir
    maxHeapSize = "3g"
}

evalTask("evaluate", "dev", "Runs every design on the development seeds and writes reports/dev.json.")
evalTask("heldout", "heldout", "Runs the held-out seeds once; a second run refuses.")
evalTask("readme", "readme", "Writes REPORT.md, the README results block and the example monitors from reports/.")
evalTask("gate", "gate", "Fails if a report misses a threshold in config/gates.json.")
