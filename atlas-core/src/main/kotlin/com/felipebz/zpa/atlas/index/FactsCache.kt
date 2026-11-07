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
import com.felipebz.zpa.atlas.model.*
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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

/**
 * The facts cache of `index --incremental`, beside the index file. Gzip-compressed; strings are written once and then
 * referred to by number. It ends with a marker, and is read to the end of the gzip stream, whose checksum and length
 * gzip checks: a cache cut short or altered is refused. It is written to a temporary file moved over the old one, so a
 * run killed midway leaves the old cache or none.
 *
 * Reading it needs nothing of the run: its [inputs][CacheContents.inputs] are compared, and its dictionary relations
 * taken back, by the caller. So it can be read while the dictionary is still loading.
 */
internal object FactsCache {
    private const val MAGIC = "zpa-atlas facts cache"
    private const val FORMAT = 26
    private const val END = "end of the facts cache"
    private const val MAX_STRING_BYTES = 1 shl 26

    fun pathFor(index: Path): Path = index.resolveSibling(index.fileName.toString() + ".facts")

    fun write(target: Path, contents: CacheContents) {
        val temporary = Files.createTempFile(target.toAbsolutePath().parent, target.fileName.toString(), ".tmp")
        try {
            // The fastest level: the cache is written on every run, and its strings are already written once each.
            object : GZIPOutputStream(BufferedOutputStream(Files.newOutputStream(temporary)), 1 shl 16) {
                init {
                    def.setLevel(Deflater.BEST_SPEED)
                }
            }.use { gzip ->
                val out = Output(DataOutputStream(BufferedOutputStream(gzip, 1 shl 16)))
                out.contents(contents)
                out.data.writeUTF(END)
                out.data.flush()
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * The cache at [source].
     *
     * @throws DamagedCacheException when it is not a whole cache of this format
     */
    fun read(source: Path): CacheContents = try {
        GZIPInputStream(BufferedInputStream(Files.newInputStream(source), 1 shl 16), 1 shl 16).use { gzip ->
            val data = DataInputStream(BufferedInputStream(gzip, 1 shl 16))
            val contents = Input(data).contents()
            // Reading past the marker makes gzip check its checksum and length.
            if (data.readUTF() != END || data.read() != -1) throw DamagedCacheException("it does not end where it should")
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
     * The facts of [file] with their dictionary relations taken back from [dictionary], the one the cache was written
     * with.
     *
     * @throws DamagedCacheException when [dictionary] lacks one of them
     */
    fun withRelations(file: CachedFile, dictionary: AtlasDictionary, byKey: Map<String, ProjectExternalRelation>): FileFacts {
        if (file.relations.isEmpty()) return file.facts
        val relations = file.relations.map { reference ->
            byKey[reference.key] ?: dictionary.metadata.findRelation(ProjectIdentifier.fromSource(reference.schema), ProjectIdentifier.fromSource(reference.name))
                .relation.orElse(null)?.takeIf { RelationResolver.dictionaryKey(it) == reference.key }
            ?: throw DamagedCacheException("the dictionary has no relation ${reference.key}")
        }
        val facts = file.facts
        return FileFacts(
            facts.path, facts.sha256, facts.sizeBytes, facts.encoding, facts.parseStatus, facts.recoveredRegions, facts.compilationSchema, facts.units,
            facts.statements, facts.occurrences, facts.entities, facts.relations, facts.coverage, relations, facts.dictionarySynonyms, facts.triggerEvents,
            facts.declaredTexts, facts.docComments, facts.usesGaps, facts.membersGaps, facts.declaredShapes, facts.intos, facts.placesLeftOut, facts.kind,
            facts.formsObjects, facts.formsUses
        )
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
                nullable(file.reader)
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
            string(facts.kind.name)
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
            list(facts.declaredTexts) { string(it.entityKey); nullable(it.datatype); nullable(it.value); data.writeBoolean(it.valueCut) }
            list(facts.docComments) {
                string(it.entityKey); data.writeBoolean(it.inBody); string(it.shape.name); data.writeInt(it.line); string(it.text); data.writeBoolean(it.textCut)
            }
            list(facts.usesGaps) { string(it.entityKey); list(it.reasons) { reason -> string(reason) } }
            list(facts.membersGaps) { string(it.entityKey); list(it.reasons) { reason -> string(reason) } }
            list(facts.declaredShapes) { string(it.entityKey); shape(it.shape) }
            list(facts.intos) { into ->
                string(into.source.name); data.writeBoolean(into.bulk); data.writeInt(into.unitIndex); nullable(into.containerKey)
                nullableInt(into.statementLocalId); list(into.branches) { items -> list(items) { item(it) } }; nullable(into.cursorKey)
                list(into.targets) { target(it) }
                data.writeBoolean(into.row != null); into.row?.let { target(it) }; nullable(into.rowField); nullable(into.expression); strings(into.functions); nullable(into.parameterKey); nullable(into.fromParameter); nullable(into.cursorKind); nullable(into.cursorType)
                data.writeBoolean(into.call != null); into.call?.let { span(it) }
                data.writeBoolean(into.resultOf != null); into.resultOf?.let { span(it) }
                data.writeBoolean(into.keeps)
            }
            list(facts.placesLeftOut.entries.toList()) { string(it.key); data.writeInt(it.value) }
            list(facts.formsObjects) {
                string(it.entityKey); nullableInt(it.codeFirstLine); nullableInt(it.codeLastLine); nullable(it.unitKind?.name)
                data.writeBoolean(it.block != null)
                it.block?.let { block ->
                    data.writeBoolean(block.database); string(block.sourceType.name); string(block.sourceName); nullable(block.sourceKey)
                    string(block.targetType.name); string(block.targetName); nullable(block.targetKey); data.writeBoolean(block.queryAllowed)
                    data.writeBoolean(block.insertAllowed); data.writeBoolean(block.updateAllowed); data.writeBoolean(block.deleteAllowed); string(block.where)
                    string(block.orderBy)
                }
                data.writeBoolean(it.item != null)
                it.item?.let { item ->
                    data.writeBoolean(item.database); string(item.columnName); string(item.kind.name); data.writeBoolean(item.insertAllowed)
                    data.writeBoolean(item.updateAllowed); nullable(item.columnKey); nullable(item.targetColumnKey)
                }
            }
            list(facts.formsUses) {
                string(it.containerKey); string(it.written); nullable(it.entityKey); strings(it.roles.map { role -> role.name }); string(it.status.name)
                nullable(it.reason)
            }
        }

        fun item(item: QueryItem) {
            nullable(item.name); nullable(item.columnKey); nullable(item.expression); data.writeBoolean(item.opaque); strings(item.functions); nullable(item.combined); list(item.parts) { item(it) }; nullable(item.notColumn)
        }

        /** An `INTO` target, or the record of an assignment. */
        fun target(target: IntoTarget) {
            span(target.span); string(target.written); nullable(target.entityKey); strings(target.path); data.writeBoolean(target.element)
            data.writeBoolean(target.local != null); target.local?.let { shape(it) }; nullable(target.declaredType); nullable(target.reason)
            nullable(target.localKey)
        }

        /** A [TypeShape], its variant first, then its fields, nested shapes written the same way. */
        fun shape(shape: TypeShape) {
            fun optional(nested: TypeShape?) {
                data.writeBoolean(nested != null)
                nested?.let { shape(it) }
            }
            when (shape) {
                is TypeShape.Anchored -> {
                    data.writeByte(0); data.writeBoolean(shape.rowType); nullable(shape.anchorKey); optional(shape.local); strings(shape.path); nullable(shape.reason)
                }
                is TypeShape.Sized -> {
                    data.writeByte(1); string(shape.type); nullableInt(shape.length); nullable(shape.unit); nullableInt(shape.scale)
                }
                is TypeShape.Unsized -> {
                    data.writeByte(2); string(shape.type)
                }
                is TypeShape.Named -> {
                    data.writeByte(3); nullable(shape.typeKey); optional(shape.local); nullable(shape.reason)
                }
                is TypeShape.Record -> {
                    data.writeByte(4); list(shape.fields) { string(it.name); nullable(it.key); optional(it.local) }
                }
                is TypeShape.Collection -> {
                    data.writeByte(5); shape(shape.element)
                }
                is TypeShape.CursorRow -> {
                    data.writeByte(6); list(shape.items) { item(it) }
                }
                is TypeShape.Unknown -> {
                    data.writeByte(7); string(shape.reason); data.writeBoolean(shape.strongCursor)
                }
                is TypeShape.OutParameter -> {
                    data.writeByte(8); string(shape.mode); shape(shape.type)
                }
            }
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

    private class Input(val data: DataInputStream) {
        private val pool = ArrayList<String>()

        fun contents(): CacheContents {
            if (data.readUTF() != MAGIC) throw DamagedCacheException("it is not a facts cache")
            val format = data.readInt()
            if (format != FORMAT) throw DamagedCacheException("its format is $format, not $FORMAT")
            val inputs = map()
            val preparation = string()
            val schemas = strings().toSet()
            val omitted = strings().toSet()
            val types = count()
            val global = CacheGlobal(preparation, schemas, omitted, buildMap { repeat(types) { put(string(), TypeSummary(string(), nullable())) } })
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

        fun facts(): Pair<FileFacts, List<RelationReference>> {
            val path = string()
            val sha256 = string()
            val sizeBytes = data.readLong()
            val encoding = string()
            val parseStatus = enumValueOf<ParseStatus>(string())
            val kind = enumValueOf<FileKind>(string())
            val recoveredRegions = data.readInt()
            val compilationSchema = nullable()
            val units = list { UnitFact(enumValueOf(string()), string(), nullable(), span(), nullable()) }
            val statements = list { StatementFact(data.readInt(), data.readInt(), nullable(), enumValueOf(string()), span(), string()) }
            val occurrences = list { occurrence() }
            val entities = list { EntityFact(string(), enumValueOf(string()), nullable(), string(), nullable(), string(), data.readBoolean(), data.readInt()) }
            val relations = list { RelationFact(string(), enumValueOf(string()), string()) }
            val coverage = list { CoverageFact(string(), string()) }
            val dictionaryRelations = list { RelationReference(string(), string(), string()) }
            val synonyms = list { DictionarySynonym(string(), string(), string(), string(), string()) }
            val triggerEvents = list { TriggerEventFact(string(), enumValueOf(string()), nullable()?.let { enumValueOf(it) }, string(), nullable()) }
            val declaredTexts = list { DeclaredTextFact(string(), nullable(), nullable(), data.readBoolean()) }
            val docComments = list { DocCommentFact(string(), data.readBoolean(), enumValueOf(string()), data.readInt(), string(), data.readBoolean()) }
            val usesGaps = list { UsesGapFact(string(), list { string() }) }
            val membersGaps = list { MembersGapFact(string(), list { string() }) }
            val declaredShapes = list { DeclaredShapeFact(string(), shape()) }
            val intos = list {
                IntoFact(enumValueOf(string()), data.readBoolean(), data.readInt(), nullable(), nullableInt(), list { list { item() } }, nullable(),
                    list { target() }, if (data.readBoolean()) target() else null, nullable(), nullable(), strings(), nullable(), nullable(), nullable(), nullable(),
                    if (data.readBoolean()) span() else null, if (data.readBoolean()) span() else null, data.readBoolean())
            }
            val placesLeftOut = list { string() to data.readInt() }.toMap()
            val formsObjects = list {
                FormsObjectFact(string(), nullableInt(), nullableInt(), nullable()?.let { enumValueOf<UnitKind>(it) },
                    if (data.readBoolean()) {
                        FormsBlockFact(data.readBoolean(), enumValueOf(string()), string(), nullable(), enumValueOf(string()), string(), nullable(),
                            data.readBoolean(), data.readBoolean(), data.readBoolean(), data.readBoolean(), string(), string())
                    } else null,
                    if (data.readBoolean()) {
                        FormsItemFact(data.readBoolean(), string(), enumValueOf(string()), data.readBoolean(), data.readBoolean(), nullable(), nullable())
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

        fun item(): QueryItem = QueryItem(nullable(), nullable(), nullable(), data.readBoolean(), strings(), nullable(), list { item() }, nullable())

        fun target(): IntoTarget =
            IntoTarget(span(), string(), nullable(), strings(), data.readBoolean(), if (data.readBoolean()) shape() else null, nullable(), nullable(), nullable())

        fun shape(): TypeShape {
            fun optional(): TypeShape? = if (data.readBoolean()) shape() else null
            return when (val variant = data.readByte().toInt()) {
                0 -> TypeShape.Anchored(data.readBoolean(), nullable(), optional(), strings(), nullable())
                1 -> TypeShape.Sized(string(), nullableInt(), nullable(), nullableInt())
                2 -> TypeShape.Unsized(string())
                3 -> TypeShape.Named(nullable(), optional(), nullable())
                4 -> TypeShape.Record(list { ShapeField(string(), nullable(), optional()) })
                5 -> TypeShape.Collection(shape())
                6 -> TypeShape.CursorRow(list { item() })
                7 -> TypeShape.Unknown(string(), data.readBoolean())
                8 -> TypeShape.OutParameter(string(), shape())
                else -> throw DamagedCacheException("a type shape of variant $variant")
            }
        }

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
    }
}
