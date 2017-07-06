/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.kotlin.dsl.resolver

import org.gradle.kotlin.dsl.tooling.models.KotlinBuildScriptModel
import org.gradle.tooling.ProgressListener
import org.jetbrains.kotlin.com.intellij.util.containers.SoftKeySoftValueHashMap
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.*
import java.util.Arrays.equals
import kotlin.script.dependencies.DependenciesResolver.ResolveResult
import kotlin.script.dependencies.Environment
import kotlin.script.dependencies.ScriptContents
import kotlin.script.dependencies.ScriptDependencies
import kotlin.script.dependencies.asSuccess
import kotlin.script.dependencies.experimental.AsyncDependenciesResolver


// this is a workaround for kotlin pre 1.1.4 plugin version requiring primary constructor to be parameterless
class KotlinBuildScriptDependenciesResolver: KotlinBuildScriptDependenciesResolverBase(RemoteBuildScriptModelFetcher())

open class KotlinBuildScriptDependenciesResolverBase internal constructor(
    private val modelFetcher: BuildScriptModelFetcher
): AsyncDependenciesResolver {

    private class CacheKey(val filePath: String, val buildscriptBlockHash: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other?.javaClass != javaClass) return false

            other as CacheKey

            if (filePath != other.filePath) return false
            if (!equals(buildscriptBlockHash, other.buildscriptBlockHash)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = filePath.hashCode()
            result = 31 * result + Arrays.hashCode(buildscriptBlockHash)
            return result
        }
    }

    private val cache = SoftKeySoftValueHashMap<CacheKey, ResolveResult>()

    override suspend fun resolveAsync(
        scriptContents: ScriptContents,
        environment: Environment
    ): ResolveResult {
        try {
            val cacheKey = createCacheKey(scriptContents, environment)
            if (cacheKey != null) {
                val previousResult = synchronized(cache) { cache[cacheKey] }
                if (previousResult != null) {
                    log(ResolvedToPrevious(scriptContents.file, environment, previousResult.dependencies))
                    return previousResult
                }
            }

            return assembleDependenciesFrom(scriptContents.file, environment).also {
                synchronized(cache) { cache[cacheKey] = it }
            }

        } catch (e: Exception) {
            log(ResolutionFailure(scriptContents.file, e))
            throw e
        }
    }

    private
    fun createCacheKey(script: ScriptContents, environment: Environment): CacheKey? {
        val filePath = script.file?.canonicalPath ?: return null
        val buildscriptBlockHash = buildscriptBlockHashFor(script, environment) ?: return null
        return CacheKey(filePath, buildscriptBlockHash)
    }

    private
    suspend fun assembleDependenciesFrom(
        scriptFile: File?,
        environment: Environment
    ): ResolveResult {

        val request = modelRequestFrom(scriptFile, environment)
        log(SubmittedModelRequest(scriptFile, request))

        val response = submit(request, progressLogger(scriptFile))
        log(ReceivedModelResponse(scriptFile, response))

        val scriptDependencies = dependenciesFrom(response)
        log(ResolvedDependencies(scriptFile, scriptDependencies))

        return scriptDependencies.asSuccess()
    }

    private
    fun modelRequestFrom(scriptFile: File?, environment: Environment): KotlinBuildScriptModelRequest {

        @Suppress("unchecked_cast")
        fun stringList(key: String) =
            (environment[key] as? List<String>) ?: emptyList()

        fun path(key: String) =
            (environment[key] as? String)?.let(::File)

        val importedProjectRoot = environment["projectRoot"] as File
        return KotlinBuildScriptModelRequest(
            projectDir = scriptFile?.let { projectRootOf(it, importedProjectRoot) } ?: importedProjectRoot,
            scriptFile = scriptFile,
            gradleInstallation = gradleInstallationFrom(environment),
            gradleUserHome = path("gradleUserHome"),
            javaHome = path("gradleJavaHome"),
            options = stringList("gradleOptions"),
            jvmOptions = stringList("gradleJvmOptions"))
    }

    private
    suspend fun submit(request: KotlinBuildScriptModelRequest, progressListener: ProgressListener): KotlinBuildScriptModel =
        modelFetcher.fetch(request) {
            addProgressListener(progressListener)
        }

    private
    fun progressLogger(scriptFile: File?) =
        ProgressListener { log(ResolutionProgress(scriptFile, it.description)) }

    private
    fun gradleInstallationFrom(environment: Environment): GradleInstallation =
        (environment["gradleHome"] as? File)?.let(GradleInstallation::Local)
            ?: (environment["gradleUri"] as? URI)?.let(GradleInstallation::Remote)
            ?: (environment["gradleVersion"] as? String)?.let(GradleInstallation::Version)
            ?: GradleInstallation.Wrapper

    private
    fun dependenciesFrom(
        response: KotlinBuildScriptModel) =

        ScriptDependencies(
            classpath = response.classPath,
            sources = response.sourcePath,
            imports = response.implicitImports
        )

    private
    fun log(event: ResolverEvent) =
        ResolverEventLogger.log(event)
}

internal
interface BuildScriptModelFetcher {
    suspend fun fetch(
        request: KotlinBuildScriptModelRequest,
        modelBuilderCustomization: ModelBuilderCustomization = {}): KotlinBuildScriptModel
}

private
fun buildscriptBlockHashFor(script: ScriptContents, environment: Environment): ByteArray? {

    @Suppress("unchecked_cast")
    val getScriptSectionTokens = environment["getScriptSectionTokens"] as? ScriptSectionTokensProvider ?: return null
    val messageDigest = MessageDigest.getInstance("MD5")
    val text = script.text ?: script.file?.readText() ?: return null

    fun updateWith(section: String) =
        getScriptSectionTokens(text, section).forEach {
            messageDigest.update(it.toString().toByteArray())
        }

    updateWith("buildscript")
    updateWith("plugins")
    return messageDigest.digest()
}


internal
typealias ScriptSectionTokensProvider = (CharSequence, String) -> Sequence<CharSequence>


internal
fun projectRootOf(scriptFile: File, importedProjectRoot: File): File {

    fun isProjectRoot(dir: File) = File(dir, "settings.gradle").isFile

    tailrec fun test(dir: File): File =
        when {
            dir == importedProjectRoot -> importedProjectRoot
            isProjectRoot(dir)         -> dir
            else                       -> {
                val parentDir = dir.parentFile
                when (parentDir) {
                    null, dir -> scriptFile.parentFile // external project
                    else      -> test(parentDir)
                }
            }
        }

    return test(scriptFile.parentFile)
}
