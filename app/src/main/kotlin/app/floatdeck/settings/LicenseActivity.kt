@file:Suppress("ktlint:standard:function-naming")

package app.floatdeck.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.floatdeck.R
import app.floatdeck.BuildConfig

/** Open source licenses screen Activity. */
class LicenseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    LicenseScreen(onBack = { finish() })
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.open_source_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // FloatDeck self declaration
            item {
                ProjectLicenseCard(
                    project = OpenSourceProject(
                        name = "FloatDeck",
                        version = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        license = LicenseType.MIT,
                        url = "https://github.com/windrunner/FloatDeck",
                    ),
                )
            }

            // Grouped by license type
            val grouped = OpenSourceProjects.all.groupBy { it.license }
            grouped.forEach { (license, projects) ->
                item {
                    HorizontalDivider()
                    Text(
                        text = license.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                items(projects) { project ->
                    ProjectLicenseCard(
                        project = project,
                        onClickUrl = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(project.url)),
                            )
                        },
                    )
                }
                item {
                    LicenseTextCard(license = license)
                }
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun ProjectLicenseCard(
    project: OpenSourceProject,
    onClickUrl: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = project.license.shortName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (project.version.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.version,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onClickUrl != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = project.url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(onClick = onClickUrl),
                )
            }
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun LicenseTextCard(license: LicenseType) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = license.fullText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Open source project information. */
data class OpenSourceProject(
    val name: String,
    val version: String,
    val license: LicenseType,
    val url: String,
)

/** License type with full legal text. */
sealed class LicenseType(
    val shortName: String,
    val displayName: String,
    val fullText: String,
) {
    data object Apache2 : LicenseType(
        shortName = "Apache-2.0",
        displayName = "Apache License 2.0",
        fullText = """
            Licensed under the Apache License, Version 2.0 (the "License");
            you may not use this file except in compliance with the License.
            You may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

            Unless required by applicable law or agreed to in writing, software
            distributed under the License is distributed on an "AS IS" BASIS,
            WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
            See the License for the specific language governing permissions and
            limitations under the License.
        """.trimIndent(),
    )

    data object MIT : LicenseType(
        shortName = "MIT",
        displayName = "MIT License",
        fullText = """
            Permission is hereby granted, free of charge, to any person obtaining a copy
            of this software and associated documentation files (the "Software"), to deal
            in the Software without restriction, including without limitation the rights
            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
            copies of the Software, and to permit persons to whom the Software is
            furnished to do so, subject to the following conditions:

            The above copyright notice and this permission notice shall be included in all
            copies or substantial portions of the Software.

            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
            SOFTWARE.
        """.trimIndent(),
    )

    data object EPL2 : LicenseType(
        shortName = "EPL-2.0",
        displayName = "Eclipse Public License 2.0",
        fullText = """
            This program and the accompanying materials are made available under the
            terms of the Eclipse Public License v. 2.0 which is available at
            https://www.eclipse.org/legal/epl-2.0/.

            SPDX-License-Identifier: EPL-2.0
        """.trimIndent(),
    )
}

/** Predefined list of open source dependencies. */
object OpenSourceProjects {
    val all = listOf(
        // AndroidX
        OpenSourceProject(
            name = "AndroidX Core KTX",
            version = "",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/androidx/releases/core",
        ),
        OpenSourceProject(
            name = "AndroidX Lifecycle",
            version = "",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/androidx/releases/lifecycle",
        ),
        OpenSourceProject(
            name = "AndroidX Activity Compose",
            version = "",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/androidx/releases/activity",
        ),
        OpenSourceProject(
            name = "Jetpack Compose",
            version = "BOM 2025.05.00",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/compose",
        ),
        OpenSourceProject(
            name = "AndroidX Navigation Compose",
            version = "",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/androidx/releases/navigation",
        ),
        OpenSourceProject(
            name = "AndroidX DataStore",
            version = "",
            license = LicenseType.Apache2,
            url = "https://developer.android.com/jetpack/androidx/releases/datastore",
        ),
        // Third-party
        OpenSourceProject(
            name = "Coil",
            version = "3.1.0",
            license = LicenseType.Apache2,
            url = "https://github.com/coil-kt/coil",
        ),
        OpenSourceProject(
            name = "kotlin-semver",
            version = "3.0.0",
            license = LicenseType.MIT,
            url = "https://github.com/z4kn4fein/kotlin-semver",
        ),
        // Test
        OpenSourceProject(
            name = "JUnit 5",
            version = "",
            license = LicenseType.EPL2,
            url = "https://junit.org/junit5/",
        ),
        OpenSourceProject(
            name = "MockK",
            version = "",
            license = LicenseType.Apache2,
            url = "https://github.com/mockk/mockk",
        ),
    )
}
