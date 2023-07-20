/*
 * Copyright (C) 2023 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.helper.commands

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.readValue

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file

import org.ossreviewtoolkit.helper.utils.writeOrtResult
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.AnalyzerRun
import org.ossreviewtoolkit.model.FileFormat
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.PackageReference
import org.ossreviewtoolkit.model.Project
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.Repository
import org.ossreviewtoolkit.model.ResolvedPackageCurations
import org.ossreviewtoolkit.model.Scope
import org.ossreviewtoolkit.model.VcsInfo
import org.ossreviewtoolkit.model.VcsType
import org.ossreviewtoolkit.model.config.Excludes
import org.ossreviewtoolkit.model.config.OrtConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.config.ScopeExclude
import org.ossreviewtoolkit.model.config.ScopeExcludeReason
import org.ossreviewtoolkit.model.orEmpty
import org.ossreviewtoolkit.model.utils.addPackageCurations
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.PackageCurationProviderFactory
import org.ossreviewtoolkit.plugins.packagecurationproviders.api.SimplePackageCurationProvider
import org.ossreviewtoolkit.utils.common.expandTilde
import org.ossreviewtoolkit.utils.ort.ORT_CONFIG_FILENAME
import org.ossreviewtoolkit.utils.ort.ortConfigDirectory

internal class CreateFlatAnalyzerResultCommand : CliktCommand(
    "A command which turns a simple definition file into an analyzer result."
) {
    private val simpleDefinitionFile by option(
        "--simple-definition-file", "-i",
        help = "The simple definition file to read the project definition from."
    ).convert { it.expandTilde() }
        .file(mustExist = false, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .convert { it.absoluteFile.normalize() }
        .required()

    private val ortFile by option(
        "--ort-file", "-o",
        help = "The ORT file to write the generated synthetic analyzer result to."
    ).convert { it.expandTilde() }
        .file(mustExist = false, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = false)
        .convert { it.absoluteFile.normalize() }
        .required()

    private val configFile by option(
        "--config",
        help = "The path to the ORT configuration file that configures the scan results storage."
    ).convert { it.expandTilde() }
        .file(mustExist = true, canBeFile = true, canBeDir = false, mustBeWritable = false, mustBeReadable = true)
        .convert { it.absoluteFile.normalize() }
        .default(ortConfigDirectory.resolve(ORT_CONFIG_FILENAME))

    override fun run() {
        val definitionFile = FileFormat.forFile(simpleDefinitionFile).mapper.readValue<SimpleDefinitionFile>(
            simpleDefinitionFile
        )

        val projectVcs = VcsInfo(
            type = definitionFile.vcsType ?: VcsType.UNKNOWN,
            url = definitionFile.vcsUrl.orEmpty(),
            revision = definitionFile.vcsRevision.orEmpty(),
            path = definitionFile.vcsPath.orEmpty()
        )

        val projectName = definitionFile.name?.takeUnless { it.isBlank() } ?: DEFAULT_PROJECT_NAME

        val project = Project.EMPTY.copy(
            id = Identifier("Unmanaged::$projectName:"),
            vcs = projectVcs,
            vcsProcessed = projectVcs.normalize(),
            scopeDependencies = setOfNotNull(
                definitionFile.dependencies.filterNot { it.isExcluded }.toScope(MAIN_SCOPE_NAME),
                definitionFile.dependencies.filter { it.isExcluded }.toScope(EXCLUDED_SCOPE_NAME)
            )
        )

        val ortConfig = OrtConfiguration.load(emptyMap(), configFile)

        val packageCurationProviders = buildList {
            addAll(PackageCurationProviderFactory.create(ortConfig.packageCurationProviders))
        }

        val ortResult = OrtResult(
            analyzer = AnalyzerRun.EMPTY.copy(
                result = AnalyzerResult(
                    projects = setOf(project),
                    packages = definitionFile.dependencies.mapTo(mutableSetOf()) { it.toPackage() }
                )
            ),
            repository = Repository(
                vcs = projectVcs.normalize(),
                config = RepositoryConfiguration(
                    excludes = Excludes(
                        scopes = listOf(
                            ScopeExclude(EXCLUDED_SCOPE_NAME, ScopeExcludeReason.DEV_DEPENDENCY_OF)
                        )
                    )
                )
            )
        ).addPackageCurations(packageCurationProviders)

        writeOrtResult(ortResult, ortFile)
    }
}

private const val DEFAULT_PROJECT_NAME = "unknown"
private const val EXCLUDED_SCOPE_NAME = "excluded"
private const val MAIN_SCOPE_NAME = "main"

private data class SimpleDefinitionFile(
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val name: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsType: VcsType? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsUrl: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsRevision: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsPath: String? = null,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    val dependencies: List<Dependency> = emptyList()
)

private data class Dependency(
    val id: Identifier,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsType: VcsType? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsUrl: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsRevision: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val vcsPath: String? = null,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    val sourceArtifactUrl: String? = null,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val isExcluded: Boolean = false,
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    val isDynamicallyLinked: Boolean = false
)

private fun Collection<Dependency>.toScope(name: String) = Scope(
    name = name,
    dependencies = mapTo(mutableSetOf()) { dependency ->
        PackageReference(
            id = dependency.id,
            linkage = PackageLinkage.STATIC.takeUnless { dependency.isDynamicallyLinked } ?: PackageLinkage.DYNAMIC
        )
    }
)

private fun Dependency.toPackage(): Package {
    val vcs = VcsInfo(
        type = vcsType ?: VcsType.UNKNOWN,
        url = vcsUrl.orEmpty(),
        revision = vcsRevision.orEmpty(),
        path = vcsPath.orEmpty()
    )

    return Package.EMPTY.copy(
        id = id,
        sourceArtifact = sourceArtifactUrl?.let { RemoteArtifact(url = it, Hash.NONE) }.orEmpty(),
        vcs = vcs,
        vcsProcessed = vcs.normalize()
    )
}
