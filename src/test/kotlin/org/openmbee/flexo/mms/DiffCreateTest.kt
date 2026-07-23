package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.ApplicationTestBuilder
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP
import org.apache.jena.vocabulary.DCTerms
import org.apache.jena.vocabulary.RDF
import org.openmbee.flexo.mms.util.*

/**
 * Tests diff creation via POST /orgs/{org}/repos/{repo}/diffs with repo-addressed diff IRIs.
 */
class DiffCreateTest : RefAny() {

    private val lockId = "before-change"
    private val lockPath = "$demoRepoPath/locks/$lockId"
    private val diffsPath = "$demoRepoPath/diffs"

    private fun askBackend(query: String): Boolean {
        return QueryExecutionHTTP.service(backend.getQueryUrl()).query(query).build().use {
            it.execAsk()
        }
    }

    private fun diffBody(extra: String = ""): String {
        return withAllTestPrefixes("""
            <>
                mms:srcRef <${localIri(lockPath)}> ;
                mms:dstRef <${localIri(masterBranchPath)}> ;
                .
            $extra
        """.trimIndent())
    }

    /**
     * Commits a change on master, locks the pre-change commit, commits another change, so the
     * lock and master differ by exactly one inserted triple.
     */
    private suspend fun ApplicationTestBuilder.setupDivergentRefs() {
        commitModel(masterBranchPath, withAllTestPrefixes("""
            insert data {
                <urn:test:base> <urn:test:p> <urn:test:o> .
            }
        """.trimIndent()))

        createLock(demoRepoPath, masterBranchPath, lockId)

        commitModel(masterBranchPath, withAllTestPrefixes("""
            insert data {
                <urn:test:added> <urn:test:p> <urn:test:o> .
            }
        """.trimIndent()))
    }

    init {
        "create diff between lock and branch" {
            testApplication {
                setupDivergentRefs()

                httpPost(diffsPath) {
                    header(HttpHeaders.SLUG, "my-diff")
                    setTurtleBody(diffBody())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location] shouldBe localIri("$diffsPath/my-diff")

                    // the response carries the diff's metadata at its repo-addressed IRI
                    this includesTriples {
                        subject(localIri("$diffsPath/my-diff")) {
                            includes(
                                RDF.type exactly MMS.Diff,
                            )
                        }
                    }
                }

                // the ins graph of the diff contains exactly the added triple
                withClue("diff ins graph should contain the triple added after the lock") {
                    askBackend("""
                        PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
                        ASK {
                            GRAPH <${localIri("$demoRepoPath/graphs/Metadata")}> {
                                <${localIri("$diffsPath/my-diff")}> mms:insGraph ?insGraph .
                            }
                            GRAPH ?insGraph {
                                <urn:test:added> <urn:test:p> <urn:test:o> .
                            }
                        }
                    """.trimIndent()) shouldBe true
                }
            }
        }

        "create diff with extra user metadata" {
            testApplication {
                setupDivergentRefs()

                httpPost(diffsPath) {
                    header(HttpHeaders.SLUG, "titled-diff")
                    setTurtleBody(diffBody("""
                        <> dct:title "A titled diff"@en .
                    """.trimIndent()))
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created

                    this includesTriples {
                        subject(localIri("$diffsPath/titled-diff")) {
                            includes(
                                RDF.type exactly MMS.Diff,
                                DCTerms.title exactly "A titled diff".en,
                            )
                        }
                    }
                }
            }
        }

        "create diff without slug generates an id" {
            testApplication {
                setupDivergentRefs()

                httpPost(diffsPath) {
                    setTurtleBody(diffBody())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                    this.headers[HttpHeaders.Location]!! shouldStartWith localIri("$diffsPath/")
                }
            }
        }

        "create diff leaves no dangling transaction" {
            testApplication {
                setupDivergentRefs()

                httpPost(diffsPath) {
                    header(HttpHeaders.SLUG, "txn-check-diff")
                    setTurtleBody(diffBody())
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Created
                }

                withClue("transactions graph should be empty after diff creation") {
                    askBackend("""
                        ASK {
                            GRAPH <$ROOT_CONTEXT/graphs/Transactions> {
                                ?txn ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }
            }
        }
    }
}
