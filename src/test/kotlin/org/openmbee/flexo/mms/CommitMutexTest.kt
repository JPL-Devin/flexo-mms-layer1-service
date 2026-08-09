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

        "taking over an abandoned mutex reclaims everything the dead request created" {
            testApplication {
                val txnId = "00000000-0000-4000-8000-000000000000"
                val txnIri = localIri("/transactions/$txnId")
                val policyIri = localIri("/policies/AutoDiffOwner.$txnId")
                val diffIri = localIri("$demoRepoPath/commits/$txnId/diffs/$txnId")
                val insGraph = localIri("$demoRepoPath/graphs/Diff.Ins.$txnId")
                val delGraph = localIri("$demoRepoPath/graphs/Diff.Del.$txnId")
                val modelGraph = localIri("$demoRepoPath/graphs/Model.$txnId")
                val loadGraph = localIri("$demoRepoPath/graphs/Load.$txnId")
                val lockIri = localIri("$demoRepoPath/locks/Commit.$txnId")
                val snapshotIri = localIri("$demoRepoPath/snapshots/Model.$txnId")
                val phantomCommitIri = localIri("$demoRepoPath/commits/$txnId")
                val metadataGraph = localIri("$demoRepoPath/graphs/Metadata")

                // a crashed request holding the master mutex, with its sub-transaction record,
                // created policy and write-ahead snapshot for a commit that never landed
                UpdateExecutionHTTP.service(backend.getUpdateUrl()).update("""
                    PREFIX m-graph: <$ROOT_CONTEXT/graphs/>
                    PREFIX mms: <https://mms.openmbee.org/rdf/ontology/>
                    PREFIX mms-txn: <https://mms.openmbee.org/rdf/ontology/txn.>
                    PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
                    INSERT DATA {
                        GRAPH m-graph:Transactions {
                            <$txnIri> a mms:Transaction ;
                                mms-txn:mutex <${localIri(masterBranchPath)}> ;
                                mms:created "2020-01-01T00:00:00.000Z"^^xsd:dateTime ;
                                mms:createdPolicy <$policyIri> .

                            <${txnIri}diff> a mms:Transaction ;
                                mms-txn:insGraph <$insGraph> ;
                                mms-txn:delGraph <$delGraph> .
                        }

                        GRAPH m-graph:AccessControl.Policies {
                            <$policyIri> a mms:Policy ;
                                mms:scope <$diffIri> .
                        }

                        GRAPH <$metadataGraph> {
                            <$diffIri> a mms:Diff ;
                                mms:insGraph <$insGraph> ;
                                mms:delGraph <$delGraph> .

                            <$lockIri> a mms:Lock ;
                                mms:commit <$phantomCommitIri> ;
                                mms:snapshot <$snapshotIri> .

                            <$snapshotIri> a mms:Model ;
                                mms:graph <$modelGraph> .
                        }

                        GRAPH m-graph:Graphs {
                            <$modelGraph> a mms:ModelGraph .
                        }

                        GRAPH <$insGraph> { <urn:test:a> <urn:test:b> <urn:test:c> . }
                        GRAPH <$delGraph> { <urn:test:a> <urn:test:b> <urn:test:c> . }
                        GRAPH <$modelGraph> { <urn:test:a> <urn:test:b> <urn:test:c> . }
                        GRAPH <$loadGraph> { <urn:test:a> <urn:test:b> <urn:test:c> . }
                    }
                """.trimIndent()).execute()

                commitModel(masterBranchPath, commitBody)

                withClue("sub-transaction record should have been deleted") {
                    askBackend("""
                        ASK {
                            GRAPH <$ROOT_CONTEXT/graphs/Transactions> {
                                <${txnIri}diff> ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }

                withClue("created policy should have been reclaimed") {
                    askBackend("""
                        ASK {
                            GRAPH <$ROOT_CONTEXT/graphs/AccessControl.Policies> {
                                <$policyIri> ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }

                withClue("diff, lock and snapshot metadata should have been reclaimed") {
                    askBackend("""
                        ASK {
                            GRAPH <$metadataGraph> {
                                values ?s { <$diffIri> <$lockIri> <$snapshotIri> }
                                ?s ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }

                withClue("model graph registry entry should have been reclaimed") {
                    askBackend("""
                        ASK {
                            GRAPH <$ROOT_CONTEXT/graphs/Graphs> {
                                <$modelGraph> ?p ?o .
                            }
                        }
                    """.trimIndent()) shouldBe false
                }

                for(graph in listOf(insGraph, delGraph, modelGraph, loadGraph)) {
                    withClue("graph <$graph> should have been dropped") {
                        askBackend("""
                            ASK {
                                GRAPH <$graph> {
                                    ?s ?p ?o .
                                }
                            }
                        """.trimIndent()) shouldBe false
                    }
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
