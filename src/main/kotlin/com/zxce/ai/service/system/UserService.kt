package com.zxce.ai.service.system

import com.fasterxml.jackson.databind.json.JsonMapper
import kotlinx.coroutines.Dispatchers
import com.zxce.ai.interceptor.ErrorWebFilter
import com.zxce.ai.manager.SessionManager
import com.zxce.ai.model.alumni.AlkOrganizationMember
import com.zxce.ai.model.alumni.AlkClass
import com.zxce.ai.model.alumni.AlkSchoolAdmin
import com.zxce.ai.model.alumni.AlkCollege
import com.zxce.ai.model.alumni.AlkCollegeAdmin
import com.zxce.ai.model.alumni.AlkMajor
import com.zxce.ai.model.alumni.AlkMajorAdmin
import com.zxce.ai.model.alumni.AlkClassAdmin
import com.zxce.ai.model.alumni.query.QAlkOrganizationMember
import com.zxce.ai.model.alumni.query.QAlkConversation
import com.zxce.ai.model.alumni.query.QAlkMessage
import com.zxce.ai.model.alumni.query.QAlkGroupConversationMember
import com.zxce.ai.model.forum.AlkForumCollection
import com.zxce.ai.model.forum.AlkForumComment
import com.zxce.ai.model.forum.AlkForumLike
import com.zxce.ai.model.forum.AlkForumPost
import com.zxce.ai.model.forum.AlkForumShare
import com.zxce.ai.model.forum.query.QAlkForumCategory
import com.zxce.ai.model.forum.query.QAlkForumCollection
import com.zxce.ai.model.forum.query.QAlkForumComment
import com.zxce.ai.model.forum.query.QAlkForumLike
import com.zxce.ai.model.forum.query.QAlkForumPost
import com.zxce.ai.model.forum.query.QAlkForumShare
import com.zxce.ai.model.alumni.query.QAlkOrganization
import com.zxce.ai.model.alumni.query.QAlkClass
import com.zxce.ai.model.alumni.query.QAlkSchool
import com.zxce.ai.model.alumni.query.QAlkSchoolAdmin
import com.zxce.ai.model.alumni.query.QAlkCollege
import com.zxce.ai.model.alumni.query.QAlkCollegeAdmin
import com.zxce.ai.model.alumni.query.QAlkMajor
import com.zxce.ai.model.alumni.query.QAlkMajorAdmin
import com.zxce.ai.model.alumni.query.QAlkClassAdmin
import com.zxce.ai.model.alumni.query.QAlkConnection
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.SysUserRolesEntity
import com.zxce.ai.model.system.ViewUserRoleEntity
import com.zxce.ai.model.system.query.QSysRoleEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.model.system.query.QSysUserRolesEntity
import com.zxce.ai.model.system.query.QViewUserRoleEntity
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.session.SessionMap
import com.zxce.ai.util.success
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.*
import org.springframework.http.codec.multipart.FilePart
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.context.ApplicationContext
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.io.IOException
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.*
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

@Service
class UserService {
    private val logger = LoggerFactory.getLogger(UserService::class.java)!!

    companion object {
        /** 头像 / 背景图大小上限：5MB */
        private const val MAX_IMAGE_SIZE = 5L * 1024 * 1024

        /** 允许上传的图片扩展名 */
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif")

        /**
         * 校验并返回图片扩展名。
         *
         * @param file 上传的图片
         * @return 小写扩展名
         * @throws IllegalArgumentException 当格式不受支持时
         */
        private fun imageExtension(file: FilePart): String {
            val name = com.zxce.ai.service.oss.UserFileService.sanitizeFileName(file.filename())
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext !in IMAGE_EXTENSIONS) {
                throw IllegalArgumentException("仅支持 jpg / jpeg / png / webp / gif 格式的图片")
            }
            return ext
        }

        /**
         * 将图片写入目标文件，并校验大小。
         *
         * @param file 上传的图片
         * @param outFile 目标文件
         * @throws IllegalArgumentException 当图片超过大小上限时
         */
        private suspend fun transferImage(file: FilePart, outFile: java.io.File) {
            file.transferTo(outFile).awaitSingleOrNull()
            if (outFile.length() > MAX_IMAGE_SIZE) {
                outFile.delete()
                throw IllegalArgumentException("图片大小不能超过 ${MAX_IMAGE_SIZE / 1024 / 1024}MB")
            }
        }
    }

    // 初始化块
    init {
        Path("./work/image/avatar").createDirectories()
        Path("./work/image/background").createDirectories()
    }

    @Autowired
    lateinit var encoder: PasswordEncoder

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var session: SessionManager

    @Autowired
    lateinit var userRoleService: UserRoleService

    @Autowired
    lateinit var applicationContext: ApplicationContext

    /**
     * 公开注册接口，不需要登录验证即可创建用户
     *
     * @param data 用户注册数据，包含 phone（电话）、email（邮箱）、roleId（角色ID）、userName（用户名）、password（密码）等字段
     * @return 新创建的用户角色实体（ViewUserRoleEntity）
     * @throws IllegalStateException 当同名用户已存在时抛出
     */
    suspend fun createPublic(data: Bundle): ViewUserRoleEntity {
        val acp = com.zxce.ai.util.spring.ApplicationContextProvider { applicationContext }
        val phone: String? = data("phone")
        val email: String? = data("email")
        val roleId: Int = data("roleId")!!
        var userName: String = data("userName")!!
        if ("userName" !in data) {
            userName = phone ?: email ?: "user_${System.currentTimeMillis()}"
            data["userName"] = userName
        }
        data["password"]?.let {
            data["password"] = encoder.encode(it.toString())
        }
        val exists = acp.query<QSysUserEntity> {
            or {
                phone?.let { this.phone.eq(it) }
                email?.let { this.email.eq(it) }
                this.userName.eq(userName)
            }
        }.toAsync().exists()
        if (exists) {
            throw IllegalStateException("同名用户已存在")
        }
        val userId = UUID.randomUUID()
        acp.transaction { tran ->
            acp.createModel<SysUserEntity>(data).apply {
                id = userId
                withContext(Dispatchers.IO){
                    insert(tran)
                }
            }
            val hasRole = acp.query<QSysUserRolesEntity> {
                this.userId.eq(userId)
            }.toAsync().exists()
            if (!hasRole) {
                acp.createModel<SysUserRolesEntity> {
                    this.userId = userId
                    this.roleId = roleId
                    this.createdBy = userId
                    withContext(Dispatchers.IO){
                        insert(tran)
                    }
                }
            }
        }
        return acp.query<QViewUserRoleEntity>().userId.eq(userId).orderBy().id.asc().findList().firstOrNull()!!
    }

    @Suppress("UNCHECKED_CAST")
    /**
     * 获取用户列表（分页），支持关键字搜索
     *
     * @param ctx 上下文对象，包含会话信息等
     * @param pageNo 页码，从1开始，默认为1
     * @param pageSize 每页显示数量，默认为10
     * @param keys 搜索关键字，支持按用户名、真实姓名、手机号、邮箱及角色信息搜索
     * @return 包含用户信息列表和总记录数的Pair
     */
    suspend fun list(ctx: Context, pageNo: Int = 1, pageSize: Int = 10, keys: String? = null): Pair<List<Bundle>, Int> {
        val arrList = bundleOf()
        if (!keys.isNullOrEmpty()) {
            // 查询User匹配信息
            ctx.query<QSysUserEntity> {
                or {
                    userName.contains(keys)
                    realName.contains(keys)
                    phone.contains(keys)
                    email.contains(keys)
                }
                select(id)
                val idList = toAsync().findSingleAttributeSet<UUID>()
                if (idList.isNotEmpty()) arrList["userId"] = idList
            }
            // 查询角色匹配信息
            ctx.query<QSysRoleEntity> {
                or {
                    roleName.contains(keys)
                    type.contains(keys)
                }
                select(id)
                val rs = toAsync().findSingleAttributeSet<Int>()
                if (rs.isNotEmpty()) arrList["roleId"] = rs
            }
        }

        val offset = (pageNo - 1) * pageSize

        // 从视图中查询所有用户。
        val (userRoles, count) = ctx.query<QViewUserRoleEntity> {

            if (arrList.isNotEmpty()) {
                if (arrList.size > 1) or()
                arrList.forEach { (k, v) ->
                    when (k) {
                        "userId" -> userId.isIn(v as Set<UUID>)
                        "roleId" -> roleId.isIn(v as Set<Int>)
                    }
                }
                if (arrList.size > 1) endOr()
            }
            orderBy {
                roleId.asc()
                userName.asc()
            }
            setFirstRow(offset)
            setMaxRows(pageSize)

        }.toAsync().findPagedList().run {
            list.map(mapper::toBundle) to totalCount
        }

        if (userRoles.isNotEmpty()) {
            val users = ctx.query<QSysUserEntity> {
                id.isIn(userRoles.map { it.getAs<String>("userId")!!.toUuid() })
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
            userRoles.forEach {
                it.scope {
                    "realName" set ""
                    users[it["userId"].toString().toUuid()]?.let { user ->
                        user.remove("password")
                        user.remove("phone")
                        user.remove("email")
                        user.remove("isUse")
                        it.putAll(user)
                    } ?: let {
                        "realName" set "<账户不存在>"
                        "isUse" set false
                    }
                }
            }
        }

        return userRoles to count
    }

    /**
     * 获取同班同学列表，根据用户角色返回不同范围的同学
     * 学生视角：同校所有学生 + 管理该学校的所有管理员
     * 管理员视角：管理范围内的所有学生和下属管理员
     * 兜底：非管理员且无组织的用户，只能看到同角色用户
     *
     * @param ctx 上下文对象，包含当前用户信息
     * @return 同学信息列表
     */
    suspend fun getClassmates(ctx: Context): List<Bundle> {
        val currentUserId = ctx.userId.toUuid()

        val myOrgs = ctx.query<QAlkOrganizationMember> {
            userId.eq(currentUserId)
            status.eq(1)
            deleted.ne(true)
        }.toAsync().findList()
            .map { it.organizationId }
            .toSet()

        val myRoleType = ctx.query<QViewUserRoleEntity> {
            userId.eq(currentUserId)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"

        // 获取用户的管理员分配
        val mySchoolAdminIds = ctx.query<QAlkSchoolAdmin> {
            userId.eq(currentUserId); deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val myCollegeAdminIds = ctx.query<QAlkCollegeAdmin> {
            userId.eq(currentUserId); deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val myMajorAdminIds = ctx.query<QAlkMajorAdmin> {
            userId.eq(currentUserId); deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val myClassAdminIds = ctx.query<QAlkClassAdmin> {
            userId.eq(currentUserId); deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        val adminRoleTypes = listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin")
        val hasAdminAssignments = mySchoolAdminIds.isNotEmpty() || myCollegeAdminIds.isNotEmpty() ||
                myMajorAdminIds.isNotEmpty() || myClassAdminIds.isNotEmpty()
        val isAdmin = myRoleType in adminRoleTypes || hasAdminAssignments
        val isStudent = myOrgs.isNotEmpty()

        val classmateIds = mutableSetOf<UUID>()

        // === 学生视角：同校所有学生 + 管理该学校的所有管理员 ===
        if (isStudent) {
            val userOrgs = ctx.query<QAlkOrganization> {
                id.isIn(myOrgs); deleted.ne(true)
            }.toAsync().findList()

            val schoolIds = userOrgs.mapNotNull { it.schoolId }.toSet()

            if (schoolIds.isNotEmpty()) {
                // 查找这些学校的所有组织
                val schoolOrgs = ctx.query<QAlkOrganization> {
                    schoolId.isIn(schoolIds); deleted.ne(true)
                }.toAsync().findList()

                val schoolOrgIds = schoolOrgs.map { it.id }.toSet()

                // 同校所有学生
                if (schoolOrgIds.isNotEmpty()) {
                    classmateIds.addAll(ctx.query<QAlkOrganizationMember> {
                        organizationId.isIn(schoolOrgIds); status.eq(1)
                        userId.ne(currentUserId); deleted.ne(true)
                    }.toAsync().findList().map { it.userId })
                }

                // 管理这些学校的所有管理员
                classmateIds.addAll(ctx.query<QAlkSchoolAdmin> {
                    schoolId.isIn(schoolIds); deleted.ne(true); userId.ne(currentUserId)
                }.toAsync().findList().map { it.userId })

                // 获取学院ID：优先从组织表，fallback到QAlkCollege表
                var collegeIds = schoolOrgs.mapNotNull { it.collegeId }.toSet()
                if (collegeIds.isEmpty()) {
                    collegeIds = ctx.query<QAlkCollege> {
                        this.schoolId.isIn(schoolIds); deleted.ne(true)
                    }.toAsync().findList().map { it.id }.toSet()
                }
                if (collegeIds.isNotEmpty()) {
                    classmateIds.addAll(ctx.query<QAlkCollegeAdmin> {
                        collegeId.isIn(collegeIds); deleted.ne(true); userId.ne(currentUserId)
                    }.toAsync().findList().map { it.userId })
                }

                // 获取专业ID：优先从组织表，fallback到QAlkMajor表
                var majorIds = schoolOrgs.mapNotNull { it.majorId }.toSet()
                if (majorIds.isEmpty()) {
                    majorIds = ctx.query<QAlkMajor> {
                        collegeId.isIn(collegeIds); deleted.ne(true)
                    }.toAsync().findList().map { it.id }.toSet()
                }
                if (majorIds.isNotEmpty()) {
                    classmateIds.addAll(ctx.query<QAlkMajorAdmin> {
                        majorId.isIn(majorIds); deleted.ne(true); userId.ne(currentUserId)
                    }.toAsync().findList().map { it.userId })
                }

                // 获取班级ID：优先从组织表，fallback到QAlkClass表
                var classIds = schoolOrgs.mapNotNull { it.classId }.toSet()
                if (classIds.isEmpty()) {
                    classIds = ctx.query<QAlkClass> {
                        majorId.isIn(majorIds); deleted.ne(true)
                    }.toAsync().findList().map { it.id }.toSet()
                }
                if (classIds.isNotEmpty()) {
                    classmateIds.addAll(ctx.query<QAlkClassAdmin> {
                        classId.isIn(classIds); deleted.ne(true); userId.ne(currentUserId)
                    }.toAsync().findList().map { it.userId })
                }
            }
        }

        // === 管理员视角：管理范围内的所有学生和下属管理员 ===
        // super_admin/admin 无论是否有分配都能看到所有人
        // 其他管理员角色需要有实际分配才能看到管理范围
        if (isAdmin && (hasAdminAssignments || myRoleType in listOf("super_admin", "admin"))) {
            classmateIds.addAll(getManagedUserIds(ctx, currentUserId, myRoleType))
        }

        // === 兜底：非管理员且无组织的用户，只能看到同角色用户 ===
        if (!isStudent && !isAdmin) {
            classmateIds.addAll(ctx.query<QViewUserRoleEntity> {
                type.eq(myRoleType); userId.ne(currentUserId)
            }.toAsync().findList().map { it.userId })
        }

        if (classmateIds.isEmpty()) {
            return emptyList()
        }

        val classmates = ctx.query<QViewUserRoleEntity> {
            userId.isIn(classmateIds)
        }.toAsync().findList()

        return classmates.map { mapper.toBundle(it) }.also { bundles ->
            val userIds = bundles.map { it["userId"].toString().toUuid() }.toSet()
            val users = ctx.query<QSysUserEntity> {
                id.isIn(userIds)
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
            bundles.forEach { bundle ->
                users[bundle["userId"].toString().toUuid()]?.let { user ->
                    user.remove("password")
                    user.remove("phone")
                    user.remove("email")
                    user.remove("isUse")
                    bundle.putAll(user)
                }
            }
        }
    }

    /**
     * 获取管理员管理范围内的所有用户ID（包括学生和下属次级管理员）
     * 优先使用 AlkOrganization 表的外键，fallback 到独立的 college/major/class 表
     *
     * @param ctx 上下文对象
     * @param adminUserId 管理员用户ID
     * @param myRoleType 当前管理员的角色类型
     * @return 管理范围内的所有用户ID集合
     */
    private suspend fun getManagedUserIds(ctx: Context, adminUserId: UUID, myRoleType: String): Set<UUID> {
        val managedIds = mutableSetOf<UUID>()

        // 超级管理员/管理员：返回所有组织成员和所有各级管理员
        if (myRoleType == "super_admin" || myRoleType == "admin") {
            managedIds.addAll(ctx.query<QAlkOrganizationMember> {
                status.eq(1); userId.ne(adminUserId); deleted.ne(true)
            }.toAsync().findList().map { it.userId })
            managedIds.addAll(ctx.query<QAlkSchoolAdmin> { deleted.ne(true); userId.ne(adminUserId) }.toAsync().findList().map { it.userId })
            managedIds.addAll(ctx.query<QAlkCollegeAdmin> { deleted.ne(true); userId.ne(adminUserId) }.toAsync().findList().map { it.userId })
            managedIds.addAll(ctx.query<QAlkMajorAdmin> { deleted.ne(true); userId.ne(adminUserId) }.toAsync().findList().map { it.userId })
            managedIds.addAll(ctx.query<QAlkClassAdmin> { deleted.ne(true); userId.ne(adminUserId) }.toAsync().findList().map { it.userId })
            return managedIds
        }

        // 学校管理员：管理学校下的所有人员，包括下属院级/专业/班级管理员
        ctx.query<QAlkSchoolAdmin> {
            userId.eq(adminUserId); deleted.ne(true)
        }.toAsync().findList().forEach { sa ->
            val schoolId = sa.schoolId

            // 获取该学校的所有组织
            val schoolOrgs = ctx.query<QAlkOrganization> {
                this.schoolId.eq(schoolId); deleted.ne(true)
            }.toAsync().findList()

            val orgIds = schoolOrgs.map { it.id }.toSet()
            if (orgIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkOrganizationMember> {
                    organizationId.isIn(orgIds); status.eq(1); userId.ne(adminUserId); deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }

            // 获取学院ID：优先从组织表，fallback到QAlkCollege表
            var collegeIds = schoolOrgs.mapNotNull { it.collegeId }.toSet()
            if (collegeIds.isEmpty()) {
                collegeIds = ctx.query<QAlkCollege> {
                    this.schoolId.eq(schoolId); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (collegeIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkCollegeAdmin> {
                    collegeId.isIn(collegeIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }

            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIds = schoolOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIds.isEmpty()) {
                majorIds = ctx.query<QAlkMajor> {
                    collegeId.isIn(collegeIds); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (majorIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkMajorAdmin> {
                    majorId.isIn(majorIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = schoolOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass> {
                    majorId.isIn(majorIds); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkClassAdmin> {
                    classId.isIn(classIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }
        }

        // 学院管理员：管理学院下的所有人员，包括下属专业/班级管理员
        ctx.query<QAlkCollegeAdmin> {
            userId.eq(adminUserId); deleted.ne(true)
        }.toAsync().findList().forEach { ca ->
            val collegeId = ca.collegeId

            val collegeOrgs = ctx.query<QAlkOrganization> {
                this.collegeId.eq(collegeId); deleted.ne(true)
            }.toAsync().findList()

            val orgIds = collegeOrgs.map { it.id }.toSet()
            if (orgIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkOrganizationMember> {
                    organizationId.isIn(orgIds); status.eq(1); userId.ne(adminUserId); deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }

            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIds = collegeOrgs.mapNotNull { it.majorId }.toSet()
            if (majorIds.isEmpty()) {
                majorIds = ctx.query<QAlkMajor> {
                    this.collegeId.eq(collegeId); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (majorIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkMajorAdmin> {
                    majorId.isIn(majorIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = collegeOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass> {
                    majorId.isIn(majorIds); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkClassAdmin> {
                    classId.isIn(classIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }
        }

        // 专业管理员：管理专业下的所有人员，包括下属班级管理员
        ctx.query<QAlkMajorAdmin> {
            userId.eq(adminUserId); deleted.ne(true)
        }.toAsync().findList().forEach { ma ->
            val majorId = ma.majorId

            val majorOrgs = ctx.query<QAlkOrganization> {
                this.majorId.eq(majorId); deleted.ne(true)
            }.toAsync().findList()

            val orgIds = majorOrgs.map { it.id }.toSet()
            if (orgIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkOrganizationMember> {
                    organizationId.isIn(orgIds); status.eq(1); userId.ne(adminUserId); deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = majorOrgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass> {
                    this.majorId.eq(majorId); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkClassAdmin> {
                    classId.isIn(classIds); deleted.ne(true); userId.ne(adminUserId)
                }.toAsync().findList().map { it.userId })
            }
        }

        // 班级管理员：管理班级下的所有人员（无下属管理员）
        ctx.query<QAlkClassAdmin> {
            userId.eq(adminUserId); deleted.ne(true)
        }.toAsync().findList().forEach { ca ->
            val classId = ca.classId

            val orgIds = ctx.query<QAlkOrganization> {
                this.classId.eq(classId); deleted.ne(true)
            }.toAsync().findList().map { it.id }.toSet()
            if (orgIds.isNotEmpty()) {
                managedIds.addAll(ctx.query<QAlkOrganizationMember> {
                    organizationId.isIn(orgIds); status.eq(1); userId.ne(adminUserId); deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }
        }

        return managedIds
    }

    /**
     * 按照文本搜索用户，默认只显示10条结果
     *
     * @param ctx 上下文对象
     * @param keys 搜索关键字，按用户名进行模糊匹配
     * @return 匹配的用户实体列表
     */
    suspend fun userSearching(ctx: Context, keys: String?): List<SysUserEntity> {
        return ctx.query<QSysUserEntity> {
            if (!keys.isNullOrEmpty()) {
                userName.contains(keys)
            }
            setMaxRows(10)
        }.toAsync().findList()
    }

    /**
     * 根据用户ID列表查找用户信息
     *
     * @param ctx 上下文对象
     * @param idList 用户ID列表，元素可能为null（会被过滤掉）
     * @return 用户信息列表（已移除敏感字段：password、phone、email、isUse）
     */
    suspend fun get(ctx: Context, idList: List<String?>): List<Bundle> {
        val list = ctx.query<QSysUserEntity> {
            id.isIn(idList.filterNotNull().map { it.toUuid() })
        }.toAsync().findList()
        return mapper.toBundle(list).onEach {
            it.remove("password")
            it.remove("phone")
            it.remove("email")
            it.remove("isUse")
        }
    }

    /**
     * 创建新用户
     *
     * @param ctx 上下文对象
     * @param data 用户数据，包含 phone（电话）、email（邮箱）、roleId（角色ID）、userName（用户名）、password（密码）等字段
     * @return 新创建的用户实体
     * @throws IllegalStateException 当同名用户已存在时抛出
     */
    suspend fun create(ctx: Context, data: Bundle): SysUserEntity = ctx {
        val phone: String? = data("phone")
        val email: String? = data("email")

        val roleId: Int = data("roleId")!!
        var userName: String = data("userName")!!
        if ("userName" !in data) {
            userName = phone ?: email ?: "user_${System.currentTimeMillis()}"
            data["userName"] = userName
        }

        // 如果存在密码，则对密码进行加密
        data["password"]?.let {
            data["password"] = encoder.encode(it.toString())
        }

        // 查询或创建用户
        val exists = query<QSysUserEntity> {
            or {
                phone?.let { this.phone.eq(it) }
                email?.let { this.email.eq(it) }
                this.userName.eq(userName)
            }
        }.toAsync().exists()

        if (exists) {
            throw IllegalStateException("同名用户已存在")
        }
        val userId = UUID.randomUUID()
        transaction { tran ->
            mapper.toModel<SysUserEntity>(data).apply {
                applyDatabase(ctx)
                id = userId
                withContext(Dispatchers.IO){
                    insert(tran)
                }
            }

            val hasRole = query<QSysUserRolesEntity> {
                this.userId.eq(userId)
            }.toAsync().exists()

            if (!hasRole) {
                createModel<SysUserRolesEntity> {
                    this.userId = userId
                    this.roleId = roleId
                    this.createdBy = ctx.userIdOrNull ?: userId
                    withContext(Dispatchers.IO){
                        insert(tran)
                    }
                }
            }
        }

        return@ctx query<QSysUserEntity>().id.eq(userId).findOne()!!
    }

    /**
     * 获取用户基本信息，包括角色ID和角色类型
     *
     * @param ctx 上下文对象
     * @param id 用户ID字符串
     * @return 用户信息Bundle（包含用户基本信息、roleId、roleType等字段）
     * @throws NoSuchElementException 当用户不存在时抛出
     */
    suspend fun getUserInfo(ctx: Context, id: String): Bundle = ctx {
        val id1 = id.toUuid()
        val userInfo = query<QSysUserEntity> {
            this.id.eq(id1)
        }.toAsync().findOne()?.let { mapper.toBundle(it) }

        userInfo ?: throw NoSuchElementException("找不到用户")

        val role = query<QSysUserRolesEntity> {
            userId.eq(id1)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
        userInfo["roleId"] = role?.roleId ?: 4
        // 同时返回角色类型字符串，方便前端使用
        if (role != null) {
            val roleEntity = query<QSysRoleEntity>().id.eq(role.roleId).toAsync().findOne()
            userInfo["roleType"] = roleEntity?.type ?: "student"
        } else {
            userInfo["roleType"] = "student"
        }
        return userInfo
    }

    /**
     * 修改用户信息，同时处理密码加密和角色更新
     *
     * @param ctx 上下文对象
     * @param data 用户更新数据，包含 id（用户ID）、roleId（可选，用于更新角色）、password（可选，为空时移除密码）等字段
     * @return 成功响应
     */
    suspend fun update(ctx: Context, data: Bundle): Any = ctx {
        // 加密或移除密码
        data["password"]?.let {
            val str = it.toString()
            if (str.isEmpty()) {
                data.remove("password")
            } else {
                // myInfo 等接口会返回已加密的密文，前端原样回传时不应再次加密；
                // 与数据库现存密码比对，相同则说明是回传的密文，跳过加密避免密码被破坏
                val idStr = data["id"]?.toString()
                val existing = idStr?.let { query<QSysUserEntity>().id.eq(it.toUuid()).findOne()?.password }
                if (existing != null && existing == str) {
                    data.remove("password")
                } else {
                    data["password"] = encoder.encode(str)
                }
            }
        }
        transaction { tran ->
            // 更新用户信息
            val model = ctx.createModel<SysUserEntity>(data, true)
            withContext(Dispatchers.IO){
                model.update(tran)
            }

            // 更新角色信息
            data.getAs<Int>("roleId")?.let { newRoleId ->
                val id: UUID = data["id"]!!.toString().toUuid()
                val role = query<QSysUserRolesEntity>().userId.eq(id).orderBy().id.asc().toAsync().findList().firstOrNull()
                withContext(Dispatchers.IO){
                    if (role != null && role.roleId != newRoleId) {
                        role.roleId = newRoleId
                        role.save(tran)
                    } else if (role == null) {
                        createModel<SysUserRolesEntity> {
                            this.roleId = newRoleId
                            this.userId = id
                        }.save(tran)
                    }
                }
            }
        }
        return success()
    }

    /**
     * 删除用户（软删除），同时清理相关数据：角色关联、组织成员、管理员分配、群聊成员、私信会话/消息、论坛数据等
     *
     * @param ctx 上下文对象
     * @param ids 要删除的用户ID列表
     * @return 成功响应
     */
    suspend fun delete(ctx: Context, ids: List<String>): Any = ctx {
        val userUuids = ids.map { it.toUuid() }
        withContext(Dispatchers.IO) {
            // 1. 删除用户角色关联
            query<QSysUserRolesEntity> {
                this.userId.isIn(userUuids)
            }.toAsync().delete()

            // 2. 软删除用户本身
            query<QSysUserEntity> {
                id.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { user ->
                user.deleted = true
                user.save()
            }

            // 3. 清理组织成员
            query<QAlkOrganizationMember> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }

            // 4. 清理管理员分配
            query<QAlkSchoolAdmin> { userId.isIn(userUuids); deleted.ne(true) }
                .toAsync().findList().forEach { it.delete() }
            query<QAlkCollegeAdmin> { userId.isIn(userUuids); deleted.ne(true) }
                .toAsync().findList().forEach { it.delete() }
            query<QAlkMajorAdmin> { userId.isIn(userUuids); deleted.ne(true) }
                .toAsync().findList().forEach { it.delete() }
            query<QAlkClassAdmin> { userId.isIn(userUuids); deleted.ne(true) }
                .toAsync().findList().forEach { it.delete() }

            // 5. 清理群聊成员
            query<QAlkGroupConversationMember> { userId.isIn(userUuids); deleted.ne(true) }
                .toAsync().findList().forEach { it.delete() }

            // 6. 清理私信会话（作为发送方或接收方）
            query<QAlkConversation> {
                userId.isIn(userUuids).or().targetUserId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }

            // 7. 清理私信消息记录
            query<QAlkMessage> {
                senderId.isIn(userUuids).or().receiverId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }

            // 8. 清理论坛相关数据
            // 8a. 软删除该用户的帖子（含转发帖）
            val userPosts = query<QAlkForumPost> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList()
            // 回滚用户的转发帖对应原帖的 sharesCount
            val repostFromIds = userPosts.mapNotNull { it.repostFromId }.toSet()
            if (repostFromIds.isNotEmpty()) {
                val originalPosts = query<QAlkForumPost> {
                    id.isIn(repostFromIds)
                    deleted.ne(true)
                }.toAsync().findList().associateBy { it.id }
                userPosts.filter { it.repostFromId != null }.forEach { repost ->
                    originalPosts[repost.repostFromId]?.let { original ->
                        original.sharesCount = maxOf(0, original.sharesCount - 1)
                        original.save()
                    }
                }
            }
            // 更新分区帖子计数
            val categoryIdMap = userPosts.groupBy { it.categoryId }
            categoryIdMap.forEach { (catId, posts) ->
                query<QAlkForumCategory> { id.eq(catId) }.toAsync().findOne()?.let { cat ->
                    cat.postCount = (cat.postCount - posts.size).coerceAtLeast(0)
                    cat.save()
                }
            }
            userPosts.forEach { post ->
                post.deleted = true
                post.save()
            }
            // 8b. 清理该用户帖子下的评论（随帖子一起删除）
            val userPostIds = userPosts.map { it.id }.toSet()
            if (userPostIds.isNotEmpty()) {
                query<QAlkForumComment> {
                    postId.isIn(userPostIds)
                    deleted.ne(true)
                }.toAsync().findList().forEach { comment ->
                    comment.deleted = true
                    comment.save()
                }
            }
            // 8c. 清理该用户的评论
            query<QAlkForumComment> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { comment ->
                comment.deleted = true
                comment.save()
            }
            // 8d. 清理该用户的点赞
            query<QAlkForumLike> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }
            // 8e. 清理该用户的收藏
            query<QAlkForumCollection> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }
            // 8f. 清理该用户的分享记录
            query<QAlkForumShare> {
                userId.isIn(userUuids)
                deleted.ne(true)
            }.toAsync().findList().forEach { it.delete() }
        }
        return success()
    }

    /**
     * 更新用户密码，需验证原密码
     *
     * @param ctx 上下文对象
     * @param id 用户ID
     * @param origin 原密码
     * @param target 新密码
     * @return 成功响应
     * @throws NoSuchElementException 当用户不存在时抛出
     * @throws IllegalStateException 当原密码错误时抛出
     */
    suspend fun updatePassword(ctx: Context, id: String, origin: String, target: String): Any {
        val user = ctx.query<QSysUserEntity>().id.eq(id.toUuid()).toAsync().findOne()
        user ?: throw NoSuchElementException("用户不存在")
        if (!user.password.isNullOrEmpty() && !encoder.matches(origin, user.password)) {
            throw IllegalStateException("密码错误！")
        }
        withContext(Dispatchers.IO){
            user.password = encoder.encode(target)
            user.update()
        }
        return success()
    }

    /**
     * 获取当前登录用户的完整信息
     *
     * @param ctx 上下文对象，包含当前会话信息
     * @return 当前用户实体
     */
    suspend fun myUserInfo(ctx: Context): SysUserEntity {
        return ctx.query<QSysUserEntity> {
            id.eq(ctx.user.id)
            deleted.ne(true)
        }.toAsync().findOne()!!.also {
            // 密码为密文，不应回传给前端，避免前端原样回传时被二次加密
            it.password = null
        }
    }

    /**
     * 获取当前登录用户的角色信息
     *
     * @param ctx 上下文对象
     * @return 用户角色信息Bundle
     */
    suspend fun myUserRole(ctx: Context): Bundle {
        return userRoleService.getRoleByUserId(ctx, bundleScope {
            "userId" set ctx.userId
        })
    }

    /**
     * 获取当前管理员的管理范围信息
     *
     * @param ctx 上下文对象
     * @return 包含 roleType、orgs（组织列表，含学校/学院/专业/班级信息）的Bundle
     */
    suspend fun getMyAdminScope(ctx: Context): Bundle = ctx {
        val currentUserId = ctx.userId.toUuid()
        val role = query<QViewUserRoleEntity>().userId.eq(currentUserId).orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type ?: "student"

        // 如果是非管理员角色，直接返回
        if (roleType !in listOf("school_admin", "college_admin", "major_admin", "class_admin")) {
            return@ctx bundleScope { "roleType" set roleType }
        }

        // 收集所有管理的组织列表
        val orgList = mutableListOf<Bundle>()

        when (roleType) {
            "school_admin" -> {
                val schoolAdmins = query<QAlkSchoolAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                for (admin in schoolAdmins) {
                    query<QAlkSchool>().id.eq(admin.schoolId).deleted.ne(true).toAsync().findOne()?.let { school ->
                        orgList.add(bundleScope {
                            "schoolId" set school.id.toString()
                            "schoolName" set school.name
                        })
                    }
                }
            }
            "college_admin" -> {
                val collegeAdmins = query<QAlkCollegeAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                for (admin in collegeAdmins) {
                    query<QAlkCollege>().id.eq(admin.collegeId).deleted.ne(true).toAsync().findOne()?.let { college ->
                        query<QAlkSchool>().id.eq(college.schoolId).deleted.ne(true).toAsync().findOne()?.let { school ->
                            orgList.add(bundleScope {
                                "collegeId" set college.id.toString()
                                "collegeName" set college.name
                                "schoolId" set school.id.toString()
                                "schoolName" set school.name
                            })
                        }
                    }
                }
            }
            "major_admin" -> {
                val majorAdmins = query<QAlkMajorAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                for (admin in majorAdmins) {
                    query<QAlkMajor>().id.eq(admin.majorId).deleted.ne(true).toAsync().findOne()?.let { major ->
                        query<QAlkCollege>().id.eq(major.collegeId).deleted.ne(true).toAsync().findOne()?.let { college ->
                            query<QAlkSchool>().id.eq(major.schoolId).deleted.ne(true).toAsync().findOne()?.let { school ->
                                orgList.add(bundleScope {
                                    "majorId" set major.id.toString()
                                    "majorName" set major.name
                                    "collegeId" set college.id.toString()
                                    "collegeName" set college.name
                                    "schoolId" set school.id.toString()
                                    "schoolName" set school.name
                                })
                            }
                        }
                    }
                }
            }
            "class_admin" -> {
                val classAdmins = query<QAlkClassAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                for (admin in classAdmins) {
                    query<QAlkClass>().id.eq(admin.classId).deleted.ne(true).toAsync().findOne()?.let { clazz ->
                        query<QAlkMajor>().id.eq(clazz.majorId).deleted.ne(true).toAsync().findOne()?.let { major ->
                            query<QAlkCollege>().id.eq(clazz.collegeId).deleted.ne(true).toAsync().findOne()?.let { college ->
                                query<QAlkSchool>().id.eq(clazz.schoolId).deleted.ne(true).toAsync().findOne()?.let { school ->
                                    orgList.add(bundleScope {
                                        "classId" set clazz.id.toString()
                                        "className" set clazz.name
                                        "majorId" set major.id.toString()
                                        "majorName" set major.name
                                        "collegeId" set college.id.toString()
                                        "collegeName" set college.name
                                        "schoolId" set school.id.toString()
                                        "schoolName" set school.name
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }

        return@ctx bundleScope {
            "roleType" set roleType
            "orgs" set orgList
        }
    }

    /**
     * 按管理员的作用域范围查询用户列表（分页）
     * 次级管理员只能看到自己范围内的用户，不能看到超级管理员
     *
     * @param ctx 上下文对象
     * @param pageNo 页码，从1开始，默认为1
     * @param pageSize 每页显示数量，默认为10
     * @param keys 搜索关键字，支持按用户名、真实姓名、手机号搜索
     * @return 包含用户信息列表和总记录数的Pair
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun listByAdminScope(ctx: Context, pageNo: Int = 1, pageSize: Int = 10, keys: String? = null): Pair<List<Bundle>, Int> {
        val currentUserId = ctx.userId.toUuid()
        val currentRole = ctx.query<QViewUserRoleEntity>().userId.eq(currentUserId).orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = currentRole?.type ?: "student"

        // 收集范围内的所有 userId
        val userIds = mutableSetOf<UUID>()

        // 辅助函数：通过 orgIds 查询组织成员（学生）
        suspend fun addStudentMembersByOrgs(orgIds: Set<UUID>) {
            if (orgIds.isEmpty()) return
            val members = ctx.query<QAlkOrganizationMember> {
                organizationId.isIn(orgIds)
                status.eq(1)
                deleted.ne(true)
            }.toAsync().findList()
            members.mapTo(userIds) { it.userId }
        }

        // 辅助函数：获取学校下的所有层级ID
        suspend fun getSchoolScope(schoolId: UUID) {
            // 本校所有校级管理员
            userIds.addAll(ctx.query<QAlkSchoolAdmin> {
                this.schoolId.eq(schoolId)
                deleted.ne(true)
            }.toAsync().findList().map { it.userId })

            // 从组织表获取所有层级ID
            val orgs = ctx.query<QAlkOrganization> {
                this.schoolId.eq(schoolId)
                deleted.ne(true)
            }.toAsync().findList()

            val orgIds = orgs.map { it.id }.toSet()
            addStudentMembersByOrgs(orgIds)

            // 获取学院ID：优先从组织表，fallback到QAlkCollege表
            var collegeIds = orgs.mapNotNull { it.collegeId }.toSet()
            if (collegeIds.isEmpty()) {
                collegeIds = ctx.query<QAlkCollege> {
                    this.schoolId.eq(schoolId); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (collegeIds.isNotEmpty()) {
                userIds.addAll(ctx.query<QAlkCollegeAdmin> {
                    collegeId.isIn(collegeIds)
                    deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }

            // 获取专业ID：优先从组织表，fallback到QAlkMajor表
            var majorIds = orgs.mapNotNull { it.majorId }.toSet()
            if (majorIds.isEmpty()) {
                majorIds = ctx.query<QAlkMajor> {
                    collegeId.isIn(collegeIds); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (majorIds.isNotEmpty()) {
                userIds.addAll(ctx.query<QAlkMajorAdmin> {
                    majorId.isIn(majorIds)
                    deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }

            // 获取班级ID：优先从组织表，fallback到QAlkClass表
            var classIds = orgs.mapNotNull { it.classId }.toSet()
            if (classIds.isEmpty()) {
                classIds = ctx.query<QAlkClass> {
                    majorId.isIn(majorIds); deleted.ne(true)
                }.toAsync().findList().map { it.id }.toSet()
            }
            if (classIds.isNotEmpty()) {
                userIds.addAll(ctx.query<QAlkClassAdmin> {
                    classId.isIn(classIds)
                    deleted.ne(true)
                }.toAsync().findList().map { it.userId })
            }
        }

        when (roleType) {
            "school_admin" -> {
                val schoolAdmins = ctx.query<QAlkSchoolAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                if (schoolAdmins.isEmpty()) return Pair(emptyList(), 0)
                val schoolIds = schoolAdmins.map { it.schoolId }.toSet()
                for (sid in schoolIds) {
                    getSchoolScope(sid)
                }
            }
            "college_admin" -> {
                val collegeAdmins = ctx.query<QAlkCollegeAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                if (collegeAdmins.isEmpty()) return Pair(emptyList(), 0)
                val cids = collegeAdmins.map { it.collegeId }.toSet()
                for (collegeId in cids) {
                    userIds.addAll(ctx.query<QAlkCollegeAdmin> {
                        this.collegeId.eq(collegeId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.userId })

                    val orgs = ctx.query<QAlkOrganization> {
                        this.collegeId.eq(collegeId)
                        deleted.ne(true)
                    }.toAsync().findList()

                    addStudentMembersByOrgs(orgs.map { it.id }.toSet())

                    // 获取专业ID：优先从组织表，fallback到QAlkMajor表
                    var majorIds = orgs.mapNotNull { it.majorId }.toSet()
                    if (majorIds.isEmpty()) {
                        majorIds = ctx.query<QAlkMajor> {
                            this.collegeId.eq(collegeId); deleted.ne(true)
                        }.toAsync().findList().map { it.id }.toSet()
                    }
                    if (majorIds.isNotEmpty()) {
                        userIds.addAll(ctx.query<QAlkMajorAdmin> {
                            majorId.isIn(majorIds)
                            deleted.ne(true)
                        }.toAsync().findList().map { it.userId })
                    }

                    // 获取班级ID：优先从组织表，fallback到QAlkClass表
                    var classIds = orgs.mapNotNull { it.classId }.toSet()
                    if (classIds.isEmpty()) {
                        classIds = ctx.query<QAlkClass> {
                            majorId.isIn(majorIds); deleted.ne(true)
                        }.toAsync().findList().map { it.id }.toSet()
                    }
                    if (classIds.isNotEmpty()) {
                        userIds.addAll(ctx.query<QAlkClassAdmin> {
                            classId.isIn(classIds)
                            deleted.ne(true)
                        }.toAsync().findList().map { it.userId })
                    }
                }
            }
            "major_admin" -> {
                val majorAdmins = ctx.query<QAlkMajorAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                if (majorAdmins.isEmpty()) return Pair(emptyList(), 0)
                val mids = majorAdmins.map { it.majorId }.toSet()
                for (majorId in mids) {
                    userIds.addAll(ctx.query<QAlkMajorAdmin> {
                        this.majorId.eq(majorId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.userId })

                    val orgs = ctx.query<QAlkOrganization> {
                        this.majorId.eq(majorId)
                        deleted.ne(true)
                    }.toAsync().findList()

                    addStudentMembersByOrgs(orgs.map { it.id }.toSet())

                    // 获取班级ID：优先从组织表，fallback到QAlkClass表
                    var classIds = orgs.mapNotNull { it.classId }.toSet()
                    if (classIds.isEmpty()) {
                        classIds = ctx.query<QAlkClass> {
                            this.majorId.eq(majorId); deleted.ne(true)
                        }.toAsync().findList().map { it.id }.toSet()
                    }
                    if (classIds.isNotEmpty()) {
                        userIds.addAll(ctx.query<QAlkClassAdmin> {
                            classId.isIn(classIds)
                            deleted.ne(true)
                        }.toAsync().findList().map { it.userId })
                    }
                }
            }
            "class_admin" -> {
                val classAdmins = ctx.query<QAlkClassAdmin> {
                    this.userId.eq(currentUserId)
                    deleted.ne(true)
                }.toAsync().findList()
                if (classAdmins.isEmpty()) return Pair(emptyList(), 0)
                val classIds = classAdmins.map { it.classId }.toSet()
                userIds.addAll(ctx.query<QAlkClassAdmin> {
                    classId.isIn(classIds)
                    deleted.ne(true)
                }.toAsync().findList().map { it.userId })

                // 本班学生成员
                val classOrgs = ctx.query<QAlkOrganization> {
                    classId.isIn(classIds)
                    deleted.ne(true)
                }.toAsync().findList()
                addStudentMembersByOrgs(classOrgs.map { it.id }.toSet())
            }
            // 超级管理员和其他角色不适用
            else -> return list(ctx, pageNo, pageSize, keys)
        }

        // 包含当前用户
        userIds.add(currentUserId)

        if (userIds.isEmpty()) return Pair(emptyList(), 0)

        // 搜索过滤
        val arrList = bundleOf()
        if (!keys.isNullOrEmpty()) {
            ctx.query<QSysUserEntity> {
                or {
                    userName.contains(keys)
                    realName.contains(keys)
                    phone.contains(keys)
                }
                select(id)
                val idList = toAsync().findSingleAttributeSet<UUID>()
                if (idList.isNotEmpty()) arrList["userId"] = idList
            }
        }

        val offset = (pageNo - 1) * pageSize

        val (userRoles, count) = ctx.query<QViewUserRoleEntity> {
            userId.isIn(userIds)

            if (arrList.isNotEmpty()) {
                arrList.forEach { (k, v) ->
                    when (k) {
                        "userId" -> userId.isIn(v as Set<UUID>)
                    }
                }
            }

            orderBy {
                roleId.asc()
                userName.asc()
            }
            setFirstRow(offset)
            setMaxRows(pageSize)
        }.toAsync().findPagedList().run {
            list.map(mapper::toBundle) to totalCount
        }

        if (userRoles.isNotEmpty()) {
            val users = ctx.query<QSysUserEntity> {
                id.isIn(userRoles.map { it.getAs<String>("userId")!!.toUuid() })
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
            userRoles.forEach {
                it.scope {
                    "realName" set ""
                    users[it["userId"].toString().toUuid()]?.let { user ->
                        user.remove("password")
                        user.remove("phone")
                        user.remove("email")
                        user.remove("isUse")
                        it.putAll(user)
                    } ?: let {
                        "realName" set "<账户不存在>"
                        "isUse" set false
                    }
                }
            }
        }

        return userRoles to count
    }

    /**
     * 获取当前管理员可分配的角色列表
     * 高级管理员可以分配低级别的角色，如学校管理员可分配学院管理员、专业管理员、班级管理员、学生等
     *
     * @param ctx 上下文对象
     * @return 可分配的角色类型字符串列表
     */
    suspend fun getAssignableRoles(ctx: Context): List<String> = ctx {
        val currentUserId = ctx.userId.toUuid()
        val role = query<QViewUserRoleEntity>().userId.eq(currentUserId).orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type ?: "student"

        return@ctx when (roleType) {
            "school_admin" -> listOf("college_admin", "major_admin", "class_admin", "student")
            "college_admin" -> listOf("major_admin", "class_admin", "student")
            "major_admin" -> listOf("class_admin", "student")
            "class_admin" -> listOf("student")
            // 超级管理员可以分配所有角色
            "admin", "super_admin" -> listOf("super_admin", "school_admin", "college_admin", "major_admin", "class_admin", "student")
            else -> emptyList()
        }
    }

    /**
     * 获取当前用户的会话信息
     *
     * @param ctx 上下文对象
     * @return 会话信息Bundle，如果不存在则返回null
     */
    suspend fun mySession(ctx: Context): Bundle? {
        return withContext(Dispatchers.IO) {
            session.mySession(ctx)?.let { mutableMapOf<String, Any?>().apply { putAll(it) } }
        }
    }

    /**
     * 获取当前用户的统计数据：动态数、人脉数、校友数（管理员）、关注数
     *
     * @param ctx 上下文对象
     * @return 统计数据Bundle，包含 postCount（动态数）、connectionCount（人脉数）、followingCount（关注数）、alumniCount（校友数）、isAdmin（是否管理员）
     */
    suspend fun getUserStats(ctx: Context): Bundle = ctx {
        val currentUserId = ctx.userId.toUuid()
        logger.info("getUserStats 开始, userId: $currentUserId")

        // 简化实现：直接计算而不使用复杂的查询
        val postCount = try {
            ctx.query<QAlkForumPost>()
                .userId.eq(currentUserId)
                .deleted.ne(true)
                .toAsync().findList().size
        } catch (e: Exception) {
            logger.warn("获取帖子数量失败: ${e.message}")
            0
        }

        val connectionCount = try {
            ctx.query<QAlkConnection>()
                .userId.eq(currentUserId)
                .status.`in`(listOf(1, 2, 3))
                .toAsync().findList().size
        } catch (e: Exception) {
            logger.warn("获取人脉数量失败: ${e.message}")
            0
        }
        val followingCount = try {
            ctx.query<QAlkConnection>()
                .userId.eq(currentUserId)
                .status.`in`(listOf(1, 3))
                .toAsync().findList().size
        } catch (e: Exception) {
            logger.warn("获取关注数量失败: ${e.message}")
            0
        }

        val myRoleType = try {
            ctx.query<QViewUserRoleEntity> {
                userId.eq(currentUserId)
            }.orderBy().id.asc().toAsync().findList().firstOrNull()?.type ?: "student"
        } catch (e: Exception) {
            logger.warn("获取角色类型失败: ${e.message}")
            "student"
        }

        val isAdmin = myRoleType in listOf("super_admin", "admin", "school_admin", "college_admin", "major_admin", "class_admin")

        // 计算校友数：管理员查看所有组织成员
        var alumniCount = 0
        if (isAdmin) {
            try {
                // 直接查询所有组织成员，避免复杂的权限过滤
                val members = ctx.query<QAlkOrganizationMember>()
                    .status.eq(1)
                    .deleted.ne(true)
                    .toAsync().findList()
                alumniCount = members.map { it.userId }.toSet().size
            } catch (e: Exception) {
                logger.warn("计算校友数量失败: ${e.message}")
                alumniCount = 0
            }
        }

        logger.info("getUserStats 完成: postCount=$postCount, alumniCount=$alumniCount, isAdmin=$isAdmin")
        return@ctx bundleScope {
            "postCount" set postCount
            "connectionCount" set connectionCount
            "followingCount" set followingCount
            "alumniCount" set alumniCount
            "isAdmin" set isAdmin
        }
    }

    /**
     * 管理端总览统计：用户、帖子、组织、学校、职位、投递、连接、消息数量。
     */
    suspend fun getOverviewStats(ctx: Context): Bundle = ctx {
        suspend fun count(block: suspend () -> Int): Int = try { block() } catch (e: Exception) { logger.warn("统计查询失败: ${e.message}"); 0 }
        val userCount = count { ctx.query<QSysUserEntity>().toAsync().findCount() }
        val postCount = count { ctx.query<QAlkForumPost>().deleted.ne(true).toAsync().findCount() }
        val orgCount = count { ctx.query<QAlkOrganization>().deleted.ne(true).toAsync().findCount() }
        val schoolCount = count { ctx.query<QAlkSchool>().toAsync().findCount() }
        val jobCount = count { ctx.query<com.zxce.ai.model.job.query.QAlkJob>().toAsync().findCount() }
        val applicationCount = count { ctx.query<com.zxce.ai.model.job.query.QAlkJobApplication>().toAsync().findCount() }
        val connectionCount = count { ctx.query<QAlkConnection>().status.`in`(listOf(1, 2, 3)).toAsync().findCount() }
        val messageCount = count { ctx.query<QAlkMessage>().toAsync().findCount() }
        return@ctx bundleScope {
            "userCount" set userCount
            "postCount" set postCount
            "orgCount" set orgCount
            "schoolCount" set schoolCount
            "jobCount" set jobCount
            "applicationCount" set applicationCount
            "connectionCount" set connectionCount
            "messageCount" set messageCount
        }
    }

    /**
     * 获取用户头像文件流
     *
     * @param ctx 上下文对象
     * @param id 用户ID
     * @return ResponseEntity 包含头像文件流，若不存在则返回404
     */
    suspend fun avatar(ctx: Context, id: String): ResponseEntity<Flux<DataBuffer>> {
        ctx as IWebExchangeContext

        val avatar = ctx.query<QSysUserEntity> {
            this.id.eq(id.toUuid())
        }.toAsync().findOne()?.avatar
        if (avatar == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
        val path = Path("work/image/avatar", avatar)
        if (!path.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        // 文件更新检查
        val lastModified = ctx.exchange.request.headers.ifModifiedSince;
        val fileLastModified = path.toFile().lastModified()

        if (lastModified == fileLastModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .lastModified(fileLastModified)
                .build()
        }

        // 返回文件
        val fileFlux: Flux<DataBuffer> = DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 8192)
        val mime = MediaTypeFactory.getMediaType(avatar).orElse(MediaType.APPLICATION_OCTET_STREAM)
        return ResponseEntity.ok()
            .contentType(mime)
            .contentLength(path.toFile().length())
            .headers {
                it.setCacheControl(CacheControl.maxAge(Duration.ofMinutes(1)))
                it.lastModified = path.toFile().lastModified()
            }
            .body(fileFlux)
    }

    /**
     * 上传用户头像文件
     *
     * @param ctx 上下文对象
     * @param id 用户ID
     * @param file 上传的头像文件
     * @return 成功响应
     */
    suspend fun avatar(ctx: Context, id: String, file: FilePart): ResponseEntity<ByteArray> {
        val ext = imageExtension(file)
        val filename = "${id.toUuid()}.${ext}"
        val outFile = Path("work/image/avatar", filename).toFile()
        if (outFile.exists()) outFile.delete()
        transferImage(file, outFile)
        // 使用 ctx.database 直接操作，确保数据库更新和缓存刷新
        withContext(Dispatchers.IO) {
            val user = ctx.database.find(SysUserEntity::class.java, id.toUuid())!!
            user.avatar = filename
            ctx.database.save(user)
        }
        return success()
    }

    /**
     * 获取用户背景图文件流
     *
     * @param ctx 上下文对象
     * @param id 用户ID
     * @return ResponseEntity 包含背景图文件流，若不存在则返回404
     */
    suspend fun background(ctx: Context, id: String): ResponseEntity<Flux<DataBuffer>> {
        ctx as IWebExchangeContext

        val background = ctx.query<QSysUserEntity> {
            this.id.eq(id.toUuid())
        }.toAsync().findOne()?.background
        if (background == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }
        val path = Path("work/image/background", background)
        if (!path.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        }

        // 文件更新检查
        val lastModified = ctx.exchange.request.headers.ifModifiedSince;
        val fileLastModified = path.toFile().lastModified()

        if (lastModified == fileLastModified) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .lastModified(fileLastModified)
                .build()
        }

        // 返回文件
        val fileFlux: Flux<DataBuffer> = DataBufferUtils.read(path, DefaultDataBufferFactory.sharedInstance, 8192)
        val mime = MediaTypeFactory.getMediaType(background).orElse(MediaType.APPLICATION_OCTET_STREAM)
        return ResponseEntity.ok()
            .contentType(mime)
            .contentLength(path.toFile().length())
            .headers {
                it.setCacheControl(CacheControl.maxAge(Duration.ofMinutes(1)))
                it.lastModified = path.toFile().lastModified()
            }
            .body(fileFlux)
    }

    /**
     * 上传用户背景图文件
     *
     * @param ctx 上下文对象
     * @param id 用户ID
     * @param file 上传的背景图文件
     * @return 成功响应
     */
    suspend fun background(ctx: Context, id: String, file: FilePart): ResponseEntity<ByteArray> {
        val ext = imageExtension(file)
        val filename = "${id.toUuid()}.${ext}"
        val outFile = Path("work/image/background", filename).toFile()
        if (outFile.exists()) outFile.delete()
        transferImage(file, outFile)
        withContext(Dispatchers.IO) {
            val user = ctx.database.find(SysUserEntity::class.java, id.toUuid())!!
            user.background = filename
            ctx.database.save(user)
        }
        return success()
    }
}