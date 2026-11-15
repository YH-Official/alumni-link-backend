package com.zxce.ai.ui.view.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.zxce.ai.AlumniLink
import com.zxce.ai.model.system.SysRoleEntity
import com.zxce.ai.model.system.query.QSysRoleEntity
import com.zxce.ai.model.system.query.QSysUserRolesEntity
import com.zxce.ai.service.system.RoleService
import com.zxce.ai.service.system.UserService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleOf
import com.zxce.ai.util.bundle.getAs
import com.zxce.ai.util.ebean.query
import com.zxce.ai.util.ebean.toAsync
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.SSEContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.getBean

/**
 * 用户管理：列表、搜索、分页、新建、修改、删除、变更角色。
 *
 * 桌面端没有登录流程，这里借用库中已有的管理员账户身份构造上下文，
 * 只为通过服务层的管理范围校验，数据权限与网页端一致。
 *
 * @author 洛洛希雅Lolosia
 * @since 2026-11-12
 */

private const val PAGE_SIZE = 20

private data class ListQuery(val page: Int, val keyword: String, val tick: Int)

@Composable
fun UserPage() {
    val scope = rememberCoroutineScope()
    val userService = remember { AlumniLink.applicationContext.getBean<UserService>() }
    val roleService = remember { AlumniLink.applicationContext.getBean<RoleService>() }

    val rows = remember { mutableStateListOf<Bundle>() }
    var total by remember { mutableStateOf(0) }
    var roles by remember { mutableStateOf(listOf<Bundle>()) }
    var query by remember { mutableStateOf(ListQuery(1, "", 0)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }

    val selected = remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val userName = remember { mutableStateOf("") }
    val realName = remember { mutableStateOf("") }
    val phone = remember { mutableStateOf("") }
    val email = remember { mutableStateOf("") }
    val password = remember { mutableStateOf("") }
    val isUse = remember { mutableStateOf(true) }
    val roleId = remember { mutableStateOf<Int?>(null) }

    val studentRoleId = roles.firstOrNull { it["type"] == "student" }?.get("id") as? Int

    suspend fun loadPage() {
        val (list, count) = userService.list(adminContext(), query.page, PAGE_SIZE, query.keyword.ifEmpty { null })
        rows.clear()
        rows += list
        total = count
    }

    /** 所有数据操作都走这里：禁止重入、失败时把异常显示到状态栏 */
    fun execute(text: String, reload: Boolean = false, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        message = text
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    block()
                    if (reload) loadPage()
                }
                message = null
            } catch (e: Exception) {
                error = true
                message = "失败：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun clearForm() {
        userName.value = ""
        realName.value = ""
        phone.value = ""
        email.value = ""
        password.value = ""
        isUse.value = true
        roleId.value = studentRoleId
    }

    LaunchedEffect(query) { execute("读取中…") { loadPage() } }

    LaunchedEffect(Unit) {
        roles = roleService.list(adminContext()).filterNotNull()
            .map { bundleOf("id" to it.id, "roleName" to it.roleName, "type" to it.type) }
    }

    Row(Modifier.fillMaxSize().padding(10.dp)) {
        UserList(
            rows = rows,
            total = total,
            query = query,
            busy = busy,
            selected = selected,
            modifier = Modifier.width(360.dp).fillMaxHeight(),
            onSelect = { userId ->
                confirmDelete = false
                editing = true
                selected.value = userId
                execute("读取中…") {
                    val info = userService.getUserInfo(adminContext(), userId)
                    userName.value = info.getAs<String>("userName") ?: ""
                    realName.value = info.getAs<String>("realName") ?: ""
                    phone.value = info.getAs<String>("phone") ?: ""
                    email.value = info.getAs<String>("email") ?: ""
                    password.value = ""
                    isUse.value = info.getAs<Boolean>("isUse") ?: true
                    roleId.value = (info["roleId"] as? Number)?.toInt() ?: studentRoleId
                }
            },
            onCreate = {
                confirmDelete = false
                editing = true
                selected.value = null
                clearForm()
            },
            onSearch = { keyword -> query = query.copy(page = 1, keyword = keyword) },
            onPage = { page -> query = query.copy(page = page) },
            onRefresh = { query = query.copy(tick = query.tick + 1) },
        )

        Box(Modifier.fillMaxHeight().padding(10.dp, 0.dp)) {
            Box(Modifier.background(Color(0xFF9E9b99)).width(1.dp).fillMaxHeight().align(Alignment.Center))
        }

        Column(Modifier.fillMaxSize()) {
            if (!editing) {
                Text("选择左侧用户进行编辑，或点击“新建用户”。", fontSize = 1.em)
            } else {
                UserForm(
                    isNew = selected.value == null,
                    roles = roles,
                    busy = busy,
                    userName = userName,
                    realName = realName,
                    phone = phone,
                    email = email,
                    password = password,
                    isUse = isUse,
                    roleId = roleId,
                    confirmDelete = confirmDelete,
                    modifier = Modifier.weight(1f),
                    onSave = {
                        val userId = selected.value
                        val data = bundleOf(
                            "userName" to userName.value.trim(),
                            "realName" to realName.value.trim(),
                            "phone" to phone.value.trim(),
                            "email" to email.value.trim(),
                            "isUse" to isUse.value,
                            "roleId" to roleId.value,
                        )
                        if (userId != null) data["id"] = userId
                        if (password.value.isNotEmpty()) data["password"] = password.value

                        execute("保存中…", reload = true) {
                            val ctx = adminContext()
                            if (userId == null) {
                                selected.value = userService.create(ctx, data).id.toString()
                            } else {
                                userService.update(ctx, data)
                            }
                            password.value = ""
                        }
                    },
                    onDelete = {
                        val userId = selected.value
                        if (userId != null) execute("删除中…", reload = true) {
                            userService.delete(adminContext(), listOf(userId))
                            selected.value = null
                            editing = false
                            confirmDelete = false
                        }
                    },
                    onCancel = {
                        editing = false
                        selected.value = null
                        confirmDelete = false
                    },
                    onAskDelete = { confirmDelete = true },
                    onKeepDelete = { confirmDelete = false },
                )
            }

            message?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = if (error) Color.Red else Color(0xFF0B59F7), fontSize = 0.9.em)
            }
        }
    }
}

@Composable
private fun UserList(
    rows: List<Bundle>,
    total: Int,
    query: ListQuery,
    busy: Boolean,
    selected: State<String?>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
    onCreate: () -> Unit,
    onSearch: (String) -> Unit,
    onPage: (Int) -> Unit,
    onRefresh: () -> Unit,
) {
    var keyword by remember { mutableStateOf("") }
    val pages = (total + PAGE_SIZE - 1) / PAGE_SIZE

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = keyword,
                onValueChange = { keyword = it },
                modifier = Modifier.weight(1f),
                label = { Text("用户名 / 昵称 / 手机 / 角色") },
                singleLine = true,
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
            )
            Button(
                onClick = { onSearch(keyword.trim()) },
                enabled = !busy,
                modifier = Modifier.padding(start = 5.dp),
            ) { Text("搜索") }
        }
        Row(Modifier.padding(top = 5.dp)) {
            Button(onClick = onCreate, enabled = !busy, modifier = Modifier.weight(1f)) { Text("新建用户") }
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.padding(start = 5.dp)) { Text("刷新") }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).padding(top = 5.dp).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 5.dp),
        ) {
            items(rows) { row ->
                val userId = row.getAs<String>("userId") ?: return@items
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(if (selected.value == userId) Color(0xFFE8F1FF) else Color.Transparent)
                        .clickable(enabled = !busy) { onSelect(userId) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.getAs<String>("userName") ?: "-", fontSize = 1.em)
                        Text(
                            listOfNotNull(row.getAs<String>("realName"), row.getAs<String>("phone"))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            fontSize = 0.8.em,
                            color = Color(0xFF666666),
                        )
                    }
                    Text(row.getAs<String>("roleName") ?: "-", fontSize = 0.85.em, color = Color(0xFF0B59F7))
                }
            }
        }

        Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { onPage(query.page - 1) }, enabled = !busy && query.page > 1) { Text("上一页") }
            Text(
                "第 ${query.page} / ${maxOf(pages, 1)} 页，共 $total 人",
                modifier = Modifier.weight(1f),
                fontSize = 0.85.em,
            )
            Button(onClick = { onPage(query.page + 1) }, enabled = !busy && query.page < pages) { Text("下一页") }
        }
    }
}

@Composable
private fun UserForm(
    isNew: Boolean,
    roles: List<Bundle>,
    busy: Boolean,
    userName: MutableState<String>,
    realName: MutableState<String>,
    phone: MutableState<String>,
    email: MutableState<String>,
    password: MutableState<String>,
    isUse: MutableState<Boolean>,
    roleId: MutableState<Int?>,
    confirmDelete: Boolean,
    modifier: Modifier = Modifier,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onAskDelete: () -> Unit,
    onKeepDelete: () -> Unit,
) {
    val roleName = roles.firstOrNull { it["id"] == roleId.value }?.get("roleName").toString()
        .ifEmpty { if (isNew) "请选择角色" else "未分配" }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (isNew) "新建用户" else "编辑用户", fontSize = 1.3.em, fontWeight = FontWeight.Bold)

        FormRow("用户名") {
            InputField(userName, isError = userName.value.isBlank(), singleLine = true)
        }
        FormRow("昵称") { InputField(realName) }
        FormRow("手机号") { InputField(phone) }
        FormRow("邮箱") { InputField(email) }
        FormRow("密码") {
            TextField(
                value = password.value,
                onValueChange = { password.value = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !busy,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text(if (isNew) "必填" else "留空则不修改") },
                colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
            )
        }
        FormRow("角色") {
            var showRoles by remember { mutableStateOf(false) }
            Box {
                Button(onClick = { showRoles = true }, enabled = !busy && roles.isNotEmpty()) {
                    Text(roleName)
                }
                DropdownMenu(expanded = showRoles, onDismissRequest = { showRoles = false }) {
                    roles.forEach { role ->
                        DropdownMenuItem(onClick = {
                            roleId.value = role["id"] as? Int
                            showRoles = false
                        }) {
                            Text("${role["roleName"]}（${role["type"]}）")
                        }
                    }
                }
            }
        }
        FormRow("状态") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = isUse.value, onCheckedChange = { isUse.value = it }, enabled = !busy)
                Text(if (isUse.value) "允许登录" else "已禁用")
            }
        }

        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Button(
                onClick = onSave,
                enabled = !busy && userName.value.isNotBlank() && (roleId.value != null || !isNew),
            ) { Text("保存") }
            Button(onClick = onCancel, enabled = !busy) { Text("取消") }
            if (!isNew) {
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

        Text(
            if (isNew) "新建的用户立即生效，可直接用用户名和密码登录。"
            else "删除为软删除，同时清理该用户的角色、组织、会话与论坛数据。",
            fontSize = 0.8.em,
            color = Color(0xFF666666),
        )
    }
}

@Composable
private fun InputField(
    value: MutableState<String>,
    isError: Boolean = false,
    singleLine: Boolean = false,
) {
    TextField(
        value = value.value,
        onValueChange = { value.value = it },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        isError = isError,
        colors = TextFieldDefaults.textFieldColors(backgroundColor = Color.Transparent),
    )
}

@Composable
private fun FormRow(label: String, content: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(80.dp), fontSize = 0.9.em)
        Box(Modifier.weight(1f)) { content() }
    }
}

/** 借用数据库中一个管理员账户的会话身份，服务层的管理范围校验依赖它 */
private suspend fun adminContext(): Context = withContext(Dispatchers.IO) {
    val ctx = SSEContext(AlumniLink.applicationContext)
    if (ctx.userIdOrNull == null) {
        val adminRoleIds = ctx.query<QSysRoleEntity> {
            type.isIn(listOf("admin", "super_admin"))
        }.toAsync().findList().map(SysRoleEntity::id)
        val roleIds = adminRoleIds.ifEmpty {
            ctx.query<QSysRoleEntity> { type.contains("admin") }.toAsync().findList().map(SysRoleEntity::id)
        }
        val userId = roleIds.takeIf { it.isNotEmpty() }
            ?.let { ctx.query<QSysUserRolesEntity> { roleId.isIn(it) }.toAsync().findList().firstOrNull()?.userId }
            ?: throw IllegalStateException("数据库中没有管理员账户，无法进行用户管理")
        ctx.session["id"] = userId.toString()
    }
    ctx
}
