//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import java.io.*
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.sql.Connection
import java.sql.SQLException
import java.sql.SQLFeatureNotSupportedException
import java.util.logging.Logger
import java.util.zip.DeflaterOutputStream
import javax.sql.DataSource

/**
 * Every call a reader of a database makes through a [DataSource], in order, with what each call gave: a value, another
 * JDBC object, or an [SQLException]. [RecordingDataSource] writes it while the reader reads the database; a
 * [ReplayingDataSource] gives the same answers to the same calls, without a database. A reader that makes other calls
 * than those recorded (another query, other parameters) gets a [ReplayMismatch]: the recording does not answer it.
 *
 * Only plain values are recorded (strings, numbers, booleans); a call that gives or takes anything else makes the
 * recording [unusable][JdbcRecorder.unusable].
 */
internal object JdbcRecording {
    // Tags of the values in the stream.
    const val NULL = 0
    const val STRING = 1
    const val INT = 2
    const val LONG = 3
    const val BOOLEAN = 4
    const val DECIMAL = 5
    const val OBJECT = 6
    const val THROWN = 7
    const val SHORT = 8

    /** A call of an object of the JDBC API, as it is recorded: the method's name and parameter types. */
    fun signature(method: Method): String = method.name + method.parameterTypes.joinToString(",", "(", ")") { it.simpleName }

    /** A JDBC type a call may give, recorded as another object of the recording. */
    fun isRecordedObject(type: Class<*>): Boolean = type.isInterface && type.name.startsWith("java.sql.")

    /** Answers [method] of [proxy] when it is one of [Object]'s own; null otherwise. */
    fun objectMethod(proxy: Any, method: Method, args: Array<out Any?>?, id: Int): Any? = when (method.name) {
        "equals" -> proxy === args?.get(0)
        "hashCode" -> System.identityHashCode(proxy)
        "toString" -> "JdbcRecording#$id"
        else -> null
    }

    fun isObjectMethod(method: Method): Boolean = method.declaringClass == Any::class.java
}

/**
 * Thrown when a replayed reader makes a call the recording does not have. An [Error], so that the readers' handling of
 * [SQLException]s and runtime failures, which turns them into messages, lets it through to the caller.
 */
internal class ReplayMismatch(message: String) : Error(message)

/** The stream a [RecordingDataSource] writes: the calls, compressed, and the strings the database gave. */
internal class JdbcRecorder {
    private val bytes = ByteArrayOutputStream()
    private val deflater = DeflaterOutputStream(bytes)
    private val out = DataOutputStream(deflater)
    private val pool = HashMap<String, Int>()
    private var nextObject = 1

    /** Why the recording cannot be replayed; null while it can. */
    @Volatile
    var unusable: String? = null
        private set

    /** Every string the database gave or was given, as the schemas the reading may have read come from. */
    val strings = HashSet<String>()

    /** How many calls were recorded. */
    var calls = 0L
        private set

    /** The compressed calls, once the reading is done. */
    fun finish(): ByteArray {
        out.flush()
        deflater.finish()
        return bytes.toByteArray()
    }

    @Synchronized
    fun record(target: Int, method: Method, args: Array<out Any?>?, result: Any?, thrown: SQLException?, objectId: Int?) {
        if (unusable != null) return
        try {
            out.writeInt(target)
            string(JdbcRecording.signature(method))
            val values = args.orEmpty()
            out.writeShort(values.size)
            for (value in values) {
                if (!value(value)) return
                if (value is String) strings += value
            }
            when {
                thrown != null -> {
                    out.writeByte(JdbcRecording.THROWN)
                    out.writeInt(thrown.errorCode)
                    nullable(thrown.sqlState)
                    nullable(thrown.message)
                }
                objectId != null -> {
                    out.writeByte(JdbcRecording.OBJECT)
                    out.writeInt(objectId)
                }
                else -> {
                    if (!value(result)) return
                    if (result is String) strings += result
                }
            }
            calls++
        } catch (e: java.io.IOException) {
            unusable = "the recording could not be written: ${e.message}"
        }
    }

    fun newObject(): Int = synchronized(this) { nextObject++ }

    /** Writes [value]; false, and the recording unusable, for a value of another type. */
    private fun value(value: Any?): Boolean {
        when (value) {
            null -> out.writeByte(JdbcRecording.NULL)
            is String -> {
                out.writeByte(JdbcRecording.STRING)
                string(value)
            }
            is Int -> {
                out.writeByte(JdbcRecording.INT)
                out.writeInt(value)
            }
            is Long -> {
                out.writeByte(JdbcRecording.LONG)
                out.writeLong(value)
            }
            is Short -> {
                out.writeByte(JdbcRecording.SHORT)
                out.writeShort(value.toInt())
            }
            is Boolean -> {
                out.writeByte(JdbcRecording.BOOLEAN)
                out.writeBoolean(value)
            }
            is BigDecimal -> {
                out.writeByte(JdbcRecording.DECIMAL)
                string(value.toString())
            }
            else -> {
                unusable = "a call gave or took a ${value.javaClass.name}"
                return false
            }
        }
        return true
    }

    private fun nullable(value: String?) {
        out.writeBoolean(value != null)
        if (value != null) string(value)
    }

    private fun string(value: String) {
        val index = pool[value]
        if (index != null) {
            out.writeInt(index)
            return
        }
        pool[value] = pool.size
        out.writeInt(-1)
        val bytes = value.toByteArray(Charsets.UTF_8)
        out.writeInt(bytes.size)
        out.write(bytes)
    }
}

/** A [DataSource] that records, in [recorder], every call made through it and through the JDBC objects it hands out. */
internal class RecordingDataSource(private val real: DataSource, private val recorder: JdbcRecorder) : DataSource {
    override fun getConnection(): Connection {
        val method = DataSource::class.java.getMethod("getConnection")
        val connection = try {
            real.connection
        } catch (e: SQLException) {
            recorder.record(0, method, null, null, e, null)
            throw e
        }
        val id = recorder.newObject()
        recorder.record(0, method, null, null, null, id)
        return proxy(connection, Connection::class.java, id) as Connection
    }

    private fun proxy(target: Any, type: Class<*>, id: Int): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type), Handler(target, id))

    private inner class Handler(private val target: Any, private val id: Int) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (JdbcRecording.isObjectMethod(method)) return JdbcRecording.objectMethod(proxy, method, args, id)
            val result = try {
                method.invoke(target, *args.orEmpty())
            } catch (e: InvocationTargetException) {
                val cause = e.cause ?: e
                if (cause is SQLException) recorder.record(id, method, args, null, cause, null)
                throw cause
            }
            if (result != null && JdbcRecording.isRecordedObject(method.returnType)) {
                val objectId = recorder.newObject()
                recorder.record(id, method, args, null, null, objectId)
                return proxy(result, method.returnType, objectId)
            }
            recorder.record(id, method, args, result, null, null)
            return result
        }
    }

    override fun getConnection(username: String, password: String): Connection = throw SQLFeatureNotSupportedException()
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
    override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("Not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = false
}

/**
 * A [DataSource] that answers from a recording ([calls], the stream a [JdbcRecorder] wrote, inflated): each call must
 * be the next one recorded, made on the same object with the same arguments. Once a call is not, it throws a
 * [ReplayMismatch], and every later call (the readers' clean-up) answers nothing.
 */
internal class ReplayingDataSource(calls: InputStream, private val count: Long) : DataSource {
    private val input = DataInputStream(calls)
    private val pool = ArrayList<String>()
    private var read = 0L

    /** Why the replay stopped; null while every call matched. */
    var mismatch: String? = null
        private set

    /** Whether every recorded call was made, and nothing else. */
    val complete: Boolean get() = mismatch == null && read == count

    override fun getConnection(): Connection = answer(0, DataSource::class.java.getMethod("getConnection"), null) as Connection

    @Synchronized
    private fun answer(target: Int, method: Method, args: Array<out Any?>?): Any? {
        if (mismatch != null) return default(method.returnType)
        val expected = try {
            if (read == count) null else next()
        } catch (e: java.io.IOException) {
            fail("the recording cannot be read: ${e.message}")
        }
        val signature = JdbcRecording.signature(method)
        val given = args.orEmpty().toList()
        if (expected == null) fail("$signature was not recorded: the recording ends before it")
        if (expected.target != target || expected.signature != signature || expected.args != given) {
            fail("$signature on #$target with $given was recorded as ${expected.signature} on #${expected.target} with ${expected.args}")
        }
        read++
        return when (val result = expected.result) {
            is Thrown -> throw SQLException(result.message, result.sqlState, result.code)
            is ObjectResult -> Proxy.newProxyInstance(method.returnType.classLoader, arrayOf(method.returnType), Handler(result.id))
            else -> converted(result, method.returnType)
        }
    }

    private fun fail(message: String): Nothing {
        mismatch = message
        throw ReplayMismatch(message)
    }

    private inner class Handler(private val id: Int) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (JdbcRecording.isObjectMethod(method)) return JdbcRecording.objectMethod(proxy, method, args, id)
            return answer(id, method, args)
        }
    }

    private class Call(val target: Int, val signature: String, val args: List<Any?>, val result: Any?)
    private class ObjectResult(val id: Int)
    private class Thrown(val code: Int, val sqlState: String?, val message: String?)

    private fun next(): Call {
        val target = input.readInt()
        val signature = string()
        val args = List(input.readShort().toInt()) { value(input.readByte().toInt()) }
        val tag = input.readByte().toInt()
        val result = when (tag) {
            JdbcRecording.THROWN -> Thrown(input.readInt(), nullable(), nullable())
            JdbcRecording.OBJECT -> ObjectResult(input.readInt())
            else -> value(tag)
        }
        return Call(target, signature, args, result)
    }

    private fun value(tag: Int): Any? = when (tag) {
        JdbcRecording.NULL -> null
        JdbcRecording.STRING -> string()
        JdbcRecording.INT -> input.readInt()
        JdbcRecording.LONG -> input.readLong()
        JdbcRecording.SHORT -> input.readShort()
        JdbcRecording.BOOLEAN -> input.readBoolean()
        JdbcRecording.DECIMAL -> BigDecimal(string())
        else -> throw java.io.IOException("unknown value tag $tag")
    }

    private fun nullable(): String? = if (input.readBoolean()) string() else null

    private fun string(): String {
        val index = input.readInt()
        if (index >= 0) return pool.getOrNull(index) ?: throw java.io.IOException("unknown string $index")
        val length = input.readInt()
        if (length < 0 || length > MAX_STRING_BYTES) throw java.io.IOException("a string of $length bytes")
        val bytes = ByteArray(length)
        try {
            input.readFully(bytes)
        } catch (e: EOFException) {
            throw java.io.IOException("it ends early", e)
        }
        return String(bytes, Charsets.UTF_8).also { pool += it }
    }

    private fun converted(value: Any?, type: Class<*>): Any? = when {
        value == null -> default(type)
        type == Int::class.javaPrimitiveType || type == Int::class.javaObjectType -> (value as Number).toInt()
        type == Long::class.javaPrimitiveType || type == Long::class.javaObjectType -> (value as Number).toLong()
        type == Short::class.javaPrimitiveType || type == Short::class.javaObjectType -> (value as Number).toShort()
        else -> value
    }

    private fun default(type: Class<*>): Any? = when (type) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Short::class.javaPrimitiveType -> 0.toShort()
        Byte::class.javaPrimitiveType -> 0.toByte()
        Double::class.javaPrimitiveType -> 0.0
        Float::class.javaPrimitiveType -> 0f
        else -> null
    }

    override fun getConnection(username: String, password: String): Connection = throw SQLFeatureNotSupportedException()
    override fun getLogWriter(): PrintWriter? = null
    override fun setLogWriter(out: PrintWriter?) = Unit
    override fun setLoginTimeout(seconds: Int) = Unit
    override fun getLoginTimeout(): Int = 0
    override fun getParentLogger(): Logger = throw SQLFeatureNotSupportedException()
    override fun <T : Any?> unwrap(iface: Class<T>): T = throw SQLException("Not a wrapper")
    override fun isWrapperFor(iface: Class<*>): Boolean = false

    private companion object {
        const val MAX_STRING_BYTES = 1 shl 26
    }
}
