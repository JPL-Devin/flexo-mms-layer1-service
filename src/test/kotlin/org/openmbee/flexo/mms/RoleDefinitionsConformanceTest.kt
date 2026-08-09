package org.openmbee.flexo.mms

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.apache.jena.graph.Node
import org.apache.jena.riot.RDFDataMgr
import org.apache.jena.vocabulary.RDF
import java.io.File

/**
 * Guards against drift between the role IRIs the service references (the [Role] enum, emitted
 * into policies by `autoPolicy(...)`) and the roles actually declared in the generated cluster
 * init definitions (`cluster.trig`, generated from the deploy folder). A policy referencing an
 * undeclared role fails silently — it simply never grants anything — so any drift between the
 * two must fail loudly here instead.
 */
class RoleDefinitionsConformanceTest : StringSpec({

    // Role enum entries knowingly absent from the generated definitions: the deploy folder no
    // longer declares Diff roles, and never declares Group/Policy roles, yet auto-policies still
    // reference AdminDiff (diff creation) and AdminGroup (group creation) — those policies grant
    // nothing beyond what ancestor scopes provide. Kept here as an explicit ledger; the
    // assertions below also verify these are still missing, so if the definitions ever declare
    // one of them, this list must shrink accordingly.
    val knownUndeclared = setOf(Role.ADMIN_DIFF, Role.ADMIN_GROUP, Role.ADMIN_POLICY)

    val declaredRoles: Set<String> = run {
        val clusterFilePath = File(
            RoleDefinitionsConformanceTest::class.java.classLoader.getResource("cluster.trig")!!.file
        ).absolutePath
        val roleType = org.apache.jena.graph.NodeFactory.createURI("${MMS.uri}Role")
        RDFDataMgr.loadDataset(clusterFilePath).asDatasetGraph()
            .find(Node.ANY, Node.ANY, RDF.type.asNode(), roleType)
            .asSequence()
            .map { it.subject.uri }
            .toSet()
    }

    "every Role enum entry is declared as mms:Role in the cluster init definitions" {
        for(role in Role.entries) {
            if(role in knownUndeclared) continue
            withClue("Role.${role.name} (${role.iri}) must be declared in cluster.trig; " +
                    "policies referencing an undeclared role never authorize anything") {
                declaredRoles shouldContain role.iri
            }
        }
    }

    "the known-undeclared ledger is still accurate" {
        for(role in knownUndeclared) {
            withClue("Role.${role.name} is now declared in cluster.trig; " +
                    "remove it from knownUndeclared") {
                declaredRoles shouldNotContain role.iri
            }
        }
    }
})
