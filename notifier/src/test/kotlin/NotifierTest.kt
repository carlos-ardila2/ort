/*
 * Copyright (C) 2022 The ORT Project Authors (see <https://github.com/oss-review-toolkit/ort/blob/main/NOTICE>)
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

package org.ossreviewtoolkit.notifier

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.types.shouldBeInstanceOf

import kotlin.script.experimental.api.ResultValue

import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

import org.ossreviewtoolkit.model.config.JiraConfiguration
import org.ossreviewtoolkit.model.config.NotifierConfiguration
import org.ossreviewtoolkit.model.config.OrtConfiguration
import org.ossreviewtoolkit.model.config.SendMailConfiguration

class NotifierTest : WordSpec({
    "Client properties" should {
        "only be available if configured" {
            val sendMailConfigModule = module {
                single { OrtConfiguration(notifier = NotifierConfiguration(mail = SendMailConfiguration())) }
            }

            loadKoinModules(sendMailConfigModule)
            val mailNotifier = Notifier()

            assertSoftly {
                shouldNotThrow<RuntimeException> {
                    val result = mailNotifier.runScript(
                        """
                        mailClient as MailNotifier
                        resolutionProvider as ResolutionProvider
                        """.trimIndent()
                    )
                    result.shouldBeInstanceOf<ResultValue.Value>()
                }

                shouldThrow<RuntimeException> {
                    mailNotifier.runScript("jiraClient")
                }
            }

            val jiraConfigModule = module {
                single { OrtConfiguration(notifier = NotifierConfiguration(jira = JiraConfiguration())) }
            }

            loadKoinModules(jiraConfigModule)
            val jiraNotifier = Notifier()

            assertSoftly {
                shouldThrow<RuntimeException> {
                    jiraNotifier.runScript("mailClient")
                }

                shouldNotThrow<RuntimeException> {
                    val result = jiraNotifier.runScript(
                        """
                        jiraClient as JiraNotifier
                        resolutionProvider as ResolutionProvider
                        """.trimIndent()
                    )
                    result.shouldBeInstanceOf<ResultValue.Value>()
                }
            }
        }
    }
})
