package com.zxce.ai.service.forum

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.model.alumni.AlkOrganization
import com.zxce.ai.model.alumni.AlkSchool
import com.zxce.ai.model.alumni.query.QAlkOrganization
import com.zxce.ai.model.alumni.query.QAlkSchool
import com.zxce.ai.model.alumni.query.QAlkSchoolAdmin
import com.zxce.ai.model.alumni.query.QAlkCollegeAdmin
import com.zxce.ai.model.alumni.query.QAlkMajorAdmin
import com.zxce.ai.model.alumni.query.QAlkClassAdmin
import com.zxce.ai.model.alumni.query.QAlkCollege
import com.zxce.ai.model.alumni.query.QAlkMajor
import com.zxce.ai.model.alumni.query.QAlkClass
import com.zxce.ai.model.forum.*
import com.zxce.ai.model.forum.query.*
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.model.system.query.QViewUserRoleEntity
import com.zxce.ai.service.alumni.OrganizationService
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.spring.ApplicationContextProvider
import com.zxce.ai.util.success
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.*
import kotlin.time.Duration.Companion.seconds

@Service
class ForumService {

    companion object {
        const val INTERNAL_ID_ALUMNI = "ALUMNI"
        private val log = LoggerFactory.getLogger(ForumService::class.java)
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var organizationService: OrganizationService

    /** 全局锁：防止并发创建同一个组织的 forum 分区 */
    private val createCategoryLock = Mutex()

    @Autowired
    lateinit var acp: ApplicationContextProvider

    /** 启动时检查并创建"校友"系统分区 */
    @PostConstruct
    fun initAlumniCategory() {
        CoroutineScope(Dispatchers.Default).launch {
            delay(5.seconds)

            try {
                val existing = acp.query<QAlkForumCategory> {
                    internalId.eq(INTERNAL_ID_ALUMNI)
                }.findOne()
                if (existing == null) {
                    acp.createModel<AlkForumCategory> {
                        id = UUID.randomUUID()
                        schoolName = "校友"
                        internalId = INTERNAL_ID_ALUMNI
                        sortOrder = 0
                    }.save()
                    log.info("校友系统分区创建成功")
                }
            } catch (e: Exception) {
                log.warn("初始化校友系统分区失败: ${e.message}")
            }
        }
    }

    // ============================================================
    // 分区管理
    // ============================================================

    /** 获取论坛分区列表 */
    suspend fun getCategoryList(ctx: Context): Any = ctx {
        val categories = ctx.query<QAlkForumCategory>()
            .isActive.eq(true)
            .orderBy().sortOrder.asc()
            .toAsync().findList()

        // 超级管理员：所有分区均标记为已关注
        val uid = ctx.userId.toUuid()
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(uid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        if (role?.type == "super_admin") {
            return categories.map { cat ->
                mapper.toBundleDeep(cat).apply {
                    this["isFollowed"] = true
                }
            }
        }

        val followedIds = ctx.query<QAlkForumUserFollowCategory>()
            .userId.eq(uid)
            .isHidden.eq(false)
            .toAsync().findList().map { it.categoryId }.toMutableSet()
        // 校友分区始终标记为已关注
        val alumni = categories.firstOrNull { it.internalId == INTERNAL_ID_ALUMNI }
        if (alumni != null) followedIds.add(alumni.id)
        val result = categories.map { cat ->
            mapper.toBundleDeep(cat).apply {
                this["isFollowed"] = cat.id in followedIds
            }
        }
        return result
    }

    /**
     * 获取当前用户可发帖的分区列表（按角色过滤）
     * - super_admin / admin → 所有分区
     * - school_admin → 校友分区 + 管辖学校的分区
     * - college_admin → 校友分区 + 管辖学院的分区
     * - major_admin → 校友分区 + 管辖专业的分区
     * - class_admin → 校友分区 + 管辖班级的分区
     * - student / 其他 → 仅校友分区
     */
    suspend fun getPostingCategories(ctx: Context): Any = ctx {
        val categories = getPostingCategoriesInternal(ctx)
        return categories.map { mapper.toBundleDeep(it) }
    }

    // 获取当前用户可发帖的分区（内部）
    private suspend fun getPostingCategoriesInternal(ctx: Context): List<AlkForumCategory> {
        val userId = ctx.userId.toUuid()
        val roleType = ctx.user.roleType

        // 校友分区：所有人可见
        val alumniCats = ctx.query<QAlkForumCategory>()
            .internalId.eq(INTERNAL_ID_ALUMNI)
            .isActive.eq(true)
            .toAsync().findList()

        // super_admin / admin：所有分区
        if ("super_admin" in roleType || "admin" in roleType) {
            val allCats = ctx.query<QAlkForumCategory>()
                .isActive.eq(true)
                .orderBy().sortOrder.asc()
                .toAsync().findList()
            // 确保校友分区在最前
            val alumniIds = alumniCats.map { it.id }.toSet()
            return allCats.sortedWith(compareBy<AlkForumCategory> { if (it.id in alumniIds) 0 else 1 }
                .thenBy { it.sortOrder })
        }

        // 查询用户的管理员分配
        val schoolAdminIds = ctx.query<QAlkSchoolAdmin>()
            .userId.eq(userId).deleted.ne(true)
            .toAsync().findList().map { it.schoolId }.toSet()
        val collegeAdminIds = ctx.query<QAlkCollegeAdmin>()
            .userId.eq(userId).deleted.ne(true)
            .toAsync().findList().map { it.collegeId }.toSet()
        val majorAdminIds = ctx.query<QAlkMajorAdmin>()
            .userId.eq(userId).deleted.ne(true)
            .toAsync().findList().map { it.majorId }.toSet()
        val classAdminIds = ctx.query<QAlkClassAdmin>()
            .userId.eq(userId).deleted.ne(true)
            .toAsync().findList().map { it.classId }.toSet()

        val hasAnyAdmin = schoolAdminIds.isNotEmpty() || collegeAdminIds.isNotEmpty() ||
            majorAdminIds.isNotEmpty() || classAdminIds.isNotEmpty()

        if (!hasAnyAdmin) {
            // 普通校友：不可发帖，但可评论
            return emptyList()
        }

        // 管理员：根据管辖范围返回可发帖的组织分区
        // 1. 组织关联分区（organizationId 非空）
        val orgCats = ctx.query<QAlkForumCategory>()
            .organizationId.isNotNull
            .isActive.eq(true)
            .toAsync().findList()

        val allowedOrgCats = orgCats.filter { cat ->
            canAdminPostInCategory(ctx, cat, schoolAdminIds, collegeAdminIds, majorAdminIds, classAdminIds)
        }

        // 2. 学校关联分区（仅有 schoolId，无 organizationId）→ school_admin 管辖
        val schoolOnlyCats = ctx.query<QAlkForumCategory>()
            .schoolId.isNotNull
            .organizationId.isNull
            .isActive.eq(true)
            .toAsync().findList()

        val allowedSchoolCats = if (schoolAdminIds.isNotEmpty()) {
            schoolOnlyCats.filter { it.schoolId in schoolAdminIds }
        } else emptyList()

        return (alumniCats + allowedOrgCats + allowedSchoolCats).sortedBy { it.sortOrder }
    }

    /**
     * 检查管理员是否有权在指定分区发帖
     * 分区关联的组织决定了权限级别：
     * - 班级级组织 → 需要 class_admin
     * - 专业级组织 → 需要 major_admin
     * - 学院级组织 → 需要 college_admin
     * - 学校级组织 → 需要 school_admin
     */
    private suspend fun canAdminPostInCategory(
        ctx: Context,
        category: AlkForumCategory,
        schoolAdminIds: Set<UUID>,
        collegeAdminIds: Set<UUID>,
        majorAdminIds: Set<UUID>,
        classAdminIds: Set<UUID>
    ): Boolean {
        val orgId = category.organizationId ?: return false
        val org = ctx.query<QAlkOrganization>().id.eq(orgId).toAsync().findOne() ?: return false

        return when {
            // 班级级：有 classId 且在用户管辖范围内
            org.classId != null && org.classId in classAdminIds -> true
            // 专业级：有 majorId（无 classId）且在用户管辖范围内
            org.majorId != null && org.majorId !in classAdminIds && org.majorId in majorAdminIds -> true
            // 学院级：有 collegeId（无 majorId/classId）且在用户管辖范围内
            org.collegeId != null && org.collegeId !in majorAdminIds && org.collegeId in collegeAdminIds -> true
            // 学校级：仅 schoolId 且在用户管辖范围内
            org.schoolId != null && org.schoolId in schoolAdminIds -> true
            else -> false
        }
    }

    /** 切换分区关注 */
    suspend fun toggleFollowCategory(ctx: Context, categoryId: UUID): Any = ctx {
        val uid = ctx.userId.toUuid()

        // 超级管理员无需关注/取消关注，所有学校自动关注
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(uid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        if (role?.type == "super_admin") {
            return success()
        }

        // 检查是否为校友分区（不可取消关注）
        val category = ctx.query<QAlkForumCategory>().id.eq(categoryId).toAsync().findOne()
        if (category?.internalId == INTERNAL_ID_ALUMNI) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "校友分区不可取消关注")
        }
        val record = ctx.query<QAlkForumUserFollowCategory>()
            .userId.eq(uid)
            .categoryId.eq(categoryId)
            .toAsync().findOne()
        withContext(Dispatchers.IO) {
            if (record != null) {
                // 切换 is_hidden（显示/隐藏），不再删除记录
                record.isHidden = !record.isHidden
                record.save()
            } else {
                // 新关注，默认显示
                ctx.createModel<AlkForumUserFollowCategory> {
                    this.userId = uid
                    this.categoryId = categoryId
                    this.isHidden = false
                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                }.save()
            }
        }
        return success()
    }

    /** 获取我关注的分区列表 */
    suspend fun getMyFollowedCategories(ctx: Context): Any = ctx {
        val uid = ctx.userId.toUuid()

        // 超级管理员：所有已激活分区都是已关注的
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(uid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        if (role?.type == "super_admin") {
            log.info("getMyFollowedCategories: super_admin, returning all categories as followed")
            val allCategories = ctx.query<QAlkForumCategory>()
                .isActive.eq(true)
                .deleted.ne(true)
                .orderBy().sortOrder.asc()
                .toAsync().findList()
            return allCategories.map { cat ->
                mapper.toBundleDeep(cat).apply {
                    this["isFollowed"] = true
                }
            }
        }

        val followed = ctx.query<QAlkForumUserFollowCategory>()
            .userId.eq(uid)
            .isHidden.eq(false)
            .toAsync().findList().map { it.categoryId }.toMutableSet()
        log.info("getMyFollowedCategories: user=$uid, followed.size=${followed.size}, followed=$followed")
        // 校友分区始终在已关注列表中
        val alumni = ctx.query<QAlkForumCategory>()
            .internalId.eq(INTERNAL_ID_ALUMNI)
            .isActive.eq(true)
            .toAsync().findOne()
        if (alumni != null) followed.add(alumni.id)
        log.info("getMyFollowedCategories: after alumni add, followed.size=${followed.size}")
        if (followed.isEmpty()) return@ctx emptyList<Bundle>()
        val categories = ctx.query<QAlkForumCategory> {
            id.isIn(followed)
            isActive.eq(true)
            orderBy().sortOrder.asc()
        }.toAsync().findList().toMutableList()
        log.info("getMyFollowedCategories: categories.size=${categories.size}, categories=${categories.map { "${it.schoolName}(schoolId=${it.schoolId}, id=${it.id})" }}")
        // 确保校友分区始终在首位
        if (alumni != null) {
            categories.removeAll { it.id == alumni.id }
            categories.add(0, alumni)
        }
        // 为每个分区设置 isFollowed = true（因为都是已关注的）
        return categories.map { cat ->
            mapper.toBundleDeep(cat).apply {
                this["isFollowed"] = true
            }
        }
    }

    /**
     * 获取用户已加入组织对应的论坛分区，缺失则自动创建（带全局锁防止并发）
     */
    suspend fun getMyJoinedOrganizationCategories(ctx: Context): Any = ctx {
        val uid = ctx.userId.toUuid()
        log.info("getMyJoinedOrganizationCategories: user=${ctx.userId}")

        // 超级管理员：返回所有已激活的学校分区
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(uid)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        if (role?.type == "super_admin") {
            log.info("getMyJoinedOrganizationCategories: super_admin, returning all categories")
            val allCategories = ctx.query<QAlkForumCategory>()
                .isActive.eq(true)
                .deleted.ne(true)
                .orderBy().sortOrder.asc()
                .toAsync().findList()
            return@ctx allCategories.map { cat ->
                mapper.toBundleDeep(cat).apply {
                    this["isFollowed"] = true
                }
            }
        }

        val orgs = organizationService.getMyOrganizations(ctx)
        log.info("getMyJoinedOrganizationCategories: user=${ctx.userId}, orgs.size=${orgs.size}, orgs=${orgs.map { "${it.school}(schoolId=${it.schoolId}, orgId=${it.id})" }}")

        // 收集用户作为管理员管理的学校ID
        val adminSchoolIds = mutableSetOf<UUID>()

        // 校级管理员
        val schoolAdmins = query<QAlkSchoolAdmin> {
            this.userId.eq(uid)
            deleted.ne(true)
        }.toAsync().findList()
        schoolAdmins.mapTo(adminSchoolIds) { it.schoolId }

        // 院级管理员 → 通过学院找到学校
        val collegeAdmins = query<QAlkCollegeAdmin> {
            this.userId.eq(uid)
            deleted.ne(true)
        }.toAsync().findList()
        if (collegeAdmins.isNotEmpty()) {
            val collegeIds = collegeAdmins.map { it.collegeId }
            query<QAlkCollege> {
                id.isIn(collegeIds)
                deleted.ne(true)
            }.toAsync().findList().mapTo(adminSchoolIds) { it.schoolId }
        }

        // 专业管理员 → 通过专业找到学校
        val majorAdmins = query<QAlkMajorAdmin> {
            this.userId.eq(uid)
            deleted.ne(true)
        }.toAsync().findList()
        if (majorAdmins.isNotEmpty()) {
            val majorIds = majorAdmins.map { it.majorId }
            query<QAlkMajor> {
                id.isIn(majorIds)
                deleted.ne(true)
            }.toAsync().findList().mapTo(adminSchoolIds) { it.schoolId }
        }

        // 班级管理员 → 通过班级找到学校
        val classAdmins = query<QAlkClassAdmin> {
            this.userId.eq(uid)
            deleted.ne(true)
        }.toAsync().findList()
        if (classAdmins.isNotEmpty()) {
            val classIds = classAdmins.map { it.classId }
            query<QAlkClass> {
                id.isIn(classIds)
                deleted.ne(true)
            }.toAsync().findList().mapTo(adminSchoolIds) { it.schoolId }
        }

        log.info("getMyJoinedOrganizationCategories: adminSchoolIds=$adminSchoolIds")

        // 如果既没有加入组织也没有管理学校，返回空
        if (orgs.isEmpty() && adminSchoolIds.isEmpty()) return@ctx emptyList<Bundle>()

        val result = mutableListOf<Bundle>()
        val needCreate = mutableListOf<AlkOrganization>()

        // 第一轮：检查哪些组织已有 forum 分区
        for (org in orgs) {
            val existing = ctx.query<QAlkForumCategory> {
                organizationId.eq(org.id)
                isActive.eq(true)
                deleted.ne(true)
            }.orderBy().id.asc().toAsync().findList().firstOrNull()
            if (existing != null) {
                result.add(mapper.toBundleDeep(existing))
                // 确保有关注记录（仅创建，不修改 isHidden 状态）
                val followRecord = ctx.query<QAlkForumUserFollowCategory>()
                    .userId.eq(uid)
                    .categoryId.eq(existing.id)
                    .orderBy().id.asc().toAsync().findList().firstOrNull()
                if (followRecord == null) {
                    // 仅当记录不存在时才创建
                    withContext(Dispatchers.IO) {
                        ctx.createModel<AlkForumUserFollowCategory> {
                            this.userId = uid
                            this.categoryId = existing.id
                            this.isHidden = false
                            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                        }.save()
                    }
                }
                // 如果记录已存在（无论 isHidden 是 true 还是 false），不做任何修改
            } else {
                needCreate.add(org)
            }
        }

        // 第一轮（续）：检查管理员管理的学校是否有对应分区
        // 排除已在 orgs 中处理过的 schoolId
        val orgSchoolIds = orgs.mapNotNull { it.schoolId }.toSet()
        val pendingAdminSchoolIds = adminSchoolIds - orgSchoolIds
        val needCreateAdminSchoolIds = mutableSetOf<UUID>()

        for (sid in pendingAdminSchoolIds) {
            // 检查是否已有分区（通过 schoolId 查找）
            val existing = ctx.query<QAlkForumCategory> {
                schoolId.eq(sid)
                isActive.eq(true)
                deleted.ne(true)
            }.orderBy().id.asc().toAsync().findList().firstOrNull()
            if (existing != null) {
                result.add(mapper.toBundleDeep(existing))
                // 确保有关注记录
                val followRecord = ctx.query<QAlkForumUserFollowCategory>()
                    .userId.eq(uid)
                    .categoryId.eq(existing.id)
                    .orderBy().id.asc().toAsync().findList().firstOrNull()
                if (followRecord == null) {
                    withContext(Dispatchers.IO) {
                        ctx.createModel<AlkForumUserFollowCategory> {
                            this.userId = uid
                            this.categoryId = existing.id
                            this.isHidden = false
                            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                        }.save()
                    }
                }
            } else {
                needCreateAdminSchoolIds.add(sid)
            }
        }

        // 第二轮：缺失的加全局锁创建
        if (needCreate.isNotEmpty() || needCreateAdminSchoolIds.isNotEmpty()) {
            createCategoryLock.withLock {
                for (org in needCreate) {
                    log.info("Processing org in needCreate: ${org.school}(schoolId=${org.schoolId}, orgId=${org.id})")
                    // 锁内二次检查：先按 organizationId 查
                    var existing = ctx.query<QAlkForumCategory> {
                        organizationId.eq(org.id)
                        deleted.ne(true)
                    }.orderBy().id.asc().toAsync().findList().firstOrNull()
                    if (existing == null && org.schoolId != null) {
                        // 再按 schoolId 查（同一学校可能已有分区）
                        existing = ctx.query<QAlkForumCategory> {
                            schoolId.eq(org.schoolId)
                            deleted.ne(true)
                        }.orderBy().id.asc().toAsync().findList().firstOrNull()
                    }
                    log.info("Existing category found: ${existing?.schoolName}(schoolId=${existing?.schoolId}, id=${existing?.id}, isActive=${existing?.isActive})")
                    if (existing != null) {
                        // 确保分区是活跃的
                        if (!existing.isActive) {
                            withContext(Dispatchers.IO) {
                                existing.isActive = true
                                existing.save()
                            }
                        }
                        result.add(mapper.toBundleDeep(existing))
                        // 确保有关注记录（仅创建，不修改 isHidden 状态）
                        val followRecord = ctx.query<QAlkForumUserFollowCategory>()
                            .userId.eq(uid)
                            .categoryId.eq(existing.id)
                            .toAsync().findList().firstOrNull()
                        if (followRecord == null) {
                            // 仅当记录不存在时才创建
                            withContext(Dispatchers.IO) {
                                ctx.createModel<AlkForumUserFollowCategory> {
                                    this.userId = uid
                                    this.categoryId = existing.id
                                    this.isHidden = false
                                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                                }.save()
                            }
                        }
                        // 如果记录已存在（无论 isHidden 是 true 还是 false），不做任何修改
                    } else {
                        val cat = ctx.createModel<AlkForumCategory> {
                            this.schoolName = org.school
                            this.schoolId = org.schoolId
                            this.organizationId = org.id
                            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                        }
                        cat.save()
                        // 自动关注新创建的分区（默认显示在Tab栏）
                        withContext(Dispatchers.IO) {
                            ctx.createModel<AlkForumUserFollowCategory> {
                                this.userId = uid
                                this.categoryId = cat.id
                                this.isHidden = false
                                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                            }.save()
                        }
                        result.add(mapper.toBundleDeep(cat))
                    }
                }
            }
            // 处理管理员管理的学校（创建分区）
            for (sid in needCreateAdminSchoolIds) {
                // 锁内二次检查
                val existing = ctx.query<QAlkForumCategory> {
                    schoolId.eq(sid)
                    deleted.ne(true)
                }.orderBy().id.asc().toAsync().findList().firstOrNull()
                if (existing != null) {
                    if (!existing.isActive) {
                        withContext(Dispatchers.IO) {
                            existing.isActive = true
                            existing.save()
                        }
                    }
                    result.add(mapper.toBundleDeep(existing))
                    val followRecord = ctx.query<QAlkForumUserFollowCategory>()
                        .userId.eq(uid)
                        .categoryId.eq(existing.id)
                        .toAsync().findList().firstOrNull()
                    if (followRecord == null) {
                        withContext(Dispatchers.IO) {
                            ctx.createModel<AlkForumUserFollowCategory> {
                                this.userId = uid
                                this.categoryId = existing.id
                                this.isHidden = false
                                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                            }.save()
                        }
                    }
                } else {
                    val school = query<QAlkSchool>().id.eq(sid).deleted.ne(true).toAsync().findOne()
                    val cat = ctx.createModel<AlkForumCategory> {
                        this.schoolName = school?.name ?: sid.toString()
                        this.schoolId = sid
                        this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                    }
                    cat.save()
                    withContext(Dispatchers.IO) {
                        ctx.createModel<AlkForumUserFollowCategory> {
                            this.userId = uid
                            this.categoryId = cat.id
                            this.isHidden = false
                            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                        }.save()
                    }
                    result.add(mapper.toBundleDeep(cat))
                }
            }
        }

        // 去重：防止同一分区被多次添加
        val uniqueResult = result.distinctBy { it["id"] }
        log.info("getMyJoinedOrganizationCategories: result.size=${result.size}, uniqueResult.size=${uniqueResult.size}")
        return@ctx uniqueResult
    }

    /** 从组织同步分区 */
    suspend fun syncCategoriesFromOrganization(ctx: Context): Any = ctx {
        val orgs = ctx.query<QAlkOrganization> {
            status.eq(1)
            deleted.ne(true)
        }.toAsync().findList()
        val existingSchoolNames = ctx.query<QAlkForumCategory>()
            .toAsync().findList().map { it.schoolName }.toSet()
        val existingOrgIds = ctx.query<QAlkForumCategory>()
            .toAsync().findList().mapNotNull { it.organizationId }.toSet()
        withContext(Dispatchers.IO) {
            orgs.forEach { org ->
                // 为该组织创建分区（如果尚未创建）
                if (org.id !in existingOrgIds) {
                    ctx.createModel<AlkForumCategory> {
                        this.schoolName = org.school ?: ""
                        this.organizationId = org.id
                        this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                    }.save()
                }
            }
            // 兼容旧数据：为没有organizationId但有schoolName的分区补充
            orgs.groupBy { it.school }.forEach { (school, schoolOrgs) ->
                if (school !in existingSchoolNames) {
                    val firstOrg = schoolOrgs.first()
                    ctx.createModel<AlkForumCategory> {
                        this.schoolName = school ?: ""
                        this.organizationId = firstOrg.id
                        this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                    }.save()
                }
            }
        }
        return success()
    }

    /** 获取组织列表 */
    suspend fun getOrganizationList(ctx: Context): Any = ctx {
        val orgs = ctx.query<QAlkOrganization> {
            status.eq(1)
            deleted.ne(true)
            orderBy().school.asc().college.asc().major.asc().grade.asc().className.asc()
        }.toAsync().findList()
        return orgs.map { mapper.toBundleDeep(it) }
    }

    /** 创建分区 */
    suspend fun createCategory(ctx: Context, data: Bundle): Any = ctx {
        val orgId = data["organizationId"]?.toString()?.toUuidOrNull()
        val schoolName = data["schoolName"]?.toString() ?: throw IllegalArgumentException("schoolName 不能为空")
        val schoolId = data["schoolId"]?.toString()?.toUuidOrNull()
        val internalId = data["internalId"]?.toString()
        withContext(Dispatchers.IO) {
            ctx.createModel<AlkForumCategory> {
                this.schoolName = schoolName
                this.schoolId = schoolId
                this.organizationId = orgId
                this.internalId = internalId
                this.icon = data["icon"]?.toString()
                this.description = data["description"]?.toString()
                this.sortOrder = (data["sortOrder"] as? Number)?.toInt() ?: 0
                this.createdBy = ctx.userIdOrNull ?: ctx.user.id
            }.save()
        }
        return success()
    }

    // ============================================================
    // 帖子管理
    // ============================================================

    /** 创建帖子 */
    suspend fun createPost(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id

        val roleType = ctx.user.roleType
        val isSuperOrAdmin = "super_admin" in roleType || "admin" in roleType

        // 获取用户可发帖分区（非超管/管理员需校验）
        val allowedCategoryIds = if (isSuperOrAdmin) null else {
            getPostingCategoriesInternal(ctx).map { it.id }.toSet()
        }

        // 检查 categoryId 是否为学校ID，如果是则找到或创建对应的论坛分区
        val rawCategoryId = data["categoryId"]?.toString()?.toUuidOrNull()
        if (rawCategoryId != null) {
            val existingCategory = ctx.query<QAlkForumCategory>().id.eq(rawCategoryId).toAsync().findOne()
            if (existingCategory != null) {
                // 已存在的论坛分区：校验权限
                if (allowedCategoryIds != null && existingCategory.id !in allowedCategoryIds) {
                    throw ErrorResponseException(HttpStatus.FORBIDDEN,
                        "无权在「${existingCategory.schoolName}」分区发帖，请联系对应管理员")
                }
            } else {
                // 不是论坛分区ID，检查是否为学校ID
                val school = ctx.query<QAlkSchool>().id.eq(rawCategoryId).toAsync().findOne()
                if (school != null) {
                    // 查找该学校对应的论坛分区
                    val schoolCategory = ctx.query<QAlkForumCategory>()
                        .schoolId.eq(school.id)
                        .orderBy().id.asc().toAsync().findList().firstOrNull()
                    val categoryId = if (schoolCategory != null) {
                        // 已存在的学校分区：校验权限
                        if (allowedCategoryIds != null && schoolCategory.id !in allowedCategoryIds) {
                            throw ErrorResponseException(HttpStatus.FORBIDDEN,
                                "无权在「${school.name}」分区发帖，请联系校级管理员")
                        }
                        schoolCategory.id
                    } else {
                        // 创建新的学校分区：仅超管/管理员可创建
                        if (!isSuperOrAdmin) {
                            throw ErrorResponseException(HttpStatus.FORBIDDEN,
                                "无权创建「${school.name}」分区，请联系超级管理员")
                        }
                        val newCategory = ctx.createModel<AlkForumCategory> {
                            this.schoolName = school.name
                            this.schoolId = school.id
                            this.isActive = true
                            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                        }
                        newCategory.save()
                        newCategory.id
                    }
                    data["categoryId"] = categoryId
                } else {
                    // 既不是分区ID也不是学校ID
                    throw ErrorResponseException(HttpStatus.BAD_REQUEST, "无效的分区ID")
                }
            }
        }

        // 最终校验：确保目标分区在权限范围内
        val targetCategoryId = data["categoryId"]?.toString()?.toUuidOrNull()
        if (targetCategoryId != null && allowedCategoryIds != null && targetCategoryId !in allowedCategoryIds) {
            val targetCat = ctx.query<QAlkForumCategory>().id.eq(targetCategoryId).toAsync().findOne()
            val catName = targetCat?.schoolName ?: "目标"
            throw ErrorResponseException(HttpStatus.FORBIDDEN,
                "无权在「${catName}」分区发帖，请联系对应管理员")
        }

        val model = ctx.createModel<AlkForumPost>(data)
        withContext(Dispatchers.IO) {
            model.save()
            ctx.query<QAlkForumCategory>().id.eq(model.categoryId).toAsync().findOne()?.let { cat ->
                cat.postCount++
                cat.save()
            }
        }
        return success()
    }

    suspend fun repostPost(ctx: Context, data: Bundle): Any = ctx {
        val originalPostId = data["postId"]?.toString()?.toUuidOrNull()
            ?: throw IllegalArgumentException("帖子ID不能为空")
        val originalPost = ctx.query<QAlkForumPost>().id.eq(originalPostId).toAsync().findOne()
            ?: throw NoSuchElementException("原帖不存在")

        val currentUserId = ctx.userId.toUuid()

        // 原帖归属的分类
        val categoryId = data["categoryId"]?.toString()?.toUuidOrNull() ?: originalPost.categoryId

        // 允许添加附言（可选）
        val remark = data["remark"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        // 创建转发帖
        val newPost = ctx.createModel<AlkForumPost>().apply {
            id = UUID.randomUUID()
            this.categoryId = categoryId
            userId = currentUserId
            title = originalPost.title
            content = remark ?: originalPost.content
            images = originalPost.images
            repostFromId = originalPostId
            isSticky = false
            isEssence = false
            isLocked = false
            viewsCount = 0
            likesCount = 0
            commentsCount = 0
            sharesCount = 0
            lastRepliedAt = null
            createdBy = ctx.userIdOrNull ?: ctx.user.id
        }
        withContext(Dispatchers.IO) {
            newPost.save()
            // 原帖转发数 +1
            originalPost.sharesCount++
            originalPost.save()
        }

        return success()
    }

    suspend fun deletePost(ctx: Context, id: UUID): Any = ctx {
        val post = ctx.query<QAlkForumPost>().id.eq(id).toAsync().findOne()
            ?: throw NoSuchElementException("帖子不存在")
        // 权限校验：帖子创建者 或 各级管理员（学校/学院/专业/班级）可删除
        val currentUserId = ctx.userId.toUuid()
        val isOwner = currentUserId == post.userId

        if (isOwner) {
            // 创建者直接删除
            withContext(Dispatchers.IO) {
                // 删除转发帖时回滚原帖的 sharesCount
                rollbackRepostSharesCount(ctx, post)
                // 清理帖子的关联数据
                cleanupPostRelations(ctx, post.id)
                ctx.query<QAlkForumCategory>().id.eq(post.categoryId).toAsync().findOne()?.let { cat ->
                    cat.postCount = maxOf(0, cat.postCount - 1)
                    cat.save()
                }
                post.delete()
            }
            return success()
        }

        // 校友分区：仅帖子作者可删除，管理员不可代删
        val postCategory = ctx.query<QAlkForumCategory>().id.eq(post.categoryId).toAsync().findOne()
        if (postCategory?.internalId == INTERNAL_ID_ALUMNI) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "校友分区帖子仅作者本人可删除")
        }

        // 检查管理员权限
        val roleType = ctx.user.roleType
        val isSuperAdmin = "super_admin" in roleType || "admin" in roleType

        if (isSuperAdmin) {
            // 超级管理员直接删除
            withContext(Dispatchers.IO) {
                // 删除转发帖时回滚原帖的 sharesCount
                rollbackRepostSharesCount(ctx, post)
                // 清理帖子的关联数据
                cleanupPostRelations(ctx, post.id)
                ctx.query<QAlkForumCategory>().id.eq(post.categoryId).toAsync().findOne()?.let { cat ->
                    cat.postCount = maxOf(0, cat.postCount - 1)
                    cat.save()
                }
                post.delete()
            }
            return success()
        }

        // 获取帖子所属分区的学校ID
        val schoolId = postCategory?.schoolId
            ?: throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权限删除此帖子")

        // 检查各级管理员权限
        val hasAdminPermission = when {
            "school_admin" in roleType -> {
                ctx.query<QAlkSchoolAdmin> {
                    this.userId.eq(currentUserId)
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.toAsync().findOne() != null
            }
            "college_admin" in roleType -> {
                val collegeIds = ctx.query<QAlkCollege> {
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.toAsync().findList().map { it.id }
                if (collegeIds.isEmpty()) false
                else ctx.query<QAlkCollegeAdmin> {
                    this.userId.eq(currentUserId)
                    collegeId.isIn(collegeIds)
                    deleted.ne(true)
                }.toAsync().findOne() != null
            }
            "major_admin" in roleType -> {
                val majorIds = ctx.query<QAlkMajor> {
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.toAsync().findList().map { it.id }
                if (majorIds.isEmpty()) false
                else ctx.query<QAlkMajorAdmin> {
                    this.userId.eq(currentUserId)
                    majorId.isIn(majorIds)
                    deleted.ne(true)
                }.toAsync().findOne() != null
            }
            "class_admin" in roleType -> {
                val classIds = ctx.query<QAlkClass> {
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.toAsync().findList().map { it.id }
                if (classIds.isEmpty()) false
                else ctx.query<QAlkClassAdmin> {
                    this.userId.eq(currentUserId)
                    classId.isIn(classIds)
                    deleted.ne(true)
                }.toAsync().findOne() != null
            }
            else -> false
        }

        if (!hasAdminPermission) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权限删除此帖子")
        }

        withContext(Dispatchers.IO) {
            // 删除转发帖时回滚原帖的 sharesCount
            rollbackRepostSharesCount(ctx, post)
            // 清理帖子的关联数据
            cleanupPostRelations(ctx, post.id)
            ctx.query<QAlkForumCategory>().id.eq(post.categoryId).toAsync().findOne()?.let { cat ->
                cat.postCount = maxOf(0, cat.postCount - 1)
                cat.save()
            }
            post.delete()
        }
        return success()
    }

    /** 获取帖子详情 */
    suspend fun getPostDetail(ctx: Context, id: UUID): Bundle = ctx {
        val post = ctx.query<QAlkForumPost>().id.eq(id).toAsync().findOne()
            ?: throw NoSuchElementException("帖子不存在")
        // 增加浏览量
        withContext(Dispatchers.IO) {
            post.viewsCount++
            post.save()
        }
        val result = mapper.toBundleDeep(post)
        // 填充用户信息
        fillPostUserInfo(ctx, listOf(result))
        // 填充分区信息
        val category = ctx.query<QAlkForumCategory>().id.eq(post.categoryId).toAsync().findOne()
        category?.let {
            result["categoryName"] = it.schoolName
            result["categoryInternalId"] = it.internalId
        }
        // 递归填充完整转发链（直接原帖作者 + 转发链 + 根原帖）
        fillRepostChain(ctx, post, result)
        // 点赞状态（未登录时默认为false）
        val currentUserId = ctx.userIdOrNull
        result["isLiked"] = if (currentUserId != null) {
            ctx.query<QAlkForumLike>()
                .userId.eq(currentUserId)
                .targetType.eq(1)
                .targetId.eq(id)
                .toAsync().exists()
        } else false
        // 收藏状态（未登录时默认为false）
        result["isCollected"] = if (currentUserId != null) {
            ctx.query<QAlkForumCollection>()
                .userId.eq(currentUserId)
                .postId.eq(id)
                .toAsync().exists()
        } else false
        return@ctx result
    }

    /** 获取帖子列表 */
    suspend fun getPostList(
        ctx: Context,
        categoryId: UUID?,
        pageNo: Int,
        pageSize: Int,
        sortBy: String?
    ): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize
        val q = ctx.query<QAlkForumPost>()
        categoryId?.let { q.categoryId.eq(it) }
        val order = q.orderBy()
        // 置顶优先
        order.isSticky.desc()
        when (sortBy ?: "latest") {
            "hot" -> order.likesCount.desc()
            else -> order.createdAt.desc()
        }
        val (posts, count) = q.setFirstRow(offset).setMaxRows(pageSize)
            .toAsync().findPagedList().run {
                list.map(mapper::toBundleDeep) to totalCount
            }
        fillPostListInfo(ctx, posts)
        return@ctx posts to count
    }

    /** 搜索帖子 */
    suspend fun searchPosts(ctx: Context, keyword: String, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize

        // 仅按标题搜索
        val query = ctx.query<QAlkForumPost> {
            title.contains(keyword)
            orderBy().createdAt.desc()
        }

        // 获取总数
        val totalCount = query.toAsync().findCount()

        // 获取分页数据
        val posts = query
            .setFirstRow(offset)
            .setMaxRows(pageSize)
            .toAsync()
            .findList()
            .map(mapper::toBundleDeep)

        fillPostListInfo(ctx, posts)
        return@ctx posts to totalCount
    }

    /** 设置帖子置顶 */
    suspend fun setPostSticky(ctx: Context, id: UUID, isSticky: Boolean): Any = ctx {
        val post = ctx.query<QAlkForumPost>().id.eq(id).toAsync().findOne()
            ?: throw NoSuchElementException("帖子不存在")
        withContext(Dispatchers.IO) {
            post.isSticky = isSticky
            post.save()
        }
        return success()
    }

    /** 设置帖子精华 */
    suspend fun setPostEssence(ctx: Context, id: UUID, isEssence: Boolean): Any = ctx {
        val post = ctx.query<QAlkForumPost>().id.eq(id).toAsync().findOne()
            ?: throw NoSuchElementException("帖子不存在")
        withContext(Dispatchers.IO) {
            post.isEssence = isEssence
            post.save()
        }
        return success()
    }

    // ============================================================
    // 评论管理
    // ============================================================

    /** 添加评论 */
    suspend fun addComment(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        // 计算楼层号
        val postId = data["postId"].toString().toUuid()
        val maxFloor = ctx.query<QAlkForumComment>()
            .postId.eq(postId)
            .orderBy().floorNumber.desc()
            .setMaxRows(1)
            .toAsync().findOne()
        data["floorNumber"] = (maxFloor?.floorNumber ?: 0) + 1
        val model = ctx.createModel<AlkForumComment>(data)
        val post = ctx.query<QAlkForumPost>().id.eq(postId).toAsync().findOne()
        withContext(Dispatchers.IO) {
            model.save()
            post?.let {
                it.commentsCount++
                it.lastRepliedAt = java.sql.Timestamp(System.currentTimeMillis())
                it.save()
            }
        }
        return success()
    }

    /** 删除评论 */
    suspend fun deleteComment(ctx: Context, id: UUID): Any = ctx {
        val comment = ctx.query<QAlkForumComment>().id.eq(id).toAsync().findOne()
            ?: throw NoSuchElementException("评论不存在")

        // 权限校验：评论作者本人 或 管理员 可删除
        val roleType = ctx.user.roleType
        val isAdmin = "super_admin" in roleType || "admin" in roleType ||
            "school_admin" in roleType || "college_admin" in roleType ||
            "major_admin" in roleType || "class_admin" in roleType
        val isOwner = comment.userId == ctx.userId.toUuid()
        if (!isAdmin && !isOwner) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权限删除此评论")
        }

        // 递归收集所有后代评论（评论自身 + 回复 + 子回复 ...）
        val idsToDelete = mutableListOf(comment.id)
        collectAllDescendantIds(ctx, comment.id, idsToDelete)
        val totalDeletionCount = idsToDelete.size

        val post = ctx.query<QAlkForumPost>().id.eq(comment.postId).toAsync().findOne()

        withContext(Dispatchers.IO) {
            // 逐个删除所有后代评论
            idsToDelete.forEach { idToDelete ->
                ctx.query<QAlkForumComment>().id.eq(idToDelete).toAsync().delete()
            }
            post?.let { p ->
                p.commentsCount = maxOf(0, p.commentsCount - totalDeletionCount)
                p.save()
            }
        }
        return success()
    }

    /** 递归收集所有后代评论ID（不包含自身） */
    private suspend fun collectAllDescendantIds(ctx: Context, parentId: UUID, result: MutableList<UUID>) {
        val children = ctx.query<QAlkForumComment>().parentId.eq(parentId).toAsync().findList()
        children.forEach { child ->
            result.add(child.id)
            collectAllDescendantIds(ctx, child.id, result)
        }
    }

    /** 获取评论列表 */
    suspend fun getCommentList(ctx: Context, postId: UUID, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize
        // 只查顶级评论（parentId IS NULL）
        val q = ctx.query<QAlkForumComment>()
            .postId.eq(postId)
            .parentId.isNull
        val (comments, count) = q.orderBy().floorNumber.asc()
            .setFirstRow(offset).setMaxRows(pageSize)
            .toAsync().findPagedList().run {
                list.map(mapper::toBundleDeep) to totalCount
            }
        fillCommentUserInfo(ctx, comments)
        fillCommentLikeInfo(ctx, comments)
        // 为每个顶级评论查询前3条楼中楼回复
        comments.forEach { comment ->
            val commentId = comment["id"].toString().toUuid()
            val previewReplies = ctx.query<QAlkForumComment>()
                .parentId.eq(commentId)
                .orderBy().createdAt.asc()
                .setMaxRows(3)
                .toAsync().findList()
            val replyBundles = previewReplies.map(mapper::toBundleDeep)
            fillCommentUserInfo(ctx, replyBundles)
            fillCommentLikeInfo(ctx, replyBundles)
            comment["previewReplies"] = replyBundles
            comment["totalReplies"] = ctx.query<QAlkForumComment>()
                .parentId.eq(commentId)
                .toAsync().findCount()
        }
        return@ctx comments to count
    }

    /** 获取评论回复 */
    suspend fun getCommentReplies(ctx: Context, commentId: UUID, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> =
        ctx {
            val offset = (pageNo - 1) * pageSize
            val q = ctx.query<QAlkForumComment>().parentId.eq(commentId)
            val (replies, count) = q.orderBy().createdAt.asc()
                .setFirstRow(offset).setMaxRows(pageSize)
                .toAsync().findPagedList().run {
                    list.map(mapper::toBundleDeep) to totalCount
                }
            fillCommentUserInfo(ctx, replies)
            fillCommentLikeInfo(ctx, replies)
            return@ctx replies to count
        }

    // ============================================================
    // 点赞管理
    // ============================================================

    /** 切换点赞 */
    suspend fun toggleLike(ctx: Context, targetType: Int, targetId: UUID): Any = ctx {
        val uid = ctx.userId.toUuid()
        val exists = ctx.query<QAlkForumLike>()
            .userId.eq(uid)
            .targetType.eq(targetType)
            .targetId.eq(targetId)
            .toAsync().exists()
        var post: AlkForumPost? = null
        var comment: AlkForumComment? = null
        if (targetType == 1) {
            post = ctx.query<QAlkForumPost>().id.eq(targetId).toAsync().findOne()
        } else {
            comment = ctx.query<QAlkForumComment>().id.eq(targetId).toAsync().findOne()
        }
        withContext(Dispatchers.IO) {
            if (exists) {
                ctx.query<QAlkForumLike>()
                    .userId.eq(uid)
                    .targetType.eq(targetType)
                    .targetId.eq(targetId)
                    .toAsync().delete()
                post?.let { it.likesCount = maxOf(0, it.likesCount - 1); it.save() }
                comment?.let { it.likesCount = maxOf(0, it.likesCount - 1); it.save() }
            } else {
                ctx.createModel<AlkForumLike> {
                    this.userId = uid
                    this.targetType = targetType
                    this.targetId = targetId
                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                }.save()
                post?.let { it.likesCount++; it.save() }
                comment?.let { it.likesCount++; it.save() }
            }
        }
        return success()
    }

    /** 检查是否已点赞 */
    suspend fun checkLike(ctx: Context, targetType: Int, targetId: UUID): Boolean {
        val currentUserId = ctx.userIdOrNull ?: return false
        return ctx.query<QAlkForumLike>()
            .userId.eq(currentUserId)
            .targetType.eq(targetType)
            .targetId.eq(targetId)
            .toAsync().exists()
    }

    // ============================================================
    // 转发管理
    // ============================================================

    suspend fun createShare(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkForumShare>(data)
        val postId = model.postId
        val post = ctx.query<QAlkForumPost> {
            id.eq(postId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
        withContext(Dispatchers.IO) {
            model.save()
            post?.let {
                it.sharesCount++
                it.save()
            }
        }
        return success()
    }

    /** 获取分享记录列表 */
    suspend fun getShareList(ctx: Context, postId: UUID): Any = ctx {
        val shares = ctx.query<QAlkForumShare>()
            .postId.eq(postId)
            .orderBy().createdAt.desc()
            .toAsync().findList()
        val result = shares.map { mapper.toBundleDeep(it) }
        val userIds = shares.map { it.userId }.toSet()
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundleDeep(v).apply { remove("password") } }
        result.forEach { share ->
            users[share["userId"].toString().toUuid()]?.let {
                share["user"] = it
            }
        }
        return result
    }

    // ============================================================
    // 收藏管理
    // ============================================================

    /** 切换收藏 */
    suspend fun toggleCollection(ctx: Context, postId: UUID): Any = ctx {
        val uid = ctx.userId.toUuid()
        val exists = ctx.query<QAlkForumCollection>()
            .userId.eq(uid)
            .postId.eq(postId)
            .toAsync().exists()
        withContext(Dispatchers.IO) {
            if (exists) {
                ctx.query<QAlkForumCollection>()
                    .userId.eq(uid)
                    .postId.eq(postId)
                    .toAsync().delete()
            } else {
                ctx.createModel<AlkForumCollection> {
                    this.userId = uid
                    this.postId = postId
                    this.createdBy = ctx.userIdOrNull ?: ctx.user.id
                }.save()
            }
        }
        return success()
    }

    /** 获取收藏列表 */
    suspend fun getCollectionList(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val uid = ctx.userId.toUuid()
        val offset = (pageNo - 1) * pageSize
        val q = ctx.query<QAlkForumCollection>().userId.eq(uid)
        val (collections, count) = q.orderBy().createdAt.desc()
            .setFirstRow(offset).setMaxRows(pageSize)
            .toAsync().findPagedList().run {
                list.map(mapper::toBundleDeep) to totalCount
            }
        val postIds = collections.map { it["postId"].toString().toUuid() }.toSet()
        val posts = ctx.query<QAlkForumPost> {
            id.isIn(postIds)
        }.toAsync().findList().associateBy { it.id }
        collections.forEach { coll ->
            val post = posts[coll["postId"].toString().toUuid()]
            post?.let {
                val postBundle = mapper.toBundleDeep(it)
                coll["post"] = postBundle
            }
        }
        fillPostListInfo(ctx, collections.mapNotNull { it["post"] as? Bundle })
        return@ctx collections to count
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    /**
     * 删除转发帖时回滚原帖的 sharesCount。
     * 场景：B转发A的帖子 → B删除自己的转发帖 → A的帖子 sharesCount 应减1。
     */
    private suspend fun rollbackRepostSharesCount(ctx: Context, post: AlkForumPost) {
        post.repostFromId?.let { originalId ->
            ctx.query<QAlkForumPost>().id.eq(originalId).toAsync().findOne()?.let { original ->
                original.sharesCount = maxOf(0, original.sharesCount - 1)
                original.save()
            }
        }
    }

    /**
     * 删除帖子时清理其关联数据（评论、点赞、收藏、分享记录）。
     * 帖子软删除后，其下的关联数据也应一并清理，避免孤儿数据。
     */
    private suspend fun cleanupPostRelations(ctx: Context, postId: UUID) {
        // 清理帖子的评论（软删除）
        ctx.query<QAlkForumComment> {
            this.postId.eq(postId)
            deleted.ne(true)
        }.toAsync().findList().forEach { comment ->
            comment.deleted = true
            comment.save()
        }
        // 清理帖子的点赞（硬删除）
        ctx.query<QAlkForumLike> {
            targetId.eq(postId)
            targetType.eq(1)
            deleted.ne(true)
        }.toAsync().findList().forEach { it.delete() }
        // 清理帖子的收藏（硬删除）
        ctx.query<QAlkForumCollection> {
            this.postId.eq(postId)
            deleted.ne(true)
        }.toAsync().findList().forEach { it.delete() }
        // 清理帖子的分享记录（硬删除）
        ctx.query<QAlkForumShare> {
            this.postId.eq(postId)
            deleted.ne(true)
        }.toAsync().findList().forEach { it.delete() }
    }

    // 填充帖子列表信息（作者、转发链等）
    private suspend fun fillPostListInfo(ctx: Context, posts: List<Bundle>) {
        val userIds = posts.map { it["userId"].toString().toUuid() }.toSet()
        if (userIds.isEmpty()) return
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundleDeep(v).apply { remove("password") } }
        // 批量查询用户角色类型
        val userRoles = ctx.query<QViewUserRoleEntity> {
            userId.isIn(userIds)
        }.toAsync().findList().associateBy { it.userId }
        // 将角色类型添加到用户信息中
        users.forEach { (userId, userBundle) ->
            userRoles[userId]?.let { role ->
                userBundle["roleType"] = role.type ?: "student"
            } ?: userBundle.put("roleType", "student")
        }
        // 批量查询点赞状态（未登录时跳过）
        val currentUserId = ctx.userIdOrNull
        val postIds = posts.map { it["id"].toString().toUuid() }.toSet()
        val likedIds = if (currentUserId != null) {
            ctx.query<QAlkForumLike>()
                .userId.eq(currentUserId)
                .targetType.eq(1)
                .targetId.isIn(postIds)
                .toAsync().findList().map { it.targetId }.toSet()
        } else {
            emptySet()
        }
        // 递归收集转发链上的所有祖先帖子（支持多层嵌套转发：A→B→C 查看C时需拿到B和A）
        val repostAncestors = mutableMapOf<UUID, AlkForumPost>()
        var nextRepostIds = posts.mapNotNull { it["repostFromId"]?.toString()?.toUuidOrNull() }.toSet()
        while (nextRepostIds.isNotEmpty()) {
            val missing = nextRepostIds.filter { it !in repostAncestors }.toSet()
            if (missing.isEmpty()) break
            val found = ctx.query<QAlkForumPost> {
                id.isIn(missing)
            }.toAsync().findList()
            found.forEach { repostAncestors[it.id] = it }
            // 继续向上一层追溯
            nextRepostIds = found.mapNotNull { it.repostFromId }.toSet()
        }
        // 查询转发链上所有祖先帖子的作者用户信息
        val repostUserIds = repostAncestors.values.map { it.userId }.toSet()
        val repostUsers = if (repostUserIds.isNotEmpty()) {
            val repostUserEntities = ctx.query<QSysUserEntity> {
                id.isIn(repostUserIds)
                id.asMapKey()
            }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundleDeep(v).apply { remove("password") } }
            // 查询转发原帖作者的角色
            val repostUserRoles = ctx.query<QViewUserRoleEntity> {
                userId.isIn(repostUserIds)
            }.toAsync().findList().associateBy { it.userId }
            repostUserEntities.forEach { (userId, userBundle) ->
                repostUserRoles[userId]?.let { role ->
                    userBundle["roleType"] = role.type ?: "student"
                } ?: userBundle.put("roleType", "student")
            }
            repostUserEntities
        } else {
            emptyMap()
        }
        // 批量查评分区信息（categoryName + categoryInternalId）
        val categoryIds = posts.mapNotNull { it["categoryId"]?.toString()?.toUuidOrNull() }.toSet()
        val categoryMap = if (categoryIds.isNotEmpty()) {
            ctx.query<QAlkForumCategory> {
                id.isIn(categoryIds)
                id.asMapKey()
            }.toAsync().findMap<UUID>()
        } else {
            emptyMap()
        }
        posts.forEach { post ->
            users[post["userId"].toString().toUuid()]?.let {
                post["user"] = it
            }
            post["isLiked"] = post["id"].toString().toUuid() in likedIds
            // 填充分区信息
            post["categoryId"]?.toString()?.toUuidOrNull()?.let { catId ->
                categoryMap[catId]?.let { cat ->
                    post["categoryName"] = cat.schoolName
                    post["categoryInternalId"] = cat.internalId
                }
            }
            // 填充转发链：递归遍历 repostFromId，构建 repostChain 数组返回给前端
            post["repostFromId"]?.toString()?.toUuidOrNull()?.let { startId ->
                val chain = mutableListOf<Bundle>()
                var curId: UUID? = startId
                val visited = mutableSetOf<UUID>()
                while (curId != null && curId !in visited) {
                    visited.add(curId!!)
                    val ancestor = repostAncestors[curId]
                    if (ancestor != null) {
                        val userBundle = repostUsers[ancestor.userId]
                        // 结构与 fillRepostChain 一致：完整帖子 bundle + 嵌套 user
                        val entry = mapper.toBundleDeep(ancestor).apply {
                            userBundle?.let { put("user", it) }
                        }
                        chain.add(entry)
                        // 兼容旧字段：第一项为直接被转发的原帖作者
                        if (chain.size == 1) {
                            userBundle?.let { post["repostFrom"] = it }
                        }
                        curId = ancestor.repostFromId
                    } else {
                        // 祖先帖已被删除，停止遍历
                        break
                    }
                }
                if (chain.isNotEmpty()) {
                    post["repostChain"] = chain
                    val rootEntry = chain.last()
                    post["repostRootFromId"] = rootEntry["id"]
                    (rootEntry["user"] as? Bundle)?.let { post["repostRootFrom"] = it }
                }
            }
        }
    }

    /**
     * 学校校友动态统计：返回各学校的帖子数、评论数、活跃用户数、分区数。
     */
    suspend fun getSchoolStats(ctx: Context): List<Bundle> = ctx {
        // 1. 分区按学校分组
        val categories = ctx.query<QAlkForumCategory> { deleted.ne(true) }.toAsync().findList()
        val catToSchoolId = categories.associate { it.id to it.schoolId }
        val schoolCats = categories.groupBy { it.schoolId }

        // 2. 学校名称映射
        val schools = ctx.query<QAlkSchool> { deleted.ne(true) }.toAsync().findList()
        val schoolNameMap = schools.associate { it.id to it.name }

        // 3. 所有帖子的 id/categoryId/userId（仅选取必要字段）
        val posts = ctx.query<QAlkForumPost> { deleted.ne(true) }
            .select("id, categoryId, userId").toAsync().findList()
        val postToSchoolId = posts.associate { it.id to catToSchoolId[it.categoryId] }
        val postsBySchool = posts.groupBy { catToSchoolId[it.categoryId] }

        // 4. 所有评论的 postId/userId
        val comments = ctx.query<QAlkForumComment> { deleted.ne(true) }
            .select("postId, userId").toAsync().findList()
        val commentsBySchool = comments.groupBy { postToSchoolId[it.postId] }

        // 5. 汇总各学校统计
        val allSchoolIds = (schoolCats.keys + postsBySchool.keys + commentsBySchool.keys)
            .filterNotNull().toSet()
        return@ctx allSchoolIds.map { schoolId ->
            val schoolName = schoolNameMap[schoolId]
                ?: schoolCats[schoolId]?.firstOrNull()?.schoolName
                ?: ""
            val schoolPosts = postsBySchool[schoolId] ?: emptyList()
            val schoolComments = commentsBySchool[schoolId] ?: emptyList()
            val activeUsers = (schoolPosts.map { it.userId } + schoolComments.map { it.userId }).toSet()
            bundleOf(
                "schoolId" to schoolId,
                "schoolName" to schoolName,
                "postCount" to schoolPosts.size,
                "commentCount" to schoolComments.size,
                "activeUserCount" to activeUsers.size,
                "categoryCount" to (schoolCats[schoolId]?.size ?: 0)
            )
        }
    }

    /**
     * 递归填充完整转发链。
     * - repostFrom: 直接原帖作者（兼容旧字段）
     * - repostChain: 转发链（从直接原帖到根原帖，每个元素含原帖及作者信息）
     * - repostRootFromId / repostRootFrom: 根原帖ID及作者（最原创的帖子）
     * 设有最大深度限制与循环引用保护。
     */
    private suspend fun fillRepostChain(ctx: Context, post: AlkForumPost, result: Bundle, maxDepth: Int = 5) {
        if (post.repostFromId == null) return
        val chain = mutableListOf<Bundle>()
        val visited = mutableSetOf<UUID>()
        visited.add(post.id)
        var current = post
        var depth = 0
        while (depth < maxDepth) {
            val rid = current.repostFromId ?: break
            if (rid in visited) break // 循环引用保护
            visited.add(rid)
            val original = ctx.query<QAlkForumPost>().id.eq(rid).toAsync().findOne() ?: break
            // 原帖作者
            val authorBundle = ctx.query<QSysUserEntity>().id.eq(original.userId).toAsync().findOne()?.let { au ->
                mapper.toBundleDeep(au).apply {
                    remove("password")
                    ctx.query<QViewUserRoleEntity>().userId.eq(original.userId).toAsync().findOne()?.let { r ->
                        put("roleType", r.type ?: "student")
                    } ?: put("roleType", "student")
                }
            }
            val entry = mapper.toBundleDeep(original).apply {
                authorBundle?.let { put("user", it) }
            }
            chain.add(entry)
            // 兼容旧字段：直接原帖作者
            if (depth == 0 && authorBundle != null) {
                result["repostFrom"] = authorBundle
            }
            current = original
            depth++
        }
        if (chain.isNotEmpty()) {
            result["repostChain"] = chain
            val rootEntry = chain.last()
            result["repostRootFromId"] = rootEntry["id"]
            (rootEntry["user"] as? Bundle)?.let { result["repostRootFrom"] = it }
        }
    }

    // 填充帖子作者信息
    private suspend fun fillPostUserInfo(ctx: Context, posts: List<Bundle>) {
        val userIds = posts.map { it["userId"].toString().toUuid() }.toSet()
        if (userIds.isEmpty()) return
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundleDeep(v).apply { remove("password") } }
        // 批量查询用户角色类型
        val userRoles = ctx.query<QViewUserRoleEntity> {
            userId.isIn(userIds)
        }.toAsync().findList().associateBy { it.userId }
        // 将角色类型添加到用户信息中
        users.forEach { (userId, userBundle) ->
            userRoles[userId]?.let { role ->
                userBundle["roleType"] = role.type ?: "student"
            } ?: userBundle.put("roleType", "student")
        }
        posts.forEach { post ->
            users[post["userId"].toString().toUuid()]?.let {
                post["user"] = it
            }
        }
    }

    // 填充评论作者信息
    private suspend fun fillCommentUserInfo(ctx: Context, comments: List<Bundle>) {
        val userIds = comments.map { it["userId"].toString().toUuid() }.toSet()
        if (userIds.isEmpty()) return
        val users = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundleDeep(v).apply { remove("password") } }
        // 批量查询用户角色类型
        val userRoles = ctx.query<QViewUserRoleEntity> {
            userId.isIn(userIds)
        }.toAsync().findList().associateBy { it.userId }
        // 将角色类型添加到用户信息中
        users.forEach { (userId, userBundle) ->
            userRoles[userId]?.let { role ->
                userBundle["roleType"] = role.type ?: "student"
            } ?: userBundle.put("roleType", "student")
        }
        comments.forEach { comment ->
            users[comment["userId"].toString().toUuid()]?.let {
                comment["user"] = it
            }
        }
    }

    // 填充评论点赞状态
    private suspend fun fillCommentLikeInfo(ctx: Context, comments: List<Bundle>) {
        if (comments.isEmpty()) return
        val currentUserId = ctx.userIdOrNull
        if (currentUserId == null) {
            comments.forEach { comment ->
                comment["isLiked"] = false
            }
            return
        }
        val commentIds = comments.map { it["id"].toString().toUuid() }.toSet()
        val likedIds = ctx.query<QAlkForumLike>()
            .userId.eq(currentUserId)
            .targetType.eq(2)
            .targetId.isIn(commentIds)
            .toAsync().findList().map { it.targetId }.toSet()
        comments.forEach { comment ->
            comment["isLiked"] = comment["id"].toString().toUuid() in likedIds
        }
    }
}