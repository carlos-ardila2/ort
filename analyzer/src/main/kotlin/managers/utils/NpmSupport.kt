/*
 * Copyright (C) 2017-2019 HERE Europe B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 * License-Filename: LICENSE
 */

@file:Suppress("TooManyFunctions")

package org.ossreviewtoolkit.analyzer.managers.utils

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.node.ArrayNode

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.PathMatcher

import org.ossreviewtoolkit.analyzer.managers.Yarn2
import org.ossreviewtoolkit.model.readTree
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.utils.common.collectMessages
import org.ossreviewtoolkit.utils.common.toUri
import org.ossreviewtoolkit.utils.ort.logger
import org.ossreviewtoolkit.utils.ort.showStackTrace

/**
 * A dummy object to provide a logger for top-level functions.
 *
 * TODO: Remove this once https://youtrack.jetbrains.com/issue/KT-21599 is implemented.
 */
object NpmSupport

/**
 * Expand an NPM shortcut [url] to a regular URL as used for dependencies, see
 * https://docs.npmjs.com/cli/v7/configuring-npm/package-json#urls-as-dependencies.
 */
fun expandNpmShortcutUrl(url: String): String {
    // A hierarchical URI looks like
    //     [scheme:][//authority][path][?query][#fragment]
    // where a server-based "authority" has the syntax
    //     [user-info@]host[:port]
    val uri = url.toUri().getOrElse {
        // Fall back to returning the original URL.
        return url
    }

    val path = uri.schemeSpecificPart

    // Do not mess with crazy URLs.
    if (path.startsWith("git@") || path.startsWith("github.com") || path.startsWith("gitlab.com")) return url

    return if (!path.isNullOrEmpty() && listOf(uri.authority, uri.query).all { it == null }) {
        // See https://docs.npmjs.com/cli/v7/configuring-npm/package-json#github-urls.
        val revision = uri.fragment?.let { "#$it" }.orEmpty()

        // See https://docs.npmjs.com/cli/v7/configuring-npm/package-json#repository.
        when (uri.scheme) {
            null, "github" -> "https://github.com/$path.git$revision"
            "gist" -> "https://gist.github.com/$path$revision"
            "bitbucket" -> "https://bitbucket.org/$path.git$revision"
            "gitlab" -> "https://gitlab.com/$path.git$revision"
            else -> url
        }
    } else {
        url
    }
}

/**
 * Return whether the [directory] contains an NPM lock file.
 */
fun hasNpmLockFile(directory: File) =
    NPM_LOCK_FILES.any { lockfile ->
        File(directory, lockfile).isFile
    }

private val NPM_LOCK_FILES = listOf("npm-shrinkwrap.json", "package-lock.json")

/**
 * Return whether the [directory] contains a PNPM lock file.
 */
fun hasPnpmLockFile(directory: File) =
    PNPM_LOCK_FILES.any { lockfile ->
        File(directory, lockfile).isFile
    }

private val PNPM_LOCK_FILES = listOf("pnpm-lock.yaml")

/**
 * Return whether the [directory] contains a Yarn lock file.
 */
fun hasYarnLockFile(directory: File) =
    YARN_LOCK_FILES.any { lockfile ->
        File(directory, lockfile).isFile
    }

private val YARN_LOCK_FILES = listOf("yarn.lock")

/**
 * Return whether the [directory] contains a Yarn resource file in YAML format, specific to Yarn 2+.
 * Yarn1 has a non-YAML `.yarnrc` configuration file.
 */
fun hasYarn2ResourceFile(directory: File) = directory.resolve(Yarn2.YARN2_RESOURCE_FILE).isFile

/**
 * Map [definitionFiles] to contain only files handled by NPM.
 */
fun mapDefinitionFilesForNpm(definitionFiles: Collection<File>): Set<File> =
    getPackageJsonInfo(definitionFiles.toSet())
        .filter { !it.isHandledByYarn && !it.isHandledByPnpm }
        .mapTo(mutableSetOf()) { it.definitionFile }

/**
 * Map [definitionFiles] to contain only files handled by PNPM.
 */
fun mapDefinitionFilesForPnpm(definitionFiles: Collection<File>): Set<File> =
    getPackageJsonInfo(definitionFiles.toSet())
        .filter { it.isHandledByPnpm && !it.isPnpmWorkspaceSubmodule }
        .mapTo(mutableSetOf()) { it.definitionFile }

/**
 * Map [definitionFiles] to contain only files handled by Yarn.
 */
fun mapDefinitionFilesForYarn(definitionFiles: Collection<File>): Set<File> =
    getPackageJsonInfo(definitionFiles.toSet())
        .filter { it.isHandledByYarn && !it.isYarnWorkspaceSubmodule && !it.hasYarn2ResourceFile }
        .mapTo(mutableSetOf()) { it.definitionFile }

/**
 * Map [definitionFiles] to contain only files handled by Yarn 2+.
 */
fun mapDefinitionFilesForYarn2(definitionFiles: Collection<File>): Set<File> =
    getPackageJsonInfo(definitionFiles.toSet())
        .filter { it.isHandledByYarn && !it.isYarnWorkspaceSubmodule && it.hasYarn2ResourceFile }
        .mapTo(mutableSetOf()) { it.definitionFile }

private data class PackageJsonInfo(
    val definitionFile: File,
    val hasYarnLockfile: Boolean = false,
    val hasYarn2ResourceFile: Boolean = false,
    val hasNpmLockfile: Boolean = false,
    val hasPnpmLockfile: Boolean = false,
    val isPnpmWorkspaceRoot: Boolean = false,
    val isPnpmWorkspaceSubmodule: Boolean = false,
    val isYarnWorkspaceRoot: Boolean = false,
    val isYarnWorkspaceSubmodule: Boolean = false
) {
    val isHandledByPnpm = isPnpmWorkspaceRoot || isPnpmWorkspaceSubmodule || hasPnpmLockfile
    val isHandledByYarn = isYarnWorkspaceRoot || isYarnWorkspaceSubmodule || hasYarnLockfile
}

private fun getPackageJsonInfo(definitionFiles: Set<File>): Collection<PackageJsonInfo> {
    fun isPnpmWorkspaceRoot(directory: File) = directory.resolve("pnpm-workspace.yaml").isFile

    fun isYarnWorkspaceRoot(definitionFile: File) =
        try {
            definitionFile.readTree().has("workspaces")
        } catch (e: JsonProcessingException) {
            e.showStackTrace()

            NpmSupport.logger.error {
                "Could not parse '${definitionFile.invariantSeparatorsPath}': ${e.collectMessages()}"
            }

            false
        }

    val pnpmWorkspaceSubmodules = getPnpmWorkspaceSubmodules(definitionFiles)
    val yarnWorkspaceSubmodules = getYarnWorkspaceSubmodules(definitionFiles)

    return definitionFiles.map { definitionFile ->
        PackageJsonInfo(
            definitionFile = definitionFile,
            isPnpmWorkspaceRoot = isPnpmWorkspaceRoot(definitionFile.parentFile),
            isYarnWorkspaceRoot = isYarnWorkspaceRoot(definitionFile) &&
                    !isPnpmWorkspaceRoot(definitionFile.parentFile),
            hasYarnLockfile = hasYarnLockFile(definitionFile.parentFile),
            hasNpmLockfile = hasNpmLockFile(definitionFile.parentFile),
            hasPnpmLockfile = hasPnpmLockFile(definitionFile.parentFile),
            hasYarn2ResourceFile = hasYarn2ResourceFile(definitionFile.parentFile),
            isPnpmWorkspaceSubmodule = definitionFile in pnpmWorkspaceSubmodules,
            isYarnWorkspaceSubmodule = definitionFile in yarnWorkspaceSubmodules &&
                    definitionFile !in pnpmWorkspaceSubmodules
        )
    }
}

private fun getPnpmWorkspaceMatchers(definitionFile: File): List<PathMatcher> {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class PnpmWorkspaces(val packages: List<String>)

    fun String.isComment() = trim().startsWith("#")

    val pnpmWorkspaceFile = definitionFile.parentFile.resolve("pnpm-workspace.yaml")

    return if (pnpmWorkspaceFile.isFile && pnpmWorkspaceFile.readLines().any { !it.isComment() }) {
        val workspaceMatchers = pnpmWorkspaceFile.readValue<PnpmWorkspaces>().packages

        workspaceMatchers.map { matcher ->
            val pattern = "glob:${definitionFile.parentFile.invariantSeparatorsPath}/${matcher.removeSuffix("/")}"
            FileSystems.getDefault().getPathMatcher(pattern)
        }
    } else {
        // Empty "pnpm-workspace.yaml" files can be used for regular projects within a workspace setup, see
        // https://github.com/pnpm/pnpm/issues/2412.
        emptyList()
    }
}

private fun getPnpmWorkspaceSubmodules(definitionFiles: Set<File>): Set<File> {
    val result = mutableSetOf<File>()

    definitionFiles.forEach { definitionFile ->
        val pnpmWorkspacesFile = definitionFile.parentFile.resolve("pnpm-workspace.yaml")

        if (pnpmWorkspacesFile.isFile) {
            val pathMatchers = getPnpmWorkspaceMatchers(definitionFile)

            pathMatchers.forEach { matcher ->
                definitionFiles.forEach inner@{ other ->
                    val projectDir = other.parentFile.toPath()
                    if (other != definitionFile && matcher.matches(projectDir)) {
                        result += other
                        return@inner
                    }
                }
            }
        } else {
            return@forEach
        }
    }

    return result
}

private fun getYarnWorkspaceSubmodules(definitionFiles: Set<File>): Set<File> {
    fun getWorkspaceMatchers(definitionFile: File): List<PathMatcher> {
        var workspaces = try {
            definitionFile.readTree().get("workspaces")
        } catch (e: JsonProcessingException) {
            e.showStackTrace()

            NpmSupport.logger.error {
                "Could not parse '${definitionFile.invariantSeparatorsPath}': ${e.collectMessages()}"
            }

            null
        }

        if (workspaces != null && workspaces !is ArrayNode) {
            workspaces = workspaces["packages"]
        }

        return workspaces?.map {
            val pattern = "glob:${definitionFile.parentFile.invariantSeparatorsPath}/${it.textValue()}"
            FileSystems.getDefault().getPathMatcher(pattern)
        }.orEmpty()
    }

    val result = mutableSetOf<File>()

    definitionFiles.forEach { definitionFile ->
        val workspaceMatchers = getWorkspaceMatchers(definitionFile)
        workspaceMatchers.forEach { matcher ->
            definitionFiles.forEach inner@{ other ->
                // Since yarn workspaces matchers support '*' and '**' to match multiple directories the matcher
                // cannot be used as is for matching the 'package.json' file. Thus matching against the project
                // directory since this works out of the box. See also:
                //   https://github.com/yarnpkg/yarn/issues/3986
                //   https://github.com/yarnpkg/yarn/pull/5607
                val projectDir = other.parentFile.toPath()
                if (other != definitionFile && matcher.matches(projectDir)) {
                    result += other
                    return@inner
                }
            }
        }
    }

    return result
}
