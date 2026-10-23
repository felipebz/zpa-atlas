//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.model.CallArgumentFact
import com.felipebz.zpa.atlas.model.CoverageFact
import com.felipebz.zpa.atlas.model.EntityFact
import com.felipebz.zpa.atlas.model.FileFacts
import com.felipebz.zpa.atlas.model.OccurrenceFact
import com.felipebz.zpa.atlas.model.RelationFact
import com.felipebz.zpa.atlas.model.SourceSpan
import com.felipebz.zpa.atlas.model.StatementFact
import com.felipebz.zpa.atlas.model.TriggerEventFact
import com.felipebz.zpa.atlas.model.UnitFact
import com.felipebz.zpa.atlas.model.WrittenValueFact
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.Deflater
import java.util.zip.GZIPOutputStream

/** One declaration of a file as other files' walks can see it, without its position. */
internal class CachedDeclaration(
    /** SHA-256 of the declaration's [Rendering], positions left out. */
    val rendering: String,
    /** The declared name (the last segment of its qualified name), upper-cased. */
    val name: String,
    /** The names the declaration refers to (its types, its owner, its own name), upper-cased. */
    val references: Set<String>
)

/** What the cache keeps of one file: the text it was walked from, what it shows other files, and its facts. */
internal class CachedFile(
    val path: String,
    val sha256: String,
    val declarations: List<CachedDeclaration>,
    /** What the run's declaration catalog gives this file (keys, roles, implemented specifications), positions left out. */
    val catalogDigest: String,
    val facts: FileFacts
)

/** An object type spec as the walks read it: its members and its supertype. */
internal class TypeSummary(val members: String, val supertype: String?)

/** What every walk reads besides the declarations: changing any of it can change any file. */
internal class CacheGlobal(
    /** The preparation state and the files whose preparation failed. */
    val preparation: String,
    /** The schemas of the declared entities. */
    val schemas: Set<String>,
    /** The standalone callables whose headings produced no declaration, by qualified name. */
    val omitted: Set<String>,
    val typeMembers: Map<String, TypeSummary>
)

internal class CacheContents(
    /** What the facts depend on besides the sources: versions, options, dictionary. Ordered: the first difference is reported. */
    val inputs: Map<String, String>,
    val global: CacheGlobal,
    val files: List<CachedFile>
)

internal class DamagedCacheException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The cache was written with other [inputs][CacheContents.inputs]: [input] is the first that differs. */
internal class CacheMismatchException(val input: String) : Exception(input)

/**
 * The facts cache of `index --incremental`, beside the index file. Gzip-compressed; strings are written once and then
 * referred to by number. It ends with the SHA-256 of what precedes it: a cache cut short or altered is refused, and it
 * is written to a temporary file moved over the old one, so a run killed midway leaves the old cache or none.
 */
internal object FactsCache {
    private const val MAGIC = "zpa-atlas facts cache"
    private const val FORMAT = 1
    private const val MAX_STRING_BYTES = 1 shl 26

    fun pathFor(index: Path): Path = index.resolveSibling(index.fileName.toString() + ".facts")

    fun write(target: Path, contents: CacheContents) {
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            // The fastest level: the cache is written on every run, and its strings are already written once each.
            object : GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(temporary)), 1 shl 16) {
                init {
                    def.setLevel(Deflater.BEST_SPEED)
                }
            }.use { gzip ->
                val out = Output(DataOutputStream(DigestOutputStream(gzip, digest)))
                out.contents(contents)
                out.data.flush()
                gzip.write(digest.digest())
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * The cache at [source], when it was written with [inputs]. Its dictionary relations are taken back from
     * [dictionary], the one [inputs] describes.
     *
     * @throws CacheMismatchException when it was written with other inputs
     * @throws DamagedCacheException when it is not a whole cache of this format
     */
    fun read(source: Path, dictionary: AtlasDictionary, inputs: Map<String, String>): CacheContents = try {
        val digest = MessageDigest.getInstance("SHA-256")
        GZIPInputStream(BufferedInputStream(Files.newInputStream(source)), 1 shl 16).use { gzip ->
            val input = Input(DataInputStream(DigestInputStream(gzip, digest)), dictionary, inputs)
            val contents = input.contents()
            val expected = digest.digest()
            val stored = gzip.readNBytes(expected.size)
            if (!stored.contentEquals(expected) || gzip.read() != -1) throw DamagedCacheException("its checksum does not match")
            contents
        }
    } catch (e: DamagedCacheException) {
        throw e
    } catch (e: CacheMismatchException) {
        throw e
    } catch (e: EOFException) {
        throw DamagedCacheException("it ends early", e)
    } catch (e: Exception) {
        throw DamagedCacheException(e.message ?: e.javaClass.simpleName, e)
    }

    private class Output(val data: DataOutputStream) {
        private val pool = HashMap<String, Int>()

        fun contents(contents: CacheContents) {
            data.writeUTF(MAGIC)
            data.writeInt(FORMAT)
            map(contents.inputs)
            string(contents.global.preparation)
            strings(contents.global.schemas.sorted())
            strings(contents.global.omitted.sorted())
            data.writeInt(contents.global.typeMembers.size)
            for ((key, type) in contents.global.typeMembers.toSortedMap()) {
                string(key)
                string(type.members)
                nullable(type.supertype)
            }
            data.writeInt(contents.files.size)
            for (file in contents.files) {
                string(file.path)
                string(file.sha256)
                data.writeInt(file.declarations.size)
                for (declaration in file.declarations) {
                    string(declaration.rendering)
                    string(declaration.name)
                    strings(declaration.references.sorted())
                }
                string(file.catalogDigest)
                facts(file.facts)
            }
        }

        fun string(value: String) {
            val known = pool[value]
            if (known != null) {
                data.writeInt(known)
                return
            }
            pool[value] = pool.size
            data.writeInt(-1)
            val bytes = value.toByteArray(Charsets.UTF_8)
            data.writeInt(bytes.size)
            data.write(bytes)
        }

        fun nullable(value: String?) {
            data.writeBoolean(value != null)
            if (value != null) string(value)
        }

        fun nullableInt(value: Int?) {
            data.writeBoolean(value != null)
            if (value != null) data.writeInt(value)
        }

        fun strings(values: Collection<String>) {
            data.writeInt(values.size)
            values.forEach { string(it) }
        }

        fun map(values: Map<String, String>) {
            data.writeInt(values.size)
            values.forEach { (key, value) -> string(key); string(value) }
        }

        fun span(span: SourceSpan) {
            data.writeInt(span.startLine)
            data.writeInt(span.startCol)
            data.writeInt(span.endLine)
            data.writeInt(span.endCol)
        }

        fun <T> list(values: List<T>, write: (T) -> Unit) {
            data.writeInt(values.size)
            values.forEach(write)
        }

        fun facts(facts: FileFacts) {
            string(facts.path)
            string(facts.sha256)
            data.writeLong(facts.sizeBytes)
            string(facts.encoding)
            string(facts.parseStatus.name)
            data.writeInt(facts.recoveredRegions)
            nullable(facts.compilationSchema)
            list(facts.units) { string(it.kind.name); string(it.name); nullable(it.entityKey); span(it.span); nullable(it.compilationSchema) }
            list(facts.statements) {
                data.writeInt(it.localId); data.writeInt(it.unitIndex); nullable(it.containerKey); string(it.kind.name); span(it.span)
                string(it.normalizedHash)
            }
            list(facts.occurrences) { occurrence(it) }
            list(facts.entities) {
                string(it.key); string(it.kind.name); nullable(it.schemaName); string(it.name); nullable(it.parentKey); string(it.display)
                data.writeBoolean(it.external); data.writeInt(it.precedence)
            }
            list(facts.relations) { string(it.fromKey); string(it.kind.name); string(it.toKey) }
            list(facts.coverage) { string(it.kind); string(it.message) }
            list(facts.dictionaryRelations) { string(it.schema.originalSpelling); string(it.name.originalSpelling); string(RelationResolver.dictionaryKey(it)) }
            list(facts.dictionarySynonyms) { string(it.owner); string(it.name); string(it.targetOwner); string(it.targetName); string(it.targetType) }
            list(facts.triggerEvents) { string(it.triggerKey); string(it.timing.name); nullable(it.level?.name); string(it.event); nullable(it.columnKey) }
        }

        fun occurrence(it: OccurrenceFact) {
            data.writeInt(it.unitIndex)
            nullable(it.containerKey)
            nullableInt(it.statementLocalId)
            span(it.span)
            string(it.written)
            nullable(it.entityKey)
            strings(it.roles.map { role -> role.name })
            string(it.status.name)
            string(it.origin.name)
            nullable(it.reason)
            strings(it.candidateKeys)
            list(it.arguments) { argument ->
                data.writeInt(argument.ordinal); nullable(argument.label); nullable(argument.parameterKey); span(argument.span)
                string(argument.valueKind.name); nullable(argument.value)
            }
            data.writeBoolean(it.nameSpan != null)
            it.nameSpan?.let { span(it) }
            list(it.writtenValues) { value -> data.writeInt(value.row); string(value.valueKind.name); nullable(value.value); span(value.span) }
        }
    }

    private class Input(val data: DataInputStream, private val dictionary: AtlasDictionary, private val expected: Map<String, String>) {
        private val pool = ArrayList<String>()
        private val relations by lazy { dictionary.relations.associate { RelationResolver.dictionaryKey(it.relation) to it.relation } }

        fun contents(): CacheContents {
            if (data.readUTF() != MAGIC) throw DamagedCacheException("it is not a facts cache")
            val format = data.readInt()
            if (format != FORMAT) throw DamagedCacheException("its format is $format, not $FORMAT")
            val inputs = map()
            (expected.keys.firstOrNull { inputs[it] != expected[it] } ?: inputs.keys.firstOrNull { it !in expected })?.let { throw CacheMismatchException(it) }
            val preparation = string()
            val schemas = strings().toSet()
            val omitted = strings().toSet()
            val types = count()
            val global = CacheGlobal(preparation, schemas, omitted, buildMap { repeat(types) { put(string(), TypeSummary(string(), nullable())) } })
            val files = List(count()) {
                CachedFile(string(), string(), List(count()) { CachedDeclaration(string(), string(), strings().toSet()) }, string(), facts())
            }
            return CacheContents(inputs, global, files)
        }

        fun count(): Int = data.readInt().also { if (it < 0) throw DamagedCacheException("a negative count") }

        fun string(): String {
            val index = data.readInt()
            if (index >= 0) return pool.getOrNull(index) ?: throw DamagedCacheException("a string it has not defined")
            val size = count()
            if (size > MAX_STRING_BYTES) throw DamagedCacheException("a string of $size bytes")
            val bytes = data.readNBytes(size)
            return String(bytes, Charsets.UTF_8).also { pool += it }
        }

        fun nullable(): String? = if (data.readBoolean()) string() else null

        fun nullableInt(): Int? = if (data.readBoolean()) data.readInt() else null

        fun strings(): List<String> = List(count()) { string() }

        fun map(): Map<String, String> {
            val pairs = count()
            return LinkedHashMap<String, String>().apply { repeat(pairs) { put(string(), string()) } }
        }

        fun span() = SourceSpan(data.readInt(), data.readInt(), data.readInt(), data.readInt())

        fun <T> list(read: () -> T): List<T> = List(count()) { read() }

        fun facts(): FileFacts = FileFacts(
            path = string(),
            sha256 = string(),
            sizeBytes = data.readLong(),
            encoding = string(),
            parseStatus = enumValueOf(string()),
            recoveredRegions = data.readInt(),
            compilationSchema = nullable(),
            units = list { UnitFact(enumValueOf(string()), string(), nullable(), span(), nullable()) },
            statements = list { StatementFact(data.readInt(), data.readInt(), nullable(), enumValueOf(string()), span(), string()) },
            occurrences = list { occurrence() },
            entities = list { EntityFact(string(), enumValueOf(string()), nullable(), string(), nullable(), string(), data.readBoolean(), data.readInt()) },
            relations = list { RelationFact(string(), enumValueOf(string()), string()) },
            coverage = list { CoverageFact(string(), string()) },
            dictionaryRelations = list { relation(string(), string(), string()) },
            dictionarySynonyms = list { DictionarySynonym(string(), string(), string(), string(), string()) },
            triggerEvents = list { TriggerEventFact(string(), enumValueOf(string()), nullable()?.let { enumValueOf(it) }, string(), nullable()) }
        )

        fun occurrence() = OccurrenceFact(
            unitIndex = data.readInt(),
            containerKey = nullable(),
            statementLocalId = nullableInt(),
            span = span(),
            written = string(),
            entityKey = nullable(),
            roles = strings().mapTo(LinkedHashSet()) { enumValueOf(it) },
            status = enumValueOf(string()),
            origin = enumValueOf(string()),
            reason = nullable(),
            candidateKeys = strings(),
            arguments = list { CallArgumentFact(data.readInt(), nullable(), nullable(), span(), enumValueOf(string()), nullable()) },
            nameSpan = if (data.readBoolean()) span() else null,
            writtenValues = list { WrittenValueFact(data.readInt(), enumValueOf(string()), nullable(), span()) }
        )

        /** A dictionary relation a file resolved, taken back from the run's dictionary by its schema and name. */
        fun relation(schema: String, name: String, key: String): ProjectExternalRelation {
            relations[key]?.let { return it }
            val lookup = dictionary.metadata.findRelation(ProjectIdentifier.fromSource(schema), ProjectIdentifier.fromSource(name))
            return lookup.relation.orElse(null)?.takeIf { RelationResolver.dictionaryKey(it) == key }
                ?: throw DamagedCacheException("the dictionary has no relation $key")
        }
    }
}
