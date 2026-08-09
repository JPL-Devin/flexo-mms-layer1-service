package org.openmbee.flexo.mms.server

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import org.openmbee.flexo.mms.Layer1Context
import org.openmbee.flexo.mms.RdfContentTypes
import org.openmbee.flexo.mms.requestTimeout
import org.openmbee.flexo.mms.routes.*
import org.openmbee.flexo.mms.routes.sparql.*

// helper type for the body callback arguments to the verb methods in route handling implementor
typealias Layer1Handler<TRequestContext, TResponseContext> = suspend Layer1Context<TRequestContext, TResponseContext>.() -> Unit

// same as above but with default generic response
typealias Layer1HandlerGeneric<TRequestContext> = suspend Layer1Context<TRequestContext, GenericResponse>.() -> Unit


private val SHARED_HTTP_CLIENT_KEY = AttributeKey<HttpClient>("flexo-mms.sharedHttpClient")

/**
 * Returns the application-wide HTTP client, creating it on first use. A single client instance is
 * shared across all requests (creating one per request leaks selector threads and sockets since
 * nothing closes it); it is closed when the application stops.
 */
fun ApplicationCall.httpClient(): HttpClient {
    return application.attributes.computeIfAbsent(SHARED_HTTP_CLIENT_KEY) {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                register(RdfContentTypes.Turtle, TextConverter())
                register(RdfContentTypes.TriG, TextConverter())
                register(RdfContentTypes.NTriples, TextConverter())
                register(RdfContentTypes.NQuads, TextConverter())
                register(RdfContentTypes.RdfXml, TextConverter())
                register(RdfContentTypes.JsonLd, TextConverter())
            }

            engine {
                requestTimeout = this@httpClient.application.requestTimeout
                    ?: (30 * 60 * 1000) // default timeout of 30 minutes
            }
        }

        // release the client's resources when the application shuts down
        application.monitor.subscribe(ApplicationStopping) {
            client.close()
        }

        client
    }
}


class TextConverter: ContentConverter {
    fun stringify(obj: Any?): String {
        if(obj == null) return ""
        return obj as String
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? {
        return String(content.toByteArray(), charset)
    }

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?
    ): OutgoingContent {
        return TextContent(this.stringify(value), contentType)
    }

    //
    // override suspend fun convertForReceive(context: PipelineContext<ApplicationReceiveRequest, ApplicationCall>): Any? {
    //     return context.context.receiveText()
    // }
    //
    // override suspend fun convertForSend(
    //     context: PipelineContext<Any, ApplicationCall>,
    //     contentType: ContentType,
    //     value: Any
    // ): Any? {
    //     return TextContent(this.stringify(value), contentType.withCharset(context.call.suitableCharset()))
    // }
}


fun Application.configureRouting() {
    // install(AutoHeadResponse)

    routing {
        authenticate {
            crudOrgs()
            crudRepos()
            crudBranches()
            crudLocks()
            crudCommits()
            crudDiffs()
            squashCommits()

            storeArtifacts()
//            queryArtifacts()

            crudScratch()
            queryScratch()
            updateScratch()

            crudModel()

            crudCollections()

            crudGroups()
            crudPolicies()

            queryRepo()
            queryLock()
            queryDiff()

            queryModel()
            commitModel()

            queryCollection()
        }
    }
}
