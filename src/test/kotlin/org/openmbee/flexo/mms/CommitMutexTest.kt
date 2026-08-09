package org.openmbee.flexo.mms

import io.kotest.assertions.ktor.client.shouldHaveStatus
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP
import org.apache.jena.sparql.exec.http.UpdateExecutionHTTP
import org.openmbee.flexo.mms.util.*
import java.time.Instant

/**
 * Tests mutex takeover of abandoned ref-modifying transactions and the write-ahead/atomic
 * commit finalization: a commit must leave the branch pointing at a commit that has a
 * lock + model snapshot.
 */
class CommitMutexTest : RefAny() {

    private val staleTxnIri = "urn:test:stale-txn"

    private val commitBody = withAllTestPrefixes("""
        insert data {
            <urn:test:s> <urn:test:p> <urn:test:o> .
        }
    """.trimIndent())

    /**
     * Insert a transaction holding the master branch mutex with the given creation time.
     */
    private fun insertMutexTransaction(created: String) {
        UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("""
            PREFIX m-graph: <$ROOT_CONTEXT/graphs/>
            PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
            PREFIX mms-txn: <https://mms.openmbee.org/rdf/ontology/txn.>
            PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
            INSERT DATA {
                GRAPH m-graph:Transactions {
                    <$staleTxnIri> a mms:Transaction ;
                        mms-txn:mutex <${localIri(masterBranchPath)}> ;
                        mms:created "$created"^^xsd:dateTime .
                }
            }
        """.trimIndent()).execute()
    }

    private fun askBackend(query: String): Boolean {
        return QueryExecutionHTTP.service(backend.getQueryUrl()).query(query).build().use {
            it.execAsk()
        }
    }

    init {
        "abandoned mutex transaction is taken over by a new commit" {
            testApplication {
                // a transaction from a long-dead request holds the master mutex
                insertMutexTransaction("2020-01-01T00:00:00.000Z")

                // committing still succeeds (the abandoned transaction is deleted in the same update)
                commitModel(masterBranchPath, commitBody)

                // the abandoned transaction was removed
                withClue("stale transaction should have been deleted") {
                    askBackend("""
                        ASK {
                            GRAPH <$ROOT_CONTEXT/graphs/Transactions> {
                                <$staleTxnIri> ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }
            }
        }

        "live mutex transaction blocks a commit with 409" {
            testApplication {
                // a fresh transaction holds the master mutex
                insertMutexTransaction(Instant.now().toString())

                httpPost("$masterBranchPath/update") {
                    setSparqlUpdateBody(commitBody)
                }.apply {
                    this shouldHaveStatus HttpStatusCode.Conflict
                }
            }
        }

        "committed branch always has a lock and model snapshot for its current commit" {
            testApplication {
                val response = commitModel(masterBranchPath, commitBody)
                val commitIri = response.headers[HttpHeaders.Location]!!

                withClue("commit <$commitIri> should have an auto lock with a model snapshot") {
                    askBackend("""
                        PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
                        ASK {
                            GRAPH <${localIri("$demoRepoPath/graphs/Metadata")}> {
                                ?lock a mms:Lock ;
                                    mms:commit <$commitIri> ;
                                    mms:snapshot ?snapshot .
                                ?snapshot a mms:Model ;
                                    mms:graph ?modelGraph .
                            }
                        }
                    """.trimIndent()) shouldBe true
                }
            }
        }
    }
}
