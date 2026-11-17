package com.zxce.ai.service.admin

import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleOf
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * 数据库管理页的数据层，直接用 JDBC 读写业务库。
 *
 * 三条硬约定：
 * 1. 表名、列名一律先和 information_schema 比对（白名单），前端传来的标识符不参与拼接；
 * 2. 所有值都走 PreparedStatement 绑定，页面输入不会被当成 SQL 片段；
 * 3. 只有「普通表 + 单列主键」允许写，视图和复合主键表只读。
 *
 * 时间、金额、bit 这类列统一按字符串进出：展示所见即所写，也避开 JDBC 把
 * `0000-00-00 00:00:00` 转成 Timestamp 时抛异常。
 */
@Service
class DatabaseAdminService(
    @Value("\${datasource.db.url:jdbc:mariadb://127.0.0.1:3307/alumin-link?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8}")
    private val jdbcUrl: String,
    @Value("\${datasource.db.username:root}")
    private val jdbcUser: String,
    @Value("\${datasource.db.password:123456}")
    private val jdbcPassword: String,
) {

    companion object {
        private const val SCHEMA_CACHE_MILLIS = 15_000L
        private const val PAGE_SIZE_DEFAULT = 25
        private const val PAGE_SIZE_MAX = 200

        /** 列表里打码的列名片段：这些列的值不回显，只允许整体覆盖。 */
        private val SECRET_BITS = listOf("password", "token", "secret", "credential")

        /** 日期/时间列，用于识别 `0000-00-00` 这种当前 sql_mode 下写不进去的存量默认值。 */
        private val DATE_TYPES = setOf("timestamp", "datetime", "date", "time")

        /** 主键留空时由管理页生成 UUID 的类型。 */
        private val UUID_PK_TYPES = setOf("varchar", "char")

        private val IDENT = Regex("^[A-Za-z0-9_$]+$")
    }

    private val logger = LoggerFactory.getLogger(DatabaseAdminService::class.java)

    /** information_schema 全量扫描不便宜，短时间缓存一下，避免每次点表都重扫。 */
    @Volatile
    private var schemaCache: Pair<Long, AdminSchema>? = null

    /** 行数统计比结构更贵，单独给一个稍长一点的缓存。 */
    @Volatile
    private var countCache: Pair<Long, Map<String, Long>>? = null

    init {
        try {
            Class.forName("org.mariadb.jdbc.Driver")
        } catch (e: ClassNotFoundException) {
            logger.error("未找到 MariaDB JDBC 驱动，数据库管理页将不可用", e)
        }
    }

    // ------------------------------------------------------------------ 结构

    /** 读取整库结构（含行数），15 秒内复用上一次的结果。 */
    fun schema(force: Boolean = false): AdminSchema {
        val now = System.currentTimeMillis()
        val cached = schemaCache
        if (!force && cached != null && now - cached.first < SCHEMA_CACHE_MILLIS) return cached.second

        val schema = withConnection { conn ->
            val database = schemaName(conn)
            val columns = readColumns(conn, database)
            val tables = readTables(conn, database).map { (name, type, comment) ->
                AdminTable(name, type, comment, columns[name].orEmpty())
            }
            AdminSchema(database, serverVersion(conn), tables)
        }
        schemaCache = now to schema
        return schema
    }

    /** 按名字取一张表，名字不在白名单里就直接 404。 */
    fun table(name: String, force: Boolean = false): AdminTable {
        if (!IDENT.matches(name)) throw notFound("表名不合法：$name")
        return schema(force).tables.firstOrNull { it.name == name }
            ?: throw notFound("数据库里没有表 $name")
    }

    /** 各表的行数；只统计普通表，视图数一遍没有意义。 */
    fun rowCounts(force: Boolean = false): Map<String, Long> {
        val now = System.currentTimeMillis()
        val cached = countCache
        if (!force && cached != null && now - cached.first < SCHEMA_CACHE_MILLIS) return cached.second

        val tables = schema(force).tables.filter { it.type == "BASE TABLE" }
        val counts = withConnection { conn ->
            tables.associate { it.name to countRows(conn, it.name, "", emptyList()) }
        }
        countCache = now to counts
        return counts
    }

    /** 表目录：结构 + 行数 + 能否编辑，一次给全，页面不用再来回请求。 */
    fun catalog(force: Boolean = false): Bundle {
        val schema = schema(force)
        val counts = rowCounts(force)
        val tables = schema.tables.map { table ->
            bundleOf(
                "name" to table.name,
                "type" to table.type,
                "comment" to table.comment,
                "columns" to table.columns,
                "rows" to (counts[table.name] ?: 0L),
                "writable" to table.writable,
                "primary" to table.primary,
                "readonlyReason" to table.readonlyReason(),
            )
        }
        return bundleOf(
            "database" to schema.database,
            "serverVersion" to schema.serverVersion,
            "tables" to tables,
        )
    }

    /** 建表语句，用来在页面上看索引和列注释。 */
    fun ddl(name: String): String {
        val table = table(name)
        return withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("show create table ${ident(table.name)}").use { rs ->
                    if (rs.next()) rs.getString(2) ?: "" else ""
                }
            }
        }
    }

    // ------------------------------------------------------------------ 查

    /**
     * 分页浏览一张表。
     *
     * @param filterColumn 只允许是这张表真实存在的列，留空表示不过滤
     * @param filterValue 模糊匹配的值，走参数绑定
     */
    fun browse(
        name: String,
        page: Int?,
        size: Int?,
        filterColumn: String?,
        filterValue: String?,
    ): Bundle {
        val table = table(name)
        val columns = table.columns
        val column = filterColumn?.takeIf { it.isNotBlank() }
        if (column != null && columns.none { it.name == column }) {
            throw notFound("列 $column 不属于表 ${table.name}")
        }
        val value = filterValue?.takeIf { it.isNotEmpty() }
        val where = if (column != null && value != null) " where ${ident(column)} like ?" else ""
        val args = if (column != null && value != null) listOf(likeValue(value)) else emptyList()

        val pageSize = (size ?: PAGE_SIZE_DEFAULT).coerceIn(1, PAGE_SIZE_MAX)
        return withConnection { conn ->
            val total = countRows(conn, table.name, where, args)
            val pages = maxOf((total + pageSize - 1) / pageSize, 1L).toInt()
            val pageNo = (page ?: 1).coerceIn(1, pages)
            val rows = selectRows(conn, table, where, args, pageSize, (pageNo - 1) * pageSize)
            bundleOf(
                "table" to bundleOf(
                    "name" to table.name,
                    "type" to table.type,
                    "comment" to table.comment,
                    "columns" to columns,
                    "writable" to table.writable,
                    "primary" to table.primary,
                    "readonlyReason" to table.readonlyReason(),
                    "ddl" to null,
                ),
                "rows" to rows,
                "total" to total,
                "page" to pageNo,
                "size" to pageSize,
                "pages" to pages,
                "filterColumn" to (column ?: ""),
                "filterValue" to (value ?: ""),
            )
        }
    }

    /** 按主键读一行，供编辑表单回显。 */
    fun row(name: String, pk: String): Bundle {
        val table = table(name)
        val primary = table.primary ?: throw badRequest("表 ${table.name} 没有单列主键，无法按主键定位")
        return withConnection { conn ->
            val row = loadRow(conn, table, pk) ?: throw notFound("表 ${table.name} 里没有主键 $pk 的行")
            bundleOf(
                "table" to table.name,
                "columns" to table.columns,
                "writable" to table.writable,
                "primary" to primary,
                "values" to row,
            )
        }
    }

    // ------------------------------------------------------------------ 写

    /**
     * 新增或修改一行。
     *
     * @param pk 为空表示新增，非空表示按主键更新
     * @param values 页面提交的列值，键必须是这张表真实的列名
     * @param nulls 显式要求写成 NULL 的列
     * @return 给用户看的提示语
     */
    fun save(name: String, pk: String?, values: Map<String, String?>, nulls: Collection<String>): String {
        val table = table(name)
        if (!table.writable) throw badRequest(table.readonlyReason() ?: "表 ${table.name} 只能浏览")
        val safeValues = values.filterKeys { key -> table.columns.any { it.name == key } }
        val safeNulls = nulls.filter { key -> table.columns.any { it.name == key && it.nullable } }.toSet()

        return withConnection { conn ->
            if (pk.isNullOrEmpty()) insert(conn, table, safeValues, safeNulls)
            else update(conn, table, pk, safeValues, safeNulls)
        }
    }

    /** 删除一行，主键由页面提交。 */
    fun delete(name: String, pk: String): String {
        val table = table(name)
        if (!table.writable) throw badRequest(table.readonlyReason() ?: "表 ${table.name} 只能浏览")
        if (pk.isEmpty()) throw badRequest("没有指定要删除哪一行")
        val primary = table.primary!!
        return withConnection { conn ->
            val affected = conn.prepareStatement("delete from ${ident(table.name)} where ${ident(primary.name)} = ?")
                .use { st ->
                    st.setString(1, pk)
                    st.executeUpdate()
                }
            if (affected == 0) "没有找到主键 $pk 的行，未删除任何数据。"
            else "已删除 ${table.name} 中主键 $pk 的行。"
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun <T> withConnection(block: (Connection) -> T): T {
        return DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).use { conn ->
            conn.autoCommit = true
            block(conn)
        }
    }

    private fun schemaName(conn: Connection): String {
        return conn.catalog?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("连接上没有取到库名，检查 datasource.db.url")
    }

    private fun serverVersion(conn: Connection): String {
        return try {
            conn.metaData.databaseProductName + " " + conn.metaData.databaseProductVersion
        } catch (e: Exception) {
            logger.warn("读取数据库版本失败", e)
            ""
        }
    }

    private fun readTables(conn: Connection, database: String): List<Triple<String, String, String>> {
        val sql = "select table_name, table_type, table_comment from information_schema.tables" +
                " where table_schema = ? order by table_name"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, database)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Triple(rs.getString(1), rs.getString(2) ?: "", rs.getString(3)?.trim() ?: ""))
                    }
                }
            }
        }
    }

    private fun readColumns(conn: Connection, database: String): Map<String, List<AdminColumn>> {
        val sql = "select table_name, column_name, column_type, data_type, is_nullable, column_default," +
                " column_key, extra, column_comment from information_schema.columns" +
                " where table_schema = ? order by table_name, ordinal_position"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, database)
            st.executeQuery().use { rs ->
                val result = LinkedHashMap<String, MutableList<AdminColumn>>()
                while (rs.next()) {
                    val extra = (rs.getString(8) ?: "").uppercase()
                    val column = AdminColumn(
                        name = rs.getString(2),
                        columnType = rs.getString(3) ?: "",
                        dataType = (rs.getString(4) ?: "").lowercase(),
                        nullable = rs.getString(5) == "YES",
                        defaultValue = rs.getString(6)?.takeIf { !it.equals("NULL", ignoreCase = true) },
                        pk = rs.getString(7) == "PRI",
                        auto = extra.contains("AUTO_INCREMENT"),
                        generated = extra.contains("GENERATED"),
                        comment = rs.getString(9)?.trim() ?: "",
                    )
                    result.getOrPut(rs.getString(1)) { mutableListOf() }.add(column)
                }
                result
            }
        }
    }

    private fun countRows(conn: Connection, name: String, where: String, args: List<Any>): Long {
        return conn.prepareStatement("select count(*) from ${ident(name)}$where").use { st ->
            args.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }

    private fun selectRows(
        conn: Connection,
        table: AdminTable,
        where: String,
        args: List<Any>,
        limit: Int,
        offset: Int,
    ): List<Bundle> {
        val projection = table.columns.joinToString(", ") { ident(it.name) }
        val order = table.primary?.let { " order by ${ident(it.name)}" } ?: ""
        val sql = "select $projection from ${ident(table.name)}$where$order limit ? offset ?"
        return conn.prepareStatement(sql).use { st ->
            var index = 1
            args.forEach { st.setObject(index++, it) }
            st.setInt(index++, limit)
            st.setInt(index, offset)
            st.executeQuery().use { rs -> readAll(rs, table) }
        }
    }

    private fun loadRow(conn: Connection, table: AdminTable, pk: String): Bundle? {
        val primary = table.primary ?: return null
        val projection = table.columns.joinToString(", ") { ident(it.name) }
        val sql = "select $projection from ${ident(table.name)} where ${ident(primary.name)} = ?"
        return conn.prepareStatement(sql).use { st ->
            st.setString(1, pk)
            st.executeQuery().use { rs -> readAll(rs, table).firstOrNull() }
        }
    }

    /** ResultSet 转成「列名 -> 字符串」的有序 Map，展示层不需要再关心 JDBC 类型。 */
    private fun readAll(rs: java.sql.ResultSet, table: AdminTable): List<Bundle> {
        return buildList {
            while (rs.next()) {
                val row = bundleOf()
                table.columns.forEachIndexed { i, column ->
                    row[column.name] = readCell(rs, i + 1, column)
                }
                add(row)
            }
        }
    }

    private fun readCell(rs: java.sql.ResultSet, index: Int, column: AdminColumn): String? {
        // 密码/令牌这类值一律不往浏览器送，页面上显示成打码，编辑时留空表示不修改
        if (isSecret(column.name)) return null
        // bit(1) 取字符串会拿到裸字节，交给 getBoolean 更稳
        val raw = if (column.dataType == "bit") {
            if (rs.getBoolean(index)) "1" else "0"
        } else {
            rs.getString(index)
        }
        if (raw == null) return null
        // 存量零日期在 STRICT + NO_ZERO_DATE 下原样写回会被拒绝，按空值展示
        if (column.dataType in DATE_TYPES && raw.startsWith("0000-00-00")) return null
        return raw
    }

    private fun insert(conn: Connection, table: AdminTable, values: Map<String, String?>, nulls: Set<String>): String {
        val names = mutableListOf<String>()
        val marks = mutableListOf<String>()
        val params = mutableListOf<Any?>()
        val missing = mutableListOf<String>()
        // 自动生成的 UUID 主键要回显给用户，否则新增完不知道刚写进去的是哪一行
        var newId: String? = null

        for (column in table.columns) {
            if (column.generated) continue
            val cell = insertCell(column, values, nulls)
            when (cell) {
                Cell.Skip -> {}
                is Cell.Null -> {
                    names += column.name; marks += "?"; params += null
                }

                is Cell.Text -> {
                    names += column.name; marks += "?"; params += cell.value
                    if (column.pk) newId = cell.value
                }

                Cell.Now -> {
                    names += column.name; marks += "current_timestamp"
                }

                Cell.Missing -> missing += column.name
            }
        }

        if (missing.isNotEmpty()) {
            throw badRequest("这些必填列没有填：" + missing.joinToString("、"))
        }
        if (names.isEmpty()) throw badRequest("没有可写入的列")

        val sql = "insert into ${ident(table.name)} (${names.joinToString(", ") { ident(it) }})" +
                " values (${marks.joinToString(", ")})"
        conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, v ->
                if (v == null) st.setNull(i + 1, java.sql.Types.NULL) else st.setString(i + 1, v.toString())
            }
            st.executeUpdate()
        }
        return "已新增一行到 ${table.name}" + (newId?.let { "（主键 $it）" } ?: "") + "。"
    }

    private fun update(
        conn: Connection,
        table: AdminTable,
        pk: String,
        values: Map<String, String?>,
        nulls: Set<String>,
    ): String {
        val primary = table.primary!!
        val current = loadRow(conn, table, pk) ?: throw notFound("表 ${table.name} 里没有主键 $pk 的行，可能已被删除")

        val names = mutableListOf<String>()
        val marks = mutableListOf<String>()
        val params = mutableListOf<Any?>()

        for (column in table.columns) {
            if (column.generated || column.pk) continue
            val target = updateCell(column, values, nulls) ?: continue
            val existing = current[column.name] as String?
            // 值没变就不写进去，避免把 on update current_timestamp 的列钉死在旧值上
            val unchanged = when (target) {
                is Cell.Null -> existing == null
                is Cell.Text -> existing == target.value
                else -> true
            }
            if (unchanged) continue
            names += column.name
            when (target) {
                is Cell.Null -> {
                    marks += "?"; params += null
                }

                is Cell.Text -> {
                    marks += "?"; params += target.value
                }

                else -> marks += "current_timestamp"
            }
        }

        if (names.isEmpty()) return "没有需要修改的列（值没有变化），未提交更新。"

        val assignments = names.indices.joinToString(", ") { "${ident(names[it])} = ${marks[it]}" }
        val sql = "update ${ident(table.name)} set $assignments where ${ident(primary.name)} = ?"
        val affected = conn.prepareStatement(sql).use { st ->
            params.forEachIndexed { i, v ->
                if (v == null) st.setNull(i + 1, java.sql.Types.NULL) else st.setString(i + 1, v.toString())
            }
            st.setString(params.size + 1, pk)
            st.executeUpdate()
        }
        if (affected == 0) throw notFound("表 ${table.name} 里没有主键 $pk 的行，可能已被删除")
        return "已保存 ${table.name}（主键 $pk）的修改。"
    }

    /**
     * 新增时一列该写什么。
     *
     * 留空（且没勾 NULL）时按列属性决定：可空 -> NULL，有默认值 -> 交给数据库，
     * 零日期默认值 -> 当前时间，必填 -> 记进待补清单。
     */
    private fun insertCell(
        column: AdminColumn,
        values: Map<String, String?>,
        nulls: Set<String>,
    ): Cell {
        val raw = values[column.name]

        if (column.pk) {
            if (!raw.isNullOrEmpty()) return Cell.Text(raw)
            if (column.auto) return Cell.Skip
            if (column.dataType in UUID_PK_TYPES) return Cell.Text(UUID.randomUUID().toString())
            if (column.defaultValue != null) return Cell.Skip
            return Cell.Missing
        }

        if (column.name in nulls && column.nullable) return Cell.Null
        if (!raw.isNullOrEmpty()) return Cell.Text(raw)
        if (column.auto) return Cell.Skip
        if (column.nullable) return Cell.Null
        if (column.invalidDefault()) return Cell.Now
        if (column.defaultValue != null) return Cell.Skip
        return Cell.Missing
    }

    /**
     * 更新时一列该写什么，返回 null 表示保持原值。
     * 打码列留空表示「不修改」，避免一保存就把密码哈希抹成空。
     */
    private fun updateCell(
        column: AdminColumn,
        values: Map<String, String?>,
        nulls: Set<String>,
    ): Cell? {
        val raw = values[column.name]
        if (column.name in nulls && column.nullable) return Cell.Null
        if (raw.isNullOrEmpty()) {
            if (isSecret(column.name)) return null
            // NOT NULL 的列不能留空，可空列留空就是写 NULL
            return if (column.nullable) Cell.Null else null
        }
        return Cell.Text(raw)
    }

    // ------------------------------------------------------------------ 工具

    /** 只用于已经过白名单校验的真实表名/列名。 */
    private fun ident(name: String): String = "`" + name.replace("`", "``") + "`"

    /** LIKE 的通配符要转义，否则用户输入的 % 会变成「匹配所有」。 */
    private fun likeValue(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }

    private fun isSecret(name: String): Boolean {
        val lower = name.lowercase()
        return SECRET_BITS.any { it in lower }
    }

    private fun AdminColumn.invalidDefault(): Boolean {
        return dataType in DATE_TYPES && defaultText(defaultValue).startsWith("0000-00-00")
    }

    private fun defaultText(raw: String?): String {
        return raw?.trim()?.trim('\'')?.trim('"') ?: ""
    }

    /**
     * 业务异常交给全局 ErrorWebFilter 统一翻译成状态码：
     * 参数问题 400、找不到 404，且 message 会原样成为响应里的 msg
     * （ErrorResponseException 的 message 是 ProblemDetail 的 toString，不适合直接给用户看）。
     */
    private fun notFound(message: String) = NoSuchElementException(message)

    private fun badRequest(message: String) = IllegalArgumentException(message)

    /** 一列要写进 SQL 的内容。 */
    private sealed interface Cell {
        /** 不写这一列，交给数据库默认值。 */
        data object Skip : Cell

        /** 写成 NULL。 */
        data object Null : Cell

        /** 写成 current_timestamp。 */
        data object Now : Cell

        /** 绑定用户填的值。 */
        data class Text(val value: String) : Cell

        /** 必填但没填。 */
        data object Missing : Cell
    }
}

/** 列信息，字段与 information_schema.columns 对齐。 */
data class AdminColumn(
    val name: String,
    val columnType: String,
    val dataType: String,
    val nullable: Boolean,
    val defaultValue: String?,
    val pk: Boolean,
    val auto: Boolean,
    val generated: Boolean,
    val comment: String,
)

/** 表信息。 */
data class AdminTable(
    val name: String,
    val type: String,
    val comment: String,
    val columns: List<AdminColumn>,
)

/** 整库结构。 */
data class AdminSchema(
    val database: String,
    val serverVersion: String,
    val tables: List<AdminTable>,
)

/** 主键列（单列主键才有意义）。 */
val AdminTable.pkColumns: List<AdminColumn> get() = columns.filter { it.pk }

/** 单列主键的那一列，复合主键和无主键表返回 null。 */
val AdminTable.primary: AdminColumn? get() = pkColumns.singleOrNull()

/** 只有普通表 + 单列主键才允许增删改。 */
val AdminTable.writable: Boolean get() = type == "BASE TABLE" && pkColumns.size == 1

/** 只读原因，用于页面上给个说明，而不是让按钮莫名其妙地消失。 */
fun AdminTable.readonlyReason(): String? = when {
    type != "BASE TABLE" -> "这是一张视图，只能浏览"
    pkColumns.isEmpty() -> "这张表没有主键，只能浏览"
    pkColumns.size > 1 -> "这张表是复合主键，只能浏览"
    else -> null
}