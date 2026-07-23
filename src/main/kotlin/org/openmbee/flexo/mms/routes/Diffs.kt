package org.openmbee.flexo.mms.routes

import io.ktor.server.routing.*
import org.openmbee.flexo.mms.server.linkedDataPlatformDirectContainer
import org.openmbee.flexo.mms.routes.ldp.createDiff

private const val DIFFS_PATH = "/orgs/{orgId}/repos/{repoId}/diffs"

/**
 * Diff CRUD routing
 */
fun Route.crudDiffs() {
    // all repos
    linkedDataPlatformDirectContainer(DIFFS_PATH) {
        beforeEach = {
            parsePathParams {
                org()
                repo()
            }
        }

        // create a new diff
        post { slug ->
            // set diff id on context so the diff is minted at {repo}/diffs/{slug}
            diffId = slug

            createDiff()
        }

        // method not allowed
        otherwiseNotAllowed("diffs")
    }
}