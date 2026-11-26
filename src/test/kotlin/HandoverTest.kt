import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HandoverTest {
    @Test
    fun `should publish nothing when the default branch moves while the lock files are updated`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            world.pushToMain(mapOf("README.md" to "An app\n"))
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertTrue(
                published.output.lines().contains(
                    "::warning::The default branch moved while the lock files were being updated, " +
                        "so nothing is published. The next run updates it.",
                ),
            )
            assertEquals(
                Outcome(mapOf("main" to setOf("README.md")), gradle = listOf("kotlinUpgradeYarnLock")),
                world.outcome(),
            )
        }
    }

    @Test
    fun `should publish nothing when the updates change while the lock files are updated`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0", "3.4.0")
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertSucceeded()
            assertTrue(
                published.output.lines().contains(
                    "::warning::The updates changed while the lock files were being updated, " +
                        "so nothing is published. The next run picks them up.",
                ),
            )
            assertEquals(Outcome(mapOf("main" to emptySet()), gradle = listOf("kotlinUpgradeYarnLock")), world.outcome())
        }
    }

    @Test
    fun `should fail without publishing when the update job hands over a file other than the lock files`() {
        TestWorld().use { world ->
            // Arrange
            world.commit(mapOf(CATALOG to catalog(ktor = "3.2.0"), "kotlin-js-store/yarn.lock" to "Resolved before\n"))
            world.maven.publish("io.ktor:ktor-client-core", "3.2.0", "3.3.0")

            // Act
            val updated = world.update()
            world.editHandover {
                it.replace("{\"$CATALOG\":", "{\".github/workflows/ci.yml\":\"Planted\",\"$CATALOG\":")
            }
            val published = world.publish()

            // Assert
            updated.assertSucceeded()
            published.assertFailed(
                "The update job did not hand over just the version catalog and the lock files for $MANUAL_BRANCH.",
            )
            assertEquals(Outcome(mapOf("main" to emptySet()), gradle = listOf("kotlinUpgradeYarnLock")), world.outcome())
        }
    }
}
