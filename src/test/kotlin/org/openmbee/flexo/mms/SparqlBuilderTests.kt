package org.openmbee.flexo.mms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotStartWith
import org.apache.jena.rdf.model.ModelFactory
import org.apache.jena.rdf.model.ResourceFactory
import org.apache.jena.sparql.modify.request.UpdateDataInsert
import org.apache.jena.update.UpdateFactory
import org.apache.jena.vocabulary.DCTerms

class SparqlBuilderTests : StringSpec({
    "serializePairs produces valid SPARQL for multiple predicates and repeated objects" {
        val model = ModelFactory.createDefaultModel()
        val node = model.createResource("urn:test:subject")
        val homepage = ResourceFactory.createProperty("http://xmlns.com/foaf/0.1/homepage")

        node.addProperty(DCTerms.title, model.createLiteral("Test Title", "en"))
        node.addProperty(homepage, model.createResource("https://example.org/a"))
        node.addProperty(homepage, model.createResource("https://example.org/b"))

        val pairs = serializePairs(node)

        // must not lead with a separator
        pairs.trimStart() shouldNotStartWith ";"
        pairs.trimStart() shouldNotStartWith ","

        // embedding the pairs after a subject must parse as valid SPARQL
        // and preserve all three statements
        val ast = UpdateFactory.create("INSERT DATA { <urn:test:subject> $pairs . }")
        val insert = ast.operations.single() as UpdateDataInsert
        insert.quads.size shouldBe 3
    }

    "serializePairs of a single property has no separators" {
        val model = ModelFactory.createDefaultModel()
        val node = model.createResource("urn:test:subject")

        node.addProperty(DCTerms.title, model.createLiteral("Test Title", "en"))

        val pairs = serializePairs(node)

        val ast = UpdateFactory.create("INSERT DATA { <urn:test:subject> $pairs . }")
        val insert = ast.operations.single() as UpdateDataInsert
        insert.quads.size shouldBe 1
    }
})
