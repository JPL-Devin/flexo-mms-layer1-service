package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.openmbee.flexo.mms.util.*

/**
 * Tests that policies scoped directly to repo-nested resources (branches, scratches) and
 * access-control resources actually authorize operations on those resources — i.e. that the
 * permission BGP accepts scope typing from the repo Metadata / AccessControl graphs, not only
 * from the Cluster graph.
 */
class LeafScopePolicyTest : RefAny() {

    private val testUsername = "bob"
    private val testUserAuth = AuthStruct(testUsername)

    private val branchAId = "branch-a"
    private val branchBId = "branch-b"
    private val branchAPath = "$demoRepoPath/branches/$branchAId"
    private val branchBPath = "$demoRepoPath/branches/$branchBId"

    private val scratchId = "scratch-x"
    private val scratchPath = "$demoRepoPath/scratches/$scratchId"

    /**
     * Insert a user into the AccessControl.Agents graph via direct SPARQL Update.
     */
    private fun insertUser(username: String) {
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("""
            PREFIX m-graph: <$ROOT_CONTEXT/graphs/>
            PREFIX m-user: <$ROOT_CONTEXT/users/>
            PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
            INSERT DATA {
                GRAPH m-graph:AccessControl.Agents {
                    m-user:$username a mms:User ;
                        mms:id "$username" .
                }
            }
        """.trimIndent()).execute()
    }

    /**
     * Create a policy (as root) that grants the given roles to a user on a specific scope.
     */
    private suspend fun ApplicationTestBuilder.createScopedPolicy(
        policyId: String,
        userPath: String,
        scopePath: String,
        roles: List<String>,
    ): HttpResponse {
        return httpPut("/policies/$policyId", true) {
            setTurtleBody(withAllTestPrefixes("""
                <>
                    mms:subject <${localIri(userPath)}> ;
                    mms:scope <${localIri(scopePath)}> ;
                    mms:role ${roles.joinToString(", ") { "<$it>" }} ;
                    .
            """.trimIndent()))
        }.apply {
            this shouldHaveStatus HttpStatusCode.Created
        }
    }

    /**
     * Make a request authenticated as the given user.
     */
    private suspend fun ApplicationTestBuilder.requestAs(
        auth: AuthStruct,
        httpMethod: HttpMethod,
        uri: String,
        setup: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse {
        return client.request {
            method = httpMethod
            url(uri)
            header("Authorization", authorization(auth))
            setup()
        }
    }

    private val patchBody = withAllTestPrefixes("""
        insert data {
            <> foaf:homepage <https://www.openmbee.org/> .
        }
    """.trimIndent())

    init {
        "branch-scoped AdminBranch policy authorizes patching that branch only" {
            insertUser(testUsername)

            testApplication {
                // as root: create two branches
                createBranch(demoRepoPath, "master", branchAId, "Branch A")
                createBranch(demoRepoPath, "master", branchBId, "Branch B")

                // grant bob AdminBranch on branch-a only
                createScopedPolicy(
                    policyId = "BobAdminBranchA",
                    userPath = "/users/$testUsername",
                    scopePath = branchAPath,
                    roles = listOf(Role.ADMIN_BRANCH.iri),
                )

                // bob can patch branch-a
                requestAs(testUserAuth, HttpMethod.Patch, branchAPath) {
                    setSparqlUpdateBody(patchBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }

                // bob cannot patch branch-b
                requestAs(testUserAuth, HttpMethod.Patch, branchBPath) {
                    setSparqlUpdateBody(patchBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }

        "scratch-scoped AdminScratch policy authorizes replacing that scratch" {
            insertUser(testUsername)

            testApplication {
                // as root: create the scratch
                httpPut(scratchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:title "Scratch X"@en .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                // grant bob AdminScratch on the scratch only
                createScopedPolicy(
                    policyId = "BobAdminScratchX",
                    userPath = "/users/$testUsername",
                    scopePath = scratchPath,
                    roles = listOf(Role.ADMIN_SCRATCH.iri),
                )

                // bob can replace the scratch
                requestAs(testUserAuth, HttpMethod.Put, scratchPath) {
                    setTurtleBody(withAllTestPrefixes("""
                        <> dct:title "Scratch X Replaced"@en .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "org-scoped ReadOrg policy authorizes reading that org directly" {
            insertUser(testUsername)

            testApplication {
                // demo org already created by RefAny setup; grant bob ReadOrg on it
                createScopedPolicy(
                    policyId = "BobReadDemoOrg",
                    userPath = "/users/$testUsername",
                    scopePath = demoOrgPath,
                    roles = listOf(MMS_OBJECT.ROLE.ReadOrg.uri),
                )

                // bob can GET the org directly (not just via the list endpoint)
                requestAs(testUserAuth, HttpMethod.Get, demoOrgPath).apply {
                    this shouldHaveStatus HttpStatusCode.OK
                }
            }
        }

        "user without any policy cannot patch a branch" {
            insertUser(testUsername)

            testApplication {
                createBranch(demoRepoPath, "master", branchAId, "Branch A")

                requestAs(testUserAuth, HttpMethod.Patch, branchAPath) {
                    setSparqlUpdateBody(patchBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Forbidden
                }
            }
        }
    }
}
