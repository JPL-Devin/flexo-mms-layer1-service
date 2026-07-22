package org.openmbee.flexo.mms

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.ktor.http.*
import org.apache.jena.sparql.exec.http.QueryExecutionHTTP
import org.openmbee.flexo.mms.util.*

/**
 * Tests the write-ahead/atomic commit finalization: a commit must leave the branch pointing at
 * a commit that has a lock + model snapshot.
 */
class CommitMutexTest : RefAny() {

    private val commitBody = withAllTestPrefixes("""
        insert data {
            <urn:test:s> <urn:test:p> <urn:test:o> .
        }
    """.trimIndent())

    private fun askBackend(query: String): Boolean {
        return QueryExecutionHTTP.service(backend.getQueryUrl()).query(query).build().use {
            it.execAsk()
        }
    }

    init {
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
