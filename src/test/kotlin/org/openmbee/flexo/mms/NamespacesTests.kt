package org.openmbee.flexo.mms

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.jena.rdf.model.ResourceFactory

class NamespacesTests : StringSpec({
    "terse renders a prefixed local name for a known namespace" {
        val prefixes = PrefixMapBuilder().add(
            "ex" to "https://example.org/ontology/",
        )

        val property = ResourceFactory.createProperty("https://example.org/ontology/someProperty")

        prefixes.terse(property) shouldBe "ex:someProperty"
    }

    "terse renders the full IRI for an unknown namespace" {
        val prefixes = PrefixMapBuilder().add(
            "ex" to "https://example.org/ontology/",
        )

        val property = ResourceFactory.createProperty("https://other.org/vocab/thing")

        prefixes.terse(property) shouldBe "<https://other.org/vocab/thing>"
    }
})
