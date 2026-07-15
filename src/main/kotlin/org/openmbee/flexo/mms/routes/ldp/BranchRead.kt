package org.openmbee.flexo.mms.routes.ldp

import io.ktor.http.*
import io.ktor.server.response.*
import org.openmbee.flexo.mms.*
import org.openmbee.flexo.mms.routes.SPARQL_VAR_NAME_BRANCH
import org.openmbee.flexo.mms.server.LdpDcLayer1Context
import org.openmbee.flexo.mms.server.LdpGetResponse
import org.openmbee.flexo.mms.server.LdpHeadResponse
import org.openmbee.flexo.mms.server.LdpReadResponse

// GraphDB drops wildcard (?branch_p ?branch_o) bindings from list-all CONSTRUCT queries
// when joined with the access-control BGP. Bind branch metadata explicitly for that case.
private val SPARQL_BGP_BRANCH_LIST_PROPERTIES = """
    optional { ?$SPARQL_VAR_NAME_BRANCH mms:commit ?__branch_commit . }
    optional { ?$SPARQL_VAR_NAME_BRANCH mms:created ?__branch_created . }
    optional { ?$SPARQL_VAR_NAME_BRANCH dct:title ?__branch_title . }
    optional { ?$SPARQL_VAR_NAME_BRANCH mms:id ?__branch_id . }
    optional { ?$SPARQL_VAR_NAME_BRANCH mms:snapshot ?__branch_snapshot . }
    optional { ?$SPARQL_VAR_NAME_BRANCH mms:createdBy ?__branch_createdBy . }
"""

private val SPARQL_CONSTRUCT_BRANCH_LIST_PROPERTIES = """
    mms:commit ?__branch_commit ;
    mms:created ?__branch_created ;
    dct:title ?__branch_title ;
    mms:id ?__branch_id ;
    mms:snapshot ?__branch_snapshot ;
    mms:createdBy ?__branch_createdBy ;
"""

// reusable basic graph pattern for matching branch(es)
private val SPARQL_BGP_BRANCH: (Boolean, Boolean) -> String = { allBranches, allData -> """
    graph mor-graph:Metadata {
        ${"optional {" iff allBranches}${"""
            ?$SPARQL_VAR_NAME_BRANCH a mms:Branch ;
                mms:etag ?__mms_etag${" ;" iff (allData && !allBranches)}
                ${"?branch_p ?branch_o" iff (allData && !allBranches)}
                .
        """.reindent(if(allBranches) 3 else 2)}
        ${"}" iff allBranches}

        ${SPARQL_BGP_BRANCH_LIST_PROPERTIES.reindent(2) iff (allData && allBranches)}

        ${"""
            optional {
                ?thing mms:branch ?$SPARQL_VAR_NAME_BRANCH ;
                    ?thing_p ?thing_o .
            }
        """.reindent(2) iff allData}
    }

    ${permittedActionSparqlBgp(Permission.READ_BRANCH, Scope.BRANCH,
        if(allBranches) "^morb:?$".toRegex() else null,
        if(allBranches) "" else null)}
"""}

// construct graph of all relevant branch metadata
private val SPARQL_CONSTRUCT_BRANCH: (Boolean, Boolean) -> String = { allBranches, allData -> """
    construct {
        ?$SPARQL_VAR_NAME_BRANCH a mms:Branch ;
            mms:etag ?__mms_etag ;
            ${"?branch_p ?branch_o ;" iff (allData && !allBranches)}
            ${SPARQL_CONSTRUCT_BRANCH_LIST_PROPERTIES iff (allData && allBranches)}
            .

        ?thing ?thing_p ?thing_o .
        
        ${generateReadContextBgp(Permission.READ_BRANCH).reindent(2)}
    } where {
        ${SPARQL_BGP_BRANCH(allBranches, allData).reindent(2)}
    }
""" }


/**
 * Fetches branch(es) metadata
 */
suspend fun LdpDcLayer1Context<LdpReadResponse>.fetchBranches(allBranches: Boolean=false, allData: Boolean=false): String {
    // cache whether this request is asking for all branches
    val branchIri = if(allBranches) null else prefixes["morb"]!!

    // fetch all branch details
    val constructResponseText = executeSparqlConstructOrDescribe(SPARQL_CONSTRUCT_BRANCH(allBranches, allData)) {
        acceptReplicaLag = true

        // internal query, give it all the prefixes
        prefixes(prefixes)

        // get by branchId
        branchIri?.let {
            iri(
                SPARQL_VAR_NAME_BRANCH to it,
            )
        }
    }

    // parse the response
    parseConstructResponse(constructResponseText) {
        // hash all the repo etags
        if(allBranches) {
            handleEtagAndPreconditions(model, MMS.Branch)
        }
        // just the individual repo
        else {
            handleEtagAndPreconditions(model, branchIri)
        }
    }

    return constructResponseText
}

/**
 * Fetches branch(es) metadata, ignoring unnecessary data to satisfy a HEAD request
 */
suspend fun LdpDcLayer1Context<LdpHeadResponse>.headBranches(allBranches: Boolean=false) {
    fetchBranches(allBranches, false)

    // respond
    call.respond(HttpStatusCode.NoContent)
}

/**
 * Fetches branch(es) metadata and all relevant data to satisfy a GET request
 */
suspend fun LdpDcLayer1Context<LdpGetResponse>.getBranches(allBranches: Boolean=false) {
    val constructResponseText = fetchBranches(allBranches, true)

    // respond
    call.respondText(constructResponseText, contentType = RdfContentTypes.Turtle)
}
