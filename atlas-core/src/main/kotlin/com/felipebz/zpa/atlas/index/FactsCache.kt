//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.index

import com.felipebz.zpa.api.project.ProjectExternalRelation
import com.felipebz.zpa.api.project.ProjectIdentifier
import com.felipebz.zpa.api.project.ProjectSnapshot
import com.felipebz.zpa.atlas.dictionary.AtlasDictionary
import com.felipebz.zpa.atlas.dictionary.DictionarySynonym
import com.felipebz.zpa.atlas.extract.RelationResolver
import com.felipebz.zpa.atlas.index.FactsCache.CHUNKS
import com.felipebz.zpa.atlas.index.FactsCache.CHUNK_BYTES
import com.felipebz.zpa.atlas.index.FactsCache.MAGIC
import com.felipebz.zpa.atlas.model.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
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
    /** The reader that read the Forms module the file is ([com.felipebz.zpa.atlas.forms.FormsModuleReaders.fingerprint]); null for another file. */
    val reader: String?,
    /** The file's facts, without their dictionary relations: those come back from the run's dictionary, by [relations]. */
    val facts: FileFacts,
    val relations: List<RelationReference>
)

/** A dictionary relation a file resolved, as written in the cache: the spellings to look it up by, and its key. */
internal class RelationReference(val schema: String, val name: String, val key: String)

/** An object type spec as the walks read it: its members and its supertype. */
internal data class TypeSummary(val members: String, val supertype: String?)

/** What every walk reads besides the declarations: changing any of it can change any file. */
internal data class CacheGlobal(
    /** The preparation state and the files whose preparation failed. */
    val preparation: String,
    /** The schemas of the declared entities. */
    val schemas: Set<String>,
    /** The standalone callables whose headings produced no declaration, by qualified name. */
    val omitted: Set<String>,
    val typeMembers: Map<String, TypeSummary>,
    /** The tables created in the sources, by `SCHEMA.NAME`: the digest of their columns as zpa reads them. */
    val sourceRelations: Map<String, String> = emptyMap(),
    /** The synonyms created in the sources, by `OWNER.NAME`: their target as zpa reads it. */
    val sourceSynonyms: Map<String, String> = emptyMap()
)

internal class CacheContents(
    /** What the facts depend on besides the sources: versions, options, dictionary. Ordered: the first difference is reported. */
    val inputs: Map<String, String>,
    val global: CacheGlobal,
    val files: List<CachedFile>
)

internal class DamagedCacheException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The facts cache of `index --incremental`, beside the index file. Gzip-compressed; strings are written once and then
 * referred to by number, and numbers take one byte per 7 bits they need. It ends with a marker, and is read to the end
 * of the gzip stream, whose checksum and length gzip checks: a cache cut short or altered is refused. It is written to a temporary file moved over the old one, so a
 * run killed midway leaves the old cache or none.
 *
 * Reading it needs nothing of the run: its [inputs][CacheContents.inputs] are compared, and its dictionary relations
 * taken back, by the caller. So it can be read while the dictionary is still loading.
 *
 * The atlas's own build is not one of its inputs: [FORMAT] is raised whenever what it keeps would come out otherwise,
 * or is written otherwise, and [FORMAT_DIGEST] records the [digest] of the test fixtures' cache at that format, built
 * with the published zpa the build declares, which a test compares.
 */
internal object FactsCache {
    private const val MAGIC = "zpa-atlas facts cache"
    internal const val FORMAT = 38
    internal const val FORMAT_DIGEST = "0170e19d30767f2220626f4455c8cda53fbe657d69c28a7d069487972e5e399f"
    private const val END = "end of the facts cache"
    private const val MAX_STRING_BYTES = 1 shl 26
    private const val BUFFER_BYTES = 1 shl 16
    private const val CHUNK_BYTES = 1 shl 20
    private const val CHUNKS = 4

    fun pathFor(index: Path): Path = index.resolveSibling(index.fileName.toString() + ".facts")

    fun write(target: Path, contents: CacheContents) {
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
        try {
            // The fastest level: the cache is written whenever it changes, and its strings are already written once each.
            object : GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(temporary), BUFFER_BYTES), BUFFER_BYTES) {
                init {
                    def.setLevel(Deflater.BEST_SPEED)
                }
            }.use { gzip ->
                val out = Output(gzip)
                out.contents(contents)
                out.flush()
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * A checksum of what [contents] keeps, its inputs aside: every value of every file, by [Rendering]. The lists of a
     * file and of its facts count as sets: a walk adds some facts in an order that may differ between two runs, which
     * the writer sorts.
     */
    fun digest(contents: CacheContents): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        fun addFields(value: Any) {
            for (field in Rendering.fieldsOf(value.javaClass)) {
                val fieldValue = field.get(value)
                add(field.name)
                when (fieldValue) {
                    is FileFacts -> addFields(fieldValue)
                    is List<*> -> fieldValue.map { Rendering().render(it) }.sorted().forEach(::add)
                    else -> add(Rendering().render(fieldValue))
                }
            }
        }
        add(Rendering().render(contents.global))
        for (file in contents.files.sortedBy { it.path }) addFields(file)
        return HexFormat.of().formatHex(digest.digest())
    }

    /**
     * The cache at [source].
     *
     * @throws DamagedCacheException when it is not a whole cache of this format
     */
    fun read(source: Path): CacheContents = try {
        InflatedAhead(source).use { inflated ->
            val input = Input(inflated)
            val contents = input.contents()
            // Reading past the marker makes gzip check its checksum and length.
            input.end()
            contents
        }
    } catch (e: DamagedCacheException) {
        throw e
    } catch (e: EOFException) {
        throw DamagedCacheException("it ends early", e)
    } catch (e: Exception) {
        throw DamagedCacheException(e.message ?: e.javaClass.simpleName, e)
    }

    /**
     * The facts of [file] with their relations taken back as [snapshot] finds them in [dictionary], the one the cache
     * was written with, or in the tables created in the sources, and the keys of those neither has any more (a table
     * whose `CREATE TABLE` left the sources): such a file must be walked again.
     */
    fun withRelations(
        file: CachedFile, snapshot: ProjectSnapshot, dictionary: AtlasDictionary, byKey: Map<String, ProjectExternalRelation>
    ): Pair<FileFacts, List<String>> {
        if (file.relations.isEmpty()) return file.facts to emptyList()
        val missing = ArrayList<String>()
        val relations = file.relations.mapNotNull { reference ->
            byKey[reference.key] ?: snapshot.findRelation(ProjectIdentifier.fromSource(reference.schema), ProjectIdentifier.fromSource(reference.name), dictionary.metadata)
                .relation.orElse(null)?.takeIf { RelationResolver.dictionaryKey(it) == reference.key }
            ?: run { missing += reference.key; null }
        }
        val facts = file.facts
        return FileFacts(
            facts.path, facts.sha256, facts.sizeBytes, facts.encoding, facts.parseStatus, facts.recoveredRegions, facts.compilationSchema, facts.units,
            facts.statements, facts.occurrences, facts.entities, facts.relations, facts.coverage, relations, facts.dictionarySynonyms, facts.triggerEvents,
            facts.declaredTexts, facts.docComments, facts.usesGaps, facts.membersGaps, facts.declaredShapes, facts.intos, facts.placesLeftOut, facts.kind,
            facts.formsObjects, facts.formsUses
        ) to missing
    }

    /**
     * Writes to [stream] through a buffer of its own: whole ints by their 7-bit groups ([count] those that are never
     * negative, [int] the others zigzagged), so that small ones take one byte. The header ([MAGIC], [FORMAT]) is
     * written as every format has written it, so that a cache of another format says which it is.
     */
    private class Output(private val stream: OutputStream) {
        private val pool = HashMap<String, Int>()
        private val buffer = ByteArray(BUFFER_BYTES)
        private var position = 0

        fun contents(contents: CacheContents) {
            header()
            map(contents.inputs)
            string(contents.global.preparation)
            strings(contents.global.schemas.sorted())
            strings(contents.global.omitted.sorted())
            count(contents.global.typeMembers.size)
            for ((key, type) in contents.global.typeMembers.toSortedMap()) {
                string(key)
                string(type.members)
                nullable(type.supertype)
            }
            map(contents.global.sourceRelations.toSortedMap())
            map(contents.global.sourceSynonyms.toSortedMap())
            count(contents.files.size)
            for (file in contents.files) {
                string(file.path)
                string(file.sha256)
                count(file.declarations.size)
                for (declaration in file.declarations) {
                    string(declaration.rendering)
                    string(declaration.name)
                    strings(declaration.references.sorted())
                }
                string(file.catalogDigest)
                nullable(file.reader)
                facts(file.facts)
            }
            bytes(END.toByteArray(Charsets.UTF_8))
        }

        private fun header() {
            val magic = MAGIC.toByteArray(Charsets.US_ASCII)
            byte(magic.size ushr 8); byte(magic.size)
            magic.forEach { byte(it.toInt()) }
            for (shift in intArrayOf(24, 16, 8, 0)) byte(FORMAT ushr shift)
        }

        fun flush() {
            stream.write(buffer, 0, position)
            position = 0
        }

        private fun room(bytes: Int) {
            if (position + bytes > buffer.size) flush()
        }

        fun byte(value: Int) {
            room(1)
            buffer[position++] = value.toByte()
        }

        fun boolean(value: Boolean) = byte(if (value) 1 else 0)

        fun count(value: Int) = long(value.toLong())

        fun int(value: Int) = long(((value shl 1) xor (value shr 31)).toLong() and 0xFFFFFFFFL)

        /** [value], never negative, by its 7-bit groups, the lowest first, each but the last with its high bit set. */
        fun long(value: Long) {
            room(10)
            var rest = value
            while (rest and 0x7FL.inv() != 0L) {
                buffer[position++] = ((rest and 0x7F) or 0x80).toByte()
                rest = rest ushr 7
            }
            buffer[position++] = rest.toByte()
        }

        private fun bytes(value: ByteArray) {
            count(value.size)
            if (value.size > buffer.size) {
                flush()
                stream.write(value)
                return
            }
            room(value.size)
            System.arraycopy(value, 0, buffer, position, value.size)
            position += value.size
        }

        /** A string written before as its number plus one; a new one as 0 and its UTF-8 bytes. */
        fun string(value: String) {
            val known = pool[value]
            if (known != null) {
                count(known + 1)
                return
            }
            pool[value] = pool.size
            count(0)
            bytes(value.toByteArray(Charsets.UTF_8))
        }

        fun nullable(value: String?) {
            boolean(value != null)
            if (value != null) string(value)
        }

        fun nullableInt(value: Int?) {
            boolean(value != null)
            if (value != null) int(value)
        }

        fun strings(values: Collection<String>) {
            count(values.size)
            values.forEach { string(it) }
        }

        fun map(values: Map<String, String>) {
            count(values.size)
            values.forEach { (key, value) -> string(key); string(value) }
        }

        fun span(span: SourceSpan) {
            int(span.startLine)
            int(span.startCol)
            int(span.endLine)
            int(span.endCol)
        }

        fun <T> list(values: List<T>, write: (T) -> Unit) {
            count(values.size)
            values.forEach(write)
        }

        fun facts(facts: FileFacts) {
            string(facts.path)
            string(facts.sha256)
            long(facts.sizeBytes)
            string(facts.encoding)
            string(facts.parseStatus.name)
            string(facts.kind.name)
            int(facts.recoveredRegions)
            nullable(facts.compilationSchema)
            list(facts.units) { string(it.kind.name); string(it.name); nullable(it.entityKey); span(it.span); nullable(it.compilationSchema) }
            list(facts.statements) {
                int(it.localId); int(it.unitIndex); nullable(it.containerKey); string(it.kind.name); span(it.span)
                string(it.normalizedHash)
            }
            list(facts.occurrences) { occurrence(it) }
            list(facts.entities) {
                string(it.key); string(it.kind.name); nullable(it.schemaName); string(it.name); nullable(it.parentKey); string(it.display)
                boolean(it.external); int(it.precedence)
            }
            list(facts.relations) { string(it.fromKey); string(it.kind.name); string(it.toKey) }
            list(facts.coverage) { string(it.kind); string(it.message) }
            list(facts.dictionaryRelations) { string(it.schema.originalSpelling); string(it.name.originalSpelling); string(RelationResolver.dictionaryKey(it)) }
            list(facts.dictionarySynonyms) { string(it.owner); string(it.name); string(it.targetOwner); string(it.targetName); string(it.targetType) }
            list(facts.triggerEvents) { string(it.triggerKey); string(it.timing.name); nullable(it.level?.name); string(it.event); nullable(it.columnKey) }
            list(facts.declaredTexts) { string(it.entityKey); nullable(it.datatype); nullable(it.value); boolean(it.valueCut) }
            list(facts.docComments) {
                string(it.entityKey); boolean(it.inBody); string(it.shape.name); int(it.line); string(it.text); boolean(it.textCut)
            }
            list(facts.usesGaps) { string(it.entityKey); list(it.reasons) { reason -> string(reason) } }
            list(facts.membersGaps) { string(it.entityKey); list(it.reasons) { reason -> string(reason) } }
            list(facts.declaredShapes) { string(it.entityKey); shape(it.shape) }
            list(facts.intos) { into ->
                string(into.source.name); boolean(into.bulk); int(into.unitIndex); nullable(into.containerKey)
                nullableInt(into.statementLocalId); list(into.branches) { items -> list(items) { item(it) } }; nullable(into.cursorKey)
                list(into.targets) { target(it) }
                boolean(into.row != null); into.row?.let { target(it) }; nullable(into.rowField); nullable(into.expression); strings(into.functions); nullable(into.parameterKey); nullable(into.fromParameter); nullable(into.cursorKind); nullable(into.cursorType)
                boolean(into.call != null); into.call?.let { span(it) }
                boolean(into.resultOf != null); into.resultOf?.let { span(it) }
                boolean(into.keeps)
            }
            list(facts.placesLeftOut.entries.toList()) { string(it.key); int(it.value) }
            list(facts.formsObjects) {
                string(it.entityKey); nullableInt(it.codeFirstLine); nullableInt(it.codeLastLine); nullable(it.unitKind?.name)
                boolean(it.block != null)
                it.block?.let { block ->
                    boolean(block.database); string(block.sourceType.name); string(block.sourceName); nullable(block.sourceKey)
                    string(block.targetType.name); string(block.targetName); nullable(block.targetKey); boolean(block.queryAllowed)
                    boolean(block.insertAllowed); boolean(block.updateAllowed); boolean(block.deleteAllowed); string(block.where)
                    string(block.orderBy)
                }
                boolean(it.item != null)
                it.item?.let { item ->
                    boolean(item.database); string(item.columnName); string(item.kind.name); boolean(item.insertAllowed)
                    boolean(item.updateAllowed); nullable(item.columnKey); nullable(item.targetColumnKey)
                }
            }
            list(facts.formsUses) {
                string(it.containerKey); string(it.written); nullable(it.entityKey); strings(it.roles.map { role -> role.name }); string(it.status.name)
                nullable(it.reason)
            }
        }

        fun item(item: QueryItem) {
            nullable(item.name); nullable(item.columnKey); nullable(item.expression); boolean(item.opaque); strings(item.functions); nullable(item.combined); list(item.parts) { item(it) }; nullable(item.notColumn); boolean(item.routine)
        }

        /** An `INTO` target, or the record of an assignment. */
        fun target(target: IntoTarget) {
            span(target.span); string(target.written); nullable(target.entityKey); strings(target.path); boolean(target.element)
            boolean(target.local != null); target.local?.let { shape(it) }; nullable(target.declaredType); nullable(target.reason)
            nullable(target.localKey)
        }

        /** A [TypeShape], its variant first, then its fields, nested shapes written the same way. */
        fun shape(shape: TypeShape) {
            fun optional(nested: TypeShape?) {
                boolean(nested != null)
                nested?.let { shape(it) }
            }
            when (shape) {
                is TypeShape.Anchored -> {
                    byte(0); boolean(shape.rowType); nullable(shape.anchorKey); optional(shape.local); strings(shape.path); nullable(shape.reason)
                }
                is TypeShape.Sized -> {
                    byte(1); string(shape.type); nullableInt(shape.length); nullable(shape.unit); nullableInt(shape.scale)
                }
                is TypeShape.Unsized -> {
                    byte(2); string(shape.type)
                }
                is TypeShape.Named -> {
                    byte(3); nullable(shape.typeKey); optional(shape.local); nullable(shape.reason)
                }
                is TypeShape.Record -> {
                    byte(4); list(shape.fields) { string(it.name); nullable(it.key); optional(it.local) }
                }
                is TypeShape.Collection -> {
                    byte(5); shape(shape.element)
                }
                is TypeShape.CursorRow -> {
                    byte(6); list(shape.items) { item(it) }
                }
                is TypeShape.Unknown -> {
                    byte(7); string(shape.reason); boolean(shape.strongCursor)
                }
                is TypeShape.OutParameter -> {
                    byte(8); string(shape.mode); shape(shape.type)
                }
            }
        }

        fun occurrence(it: OccurrenceFact) {
            int(it.unitIndex)
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
                int(argument.ordinal); nullable(argument.label); nullable(argument.parameterKey); span(argument.span)
                string(argument.valueKind.name); nullable(argument.value)
            }
            boolean(it.nameSpan != null)
            it.nameSpan?.let { span(it) }
            list(it.writtenValues) { value -> int(value.row); string(value.valueKind.name); nullable(value.value); span(value.span) }
        }
    }

    /**
     * The bytes the gzip file [source] holds, inflated on a thread of their own while the bytes before are decoded: at
     * most [CHUNKS] chunks of [CHUNK_BYTES] are held at once, and refilled once read. What the inflation throws is thrown
     * to the reader where it would have read the bytes.
     */
    private class InflatedAhead(source: Path) : InputStream() {
        private class Chunk(val bytes: ByteArray, var length: Int = 0)

        private val free = ArrayBlockingQueue<Chunk>(CHUNKS)
        /** Inflated chunks, then [Unit] at the end, or what the inflation threw. */
        private val full = ArrayBlockingQueue<Any>(CHUNKS + 1)
        private var current: Chunk? = null
        private var position = 0
        private var ended = false
        private val inflation: Thread

        init {
            repeat(CHUNKS) { free.add(Chunk(ByteArray(CHUNK_BYTES))) }
            inflation = Thread({ inflate(source) }, "facts cache inflation").apply {
                isDaemon = true
                start()
            }
        }

        private fun inflate(source: Path) {
            try {
                GZIPInputStream(BufferedInputStream(Files.newInputStream(source), BUFFER_BYTES), BUFFER_BYTES).use { gzip ->
                    while (true) {
                        val chunk = free.take()
                        chunk.length = gzip.readNBytes(chunk.bytes, 0, chunk.bytes.size)
                        if (chunk.length == 0) break
                        full.put(chunk)
                    }
                }
                full.put(Unit)
            } catch (_: InterruptedException) {
                // Closed before the end: nobody reads what is left.
            } catch (e: Throwable) {
                full.offer(e)
            }
        }

        /** The next chunk with bytes to read; false at the end. */
        private fun next(): Boolean {
            if (ended) return false
            current?.let { free.put(it) }
            current = null
            position = 0
            when (val item = full.take()) {
                is Chunk -> current = item
                is Throwable -> throw item
                else -> ended = true
            }
            return !ended
        }

        override fun read(): Int {
            val chunk = current?.takeIf { position < it.length } ?: if (next()) current!! else return -1
            return chunk.bytes[position++].toInt() and 0xFF
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val chunk = current?.takeIf { position < it.length } ?: if (next()) current!! else return -1
            val count = minOf(length, chunk.length - position)
            System.arraycopy(chunk.bytes, position, bytes, offset, count)
            position += count
            return count
        }

        override fun close() {
            inflation.interrupt()
            inflation.join()
        }
    }

    /** Reads what [Output] writes from [stream], through a buffer of its own. */
    private class Input(private val stream: InputStream) {
        private val pool = ArrayList<String>()
        private val buffer = ByteArray(BUFFER_BYTES)
        private var position = 0
        private var limit = 0

        fun contents(): CacheContents {
            header()
            val inputs = map()
            val preparation = string()
            val schemas = strings().toSet()
            val omitted = strings().toSet()
            val types = count()
            val typeMembers = buildMap { repeat(types) { put(string(), TypeSummary(string(), nullable())) } }
            val global = CacheGlobal(preparation, schemas, omitted, typeMembers, map(), map())
            val files = List(count()) {
                val path = string()
                val sha256 = string()
                val declarations = List(count()) { CachedDeclaration(string(), string(), strings().toSet()) }
                val digest = string()
                val reader = nullable()
                val (facts, relations) = facts()
                CachedFile(path, sha256, declarations, digest, reader, facts, relations)
            }
            return CacheContents(inputs, global, files)
        }

        private fun header() {
            val size = (byte() and 0xFF) shl 8 or (byte() and 0xFF)
            if (size != MAGIC.length || String(ByteArray(size) { byte().toByte() }, Charsets.US_ASCII) != MAGIC) throw DamagedCacheException("it is not a facts cache")
            var format = 0
            repeat(4) { format = format shl 8 or (byte() and 0xFF) }
            if (format != FORMAT) throw DamagedCacheException("its format is $format, not $FORMAT")
        }

        /** Checks that the marker [Output] ends with comes next, and then the end of [stream]. */
        fun end() {
            if (String(bytes(), Charsets.UTF_8) != END || position < limit || stream.read() != -1) throw DamagedCacheException("it does not end where it should")
        }

        /** Makes [bytes] bytes, at most the buffer's size, readable from [position]. */
        private fun fill(bytes: Int) {
            System.arraycopy(buffer, position, buffer, 0, limit - position)
            limit -= position
            position = 0
            while (limit < bytes) {
                val read = stream.read(buffer, limit, buffer.size - limit)
                if (read < 0) throw EOFException()
                limit += read
            }
        }

        fun byte(): Int {
            if (position == limit) fill(1)
            return buffer[position++].toInt()
        }

        fun boolean(): Boolean = when (byte()) {
            0 -> false
            1 -> true
            else -> throw DamagedCacheException("a boolean that is neither")
        }

        fun count(): Int = varint().also { if (it < 0) throw DamagedCacheException("a count of ${it.toUInt()}") }

        fun int(): Int = varint().let { (it ushr 1) xor -(it and 1) }

        /** The 32 bits [Output.long] wrote in at most five groups. */
        private fun varint(): Int {
            var at = position
            if (limit - at < 5) return slowVarint()
            val bytes = buffer
            var group = bytes[at++].toInt()
            if (group >= 0) {
                position = at
                return group
            }
            var result = group and 0x7F
            var shift = 7
            while (true) {
                group = bytes[at++].toInt()
                result = result or ((group and 0x7F) shl shift)
                if (group >= 0) break
                shift += 7
                if (shift > 28) throw DamagedCacheException("a number too long")
            }
            if (shift == 28 && group > 0x0F) throw DamagedCacheException("a number too long")
            position = at
            return result
        }

        private fun slowVarint(): Int = long().let {
            if (it > 0xFFFFFFFFL) throw DamagedCacheException("a number too long")
            it.toInt()
        }

        fun long(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val group = byte()
                result = result or ((group and 0x7F).toLong() shl shift)
                if (group and 0x80 == 0) return result
                shift += 7
                if (shift > 56) throw DamagedCacheException("a number too long")
            }
        }

        private fun size(): Int = count().also { if (it > MAX_STRING_BYTES) throw DamagedCacheException("a string of $it bytes") }

        private fun bytes(): ByteArray = bytes(size())

        private fun bytes(size: Int): ByteArray {
            val bytes = ByteArray(size)
            var done = minOf(size, limit - position)
            System.arraycopy(buffer, position, bytes, 0, done)
            position += done
            while (done < size) {
                val read = stream.read(bytes, done, size - done)
                if (read < 0) throw EOFException()
                done += read
            }
            return bytes
        }

        fun string(): String {
            val reference = count()
            if (reference > 0) return pool.getOrNull(reference - 1) ?: throw DamagedCacheException("a string it has not defined")
            val size = size()
            val text = if (size <= buffer.size) {
                if (limit - position < size) fill(size)
                String(buffer, position, size, Charsets.UTF_8).also { position += size }
            } else {
                String(bytes(size), Charsets.UTF_8)
            }
            return text.also { pool += it }
        }

        fun nullable(): String? = if (boolean()) string() else null

        fun nullableInt(): Int? = if (boolean()) int() else null

        fun strings(): List<String> = List(count()) { string() }

        fun map(): Map<String, String> {
            val pairs = count()
            return LinkedHashMap<String, String>().apply { repeat(pairs) { put(string(), string()) } }
        }

        fun span() = SourceSpan(int(), int(), int(), int())

        fun <T> list(read: () -> T): List<T> = List(count()) { read() }

        fun facts(): Pair<FileFacts, List<RelationReference>> {
            val path = string()
            val sha256 = string()
            val sizeBytes = long()
            val encoding = string()
            val parseStatus = enumValueOf<ParseStatus>(string())
            val kind = enumValueOf<FileKind>(string())
            val recoveredRegions = int()
            val compilationSchema = nullable()
            val units = list { UnitFact(enumValueOf(string()), string(), nullable(), span(), nullable()) }
            val statements = list { StatementFact(int(), int(), nullable(), enumValueOf(string()), span(), string()) }
            val occurrences = list { occurrence() }
            val entities = list { EntityFact(string(), enumValueOf(string()), nullable(), string(), nullable(), string(), boolean(), int()) }
            val relations = list { RelationFact(string(), enumValueOf(string()), string()) }
            val coverage = list { CoverageFact(string(), string()) }
            val dictionaryRelations = list { RelationReference(string(), string(), string()) }
            val synonyms = list { DictionarySynonym(string(), string(), string(), string(), string()) }
            val triggerEvents = list { TriggerEventFact(string(), enumValueOf(string()), nullable()?.let { enumValueOf(it) }, string(), nullable()) }
            val declaredTexts = list { DeclaredTextFact(string(), nullable(), nullable(), boolean()) }
            val docComments = list { DocCommentFact(string(), boolean(), enumValueOf(string()), int(), string(), boolean()) }
            val usesGaps = list { UsesGapFact(string(), list { string() }) }
            val membersGaps = list { MembersGapFact(string(), list { string() }) }
            val declaredShapes = list { DeclaredShapeFact(string(), shape()) }
            val intos = list {
                IntoFact(enumValueOf(string()), boolean(), int(), nullable(), nullableInt(), list { list { item() } }, nullable(),
                    list { target() }, if (boolean()) target() else null, nullable(), nullable(), strings(), nullable(), nullable(), nullable(), nullable(),
                    if (boolean()) span() else null, if (boolean()) span() else null, boolean())
            }
            val placesLeftOut = list { string() to int() }.toMap()
            val formsObjects = list {
                FormsObjectFact(string(), nullableInt(), nullableInt(), nullable()?.let { enumValueOf<UnitKind>(it) },
                    if (boolean()) {
                        FormsBlockFact(boolean(), enumValueOf(string()), string(), nullable(), enumValueOf(string()), string(), nullable(),
                            boolean(), boolean(), boolean(), boolean(), string(), string())
                    } else null,
                    if (boolean()) {
                        FormsItemFact(boolean(), string(), enumValueOf(string()), boolean(), boolean(), nullable(), nullable())
                    } else null)
            }
            val formsUses = list {
                FormsUseFact(string(), string(), nullable(), strings().mapTo(LinkedHashSet()) { enumValueOf(it) }, enumValueOf(string()), nullable())
            }
            return FileFacts(
                path, sha256, sizeBytes, encoding, parseStatus, recoveredRegions, compilationSchema, units, statements, occurrences, entities,
                relations, coverage, emptyList(), synonyms, triggerEvents, declaredTexts, docComments, usesGaps, membersGaps, declaredShapes, intos, placesLeftOut,
                kind, formsObjects, formsUses
            ) to dictionaryRelations
        }

        fun item(): QueryItem = QueryItem(nullable(), nullable(), nullable(), boolean(), strings(), nullable(), list { item() }, nullable(), boolean())

        fun target(): IntoTarget =
            IntoTarget(span(), string(), nullable(), strings(), boolean(), if (boolean()) shape() else null, nullable(), nullable(), nullable())

        fun shape(): TypeShape {
            fun optional(): TypeShape? = if (boolean()) shape() else null
            return when (val variant = byte()) {
                0 -> TypeShape.Anchored(boolean(), nullable(), optional(), strings(), nullable())
                1 -> TypeShape.Sized(string(), nullableInt(), nullable(), nullableInt())
                2 -> TypeShape.Unsized(string())
                3 -> TypeShape.Named(nullable(), optional(), nullable())
                4 -> TypeShape.Record(list { ShapeField(string(), nullable(), optional()) })
                5 -> TypeShape.Collection(shape())
                6 -> TypeShape.CursorRow(list { item() })
                7 -> TypeShape.Unknown(string(), boolean())
                8 -> TypeShape.OutParameter(string(), shape())
                else -> throw DamagedCacheException("a type shape of variant $variant")
            }
        }

        fun occurrence() = OccurrenceFact(
            unitIndex = int(),
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
            arguments = list { CallArgumentFact(int(), nullable(), nullable(), span(), enumValueOf(string()), nullable()) },
            nameSpan = if (boolean()) span() else null,
            writtenValues = list { WrittenValueFact(int(), enumValueOf(string()), nullable(), span()) }
        )
    }
}
