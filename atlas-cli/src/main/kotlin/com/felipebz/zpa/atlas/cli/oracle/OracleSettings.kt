//
// SPDX-FileCopyrightText: 2026 Felipe Zorzo
// SPDX-License-Identifier: Apache-2.0
//

package com.felipebz.zpa.atlas.cli.oracle

import com.felipebz.zpa.atlas.cli.CliValidationException
import com.felipebz.zpa.atlas.cli.IndexArguments
import com.felipebz.zpa.atlas.cli.OracleOptions
import com.felipebz.zpa.atlas.extract.normalizeIdentifier
import com.felipebz.zpa.oracle.metadata.OracleMetadataRequest

/**
 * Validated database settings, with the options of zpa-cli. Schema and relation names follow Oracle identifier rules:
 * unquoted names are case-insensitive (upper-cased), `"Name"` keeps its exact case. [url] and the password are
 * secrets-bearing values: they are never included in messages or logs.
 */
class OracleSettings(
    val url: String,
    val user: String,
    val password: String,
    val schemas: List<String>,
    val relations: List<Pair<String, String>>
) {
    /** The metadata request: the schemas and relations of the options, plus [extraRelations] (synonym targets). */
    fun request(extraRelations: Collection<Pair<String, String>> = emptyList()): OracleMetadataRequest {
        val builder = OracleMetadataRequest.builder()
        schemas.forEach { builder.schema(it) }
        (relations + extraRelations).distinct().forEach { (schema, name) -> builder.relation(schema, name) }
        return builder.build()
    }

    override fun toString(): String = "OracleSettings(user=$user, schemas=$schemas, relations=$relations)"

    companion object {
        private val CREDENTIALS_IN_URL = Regex("(?:^jdbc:oracle:[a-z0-9]+:[^@]*/[^@]*@)|(?:password\\s*=)", RegexOption.IGNORE_CASE)

        /**
         * The settings when Oracle metadata is requested (`--oracle-url`), null otherwise. [about] gives the message of a
         * value that does not name its option, with that option: unchanged by default.
         */
        fun from(
            args: OracleOptions,
            about: (option: String, message: String) -> String = { _, message -> message },
            environment: (String) -> String?
        ): OracleSettings? {
            val anyOption = args.oracleUrl.isNotBlank() || args.oracleUser.isNotBlank() || args.oracleSchemas.isNotBlank() ||
                args.oracleRelations.isNotBlank()
            if (!anyOption) return null
            if (args.oracleUrl.isBlank()) {
                throw CliValidationException("--oracle-user, --oracle-schemas and --oracle-relations require --oracle-url")
            }
            if (args.oracleUser.isBlank()) throw CliValidationException("--oracle-url requires --oracle-user")
            if (CREDENTIALS_IN_URL.containsMatchIn(args.oracleUrl)) {
                throw CliValidationException("--oracle-url must not contain credentials; use --oracle-user and the password environment variable")
            }
            val schemas = entries(args.oracleSchemas).map { name(it, "--oracle-schemas") }.distinct()
            val relations = entries(args.oracleRelations).map { relation ->
                val parts = relation.split('.').map { it.trim() }
                if (parts.size != 2 || parts.any { it.isEmpty() }) {
                    throw CliValidationException("Invalid --oracle-relations entry '$relation': expected SCHEMA.RELATION")
                }
                name(parts[0], "--oracle-relations") to name(parts[1], "--oracle-relations")
            }.distinct()
            if (schemas.isEmpty() && relations.isEmpty()) {
                throw CliValidationException("--oracle-url requires --oracle-schemas or --oracle-relations")
            }
            val variable = args.oraclePasswordEnv
            if (variable.isBlank()) throw CliValidationException("--oracle-password-env must name an environment variable")
            val password = environment(variable)
            if (password.isNullOrEmpty()) {
                throw CliValidationException(about("--oracle-password-env", "The database password environment variable '$variable' is not set"))
            }
            return OracleSettings(args.oracleUrl, args.oracleUser, password, schemas, relations)
        }

        /** The `--compilation-schema` value as a dictionary name, or null. */
        fun compilationSchema(args: IndexArguments): String? {
            val spelling = args.compilationSchema.trim()
            if (spelling.isEmpty()) return null
            return name(spelling, "--compilation-schema")
        }

        private fun entries(value: String): List<String> =
            if (value.isBlank()) emptyList() else value.split(',').map { it.trim() }

        /** The dictionary name of an Oracle identifier: unquoted is upper-cased, `"Name"` is taken exactly. */
        private fun name(spelling: String, option: String): String {
            val name = normalizeIdentifier(spelling)
            if (name.isEmpty() || name.contains('"')) {
                throw CliValidationException("Invalid $option name '$spelling': use NAME (case-insensitive) or \"Name\" (exact case)")
            }
            return name
        }
    }
}
