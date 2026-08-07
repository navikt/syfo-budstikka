import org.gradle.plugin.use.PluginDependency

plugins {
    `kotlin-dsl`
}

// A convention plugin applies external plugins from this build's classpath; a plugin's marker
// artifact resolves to its implementation.
fun plugin(dependency: Provider<PluginDependency>): Provider<String> = dependency.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
    implementation(plugin(libs.plugins.kotlin.jvm))
    implementation(plugin(libs.plugins.japicmp))
}
