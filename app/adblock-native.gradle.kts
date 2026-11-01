// Builds native/adblock (Rust, adblock-rust + JNI) with cargo-ndk and packages libmonoadblock.so
// for every ABI. Applied from app/build.gradle.kts with: apply(from = "adblock-native.gradle.kts")
//
// Requirements (one-time):  rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
//                           cargo install cargo-ndk        (NDK 27.x installed in the Android SDK)
// Properties:  -Padblock.abis=arm64-v8a,x86_64   (default: all three)   -Padblock.skipNative=true  (no Rust build)

import java.io.File
import javax.inject.Inject

abstract class CargoNdkTask @Inject constructor(private val execOps: ExecOperations) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val crateDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Input
    abstract val ndkDir: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sources: FileTree
        get() = crateDir.asFileTree.matching {
            include("src/**", "Cargo.toml", "Cargo.lock", ".cargo/**")
        }

    @TaskAction
    fun build() {
        val home = System.getProperty("user.home")
        val args = mutableListOf("cargo", "ndk")
        abis.get().forEach { args += listOf("-t", it) }
        args += listOf("-P", minSdk.get().toString(), "-o", outputDir.get().asFile.absolutePath, "build", "--release")
        outputDir.get().asFile.deleteRecursively()
        execOps.exec {
            workingDir = crateDir.get().asFile
            commandLine(args)
            environment("ANDROID_NDK_HOME", ndkDir.get())
            // Gradle daemons often miss ~/.cargo/bin; cargo-ndk lives there.
            environment("PATH", "$home/.cargo/bin:" + System.getenv("PATH").orEmpty())
        }
    }
}

fun locateSdk(): File? {
    val lp = rootProject.file("local.properties")
    if (lp.exists()) {
        val p = java.util.Properties().apply { lp.inputStream().use { load(it) } }
        p.getProperty("sdk.dir")?.let { return File(it) }
    }
    return listOf("ANDROID_HOME", "ANDROID_SDK_ROOT").mapNotNull { System.getenv(it) }.firstOrNull()?.let(::File)
}

fun locateNdk(): String {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { File(it).isDirectory }?.let { return it }
    val ndkRoot = File(locateSdk() ?: error("Android SDK not found (set sdk.dir in local.properties)"), "ndk")
    val preferred = File(ndkRoot, "27.1.12297006")
    if (preferred.isDirectory) return preferred.absolutePath
    return ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }?.absolutePath
        ?: error("No NDK found in $ndkRoot (install NDK 27.1.12297006)")
}

if (providers.gradleProperty("adblock.skipNative").orNull != "true") {
    val cargoNdk = tasks.register<CargoNdkTask>("cargoNdkBuild") {
        group = "build"
        description = "Builds the Rust adblock engine for Android ABIs with cargo-ndk"
        crateDir.set(rootProject.layout.projectDirectory.dir("native/adblock"))
        abis.set(
            (providers.gradleProperty("adblock.abis").orNull ?: "arm64-v8a,armeabi-v7a,x86_64")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() },
        )
        minSdk.set(26)
        ndkDir.set(providers.provider { locateNdk() })
        outputDir.set(layout.buildDirectory.dir("rustJniLibs"))
    }

    // The output dir is registered as a jniLibs source dir in app/build.gradle.kts (typed android {} block).
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
        .configureEach { dependsOn(cargoNdk) }
}
