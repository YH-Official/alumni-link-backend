package com.zxce.ai.ui.view.database

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.zxce.ai.AlumniLink
import com.zxce.ai.service.admin.AdminColumn
import com.zxce.ai.service.admin.AdminTable
import com.zxce.ai.service.admin.DatabaseAdminService
import com.zxce.ai.service.admin.primary
import com.zxce.ai.service.admin.readonlyReason
import com.zxce.ai.service.admin.writable
import com.zxce.ai.util.bundle.Bundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.getBean

/**
 * 数据库管理页（桌面端）。
 *
 * 入口是侧边栏左下角的数据库管理按钮，页面本身直接调用 [DatabaseAdminService]，
 * 和网页版 `/admin/db` 共用同一套白名单与参数绑定，所以这里不做登录，也不会出现
 * 「浏览器里再登一次超管」的多余步骤。
 *
 * 页面分三块：左边表目录、右边行浏览器、点开某一行后的行编辑器。
 * 所有 JDBC 调用都放在 [Dispatchers.IO]，UI 线程只负责状态与绘制。
 */

private const val PAGE_SIZE = 25
private val CELL_WIDTH = 160.dp
private val HEAD_HEIGHT = 40.dp
private val ROW_MIN_HEIGHT = 34.dp

@Composable
fun DatabasePage() {
    val scope = rememberCoroutineScope()
    val service = remember { AlumniLink.applicationContext.getBean<DatabaseAdminService>() }

    // 库信息与表目录
    var database by remember { mutableStateOf("") }
    var serverVersion by remember { mutableStateOf("") }
    var tables by remember { mutableStateOf<List<AdminTable>>(emptyList()) }
    var counts by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var keyword by remember { mutableStateOf("") }
    var selectedName by remember { mutableStateOf<String?>(null) }

    // 行浏览状态
    val rows = remember { mutableStateListOf<Bundle>() }
    var total by remember { mutableStateOf(0L) }
    var pageNo by remember { mutableStateOf(1) }
    var pageCount by remember { mutableStateOf(1) }
    var filterColumn by remember { mutableStateOf("") }
    var filterValue by remember { mutableStateOf("") }
    var showDdl by remember { mutableStateOf(false) }
    var ddl by remember { mutableStateOf("") }

    // 行编辑状态
    var editing by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var editingPk by remember { mutableStateOf<String?>(null) }
    val form = remember { mutableStateMapOf<String, String>() }
    var confirmDelete by remember { mutableStateOf(false) }

    // 状态栏
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    val table = tables.firstOrNull { it.name == selectedName }

    suspend fun loadCatalog(force: Boolean = false) {
        val schema = service.schema(force)
        database = schema.database
        serverVersion = schema.serverVersion
        tables = schema.tables
        counts = service.rowCounts(force)
    }

    suspend fun loadRows(name: String, page: Int, column: String, value: String) {
        val result = service.browse(
            name = name,
            page = page,
            size = PAGE_SIZE,
            filterColumn = column.ifBlank { null },
            filterValue = value.ifBlank { null },
        )
        val list = (result["rows"] as? List<*>)?.filterIsInstance<Bundle>().orEmpty()
        rows.clear()
        rows.addAll(list)
        total = (result["total"] as? Number)?.toLong() ?: 0L
        pageNo = (result["page"] as? Number)?.toInt() ?: page
        pageCount = (result["pages"] as? Number)?.toInt() ?: 1
    }

    /** 所有数据操作的统一入口：禁止重入，失败时把异常显示到状态栏，成功时显示服务返回的提示。 */
    fun execute(text: String, block: suspend () -> String?) {
        if (busy) return
        busy = true
        failed = false
        message = text
        scope.launch {
            try {
                message = withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                failed = true
                message = "失败：${e.message ?: e.toString()}"
            } finally {
                busy = false
            }
        }
    }

    fun openTable(name: String) {
        selectedName = name
        editing = false
        creating = false
        editingPk = null
        confirmDelete = false
        showDdl = false
        ddl = ""
        pageNo = 1
        pageCount = 1
        filterColumn = ""
        filterValue = ""
        rows.clear()
        total = 0
        execute("读取表 $name …") { loadRows(name, 1, "", ""); null }
    }

    fun gotoPage(target: Int) {
        val name = selectedName ?: return
        val targetPage = target.coerceIn(1, maxOf(pageCount, 1))
        execute("读取第 $targetPage 页…") { loadRows(name, targetPage, filterColumn, filterValue); null }
    }

    fun applyFilter() {
        val name = selectedName ?: return
        execute("查询中…") { loadRows(name, 1, filterColumn, filterValue); null }
    }

    fun refreshRows() {
        val name = selectedName ?: return
        execute("刷新中…") { loadRows(name, pageNo, filterColumn, filterValue); null }
    }

    fun toggleDdl() {
        val name = selectedName ?: return
        if (showDdl) {
            showDdl = false
            return
        }
        showDdl = true
        if (ddl.isNotEmpty()) return
        execute("读取建表语句…") { ddl = service.ddl(name); null }
    }

    fun openRow(row: Bundle) {
        val current = table ?: return
        creating = false
        editing = true
        confirmDelete = false
        editingPk = current.primary?.let { row[it.name]?.toString() }
        form.clear()
        current.columns.forEach { column -> form[column.name] = (row[column.name] as? String) ?: "" }
    }

    fun createRow() {
        val current = table ?: return
        creating = true
        editing = true
        confirmDelete = false
        editingPk = null
        form.clear()
        current.columns.forEach { column -> form[column.name] = "" }
    }

    fun closeEditor() {
        editing = false
        creating = false
        editingPk = null
        confirmDelete = false
    }

    fun saveRow() {
        val current = table ?: return
        if (!current.writable) return
        val isNew = creating
        val pk = editingPk
        if (!isNew && pk == null) {
            failed = true
            message = "没有取到这一行的主键，无法保存。"
            return
        }
        val data = form.toMap()
        execute("保存中…") {
            val done = service.save(current.name, if (isNew) null else pk, data, emptyList())
            closeEditor()
            loadRows(current.name, if (isNew) 1 else pageNo, filterColumn, filterValue)
            done
        }
    }

    fun deleteRow() {
        val current = table ?: return
        val pk = editingPk ?: return
        execute("删除中…") {
            val done = service.delete(current.name, pk)
            closeEditor()
            loadRows(current.name, pageNo, filterColumn, filterValue)
            done
        }
    }

    LaunchedEffect(Unit) {
        execute("读取数据库结构…") { loadCatalog(); null }
    }

    Row(Modifier.fillMaxSize().padding(10.dp)) {
        CatalogPanel(
            database = database,
            serverVersion = serverVersion,
            tables = tables,
            counts = counts,
            keyword = keyword,
            selected = selectedName,
            busy = busy,
            modifier = Modifier.width(320.dp).fillMaxHeight(),
            onKeyword = { keyword = it },
            onRefresh = { execute("刷新数据库结构…") { loadCatalog(true); null } },
            onSelect = { openTable(it) },
        )

        // 竖直分割线
        Box(
            Modifier.padding(horizontal = 10.dp).width(1.dp).fillMaxHeight()
                .background(Color(0xFF9E9b99))
        )

        Column(Modifier.weight(1f).fillMaxHeight()) {
            val current = table
            when {
                editing && current != null -> RowEditor(
                    table = current,
                    creating = creating,
                    pk = editingPk,
                    form = form,
                    busy = busy,
                    confirmDelete = confirmDelete,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onValue = { column, value -> form[column] = value },
                    onSave = { saveRow() },
                    onDelete = { deleteRow() },
                    onAskDelete = { confirmDelete = true },
                    onKeepDelete = { confirmDelete = false },
                    onCancel = { closeEditor() },
                )

                current != null -> TableBrowser(
                    table = current,
                    rows = rows,
                    total = total,
                    pageNo = pageNo,
                    pageCount = pageCount,
                    filterColumn = filterColumn,
                    filterValue = filterValue,
                    showDdl = showDdl,
                    ddl = ddl,
                    busy = busy,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onFilterColumn = { filterColumn = it },
                    onFilterValue = { filterValue = it },
                    onApplyFilter = { applyFilter() },
                    onPage = { gotoPage(it) },
                    onRefresh = { refreshRows() },
                    onToggleDdl = { toggleDdl() },
                    onCreate = { createRow() },
                    onOpenRow = { openRow(it) },
                )

                else -> Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (tables.isEmpty()) "正在读取数据库结构…" else "从左侧选择一张表开始管理。",
                        fontSize = 1.em,
                        color = Color(0xFF666666),
                    )
                }
            }

            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            }

            message?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 6.dp),
                    color = if (failed) Color.Red else Color(0xFF0B59F7),
                    fontSize = 0.9.em,
                )
            }
        }
    }
}

/** 左侧表目录：库信息 + 表名搜索 + 表清单（带行数）。 */
@Composable
private fun CatalogPanel(
    database: String,
    serverVersion: String,
    tables: List<AdminTable>,
    counts: Map<String, Long>,
    keyword: String,
    selected: String?,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onKeyword: (String) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val visible = tables.filter { table ->
        keyword.isBlank() ||
            table.name.contains(keyword, ignoreCase = true) ||
            table.comment.contains(keyword, ignoreCase = true)
    }

    Column(modifier) {
        Text("数据库管理", fontSize = 1.4.em, fontWeight = FontWeight.Bold)
        Text(
            if (database.isBlank()) "正在读取连接信息…" else "库 $database · $serverVersion",
            modifier = Modifier.padding(top = 2.dp),
            fontSize = 0.8.em,
            color = Color(0xFF666666),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = keyword,
                onValueChange = onKeyword,
                modifier = Modifier.weight(1f),
                label = { Text("表名 / 注释") },
                singleLine = true,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
            )
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.padding(start = 5.dp)) {
                Text("刷新")
            }
        }

        LazyColumn(Modifier.weight(1f).padding(top = 5.dp).fillMaxWidth()) {
            items(visible, key = { it.name }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected == item.name) Color(0xFFE8F1FF) else Color.Transparent)
                        .clickable(enabled = !busy) { onSelect(item.name) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontSize = 0.95.em, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (item.comment.isBlank()) item.type else item.comment,
                            fontSize = 0.75.em,
                            color = Color(0xFF666666),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        (counts[item.name] ?: 0L).toString(),
                        modifier = Modifier.padding(start = 6.dp),
                        fontSize = 0.8.em,
                        color = Color(0xFF0B59F7),
                    )
                }
            }
        }

        Text(
            "共 ${tables.size} 张表",
            modifier = Modifier.padding(top = 5.dp),
            fontSize = 0.8.em,
            color = Color(0xFF666666),
        )
    }
}

/** 右侧行浏览器：表头、筛选、分页、建表语句与数据网格。 */
@Composable
private fun TableBrowser(
    table: AdminTable,
    rows: List<Bundle>,
    total: Long,
    pageNo: Int,
    pageCount: Int,
    filterColumn: String,
    filterValue: String,
    showDdl: Boolean,
    ddl: String,
    busy: Boolean,
    modifier: Modifier = Modifier,
    onFilterColumn: (String) -> Unit,
    onFilterValue: (String) -> Unit,
    onApplyFilter: () -> Unit,
    onPage: (Int) -> Unit,
    onRefresh: () -> Unit,
    onToggleDdl: () -> Unit,
    onCreate: () -> Unit,
    onOpenRow: (Bundle) -> Unit,
) {
    val tableWidth = CELL_WIDTH * table.columns.size

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(table.name, fontSize = 1.2.em, fontWeight = FontWeight.Bold)
                Text(
                    listOfNotNull(
                        table.comment.takeIf { it.isNotBlank() },
                        table.type,
                        if (table.writable) "可编辑" else table.readonlyReason(),
                    ).joinToString(" · "),
                    fontSize = 0.8.em,
                    color = Color(0xFF666666),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Button(onClick = onCreate, enabled = !busy && table.writable) { Text("新建一行") }
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.padding(start = 5.dp)) {
                Text("刷新")
            }
            Button(onClick = onToggleDdl, enabled = !busy, modifier = Modifier.padding(start = 5.dp)) {
                Text(if (showDdl) "收起建表语句" else "建表语句")
            }
        }

        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            var showColumns by remember { mutableStateOf(false) }
            Box {
                Button(onClick = { showColumns = true }, enabled = !busy) {
                    Text(if (filterColumn.isBlank()) "筛选列" else filterColumn)
                }
                DropdownMenu(expanded = showColumns, onDismissRequest = { showColumns = false }) {
                    DropdownMenuItem(onClick = { onFilterColumn(""); showColumns = false }) { Text("不筛选") }
                    table.columns.forEach { column ->
                        DropdownMenuItem(onClick = { onFilterColumn(column.name); showColumns = false }) {
                            Text(column.name)
                        }
                    }
                }
            }
            TextField(
                value = filterValue,
                onValueChange = onFilterValue,
                modifier = Modifier.weight(1f).padding(horizontal = 5.dp),
                label = { Text("模糊匹配的值") },
                singleLine = true,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
            )
            Button(onClick = onApplyFilter, enabled = !busy && filterColumn.isNotBlank()) { Text("查询") }
        }

        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPage(pageNo - 1) }, enabled = !busy && pageNo > 1) { Text("上一页") }
            Text(
                "第 $pageNo / ${maxOf(pageCount, 1)} 页，共 $total 行",
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                fontSize = 0.85.em,
            )
            Button(onClick = { onPage(pageNo + 1) }, enabled = !busy && pageNo < pageCount) { Text("下一页") }
        }

        if (showDdl) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 180.dp),
                shape = RoundedCornerShape(6.dp),
                elevation = 3.dp,
            ) {
                Text(
                    ddl.ifBlank { "读取中…" },
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(10.dp),
                    fontSize = 0.8.em,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
            val hScroll = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
                    Column(Modifier.width(tableWidth).fillMaxHeight()) {
                        TableHeaderRow(table)
                        if (rows.isEmpty()) {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text("这张表没有数据", color = Color(0xFF666666))
                            }
                        } else {
                            LazyColumn(Modifier.weight(1f)) {
                                itemsIndexed(rows) { index, row ->
                                    TableDataRow(table, index, row, onOpenRow)
                                }
                            }
                        }
                    }
                }
            }
            HorizontalScrollbar(rememberScrollbarAdapter(hScroll), Modifier.fillMaxWidth())
        }

        Text(
            "点击任意一行查看详情；只有「普通表 + 单列主键」才允许写入。",
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 0.75.em,
            color = Color(0xFF666666),
        )
    }
}

/** 网格表头：列名 + 类型，主键列带 (PK) 标记。 */
@Composable
private fun TableHeaderRow(table: AdminTable) {
    Row(
        modifier = Modifier.fillMaxWidth().height(HEAD_HEIGHT).background(Color(0xFFF0F2F5)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        table.columns.forEach { column ->
            Column(
                modifier = Modifier.width(CELL_WIDTH).fillMaxHeight().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = column.name + if (column.pk) " (PK)" else "",
                    fontSize = 0.85.em,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = column.columnType,
                    fontSize = 0.7.em,
                    color = Color(0xFF666666),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 网格中的一行，点击进入行编辑器。 */
@Composable
private fun TableDataRow(table: AdminTable, index: Int, row: Bundle, onOpen: (Bundle) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .background(if (index % 2 == 0) Color(0xFFFAFBFC) else Color.Transparent)
            .clickable { onOpen(row) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        table.columns.forEach { column ->
            val value = row[column.name]
            Text(
                text = cellText(value),
                modifier = Modifier.width(CELL_WIDTH).padding(horizontal = 8.dp),
                fontSize = 0.8.em,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (value == null) Color(0xFF9E9b99) else Color(0xFF1F2430),
            )
        }
    }
}

/** 单元格展示：NULL 和空串要能区分开。 */
private fun cellText(value: Any?): String = when (value) {
    null -> "(NULL)"
    is String -> value.ifEmpty { "(空)" }
    else -> value.toString()
}

/** 行编辑器：新增或修改一行；只读表、无主键表只能查看。 */
@Composable
private fun RowEditor(
    table: AdminTable,
    creating: Boolean,
    pk: String?,
    form: Map<String, String>,
    busy: Boolean,
    confirmDelete: Boolean,
    modifier: Modifier = Modifier,
    onValue: (String, String) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onAskDelete: () -> Unit,
    onKeepDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (creating) "新增 ${table.name} 一行"
                else "编辑 ${table.name}（主键 ${pk ?: "-"}）",
                modifier = Modifier.weight(1f),
                fontSize = 1.2.em,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Button(onClick = onCancel, enabled = !busy) { Text("返回列表") }
        }

        Text(
            if (table.writable) "可空列留空表示写入 NULL；密码/令牌这类打码列留空表示不修改。"
            else (table.readonlyReason() ?: "这张表只能浏览。"),
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 0.8.em,
            color = Color(0xFF666666),
        )

        Column(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp).verticalScroll(rememberScrollState())) {
            table.columns.forEach { column ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.width(220.dp)) {
                        Text(
                            column.name + if (column.pk) "（主键）" else "",
                            fontSize = 0.9.em,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            listOfNotNull(
                                column.columnType,
                                if (column.nullable) "可空" else "必填",
                                column.comment.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            fontSize = 0.75.em,
                            color = Color(0xFF666666),
                        )
                    }
                    val locked = !table.writable || column.generated || (column.pk && !creating)
                    TextField(
                        value = form[column.name] ?: "",
                        onValueChange = { onValue(column.name, it) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && !locked,
                        singleLine = column.dataType !in MULTILINE_TYPES,
                        placeholder = { Text(editorHint(column, creating)) },
                        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
                    )
                }
            }
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Button(onClick = onSave, enabled = !busy && table.writable) { Text("保存") }
            Button(onClick = onCancel, enabled = !busy) { Text("取消") }
            if (!creating && table.writable) {
                if (confirmDelete) {
                    Button(
                        onClick = onDelete,
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(backgroundColor = Color.Red),
                    ) { Text("确认删除") }
                    Button(onClick = onKeepDelete, enabled = !busy) { Text("不删除") }
                } else {
                    Button(onClick = onAskDelete, enabled = !busy) { Text("删除") }
                }
            }
        }
    }
}

/** 适合多行输入的数据类型，其余列都给单行输入框。 */
private val MULTILINE_TYPES = setOf(
    "text", "tinytext", "mediumtext", "longtext",
    "json", "blob", "tinyblob", "mediumblob", "longblob",
)

/** 输入框占位提示，把「为什么不能改 / 留空会怎样」直接写在字段上。 */
private fun editorHint(column: AdminColumn, creating: Boolean): String = when {
    column.generated -> "生成列，由数据库维护"
    column.pk && !creating -> "主键不可修改"
    column.pk && creating && column.dataType in setOf("varchar", "char") -> "留空自动生成 UUID"
    column.auto -> "自增，留空由数据库生成"
    isMasked(column.name) -> "打码列，留空表示不修改"
    !column.nullable -> "必填"
    else -> "留空表示 NULL"
}

/** 与 DatabaseAdminService 里的打码规则保持一致。 */
private fun isMasked(name: String): Boolean {
    val lower = name.lowercase()
    return listOf("password", "token", "secret", "credential").any { it in lower }
}
