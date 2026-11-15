package com.zxce.ai.service.alumni

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.controller.alumni.BatchOrgItem
import com.zxce.ai.manager.BroadcastManager
import com.zxce.ai.model.alumni.*
import com.zxce.ai.model.alumni.query.*
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.model.system.query.QViewUserRoleEntity
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.session.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 组织（学校/学院/专业/班级）及成员管理服务。
 * 提供组织创建、加入、审批、层级管理、管理员分配等能力，
 * 并按当前用户的管理范围进行权限校验。
 *
 * @property broadcastManager 用于向用户推送组织审批等事件通知
 * @property mapper 用于将实体与 Bundle 互转，便于接口返回
 */
@Service
class OrganizationService {

    @Autowired
    lateinit var broadcastManager: BroadcastManager

    @Autowired
    lateinit var mapper: JsonMapper

    /**
     * 按学校/年级/学院/专业/班级精确查找已审核通过的组织记录
     *
     * @param ctx 请求上下文
     * @param schoolId 学校ID
     * @param grade 年级
     * @param college 学院名称
     * @param major 专业名称
     * @param className 班级名称
     * @return 匹配到的组织记录，无匹配时返回 null
     */
    suspend fun findOrganization(
        ctx: Context,
        schoolId: UUID,
        grade: String,
        college: String,
        major: String,
        className: String
    ): AlkOrganization? {
        val school = ctx.query<QAlkSchool>().id.eq(schoolId).toAsync().findOne() ?: return null
        return ctx.query<QAlkOrganization> {
            this.school.eq(school.name)
            this.grade.eq(grade)
            this.college.eq(college)
            this.major.eq(major)
            this.className.eq(className)
            this.status.eq(1)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
    }

    /**
     * 申请创建组织，提交后进入待审核状态（status=0）。
     * 会先做字段完整性校验、重复申请检查与已存在组织检查，
     * 再按需创建学院/专业/班级层级记录。
     *
     * @param ctx 请求上下文
     * @param schoolId 学校ID
     * @param grade 年级
     * @param college 学院名称
     * @param major 专业名称
     * @param className 班级名称
     * @return 新建的组织记录（待审核）
     */
    suspend fun applyCreateOrganization(
        ctx: Context,
        schoolId: UUID,
        grade: String,
        college: String,
        major: String,
        className: String
    ): AlkOrganization {
        val createdBy = ctx.userId.toUuid()
        val school = ctx.query<QAlkSchool>().id.eq(schoolId).toAsync().findOne() ?: throw NoSuchElementException("学校不存在")

        // 字段完整性校验
        if (grade.isBlank()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "年级不能为空")
        if (college.isBlank()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "学院不能为空")
        if (major.isBlank()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "专业不能为空")
        if (className.isBlank()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "班级不能为空")

        // 重复申请检查：同一用户对同一组织不能重复提交待审核申请
        val duplicatePending = ctx.query<QAlkOrganization> {
            this.createdBy.eq(createdBy)
            this.status.eq(0)
            this.school.eq(school.name)
            this.grade.eq(grade)
            this.college.eq(college)
            this.major.eq(major)
            this.className.eq(className)
            deleted.ne(true)
        }.toAsync().findList().firstOrNull()
        if (duplicatePending != null) {
            throw ErrorResponseException(HttpStatus.CONFLICT, "您已提交过该组织的申请，请等待审核")
        }

        // 已存在相同组织（已通过审核）
        val existingApproved = ctx.query<QAlkOrganization> {
            this.status.eq(1)
            this.school.eq(school.name)
            this.grade.eq(grade)
            this.college.eq(college)
            this.major.eq(major)
            this.className.eq(className)
            deleted.ne(true)
        }.toAsync().findList().firstOrNull()
        if (existingApproved != null) {
            throw ErrorResponseException(HttpStatus.CONFLICT, "该组织已存在，直接加入即可")
        }

        // 查找或创建学院
        val collegeEntity = ctx.query<QAlkCollege>()
            .name.eq(college)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findList().firstOrNull() ?: ctx.createModel<AlkCollege> {
                id = UUID.randomUUID()
                name = college
                this.schoolId = schoolId
            }.also { it.save() }

        // 查找或创建专业
        val majorEntity = ctx.query<QAlkMajor>()
            .name.eq(major)
            .collegeId.eq(collegeEntity.id)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findList().firstOrNull() ?: ctx.createModel<AlkMajor> {
                id = UUID.randomUUID()
                name = major
                this.collegeId = collegeEntity.id
                this.schoolId = schoolId
            }.also { it.save() }

        // 查找或创建班级
        val classEntity = ctx.query<QAlkClass>()
            .name.eq(className)
            .majorId.eq(majorEntity.id)
            .collegeId.eq(collegeEntity.id)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findList().firstOrNull() ?: ctx.createModel<AlkClass> {
                id = UUID.randomUUID()
                name = className
                this.majorId = majorEntity.id
                this.collegeId = collegeEntity.id
                this.schoolId = schoolId
            }.also { it.save() }

        val organization = ctx.createModel<AlkOrganization> {
            id = UUID.randomUUID()
            this.schoolId = schoolId
            this.school = school.name
            this.grade = grade
            this.collegeId = collegeEntity.id
            this.college = college
            this.majorId = majorEntity.id
            this.major = major
            this.classId = classEntity.id
            this.className = className
            name = "${school.name} $grade $college $major $className"
            memberCount = 0
            status = 0
            this.createdBy = createdBy
        }
        withContext(Dispatchers.IO) {
            organization.save()
        }
        return organization
    }

    /**
     * 当前用户加入指定组织（已审核通过的组织可直接加入，并累加成员数）。
     * 若已存在成员记录则按其状态返回对应错误。
     *
     * @param ctx 请求上下文
     * @param organizationId 组织ID
     * @return 新建的成员记录
     */
    suspend fun joinOrganization(ctx: Context, organizationId: UUID): AlkOrganizationMember {
        val userId = ctx.userId.toUuid()
        val createdBy = userId
        
        val existingMember = ctx.query<QAlkOrganizationMember> {
            this.organizationId.eq(organizationId)
            this.userId.eq(userId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()

        if (existingMember != null) {
            when (existingMember.status) {
                1 -> throw ErrorResponseException(HttpStatus.CONFLICT, "您已是该组织成员")
                0 -> throw ErrorResponseException(HttpStatus.CONFLICT, "您的加入申请正在审核中")
                2 -> throw ErrorResponseException(HttpStatus.CONFLICT, "您的加入申请已被拒绝，请联系管理员")
                else -> throw ErrorResponseException(HttpStatus.CONFLICT, "您已加入该组织")
            }
        }

        val organization = ctx.query<QAlkOrganization> {
            id.eq(organizationId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
            ?: throw NoSuchElementException("组织不存在或已被删除")

        if (organization.status != 1) {
            throw IllegalStateException("组织未通过审核")
        }

        return withContext(Dispatchers.IO) {
            val member = ctx.createModel<AlkOrganizationMember> {
                id = UUID.randomUUID()
                this.organizationId = organizationId
                this.userId = userId
                role = 0
                status = 1
                this.createdBy = createdBy
            }
            member.applyDatabase(ctx)
            member.save()
            organization.memberCount += 1
            organization.applyDatabase(ctx)
            organization.save()
            member
        }
    }

    /**
     * 查询当前用户在指定组织中的成员状态
     *
     * @param ctx 请求上下文
     * @param organizationId 组织ID
     * @return 成员状态（0=待审核,1=已加入,2=已拒绝），未加入返回 -1
     */
    suspend fun getMemberStatus(ctx: Context, organizationId: UUID): Int {
        val userId = ctx.userId.toUuid()
        
        val member = ctx.query<QAlkOrganizationMember> {
            this.organizationId.eq(organizationId)
            this.userId.eq(userId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()

        return member?.status ?: -1
    }

    /**
     * 查询当前用户已加入（status=1）的组织列表
     *
     * @param ctx 请求上下文
     * @return 用户加入的组织列表，无加入记录时返回空列表
     */
    suspend fun getMyOrganizations(ctx: Context): List<AlkOrganization> {
        val userId = ctx.userId.toUuid()
        
        val memberList = ctx.query<QAlkOrganizationMember> {
            this.userId.eq(userId)
            status.eq(1)
            deleted.ne(true)
        }.toAsync().findList()

        if (memberList.isEmpty()) {
            return emptyList()
        }

        val organizationIds = memberList.map { it.organizationId }.toSet()
        return ctx.query<QAlkOrganization> {
            id.isIn(organizationIds)
            deleted.ne(true)
        }.toAsync().findList()
    }

    /**
     * 列出当前管理员管辖范围内的所有组织。
     * 通过分层表（学校→学院→专业→班级）逐级过滤管理员可管理的范围，
     * 并合并孤立的 alk_organization 记录。
     *
     * @param ctx 请求上下文
     * @return 管辖范围内的组织列表，按学院/专业/年级/班级排序
     */
    suspend fun listAllOrganizations(ctx: Context): List<AlkOrganization> {
        val currentUserId = ctx.userId.toUuid()
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        // 收集当前管理员可管理的范围ID
        val allowedSchoolIds = mutableSetOf<UUID>()
        val allowedCollegeIds = mutableSetOf<UUID>()
        val allowedMajorIds = mutableSetOf<UUID>()
        val allowedClassIds = mutableSetOf<UUID>()

        if (currentLevel == null || currentLevel == 0) {
            // 超级管理员/教师：返回所有
        } else {
            when (roleType) {
                "school_admin" -> {
                    allowedSchoolIds.addAll(
                        ctx.query<QAlkSchoolAdmin> {
                            userId.eq(currentUserId)
                            deleted.ne(true)
                        }.toAsync().findList().map { it.schoolId }
                    )
                }
                "college_admin" -> {
                    val myCollegeIds = ctx.query<QAlkCollegeAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.collegeId }
                    allowedCollegeIds.addAll(myCollegeIds)
                    // 查找这些学院所属的学校
                    myCollegeIds.forEach { cid ->
                        ctx.query<QAlkCollege>().id.eq(cid).deleted.ne(true).toAsync().findOne()?.schoolId
                            ?.let { allowedSchoolIds.add(it) }
                    }
                }
                "major_admin" -> {
                    val myMajorIds = ctx.query<QAlkMajorAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.majorId }
                    allowedMajorIds.addAll(myMajorIds)
                    // 查找这些专业所属的学院和学校
                    myMajorIds.forEach { mid ->
                        val major = ctx.query<QAlkMajor>().id.eq(mid).deleted.ne(true).toAsync().findOne()
                        major?.collegeId?.let { allowedCollegeIds.add(it) }
                        major?.schoolId?.let { allowedSchoolIds.add(it) }
                    }
                }
                "class_admin" -> {
                    val myClassIds = ctx.query<QAlkClassAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.classId }
                    allowedClassIds.addAll(myClassIds)
                    // 查找这些班级所属的专业、学院和学校
                    myClassIds.forEach { cid ->
                        val clazz = ctx.query<QAlkClass>().id.eq(cid).deleted.ne(true).toAsync().findOne()
                        clazz?.majorId?.let { allowedMajorIds.add(it) }
                        clazz?.collegeId?.let { allowedCollegeIds.add(it) }
                        clazz?.schoolId?.let { allowedSchoolIds.add(it) }
                    }
                }
            }
        }

        // 从分层表读取数据（alk_school → alk_college → alk_major → alk_class）
        val allSchools = ctx.query<QAlkSchool>().deleted.ne(true).toAsync().findList()
        val schools = if (currentLevel == null || currentLevel == 0) {
            allSchools
        } else {
            allSchools.filter { it.id in allowedSchoolIds }
        }
        val result = mutableListOf<AlkOrganization>()

        // 读取 alk_organization 用于获取 grade 和 memberCount
        val orgMap = ctx.query<QAlkOrganization>().deleted.ne(true).toAsync().findList()
            .associateBy { it.classId }

        for (school in schools) {
            val allColleges = ctx.query<QAlkCollege>()
                .schoolId.eq(school.id)
                .deleted.ne(true)
                .toAsync().findList()
            val colleges = if (allowedCollegeIds.isEmpty() || currentLevel == null || currentLevel == 0) {
                allColleges
            } else {
                allColleges.filter { it.id in allowedCollegeIds }
            }

            for (college in colleges) {
                val allMajors = ctx.query<QAlkMajor>()
                    .collegeId.eq(college.id)
                    .schoolId.eq(school.id)
                    .deleted.ne(true)
                    .toAsync().findList()
                val majors = if (allowedMajorIds.isEmpty() || currentLevel == null || currentLevel == 0) {
                    allMajors
                } else {
                    allMajors.filter { it.id in allowedMajorIds }
                }

                for (major in majors) {
                    val allClasses = ctx.query<QAlkClass>()
                        .majorId.eq(major.id)
                        .collegeId.eq(college.id)
                        .schoolId.eq(school.id)
                        .deleted.ne(true)
                        .toAsync().findList()
                    val classes = if (allowedClassIds.isEmpty() || currentLevel == null || currentLevel == 0) {
                        allClasses
                    } else {
                        allClasses.filter { it.id in allowedClassIds }
                    }

                    for (clazz in classes) {
                        val org = orgMap[clazz.id]
                        // 如果组织表中不存在对应记录，则跳过该班级（无法加入）
                        if (org == null) continue
                        result.add(AlkOrganization().apply {
                            id = org.id
                            this.schoolId = school.id
                            this.school = school.name
                            this.collegeId = college.id
                            this.college = college.name
                            this.majorId = major.id
                            this.major = major.name
                            this.classId = clazz.id
                            this.className = clazz.name
                            this.grade = org.grade
                            name = org.name
                            memberCount = org.memberCount
                            status = org.status
                        })
                    }
                }
            }
        }

        // 补充没有对应班级的孤立 alk_organization 记录
        val existingClassIds = result.mapNotNull { it.classId }.toSet()
        val orphanOrgs = ctx.query<QAlkOrganization> {
            status.eq(1)
            deleted.ne(true)
        }.toAsync().findList()
            .filter { it.classId != null && it.classId !in existingClassIds }
        for (org in orphanOrgs) {
            // 检查孤立组织是否在管理员管辖范围内
            if (currentLevel != null && currentLevel > 0) {
                val inScope = org.schoolId != null && org.schoolId in allowedSchoolIds
                if (!inScope) continue
            }
            // 尝试从分层表补充缺失的班级名称
            if (org.className == null && org.classId != null) {
                val clazz = ctx.query<QAlkClass>().id.eq(org.classId).deleted.ne(true).toAsync().findOne()
                if (clazz != null) {
                    org.className = clazz.name
                }
            }
            result.add(org)
        }

        return result.sortedBy { "${it.college}${it.major}${it.grade}${it.className}" }
    }

    /**
     * 列出当前管理员管辖范围内待审核（status=0）的组织申请。
     * 超级管理员/admin 可见全部，各级管理员仅可见自己管辖范围内的申请。
     *
     * @param ctx 请求上下文
     * @return 待审核组织列表，按创建时间升序
     */
    suspend fun listPendingOrganizations(ctx: Context): List<AlkOrganization> {
        val currentUserId = ctx.userId.toUuid()
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(currentUserId)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type ?: ""

        // 超级管理员和admin可以看到所有待审核
        if (roleType in listOf("super_admin", "admin")) {
            return ctx.query<QAlkOrganization> {
                status.eq(0)
                deleted.ne(true)
                orderBy().createdAt.asc()
            }.toAsync().findList()
        }

        // 校级管理员：只能看到自己学校的申请（包括下属学院、专业、班级的申请）
        if (roleType == "school_admin") {
            val schoolIds = ctx.query<QAlkSchoolAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.schoolId }

            if (schoolIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(0)
                deleted.ne(true)
                schoolId.`in`(schoolIds)
                orderBy().createdAt.asc()
            }.toAsync().findList()
        }

        // 院级管理员：只能看到自己学院的申请（包括下属专业、班级的申请）
        if (roleType == "college_admin") {
            val collegeIds = ctx.query<QAlkCollegeAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.collegeId }

            if (collegeIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(0)
                deleted.ne(true)
                collegeId.`in`(collegeIds)
                orderBy().createdAt.asc()
            }.toAsync().findList()
        }

        // 专业管理员：只能看到自己专业的申请（包括下属班级的申请）
        if (roleType == "major_admin") {
            val majorIds = ctx.query<QAlkMajorAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.majorId }

            if (majorIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(0)
                deleted.ne(true)
                majorId.`in`(majorIds)
                orderBy().createdAt.asc()
            }.toAsync().findList()
        }

        // 班级管理员：只能看到自己班级的申请
        if (roleType == "class_admin") {
            val classIds = ctx.query<QAlkClassAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.classId }

            if (classIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(0)
                deleted.ne(true)
                classId.`in`(classIds)
                orderBy().createdAt.asc()
            }.toAsync().findList()
        }

        return emptyList()
    }

    /**
     * 查询当前管理员权限范围内已审核通过的组织记录（status=1）。
     * 超级管理员/admin 可见全部，各级管理员仅可见自己管辖范围内的组织。
     *
     * @param ctx 请求上下文
     * @return 已审核通过的组织列表，按更新时间倒序
     */
    suspend fun listApprovedOrganizations(ctx: Context): List<AlkOrganization> {
        val currentUserId = ctx.userId.toUuid()
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(currentUserId)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type ?: ""

        // 超级管理员和admin可以看到所有已审核的
        if (roleType in listOf("super_admin", "admin")) {
            return ctx.query<QAlkOrganization> {
                status.eq(1)
                deleted.ne(true)
                orderBy().updatedAt.desc()
            }.toAsync().findList()
        }

        // 校级管理员
        if (roleType == "school_admin") {
            val schoolIds = ctx.query<QAlkSchoolAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.schoolId }

            if (schoolIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(1)
                deleted.ne(true)
                schoolId.`in`(schoolIds)
                orderBy().updatedAt.desc()
            }.toAsync().findList()
        }

        // 院级管理员
        if (roleType == "college_admin") {
            val collegeIds = ctx.query<QAlkCollegeAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.collegeId }

            if (collegeIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(1)
                deleted.ne(true)
                collegeId.`in`(collegeIds)
                orderBy().updatedAt.desc()
            }.toAsync().findList()
        }

        // 专业管理员
        if (roleType == "major_admin") {
            val majorIds = ctx.query<QAlkMajorAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.majorId }

            if (majorIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(1)
                deleted.ne(true)
                majorId.`in`(majorIds)
                orderBy().updatedAt.desc()
            }.toAsync().findList()
        }

        // 班级管理员
        if (roleType == "class_admin") {
            val classIds = ctx.query<QAlkClassAdmin> {
                userId.eq(currentUserId)
                deleted.ne(true)
            }.toAsync().findList().map { it.classId }

            if (classIds.isEmpty()) return emptyList()

            return ctx.query<QAlkOrganization> {
                status.eq(1)
                deleted.ne(true)
                classId.`in`(classIds)
                orderBy().updatedAt.desc()
            }.toAsync().findList()
        }

        return emptyList()
    }

    /**
     * 查询当前用户创建的组织申请记录（包含已拒绝的历史，不过滤 deleted）
     *
     * @param ctx 请求上下文
     * @return 用户创建的组织列表，按创建时间倒序
     */
    suspend fun listMyAppliedOrganizations(ctx: Context): List<AlkOrganization> {
        val userId = ctx.userId.toUuid()
        // 不过滤 deleted，让用户能看到已被拒绝的申请历史
        val result = ctx.query<QAlkOrganization> {
            createdBy.eq(userId)
            orderBy().createdAt.desc()
        }.toAsync().findList()
        return result
    }

    /**
     * 查询当前用户作为成员加入的组织，并填充各组织的未读消息数。
     * 仅返回用户真正加入的组织，不包含仅创建但未加入的组织。
     *
     * @param ctx 请求上下文
     * @return 用户加入的组织列表，按创建时间倒序，含未读消息数
     */
    suspend fun listMyOrganizations(ctx: Context): List<AlkOrganization> {
        val userId = ctx.userId.toUuid()

        // 只返回用户真正加入的组织（作为成员），不返回用户创建但未加入的组织
        // 申请创建的组织：审核通过后申请者会自动成为成员，会显示在列表中
        // 组织管理创建的组织：创建者不是成员，不会显示在列表中
        val memberOrgIds = ctx.query<QAlkOrganizationMember> {
            this.userId.eq(userId)
            status.eq(1)
            deleted.ne(true)
        }.toAsync().findList().map { it.organizationId }

        val result = if (memberOrgIds.isNotEmpty()) {
            ctx.query<QAlkOrganization> {
                id.`in`(memberOrgIds)
                deleted.ne(true)
            }.toAsync().findList().sortedByDescending { it.createdAt }
        } else {
            emptyList()
        }

        // 计算每个组织的未读消息数
        if (result.isNotEmpty()) {
            val memberships = ctx.query<QAlkGroupConversationMember>()
                .userId.eq(userId)
                .deleted.ne(true)
                .toAsync().findList()

            if (memberships.isNotEmpty()) {
                val groupIds = memberships.map { it.groupConversationId }.toSet()
                val groups = ctx.query<QAlkGroupConversation>()
                    .id.`in`(groupIds)
                    .deleted.ne(true)
                    .toAsync().findList()

                // 构建 (scopeType, scopeId) -> unreadCount 映射
                val scopeUnreadMap = mutableMapOf<Pair<String, UUID>, Int>()
                for (group in groups) {
                    val member = memberships.find { it.groupConversationId == group.id }
                    if (member != null && member.unreadCount > 0) {
                        scopeUnreadMap[Pair(group.scopeType, group.scopeId)] =
                            (scopeUnreadMap[Pair(group.scopeType, group.scopeId)] ?: 0) + member.unreadCount
                    }
                }

                // 为每个组织匹配未读数
                for (org in result) {
                    var totalUnread = 0
                    org.schoolId?.let { sid ->
                        totalUnread += scopeUnreadMap[Pair("school", sid)] ?: 0
                    }
                    org.collegeId?.let { cid ->
                        totalUnread += scopeUnreadMap[Pair("college", cid)] ?: 0
                    }
                    org.majorId?.let { mid ->
                        totalUnread += scopeUnreadMap[Pair("major", mid)] ?: 0
                    }
                    org.classId?.let { clsid ->
                        totalUnread += scopeUnreadMap[Pair("class", clsid)] ?: 0
                    }
                    org.unreadCount = totalUnread
                }
            }
        }

        return result
    }

    /**
     * 检查当前用户是否有审批组织的权限
     * 支持超级管理员、admin、教师（全局权限）和各级管理员（范围权限）
     */
    private suspend fun requireApprovalPermission(ctx: Context, org: AlkOrganization? = null) {
        val currentUserId = ctx.userId.toUuid()
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(currentUserId)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type ?: ""

        if (role == null || roleType !in listOf(
                "super_admin", "admin",
                "school_admin", "college_admin", "major_admin", "class_admin"
            )
        ) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权审核组织")
        }

        // 超级管理员、admin拥有全局权限，无需范围检查
        if (roleType in listOf("super_admin", "admin")) return

        // 各级管理员需要检查组织是否在其管理范围内
        if (org != null) {
            val inScope = when (roleType) {
                "school_admin" -> {
                    val schoolIds = ctx.query<QAlkSchoolAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.schoolId }.toSet()
                    org.schoolId in schoolIds
                }
                "college_admin" -> {
                    val collegeIds = ctx.query<QAlkCollegeAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.collegeId }.toSet()
                    org.collegeId in collegeIds
                }
                "major_admin" -> {
                    val majorIds = ctx.query<QAlkMajorAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.majorId }.toSet()
                    org.majorId in majorIds
                }
                "class_admin" -> {
                    val classIds = ctx.query<QAlkClassAdmin> {
                        userId.eq(currentUserId)
                        deleted.ne(true)
                    }.toAsync().findList().map { it.classId }.toSet()
                    org.classId in classIds
                }
                else -> false
            }
            if (!inScope) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权审核该组织，该组织不在您的管理范围内")
            }
        }
    }

    /**
     * 审核通过指定组织申请。
     * 若已存在相同组织则将申请者合并加入既有组织并将原申请标记为已合并；
     * 否则直接通过审核，并把申请者加入为成员、推送通知。
     *
     * @param ctx 请求上下文
     * @param organizationId 组织ID
     * @return 审核通过后的组织记录（可能为既有组织）
     */
    suspend fun approveOrganization(ctx: Context, organizationId: UUID): AlkOrganization {
        val org = ctx.query<QAlkOrganization> {
            id.eq(organizationId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
            ?: throw NoSuchElementException("组织不存在或已被删除")

        // 加载完整数据后进行权限范围检查
        org.applyDatabase(ctx)
        requireApprovalPermission(ctx, org)
        val createdBy = org.createdBy

        // 组织状态校验
        if (org.status == 1) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "组织已通过审核")
        }
        if (org.status == 2) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "组织已被拒绝，无法审核")
        }

        // 检查是否已存在相同组织（学校+学院+专业+年级+班级名称相同）
        val existingOrg = ctx.query<QAlkOrganization> {
            status.eq(1)
            deleted.ne(true)
            school.eq(org.school)
            college.eq(org.college)
            major.eq(org.major)
            grade.eq(org.grade)
            className.eq(org.className)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()

        if (existingOrg != null && existingOrg.id != org.id) {
            // 已存在相同组织，直接将申请者加入该组织
            withContext(Dispatchers.IO) {
                val existingMember = ctx.query<QAlkOrganizationMember> {
                    this.organizationId.eq(existingOrg.id)
                    this.userId.eq(createdBy)
                    deleted.ne(true)
                }.orderBy().id.asc().findList().firstOrNull()

                if (existingMember == null && createdBy != null) {
                    val member = ctx.createModel<AlkOrganizationMember> {
                        id = UUID.randomUUID()
                        this.organizationId = existingOrg.id
                        this.userId = createdBy
                        role = 1
                        status = 1
                        this.createdBy = createdBy
                    }
                    member.applyDatabase(ctx)
                    member.save()
                    existingOrg.memberCount = (existingOrg.memberCount ?: 0) + 1
                    existingOrg.save()
                }

                // 将原申请组织标记为已合并
                org.status = 2  // 2 = 已合并
                org.save()
            }

            createdBy?.let {
                broadcastManager.broadcastToUser("organization:approve", mapOf(
                    "organizationId" to existingOrg.id,
                    "organizationName" to existingOrg.name,
                    "school" to existingOrg.school,
                    "college" to existingOrg.college,
                    "major" to existingOrg.major,
                    "grade" to existingOrg.grade,
                    "className" to existingOrg.className,
                    "status" to 1,
                    "merged" to true
                ), it.toString())
            }

            return existingOrg
        }

        // 不存在相同组织，直接通过
        withContext(Dispatchers.IO) {
            // 补充缺失的 collegeId/majorId/classId（修复历史数据）
            if (org.collegeId == null || org.majorId == null || org.classId == null) {
                val collegeEntity = org.collegeId ?: ctx.query<QAlkCollege>()
                    .name.eq(org.college)
                    .schoolId.eq(org.schoolId)
                    .deleted.ne(true)
                    .toAsync().findList().firstOrNull()?.id

                val majorEntity = org.majorId ?: ctx.query<QAlkMajor>()
                    .name.eq(org.major)
                    .let { q -> if (collegeEntity != null) q.collegeId.eq(collegeEntity) else q }
                    .schoolId.eq(org.schoolId)
                    .deleted.ne(true)
                    .toAsync().findList().firstOrNull()?.id

                val classEntity = org.classId ?: ctx.query<QAlkClass>()
                    .name.eq(org.className ?: "")
                    .let { q -> if (majorEntity != null) q.majorId.eq(majorEntity) else q }
                    .schoolId.eq(org.schoolId)
                    .deleted.ne(true)
                    .toAsync().findList().firstOrNull()?.id

                if (org.collegeId == null) org.collegeId = collegeEntity
                if (org.majorId == null) org.majorId = majorEntity
                if (org.classId == null) org.classId = classEntity
            }

            org.status = 1
            org.save()

            if (createdBy != null) {
                val existingMember = ctx.query<QAlkOrganizationMember> {
                    this.organizationId.eq(organizationId)
                    this.userId.eq(createdBy)
                    deleted.ne(true)
                }.orderBy().id.asc().findList().firstOrNull()

                if (existingMember == null) {
                    val member = ctx.createModel<AlkOrganizationMember> {
                        id = UUID.randomUUID()
                        this.organizationId = organizationId
                        this.userId = createdBy
                        role = 1
                        status = 1
                        this.createdBy = createdBy
                    }
                    member.applyDatabase(ctx)
                    member.save()
                    org.memberCount = (org.memberCount ?: 0) + 1
                    org.save()
                }
            }
        }

        createdBy?.let {
            broadcastManager.broadcastToUser("organization:approve", mapOf(
                "organizationId" to org.id,
                "organizationName" to org.name,
                "school" to org.school,
                "college" to org.college,
                "major" to org.major,
                "grade" to org.grade,
                "className" to org.className,
                "status" to 1
            ), it.toString())
        }

        return org
    }

    /**
     * 审核拒绝指定组织申请：软删除组织记录并推送拒绝通知，使用户可重新申请。
     *
     * @param ctx 请求上下文
     * @param organizationId 组织ID
     * @return 已被拒绝的组织记录
     */
    suspend fun rejectOrganization(ctx: Context, organizationId: UUID): AlkOrganization {
        val org = ctx.query<QAlkOrganization> {
            id.eq(organizationId)
            deleted.ne(true)
        }.orderBy().id.asc().toAsync().findList().firstOrNull()
            ?: throw NoSuchElementException("组织不存在或已被删除")

        // 加载完整数据后进行权限范围检查
        org.applyDatabase(ctx)
        requireApprovalPermission(ctx, org)
        val createdBy = org.createdBy

        // 已通过的组织不可拒绝
        if (org.status == 1) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "组织已通过审核，无法拒绝")
        }

        // 审批拒绝：软删除组织记录，使其不再存在（用户可重新申请）
        withContext(Dispatchers.IO) {
            org.status = 2
            org.deleted = true
            org.save()
        }

        createdBy?.let {
            broadcastManager.broadcastToUser("organization:reject", mapOf(
                "organizationId" to org.id,
                "organizationName" to org.name,
                "school" to org.school,
                "college" to org.college,
                "major" to org.major,
                "grade" to org.grade,
                "className" to org.className,
                "status" to 2
            ), it.toString())
        }

        return org
    }

    /**
     * 更新组织信息（名称、年级等），仅限该组织的管理员或上级管理员
     *
     * @param ctx 请求上下文
     * @param id 组织ID
     * @param name 新的组织名称（可选）
     * @param grade 新的年级（可选）
     * @param college 新的学院（可选）
     * @param major 新的专业（可选）
     * @param className 新的班级名称（可选）
     * @return 更新后的组织信息
     */
    suspend fun updateOrganization(
        ctx: Context,
        id: UUID,
        name: String? = null,
        grade: String? = null,
        college: String? = null,
        major: String? = null,
        className: String? = null
    ): AlkOrganization = ctx {
        val org = query<QAlkOrganization> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("组织不存在")

        // 权限校验：检查当前用户是否有权管理该组织
        requireOrgManagePermission(ctx, org)

        // 更新字段
        name?.let { org.name = it }
        grade?.let { org.grade = it }
        college?.let { org.college = it }
        major?.let { org.major = it }
        className?.let { org.className = it }

        withContext(Dispatchers.IO) {
            org.save()
        }
        org
    }

    /**
     * 删除组织，仅限该组织的管理员或上级管理员
     *
     * @param ctx 请求上下文
     * @param id 组织ID
     */
    suspend fun deleteOrganization(ctx: Context, id: UUID) {
        val org = ctx.query<QAlkOrganization> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("组织不存在")

        // 权限校验：检查当前用户是否有权管理该组织
        requireOrgManagePermission(ctx, org)

        withContext(Dispatchers.IO) {
            // 级联删除：先删除组织成员
            ctx.query<QAlkOrganizationMember> {
                organizationId.eq(id)
            }.delete()

            // 删除组织
            org.delete()
        }
    }

    /**
     * 检查当前用户是否有权管理指定组织
     * 权限范围（取所有角色中最宽的权限）：
     * - 超级管理员/admin：所有组织
     * - 校级管理员：本校所有组织
     * - 院级管理员：本学院所有组织
     * - 专业管理员：本专业所有组织
     * - 班级管理员：本班组织
     */
    private suspend fun requireOrgManagePermission(ctx: Context, org: AlkOrganization) {
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        // 超级管理员或 admin 角色，不需要额外检查
        if (currentLevel == null || currentLevel == 0) return

        val currentUserId = ctx.userId.toUuid()

        // 收集用户所有的管理员分配（所有级别），取权限最宽的范围
        val mySchoolIds = ctx.query<QAlkSchoolAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val myCollegeIds = ctx.query<QAlkCollegeAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val myMajorIds = ctx.query<QAlkMajorAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val myClassIds = ctx.query<QAlkClassAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        // 检查是否有权限（只要在任意级别有权限即可）
        val hasPermission = when {
            // 校级权限：如果在该学校有校级管理员权限
            org.schoolId in mySchoolIds -> true
            // 院级权限：如果在该学院有院级管理员权限
            org.collegeId in myCollegeIds -> true
            // 专业权限：如果在该专业有专业管理员权限
            org.majorId in myMajorIds -> true
            // 班级权限：如果在该班级有班级管理员权限
            org.classId in myClassIds -> true
            else -> false
        }

        if (!hasPermission) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权管理该组织")
        }
    }

    /**
     * 批量创建组织（学院+专业+班级），仅校级及以上管理员可用。
     * 已存在的组织会直接复用并返回。
     *
     * @param ctx 请求上下文
     * @param schoolId 所属学校ID
     * @param items 批量创建项列表
     * @return 新建或已存在的组织列表
     */
    suspend fun batchCreateOrganizations(
        ctx: Context,
        schoolId: UUID,
        items: List<BatchOrgItem>
    ): List<AlkOrganization> {
        val createdBy = ctx.userId.toUuid()

        // 权限校验：只有超级管理员、admin、教师和校级管理员可以批量创建组织（学院+专业+班级）
        // 院级管理员及以下只能使用单个级别的创建接口
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 1) {
            // school_admin 是 level 1，允许；college_admin 及以下不允许
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "校级以下管理员不能批量创建组织，请使用对应级别的创建接口")
        }

        // 检查学校归属权限
        requireManagePermission(ctx, schoolId = schoolId)

        val school = ctx.query<QAlkSchool>().id.eq(schoolId).toAsync().findOne() ?: throw NoSuchElementException("学校不存在")
        val result = mutableListOf<AlkOrganization>()

        for (item in items) {
            val existing = ctx.query<QAlkOrganization> {
                this.school.eq(school.name)
                this.grade.eq(item.grade)
                this.college.eq(item.college)
                this.major.eq(item.major)
                this.className.eq(item.className)
                deleted.ne(true)
            }.orderBy().id.asc().toAsync().findList().firstOrNull()

            if (existing != null) {
                result.add(existing)
                continue
            }

            val college = ctx.query<QAlkCollege>()
                .name.eq(item.college)
                .schoolId.eq(schoolId)
                .deleted.ne(true)
                .toAsync().findOne() ?: ctx.createModel<AlkCollege> {
                id = UUID.randomUUID()
                name = item.college
                this.schoolId = schoolId
            }.also { it.save() }

            val major = ctx.query<QAlkMajor>()
                .name.eq(item.major)
                .collegeId.eq(college.id)
                .schoolId.eq(schoolId)
                .deleted.ne(true)
                .toAsync().findOne() ?: ctx.createModel<AlkMajor> {
                id = UUID.randomUUID()
                name = item.major
                this.collegeId = college.id
                this.schoolId = schoolId
            }.also { it.save() }

            val clazz = ctx.query<QAlkClass>()
                .name.eq(item.className)
                .majorId.eq(major.id)
                .collegeId.eq(college.id)
                .schoolId.eq(schoolId)
                .deleted.ne(true)
                .toAsync().findOne() ?: ctx.createModel<AlkClass> {
                id = UUID.randomUUID()
                name = item.className
                this.majorId = major.id
                this.collegeId = college.id
                this.schoolId = schoolId
            }.also { it.save() }

            val organization = ctx.createModel<AlkOrganization> {
                id = UUID.randomUUID()
                this.schoolId = schoolId
                this.school = school.name
                this.collegeId = college.id
                this.college = college.name
                this.majorId = major.id
                this.major = major.name
                this.classId = clazz.id
                this.className = clazz.name
                this.grade = item.grade
                name = "${school.name} ${item.grade} ${college.name} ${major.name} ${clazz.name}"
                memberCount = 0
                status = 1
                this.createdBy = createdBy
            }
            withContext(Dispatchers.IO) {
                organization.save()
            }
            result.add(organization)
        }
        return result
    }

    /**
     * 列出指定组织已加入的成员，并填充成员的用户信息（移除敏感字段）。
     *
     * @param ctx 请求上下文
     * @param organizationId 组织ID
     * @return 成员 Bundle 列表，包含 user 字段
     */
    suspend fun listMembers(ctx: Context, organizationId: UUID): List<Bundle> = ctx {
        val members = ctx.query<QAlkOrganizationMember>()
            .organizationId.eq(organizationId)
            .status.eq(1)
            .deleted.ne(true)
            .toAsync().findList()

        val userIds = members.map { it.userId }.toSet()
        val userMap = ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val users = userMap.mapValues { (_, v) -> mapper.toBundle(v).apply { remove("password") } }

        members.map { member ->
            val bundle = mapper.toBundle(member)
            val user = users[member.userId]
            if (user != null) {
                val userBundle = user.toMutableMap()
                userBundle["name"] = (userBundle["realName"]?.takeIf { it.toString().isNotBlank() }
                    ?: userBundle["userName"]?.takeIf { it.toString().isNotBlank() }
                    ?: "未知")
                bundle["user"] = userBundle
            }
            bundle
        }
    }

    // ============================================================
    // 学校管理
    // ============================================================

    /**
     * 列出当前用户可见的学校列表，按管理员管辖范围过滤。
     *
     * @param ctx 请求上下文
     * @return 学校列表，按名称升序
     */
    suspend fun listSchools(ctx: Context): List<AlkSchool> {
        val allSchools = ctx.query<QAlkSchool> {
            deleted.ne(true)
            orderBy().name.asc()
        }.toAsync().findList()

        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        // 超级管理员/教师返回所有学校
        if (currentLevel == null || currentLevel == 0) return allSchools

        // 校级管理员可查看全部学校（管理操作仍限管辖范围，由各管理接口自行校验）
        if (roleType == "school_admin") {
            return allSchools
        }

        // 院级/专业/班级管理员：通过关联找到所属学校
        when (roleType) {
            "college_admin" -> {
                val myCollegeIds = ctx.query<QAlkCollegeAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.collegeId }
                val schoolIds = myCollegeIds.mapNotNull { cid ->
                    ctx.query<QAlkCollege>().id.eq(cid).deleted.ne(true).toAsync().findOne()?.schoolId
                }.toSet()
                return allSchools.filter { it.id in schoolIds }
            }
            "major_admin" -> {
                val myMajorIds = ctx.query<QAlkMajorAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.majorId }
                val schoolIds = myMajorIds.mapNotNull { mid ->
                    ctx.query<QAlkMajor>().id.eq(mid).deleted.ne(true).toAsync().findOne()?.schoolId
                }.toSet()
                return allSchools.filter { it.id in schoolIds }
            }
            "class_admin" -> {
                val myClassIds = ctx.query<QAlkClassAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.classId }
                val schoolIds = myClassIds.mapNotNull { cid ->
                    ctx.query<QAlkClass>().id.eq(cid).deleted.ne(true).toAsync().findOne()?.schoolId
                }.toSet()
                return allSchools.filter { it.id in schoolIds }
            }
        }

        return emptyList()
    }

    /**
     * 创建学校，仅超级管理员/教师可用。已存在同名学校时直接返回。
     *
     * @param ctx 请求上下文
     * @param name 学校名称
     * @return 新建或已存在的学校
     */
    suspend fun createSchool(ctx: Context, name: String): AlkSchool {
        // 只有超级管理员/教师可以创建学校
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel == null || currentLevel > 0) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "只有超级管理员可以创建学校")
        }

        val existing = ctx.query<QAlkSchool>()
            .name.eq(name)
            .deleted.ne(true)
            .toAsync().findOne()
        if (existing != null) return existing

        val school = ctx.createModel<AlkSchool> {
            id = UUID.randomUUID()
            this.name = name
        }
        withContext(Dispatchers.IO) {
            school.save()
        }
        return school
    }

    /**
     * 修改学校名称。超级管理员/教师可改任意学校，校级管理员仅能改自己管理的学校。
     *
     * @param ctx 请求上下文
     * @param id 学校ID
     * @param name 新的学校名称
     * @return 更新后的学校
     */
    suspend fun updateSchool(ctx: Context, id: UUID, name: String): AlkSchool {
        val school = ctx.query<QAlkSchool> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("学校不存在")

        // 超级管理员/教师可以修改任何学校；校级管理员只能修改自己管理的学校
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 0) {
            val mySchoolIds = ctx.query<QAlkSchoolAdmin> {
                userId.eq(ctx.userId.toUuid())
                deleted.ne(true)
            }.toAsync().findList().map { it.schoolId }.toSet()
            if (id !in mySchoolIds) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权修改该学校")
            }
        }

        school.name = name
        withContext(Dispatchers.IO) {
            school.save()
        }
        return school
    }

    /**
     * 删除学校，仅超级管理员可用。会级联清理学校下的学院/专业/班级、组织、成员、管理员、群聊。
     *
     * @param ctx 请求上下文
     * @param id 学校ID
     */
    suspend fun deleteSchool(ctx: Context, id: UUID) {
        // 只有超级管理员可以删除学校
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel == null || currentLevel > 0) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "只有超级管理员可以删除学校")
        }

        val school = ctx.query<QAlkSchool> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("学校不存在")

        withContext(Dispatchers.IO) {
            // 先级联清理：学校下的学院/专业/班级、组织、成员、管理员、群聊
            cascadeDeleteChildren(ctx, schoolId = id)
            cleanupOrganizationsAndRelations(ctx, schoolId = id)
            school.delete()
        }
    }

    // ============================================================
    // 学院管理
    // ============================================================

    /**
     * 列出当前用户可见的学院列表，按管理员管辖范围过滤。
     *
     * @param ctx 请求上下文
     * @param schoolId 可选的学校ID，仅列出该学校下的学院
     * @return 学院列表，按名称升序
     */
    suspend fun listColleges(ctx: Context, schoolId: UUID?): List<AlkCollege> {
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        val allColleges = if (schoolId != null) {
            ctx.query<QAlkCollege> {
                this.schoolId.eq(schoolId)
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        } else {
            ctx.query<QAlkCollege> {
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        }

        if (currentLevel == null || currentLevel == 0) return allColleges

        // 收集管理员可管理的学院ID
        val allowedCollegeIds = mutableSetOf<UUID>()
        when (roleType) {
            "school_admin" -> {
                val mySchoolIds = ctx.query<QAlkSchoolAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.schoolId }.toSet()
                allowedCollegeIds.addAll(
                    ctx.query<QAlkCollege>()
                        .schoolId.`in`(mySchoolIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "college_admin" -> {
                allowedCollegeIds.addAll(
                    ctx.query<QAlkCollegeAdmin> {
                        userId.eq(ctx.userId.toUuid())
                        deleted.ne(true)
                    }.toAsync().findList().map { it.collegeId }
                )
            }
            "major_admin" -> {
                val myMajorIds = ctx.query<QAlkMajorAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.majorId }
                allowedCollegeIds.addAll(
                    myMajorIds.mapNotNull { mid ->
                        ctx.query<QAlkMajor>().id.eq(mid).deleted.ne(true).toAsync().findOne()?.collegeId
                    }
                )
            }
            "class_admin" -> {
                val myClassIds = ctx.query<QAlkClassAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.classId }
                allowedCollegeIds.addAll(
                    myClassIds.mapNotNull { cid ->
                        ctx.query<QAlkClass>().id.eq(cid).deleted.ne(true).toAsync().findOne()?.collegeId
                    }
                )
            }
        }

        return allColleges.filter { it.id in allowedCollegeIds }
    }

    /**
     * 创建学院，仅超级管理员/教师和校级管理员可用。已存在同名学院时直接返回。
     *
     * @param ctx 请求上下文
     * @param name 学院名称
     * @param schoolId 所属学校ID
     * @return 新建或已存在的学院
     */
    suspend fun createCollege(ctx: Context, name: String, schoolId: UUID): AlkCollege {
        // 只有超级管理员/教师和校级管理员可以创建学院
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 1) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "院级管理员及以下不能创建学院")
        }

        // 检查学校归属
        requireManagePermission(ctx, schoolId = schoolId)

        val existing = ctx.query<QAlkCollege>()
            .name.eq(name)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findOne()
        if (existing != null) return existing

        val college = ctx.createModel<AlkCollege> {
            id = UUID.randomUUID()
            this.name = name
            this.schoolId = schoolId
        }
        withContext(Dispatchers.IO) {
            college.save()
        }
        return college
    }

    /**
     * 修改学院名称。需在当前用户的管理范围内。
     *
     * @param ctx 请求上下文
     * @param id 学院ID
     * @param name 新的学院名称
     * @return 更新后的学院
     */
    suspend fun updateCollege(ctx: Context, id: UUID, name: String): AlkCollege {
        val college = ctx.query<QAlkCollege> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("学院不存在")

        // 检查权限
        requireManagePermission(ctx, collegeId = id)

        college.name = name
        withContext(Dispatchers.IO) {
            college.save()
        }
        return college
    }

    /**
     * 删除学院，仅超级管理员/教师和校级管理员可用。会级联清理学院下的专业/班级、组织、成员、管理员、群聊。
     *
     * @param ctx 请求上下文
     * @param id 学院ID
     */
    suspend fun deleteCollege(ctx: Context, id: UUID) {
        // 只有超级管理员/教师和校级管理员可以删除学院
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 1) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "院级管理员及以下不能删除学院")
        }

        val college = ctx.query<QAlkCollege> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("学院不存在")

        // 检查学校归属
        requireManagePermission(ctx, schoolId = college.schoolId)

        withContext(Dispatchers.IO) {
            // 级联清理：学院下的专业/班级、组织、成员、管理员、群聊
            cascadeDeleteChildren(ctx, collegeId = id)
            cleanupOrganizationsAndRelations(ctx, collegeId = id, schoolId = college.schoolId)
            college.delete()
        }
    }

    // ============================================================
    // 专业管理
    // ============================================================

    /**
     * 列出当前用户可见的专业列表，按管理员管辖范围过滤。
     *
     * @param ctx 请求上下文
     * @param collegeId 可选的学院ID，仅列出该学院下的专业
     * @return 专业列表，按名称升序
     */
    suspend fun listMajors(ctx: Context, collegeId: UUID?): List<AlkMajor> {
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        val allMajors = if (collegeId != null) {
            ctx.query<QAlkMajor> {
                this.collegeId.eq(collegeId)
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        } else {
            ctx.query<QAlkMajor> {
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        }

        if (currentLevel == null || currentLevel == 0) return allMajors

        // 收集管理员可管理的专业ID
        val allowedMajorIds = mutableSetOf<UUID>()
        when (roleType) {
            "school_admin" -> {
                val mySchoolIds = ctx.query<QAlkSchoolAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.schoolId }.toSet()
                allowedMajorIds.addAll(
                    ctx.query<QAlkMajor>()
                        .schoolId.`in`(mySchoolIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "college_admin" -> {
                val myCollegeIds = ctx.query<QAlkCollegeAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.collegeId }.toSet()
                allowedMajorIds.addAll(
                    ctx.query<QAlkMajor>()
                        .collegeId.`in`(myCollegeIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "major_admin" -> {
                allowedMajorIds.addAll(
                    ctx.query<QAlkMajorAdmin> {
                        userId.eq(ctx.userId.toUuid())
                        deleted.ne(true)
                    }.toAsync().findList().map { it.majorId }
                )
            }
            "class_admin" -> {
                val myClassIds = ctx.query<QAlkClassAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.classId }
                allowedMajorIds.addAll(
                    myClassIds.mapNotNull { cid ->
                        ctx.query<QAlkClass>().id.eq(cid).deleted.ne(true).toAsync().findOne()?.majorId
                    }
                )
            }
        }

        return allMajors.filter { it.id in allowedMajorIds }
    }

    /**
     * 创建专业，班级管理员不可用。已存在同名专业时直接返回。
     *
     * @param ctx 请求上下文
     * @param name 专业名称
     * @param collegeId 所属学院ID
     * @param schoolId 所属学校ID
     * @return 新建或已存在的专业
     */
    suspend fun createMajor(ctx: Context, name: String, collegeId: UUID, schoolId: UUID): AlkMajor {
        // 班级管理员不能创建专业
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 3) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "班级管理员不能创建专业")
        }

        // 检查学院归属
        requireManagePermission(ctx, collegeId = collegeId, schoolId = schoolId)

        val existing = ctx.query<QAlkMajor>()
            .name.eq(name)
            .collegeId.eq(collegeId)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findOne()
        if (existing != null) return existing

        val major = ctx.createModel<AlkMajor> {
            id = UUID.randomUUID()
            this.name = name
            this.collegeId = collegeId
            this.schoolId = schoolId
        }
        withContext(Dispatchers.IO) {
            major.save()
        }
        return major
    }

    /**
     * 修改专业名称。需在当前用户的管理范围内。
     *
     * @param ctx 请求上下文
     * @param id 专业ID
     * @param name 新的专业名称
     * @return 更新后的专业
     */
    suspend fun updateMajor(ctx: Context, id: UUID, name: String): AlkMajor {
        val major = ctx.query<QAlkMajor> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("专业不存在")

        // 检查权限
        requireManagePermission(ctx, majorId = id)

        major.name = name
        withContext(Dispatchers.IO) {
            major.save()
        }
        return major
    }

    /**
     * 删除专业，班级管理员不可用。会级联清理专业下的班级、组织、成员、管理员、群聊。
     *
     * @param ctx 请求上下文
     * @param id 专业ID
     */
    suspend fun deleteMajor(ctx: Context, id: UUID) {
        // 班级管理员不能删除专业
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]
        if (currentLevel != null && currentLevel > 3) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "班级管理员不能删除专业")
        }

        val major = ctx.query<QAlkMajor> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("专业不存在")

        // 检查归属权限
        requireManagePermission(ctx, collegeId = major.collegeId, schoolId = major.schoolId)

        withContext(Dispatchers.IO) {
            // 级联清理：专业下的班级、组织、成员、管理员、群聊
            cascadeDeleteChildren(ctx, majorId = id)
            cleanupOrganizationsAndRelations(ctx, majorId = id, collegeId = major.collegeId, schoolId = major.schoolId)
            major.delete()
        }
    }

    // ============================================================
    // 班级管理
    // ============================================================

    /**
     * 列出当前用户可见的班级列表，按管理员管辖范围过滤。
     *
     * @param ctx 请求上下文
     * @param majorId 可选的专业ID，仅列出该专业下的班级
     * @return 班级列表，按名称升序
     */
    suspend fun listClasses(ctx: Context, majorId: UUID?): List<AlkClass> {
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        val allClasses = if (majorId != null) {
            ctx.query<QAlkClass> {
                this.majorId.eq(majorId)
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        } else {
            ctx.query<QAlkClass> {
                deleted.ne(true)
                orderBy().name.asc()
            }.toAsync().findList()
        }

        if (currentLevel == null || currentLevel == 0) return allClasses

        // 收集管理员可管理的班级ID
        val allowedClassIds = mutableSetOf<UUID>()
        when (roleType) {
            "school_admin" -> {
                val mySchoolIds = ctx.query<QAlkSchoolAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.schoolId }.toSet()
                allowedClassIds.addAll(
                    ctx.query<QAlkClass>()
                        .schoolId.`in`(mySchoolIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "college_admin" -> {
                val myCollegeIds = ctx.query<QAlkCollegeAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.collegeId }.toSet()
                allowedClassIds.addAll(
                    ctx.query<QAlkClass>()
                        .collegeId.`in`(myCollegeIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "major_admin" -> {
                val myMajorIds = ctx.query<QAlkMajorAdmin> {
                    userId.eq(ctx.userId.toUuid())
                    deleted.ne(true)
                }.toAsync().findList().map { it.majorId }.toSet()
                allowedClassIds.addAll(
                    ctx.query<QAlkClass>()
                        .majorId.`in`(myMajorIds)
                        .deleted.ne(true)
                        .toAsync().findList().map { it.id }
                )
            }
            "class_admin" -> {
                allowedClassIds.addAll(
                    ctx.query<QAlkClassAdmin> {
                        userId.eq(ctx.userId.toUuid())
                        deleted.ne(true)
                    }.toAsync().findList().map { it.classId }
                )
            }
        }

        return allClasses.filter { it.id in allowedClassIds }
    }

    /**
     * 创建班级，所有管理员可在自己管辖范围内创建。已存在同名班级时直接返回。
     *
     * @param ctx 请求上下文
     * @param name 班级名称
     * @param majorId 所属专业ID
     * @param collegeId 所属学院ID
     * @param schoolId 所属学校ID
     * @return 新建或已存在的班级
     */
    suspend fun createClass(ctx: Context, name: String, majorId: UUID, collegeId: UUID, schoolId: UUID): AlkClass {
        // 检查权限（所有管理员都可以创建自己管辖内的班级）
        requireManagePermission(ctx, majorId = majorId, collegeId = collegeId, schoolId = schoolId)

        val existing = ctx.query<QAlkClass>()
            .name.eq(name)
            .majorId.eq(majorId)
            .collegeId.eq(collegeId)
            .schoolId.eq(schoolId)
            .deleted.ne(true)
            .toAsync().findOne()
        if (existing != null) return existing

        val clazz = ctx.createModel<AlkClass> {
            id = UUID.randomUUID()
            this.name = name
            this.majorId = majorId
            this.collegeId = collegeId
            this.schoolId = schoolId
        }
        withContext(Dispatchers.IO) {
            clazz.save()
        }
        return clazz
    }

    /**
     * 修改班级名称。需在当前用户的管理范围内。
     *
     * @param ctx 请求上下文
     * @param id 班级ID
     * @param name 新的班级名称
     * @return 更新后的班级
     */
    suspend fun updateClass(ctx: Context, id: UUID, name: String): AlkClass {
        val clazz = ctx.query<QAlkClass> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("班级不存在")

        // 检查权限
        requireManagePermission(ctx, classId = id)

        clazz.name = name
        withContext(Dispatchers.IO) {
            clazz.save()
        }
        return clazz
    }

    /**
     * 删除班级。会级联清理关联的组织、成员、管理员、群聊。
     *
     * @param ctx 请求上下文
     * @param id 班级ID
     */
    suspend fun deleteClass(ctx: Context, id: UUID) {
        val clazz = ctx.query<QAlkClass> {
            this.id.eq(id)
            deleted.ne(true)
        }.toAsync().findOne() ?: throw NoSuchElementException("班级不存在")

        // 检查权限
        requireManagePermission(ctx, classId = id)

        withContext(Dispatchers.IO) {
            // 级联清理：组织、成员、管理员、群聊
            cleanupOrganizationsAndRelations(
                ctx,
                classId = id,
                majorId = clazz.majorId,
                collegeId = clazz.collegeId,
                schoolId = clazz.schoolId
            )
            clazz.delete()
        }
    }

    // ============================================================
    // 管理员分配
    // ============================================================

    /**
     * 列出指定学校的管理员列表。
     *
     * @param ctx 请求上下文
     * @param schoolId 学校ID
     * @return 学校管理员列表
     */
    suspend fun listSchoolAdmins(ctx: Context, schoolId: UUID): List<AlkSchoolAdmin> {
        return ctx.query<QAlkSchoolAdmin> {
            this.schoolId.eq(schoolId)
            deleted.ne(true)
        }.toAsync().findList()
    }

    /**
     * 校验当前用户是否有权限分配指定级别的管理员。
     * @param ctx 上下文
     * @param targetRoleType 目标角色类型（如 "school_admin", "college_admin" 等）
     * @throws ErrorResponseException 无权分配时抛出 403
     */
    private suspend fun checkAssignPermission(ctx: Context, targetRoleType: String) {
        val currentUserId = ctx.userId.toUuid()
        val currentRole = ctx.query<QViewUserRoleEntity>().userId.eq(currentUserId).toAsync().findOne()
        val currentRoleType = currentRole?.type ?: "student"

        // 超级管理员可以分配任何角色
        if (currentRoleType in listOf("admin", "super_admin")) return

        // 权限层级：数字越小权限越高
        val levelMap = mapOf(
            "school_admin" to 1,
            "college_admin" to 2,
            "major_admin" to 3,
            "class_admin" to 4
        )

        val currentLevel = levelMap[currentRoleType]
        val targetLevel = levelMap[targetRoleType]

        if (currentLevel == null || targetLevel == null) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权分配此角色")
        }

        // 只能分配低于自己级别的角色（targetLevel > currentLevel 表示目标级别更低）
        if (targetLevel <= currentLevel) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "不能分配不低于自己级别的管理员角色")
        }
    }

    /**
     * 校验目标用户不是当前用户自己，防止自我管理。
     * @param ctx 上下文
     * @param targetUserId 目标用户ID
     * @throws ErrorResponseException 目标用户为自己时抛出 403
     */
    private suspend fun checkNotSelf(ctx: Context, targetUserId: UUID) {
        if (ctx.userId.toUuid() == targetUserId) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "不能管理自己的权限")
        }
    }

    /**
     * 获取当前用户的角色类型（返回权限最高的角色）
     * 权限层级：super_admin/admin(0) > school_admin(1) > college_admin(2) > major_admin(3) > class_admin(4)
     * 如果用户有多个角色，返回权限最高的那个
     */
    private suspend fun getCurrentUserRoleType(ctx: Context): String? {
        val roles = ctx.query<QViewUserRoleEntity>()
            .userId.eq(ctx.userId.toUuid())
            .toAsync().findList()
        
        if (roles.isEmpty()) return null
        
        // 找到权限最高的角色（level 数字最小的）
        return roles.minByOrNull { adminLevelMap[it.type] ?: Int.MAX_VALUE }?.type
    }

    /**
     * 权限层级映射：数字越小权限越高
     */
    private val adminLevelMap = mapOf(
        "super_admin" to 0,
        "admin" to 0,
        "school_admin" to 1,
        "college_admin" to 2,
        "major_admin" to 3,
        "class_admin" to 4
    )

    /**
     * 级联清理组织及其关联数据
     * 当学校/学院/专业/班级被删除时调用
     */
    private suspend fun cleanupOrganizationsAndRelations(
        ctx: Context,
        schoolId: UUID? = null,
        collegeId: UUID? = null,
        majorId: UUID? = null,
        classId: UUID? = null
    ) {
        withContext(Dispatchers.IO) {
            // 1. 查找受影响的组织
            val orgs = ctx.query<QAlkOrganization> {
                deleted.ne(true)
                if (schoolId != null) this.schoolId.eq(schoolId)
                if (collegeId != null) this.collegeId.eq(collegeId)
                if (majorId != null) this.majorId.eq(majorId)
                if (classId != null) this.classId.eq(classId)
            }.findList()

            if (orgs.isEmpty()) return@withContext

            val orgIds = orgs.map { it.id }

            // 2. 删除组织成员
            ctx.query<QAlkOrganizationMember> {
                organizationId.`in`(orgIds)
                deleted.ne(true)
            }.findList().forEach { it.delete() }

            // 3. 删除组织管理员分配
            if (schoolId != null) {
                ctx.query<QAlkSchoolAdmin> {
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.findList().forEach { it.delete() }
            }
            if (collegeId != null) {
                ctx.query<QAlkCollegeAdmin> {
                    this.collegeId.eq(collegeId)
                    deleted.ne(true)
                }.findList().forEach { it.delete() }
            }
            if (majorId != null) {
                ctx.query<QAlkMajorAdmin> {
                    this.majorId.eq(majorId)
                    deleted.ne(true)
                }.findList().forEach { it.delete() }
            }
            if (classId != null) {
                ctx.query<QAlkClassAdmin> {
                    this.classId.eq(classId)
                    deleted.ne(true)
                }.findList().forEach { it.delete() }
            }

            // 4. 删除关联群聊及其成员
            if (schoolId != null) {
                ctx.query<QAlkGroupConversation> {
                    scopeType.eq("school")
                    scopeId.eq(schoolId)
                    deleted.ne(true)
                }.findList().forEach { conv ->
                    ctx.query<QAlkGroupConversationMember> {
                        groupConversationId.eq(conv.id)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    conv.delete()
                }
            }
            if (collegeId != null) {
                ctx.query<QAlkGroupConversation> {
                    scopeType.eq("college")
                    scopeId.eq(collegeId)
                    deleted.ne(true)
                }.findList().forEach { conv ->
                    ctx.query<QAlkGroupConversationMember> {
                        groupConversationId.eq(conv.id)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    conv.delete()
                }
            }
            if (majorId != null) {
                ctx.query<QAlkGroupConversation> {
                    scopeType.eq("major")
                    scopeId.eq(majorId)
                    deleted.ne(true)
                }.findList().forEach { conv ->
                    ctx.query<QAlkGroupConversationMember> {
                        groupConversationId.eq(conv.id)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    conv.delete()
                }
            }
            if (classId != null) {
                ctx.query<QAlkGroupConversation> {
                    scopeType.eq("class")
                    scopeId.eq(classId)
                    deleted.ne(true)
                }.findList().forEach { conv ->
                    ctx.query<QAlkGroupConversationMember> {
                        groupConversationId.eq(conv.id)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    conv.delete()
                }
            }

            // 5. 软删除组织本身
            orgs.forEach { org ->
                org.deleted = true
                org.status = 2
                org.save()
            }
        }
    }

    /**
     * 级联删除子实体
     * 当删除学校/学院/专业时，同步删除下级实体及其管理员分配
     */
    private suspend fun cascadeDeleteChildren(
        ctx: Context,
        schoolId: UUID? = null,
        collegeId: UUID? = null,
        majorId: UUID? = null
    ) {
        withContext(Dispatchers.IO) {
            if (schoolId != null) {
                // 查学校下的所有学院
                val colleges = ctx.query<QAlkCollege> {
                    this.schoolId.eq(schoolId)
                    deleted.ne(true)
                }.findList()
                // 收集所有学院ID，用于清理学院管理员
                val collegeIds = colleges.map { it.id }
                if (collegeIds.isNotEmpty()) {
                    ctx.query<QAlkCollegeAdmin> {
                        this.collegeId.`in`(collegeIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }

                // 查这些学院下的所有专业
                val majors = if (collegeIds.isNotEmpty()) {
                    ctx.query<QAlkMajor> {
                        this.collegeId.`in`(collegeIds)
                        deleted.ne(true)
                    }.findList()
                } else emptyList()
                val majorIds = majors.map { it.id }
                if (majorIds.isNotEmpty()) {
                    ctx.query<QAlkMajorAdmin> {
                        this.majorId.`in`(majorIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }

                // 查这些专业下的所有班级
                val classIds = if (majorIds.isNotEmpty()) {
                    ctx.query<QAlkClass> {
                        this.majorId.`in`(majorIds)
                        deleted.ne(true)
                    }.findList().map { it.id }
                } else emptyList()
                if (classIds.isNotEmpty()) {
                    ctx.query<QAlkClassAdmin> {
                        this.classId.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    // 删除班级
                    ctx.query<QAlkClass> {
                        this.id.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }
                // 删除专业
                majors.forEach { it.delete() }
                // 删除学院
                colleges.forEach { it.delete() }
            } else if (collegeId != null) {
                // 查学院下的所有专业
                val majors = ctx.query<QAlkMajor> {
                    this.collegeId.eq(collegeId)
                    deleted.ne(true)
                }.findList()
                val majorIds = majors.map { it.id }
                if (majorIds.isNotEmpty()) {
                    ctx.query<QAlkMajorAdmin> {
                        this.majorId.`in`(majorIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }

                // 查这些专业下的所有班级
                val classIds = if (majorIds.isNotEmpty()) {
                    ctx.query<QAlkClass> {
                        this.majorId.`in`(majorIds)
                        deleted.ne(true)
                    }.findList().map { it.id }
                } else emptyList()
                if (classIds.isNotEmpty()) {
                    ctx.query<QAlkClassAdmin> {
                        this.classId.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    ctx.query<QAlkClass> {
                        this.id.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }
                // 删除专业
                majors.forEach { it.delete() }
            } else if (majorId != null) {
                // 查专业下的所有班级
                val classIds = ctx.query<QAlkClass> {
                    this.majorId.eq(majorId)
                    deleted.ne(true)
                }.findList().map { it.id }
                if (classIds.isNotEmpty()) {
                    ctx.query<QAlkClassAdmin> {
                        this.classId.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                    ctx.query<QAlkClass> {
                        this.id.`in`(classIds)
                        deleted.ne(true)
                    }.findList().forEach { it.delete() }
                }
            }
        }
    }

    /**
     * 检查当前用户是否有权管理指定范围的组织
     * 支持多角色：只要在任意级别有权限即可
     *
     * @param ctx 上下文
     * @param schoolId 目标学校ID（可选）
     * @param collegeId 目标学院ID（可选）
     * @param majorId 目标专业ID（可选）
     * @param classId 目标班级ID（可选）
     * @param minLevel 最低可操作级别（如 "college_admin" 表示至少能操作到院级）
     */
    private suspend fun requireManagePermission(
        ctx: Context,
        schoolId: UUID? = null,
        collegeId: UUID? = null,
        majorId: UUID? = null,
        classId: UUID? = null,
        minLevel: String? = null
    ) {
        val currentUserId = ctx.userId.toUuid()
        val roleType = getCurrentUserRoleType(ctx)
        val currentLevel = adminLevelMap[roleType]

        if (roleType == null || currentLevel == null) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权管理组织")
        }

        // 超级管理员/教师拥有全部权限
        if (currentLevel == 0) return

        // 收集用户所有的管理员分配（所有级别）
        val mySchoolAdminIds = ctx.query<QAlkSchoolAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.schoolId }.toSet()

        val myCollegeAdminIds = ctx.query<QAlkCollegeAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.collegeId }.toSet()

        val myMajorAdminIds = ctx.query<QAlkMajorAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.majorId }.toSet()

        val myClassAdminIds = ctx.query<QAlkClassAdmin> {
            userId.eq(currentUserId)
            deleted.ne(true)
        }.toAsync().findList().map { it.classId }.toSet()

        // 如果没有指定任何目标，检查用户是否有任何管理员权限
        if (schoolId == null && collegeId == null && majorId == null && classId == null) {
            if (mySchoolAdminIds.isEmpty() && myCollegeAdminIds.isEmpty() && 
                myMajorAdminIds.isEmpty() && myClassAdminIds.isEmpty()) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "您没有任何管理权限")
            }
            return
        }

        // 获取目标的完整层级信息
        var targetSchoolId = schoolId
        var targetCollegeId = collegeId
        var targetMajorId = majorId
        var targetClassId = classId

        // 如果指定了 classId，获取其层级信息
        if (classId != null) {
            val clazz = ctx.query<QAlkClass>().id.eq(classId).deleted.ne(true).toAsync().findOne()
                ?: throw NoSuchElementException("班级不存在")
            targetClassId = clazz.id
            targetMajorId = targetMajorId ?: clazz.majorId
            targetCollegeId = targetCollegeId ?: clazz.collegeId
            targetSchoolId = targetSchoolId ?: clazz.schoolId
        }

        // 如果指定了 majorId，获取其层级信息
        if (majorId != null && targetClassId == null) {
            val major = ctx.query<QAlkMajor>().id.eq(majorId).deleted.ne(true).toAsync().findOne()
                ?: throw NoSuchElementException("专业不存在")
            targetMajorId = major.id
            targetCollegeId = targetCollegeId ?: major.collegeId
            targetSchoolId = targetSchoolId ?: major.schoolId
        }

        // 如果指定了 collegeId，获取其层级信息
        if (collegeId != null && targetMajorId == null && targetClassId == null) {
            val college = ctx.query<QAlkCollege>().id.eq(collegeId).deleted.ne(true).toAsync().findOne()
                ?: throw NoSuchElementException("学院不存在")
            targetCollegeId = college.id
            targetSchoolId = targetSchoolId ?: college.schoolId
        }

        // 检查最低级别限制
        if (minLevel != null) {
            val minLevelValue = adminLevelMap[minLevel] ?: 0
            val targetLevel = when {
                targetSchoolId != null && targetSchoolId in mySchoolAdminIds -> 1
                targetCollegeId != null && targetCollegeId in myCollegeAdminIds -> 2
                targetMajorId != null && targetMajorId in myMajorAdminIds -> 3
                targetClassId != null && targetClassId in myClassAdminIds -> 4
                else -> Int.MAX_VALUE
            }
            if (targetLevel < minLevelValue) {
                throw ErrorResponseException(HttpStatus.FORBIDDEN, "您的权限不足以执行此操作")
            }
        }

        // 检查用户是否有权管理目标（在任意级别有权限即可）
        val hasPermission = when {
            targetSchoolId != null && targetSchoolId in mySchoolAdminIds -> true
            targetCollegeId != null && targetCollegeId in myCollegeAdminIds -> true
            targetMajorId != null && targetMajorId in myMajorAdminIds -> true
            targetClassId != null && targetClassId in myClassAdminIds -> true
            else -> false
        }

        if (!hasPermission) {
            throw ErrorResponseException(HttpStatus.FORBIDDEN, "无权管理该组织")
        }
    }

    /**
     * 分配用户为学校管理员。不可分配给自己，且当前用户需具备分配该级别的权限。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param schoolId 学校ID
     * @return 新建或已存在的学校管理员记录
     */
    suspend fun assignSchoolAdmin(ctx: Context, userId: UUID, schoolId: UUID): AlkSchoolAdmin {
        checkNotSelf(ctx, userId)
        checkAssignPermission(ctx, "school_admin")
        val existing = ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(userId)
            this.schoolId.eq(schoolId)
            deleted.ne(true)
        }.toAsync().findOne()
        if (existing != null) return existing

        val admin = ctx.createModel<AlkSchoolAdmin> {
            id = UUID.randomUUID()
            this.userId = userId
            this.schoolId = schoolId
        }
        withContext(Dispatchers.IO) {
            admin.save()
        }
        return admin
    }

    /**
     * 移除用户的学校管理员权限。不可操作自己。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param schoolId 学校ID
     */
    suspend fun removeSchoolAdmin(ctx: Context, userId: UUID, schoolId: UUID) {
        checkNotSelf(ctx, userId)
        ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(userId)
            this.schoolId.eq(schoolId)
            deleted.ne(true)
        }.toAsync().delete()
    }

    /**
     * 列出指定学院的管理员列表。
     *
     * @param ctx 请求上下文
     * @param collegeId 学院ID
     * @return 学院管理员列表
     */
    suspend fun listCollegeAdmins(ctx: Context, collegeId: UUID): List<AlkCollegeAdmin> {
        return ctx.query<QAlkCollegeAdmin> {
            this.collegeId.eq(collegeId)
            deleted.ne(true)
        }.toAsync().findList()
    }

    /**
     * 分配用户为学院管理员。不可分配给自己，且当前用户需具备分配该级别的权限。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param collegeId 学院ID
     * @return 新建或已存在的学院管理员记录
     */
    suspend fun assignCollegeAdmin(ctx: Context, userId: UUID, collegeId: UUID): AlkCollegeAdmin {
        checkNotSelf(ctx, userId)
        checkAssignPermission(ctx, "college_admin")
        val existing = ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(userId)
            this.collegeId.eq(collegeId)
            deleted.ne(true)
        }.toAsync().findOne()
        if (existing != null) return existing

        val admin = ctx.createModel<AlkCollegeAdmin> {
            id = UUID.randomUUID()
            this.userId = userId
            this.collegeId = collegeId
        }
        withContext(Dispatchers.IO) {
            admin.save()
        }
        return admin
    }

    /**
     * 移除用户的学院管理员权限。不可操作自己。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param collegeId 学院ID
     */
    suspend fun removeCollegeAdmin(ctx: Context, userId: UUID, collegeId: UUID) {
        checkNotSelf(ctx, userId)
        ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(userId)
            this.collegeId.eq(collegeId)
            deleted.ne(true)
        }.toAsync().delete()
    }

    /**
     * 列出指定专业的管理员列表。
     *
     * @param ctx 请求上下文
     * @param majorId 专业ID
     * @return 专业管理员列表
     */
    suspend fun listMajorAdmins(ctx: Context, majorId: UUID): List<AlkMajorAdmin> {
        return ctx.query<QAlkMajorAdmin> {
            this.majorId.eq(majorId)
            deleted.ne(true)
        }.toAsync().findList()
    }

    /**
     * 分配用户为专业管理员。不可分配给自己，且当前用户需具备分配该级别的权限。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param majorId 专业ID
     * @return 新建或已存在的专业管理员记录
     */
    suspend fun assignMajorAdmin(ctx: Context, userId: UUID, majorId: UUID): AlkMajorAdmin {
        checkNotSelf(ctx, userId)
        checkAssignPermission(ctx, "major_admin")
        val existing = ctx.query<QAlkMajorAdmin> {
            this.userId.eq(userId)
            this.majorId.eq(majorId)
            deleted.ne(true)
        }.toAsync().findOne()
        if (existing != null) return existing

        val admin = ctx.createModel<AlkMajorAdmin> {
            id = UUID.randomUUID()
            this.userId = userId
            this.majorId = majorId
        }
        withContext(Dispatchers.IO) {
            admin.save()
        }
        return admin
    }

    /**
     * 移除用户的专业管理员权限。不可操作自己。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param majorId 专业ID
     */
    suspend fun removeMajorAdmin(ctx: Context, userId: UUID, majorId: UUID) {
        checkNotSelf(ctx, userId)
        ctx.query<QAlkMajorAdmin> {
            this.userId.eq(userId)
            this.majorId.eq(majorId)
            deleted.ne(true)
        }.toAsync().delete()
    }

    /**
     * 列出指定班级的管理员列表。
     *
     * @param ctx 请求上下文
     * @param classId 班级ID
     * @return 班级管理员列表
     */
    suspend fun listClassAdmins(ctx: Context, classId: UUID): List<AlkClassAdmin> {
        return ctx.query<QAlkClassAdmin> {
            this.classId.eq(classId)
            deleted.ne(true)
        }.toAsync().findList()
    }

    /**
     * 分配用户为班级管理员。不可分配给自己，且当前用户需具备分配该级别的权限。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param classId 班级ID
     * @return 新建或已存在的班级管理员记录
     */
    suspend fun assignClassAdmin(ctx: Context, userId: UUID, classId: UUID): AlkClassAdmin {
        checkNotSelf(ctx, userId)
        checkAssignPermission(ctx, "class_admin")
        val existing = ctx.query<QAlkClassAdmin> {
            this.userId.eq(userId)
            this.classId.eq(classId)
            deleted.ne(true)
        }.toAsync().findOne()
        if (existing != null) return existing

        val admin = ctx.createModel<AlkClassAdmin> {
            id = UUID.randomUUID()
            this.userId = userId
            this.classId = classId
        }
        withContext(Dispatchers.IO) {
            admin.save()
        }
        return admin
    }

    /**
     * 移除用户的班级管理员权限。不可操作自己。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @param classId 班级ID
     */
    suspend fun removeClassAdmin(ctx: Context, userId: UUID, classId: UUID) {
        checkNotSelf(ctx, userId)
        ctx.query<QAlkClassAdmin> {
            this.userId.eq(userId)
            this.classId.eq(classId)
            deleted.ne(true)
        }.toAsync().delete()
    }

    /**
     * 清除用户的所有管理员分配（学校/学院/专业/班级级别）。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     */
    suspend fun clearAllAdminAssignments(ctx: Context, userId: UUID) {
        ctx.query<QAlkSchoolAdmin> { this.userId.eq(userId) }.toAsync().delete()
        ctx.query<QAlkCollegeAdmin> { this.userId.eq(userId) }.toAsync().delete()
        ctx.query<QAlkMajorAdmin> { this.userId.eq(userId) }.toAsync().delete()
        ctx.query<QAlkClassAdmin> { this.userId.eq(userId) }.toAsync().delete()
    }

    /**
     * 获取用户的所有管理员分配记录，包含学校/学院/专业/班级级别及关联信息。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @return 管理员分配列表，每项包含 type、ID 和名称等信息
     */
    suspend fun getUserAdminAssignments(ctx: Context, userId: UUID): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()

        ctx.query<QAlkSchoolAdmin> {
            this.userId.eq(userId)
            deleted.ne(true)
        }.toAsync().findList().forEach { admin ->
            ctx.query<QAlkSchool>().id.eq(admin.schoolId).deleted.ne(true).toAsync().findOne()?.let { school ->
                result.add(mapOf(
                    "type" to "school_admin",
                    "schoolId" to school.id.toString(),
                    "schoolName" to school.name
                ))
            }
        }

        ctx.query<QAlkCollegeAdmin> {
            this.userId.eq(userId)
            deleted.ne(true)
        }.toAsync().findList().forEach { admin ->
            ctx.query<QAlkCollege>().id.eq(admin.collegeId).deleted.ne(true).toAsync().findOne()?.let { college ->
                val entry = mutableMapOf(
                    "type" to "college_admin",
                    "collegeId" to college.id.toString(),
                    "collegeName" to college.name,
                    "schoolId" to college.schoolId.toString()
                )
                ctx.query<QAlkSchool>().id.eq(college.schoolId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["schoolName"] = it.name
                }
                result.add(entry)
            }
        }

        ctx.query<QAlkMajorAdmin> {
            this.userId.eq(userId)
            deleted.ne(true)
        }.toAsync().findList().forEach { admin ->
            ctx.query<QAlkMajor>().id.eq(admin.majorId).deleted.ne(true).toAsync().findOne()?.let { major ->
                val entry = mutableMapOf(
                    "type" to "major_admin",
                    "majorId" to major.id.toString(),
                    "majorName" to major.name,
                    "collegeId" to major.collegeId.toString(),
                    "schoolId" to major.schoolId.toString()
                )
                ctx.query<QAlkCollege>().id.eq(major.collegeId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["collegeName"] = it.name
                }
                ctx.query<QAlkSchool>().id.eq(major.schoolId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["schoolName"] = it.name
                }
                result.add(entry)
            }
        }

        ctx.query<QAlkClassAdmin> {
            this.userId.eq(userId)
            deleted.ne(true)
        }.toAsync().findList().forEach { admin ->
            ctx.query<QAlkClass>().id.eq(admin.classId).deleted.ne(true).toAsync().findOne()?.let { clazz ->
                val entry = mutableMapOf(
                    "type" to "class_admin",
                    "classId" to clazz.id.toString(),
                    "className" to clazz.name,
                    "majorId" to clazz.majorId.toString(),
                    "collegeId" to clazz.collegeId.toString(),
                    "schoolId" to clazz.schoolId.toString()
                )
                ctx.query<QAlkMajor>().id.eq(clazz.majorId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["majorName"] = it.name
                }
                ctx.query<QAlkCollege>().id.eq(clazz.collegeId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["collegeName"] = it.name
                }
                ctx.query<QAlkSchool>().id.eq(clazz.schoolId).deleted.ne(true).toAsync().findOne()?.let {
                    entry["schoolName"] = it.name
                }
                result.add(entry)
            }
        }

        return result
    }

    // 保留旧方法兼容
    /**
     * 获取用户的首个管理员分配记录（兼容旧接口）。
     *
     * @param ctx 请求上下文
     * @param userId 目标用户ID
     * @return 管理员分配键值对，无分配时返回空Map
     */
    suspend fun getUserAdminAssignment(ctx: Context, userId: UUID): Map<String, String> {
        val assignments = getUserAdminAssignments(ctx, userId)
        val result = mutableMapOf<String, String>()
        assignments.firstOrNull()?.let { result.putAll(it) }
        return result
    }

    /**
     * 组织树节点，用于构建学校→学院→专业→班级的层级结构。
     *
     * @property id 节点ID
     * @property name 节点名称
     * @property type 节点类型（school/college/major/class）
     * @property schoolId 学校ID（可选）
     * @property collegeId 学院ID（可选）
     * @property majorId 专业ID（可选）
     * @property children 子节点列表
     */
    data class OrgTreeNode(
        var id: UUID,
        var name: String,
        var type: String,
        var schoolId: UUID? = null,
        var collegeId: UUID? = null,
        var majorId: UUID? = null,
        var children: List<OrgTreeNode> = emptyList()
    )

    /**
     * 构建完整的组织树（学校→学院→专业→班级）。
     *
     * @param ctx 请求上下文
     * @return 组织树结构列表，按学校名称升序
     */
    suspend fun getOrganizationTree(ctx: Context): List<OrgTreeNode> {
        val schools = ctx.query<QAlkSchool>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val colleges = ctx.query<QAlkCollege>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val majors = ctx.query<QAlkMajor>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val classes = ctx.query<QAlkClass>().deleted.ne(true).orderBy().name.asc().toAsync().findList()

        val collegeMap = colleges.groupBy { it.schoolId }
        val majorMap = majors.groupBy { it.collegeId }
        val classMap = classes.groupBy { it.majorId }

        return schools.map { school ->
            val schoolColleges = collegeMap[school.id]?.map { college ->
                val collegeMajors = majorMap[college.id]?.map { major ->
                    val majorClasses = classMap[major.id]?.map { clazz ->
                        OrgTreeNode(
                            id = clazz.id,
                            name = clazz.name,
                            type = "class",
                            schoolId = school.id,
                            collegeId = college.id,
                            majorId = major.id
                        )
                    } ?: emptyList()
                    OrgTreeNode(
                        id = major.id,
                        name = major.name,
                        type = "major",
                        schoolId = school.id,
                        collegeId = college.id,
                        children = majorClasses
                    )
                } ?: emptyList()
                OrgTreeNode(
                    id = college.id,
                    name = college.name,
                    type = "college",
                    schoolId = school.id,
                    children = collegeMajors
                )
            } ?: emptyList()
            OrgTreeNode(
                id = school.id,
                name = school.name,
                type = "school",
                children = schoolColleges
            )
        }
    }

    /**
     * 构建当前用户可见的组织树（仅包含用户相关的学校，含完整层级结构）。
     * 超级管理员可见全部学校及完整层级，其他用户根据管理员分配和加入的组织确定范围。
     *
     * @param ctx 请求上下文
     * @return 用户相关的组织树列表（含学校→学院→专业→班级完整层级）
     */
    suspend fun getMyOrganizationTree(ctx: Context): List<OrgTreeNode> {
        val userId = ctx.userId.toUuid()

        // 获取用户的管理员分配
        val adminAssignments = getUserAdminAssignments(ctx, userId)

        // 获取用户加入的组织
        val joinedOrgs = getMyOrganizations(ctx)

        // 收集用户相关的学校/学院/专业/班级ID
        val relatedSchoolIds = mutableSetOf<UUID>()
        val relatedCollegeIds = mutableSetOf<UUID>()
        val relatedMajorIds = mutableSetOf<UUID>()
        val relatedClassIds = mutableSetOf<UUID>()

        // 处理管理员分配（支持多级）
        adminAssignments.forEach { entry ->
            entry["schoolId"]?.let { relatedSchoolIds.add(UUID.fromString(it)) }
            entry["collegeId"]?.let { relatedCollegeIds.add(UUID.fromString(it)) }
            entry["majorId"]?.let { relatedMajorIds.add(UUID.fromString(it)) }
            entry["classId"]?.let { relatedClassIds.add(UUID.fromString(it)) }
        }

        // 处理加入的组织
        joinedOrgs.forEach { org ->
            org.schoolId?.let { relatedSchoolIds.add(it) }
            org.collegeId?.let { relatedCollegeIds.add(it) }
            org.majorId?.let { relatedMajorIds.add(it) }
            org.classId?.let { relatedClassIds.add(it) }
            // 如果 classId 为 null，尝试通过 className 从班级表查找
            if (org.classId == null && org.className != null && org.collegeId != null && org.majorId != null) {
                val clazz = ctx.query<QAlkClass> {
                    name.eq(org.className!!)
                    collegeId.eq(org.collegeId!!)
                    majorId.eq(org.majorId!!)
                    deleted.ne(true)
                }.toAsync().findList().firstOrNull()
                clazz?.id?.let { relatedClassIds.add(it) }
            }
            // 如果 majorId 为 null，尝试通过 major 名称从专业表查找
            if (org.majorId == null && org.major.isNotBlank() && org.collegeId != null) {
                val major = ctx.query<QAlkMajor> {
                    name.eq(org.major)
                    collegeId.eq(org.collegeId!!)
                    deleted.ne(true)
                }.toAsync().findList().firstOrNull()
                major?.id?.let { relatedMajorIds.add(it) }
                major?.collegeId?.let { relatedCollegeIds.add(it) }
                major?.schoolId?.let { relatedSchoolIds.add(it) }
            }
            // 如果 collegeId 为 null，尝试通过 college 名称从学院表查找
            if (org.collegeId == null && org.college.isNotBlank() && org.schoolId != null) {
                val college = ctx.query<QAlkCollege> {
                    name.eq(org.college)
                    schoolId.eq(org.schoolId!!)
                    deleted.ne(true)
                }.toAsync().findList().firstOrNull()
                college?.id?.let { relatedCollegeIds.add(it) }
                college?.schoolId?.let { relatedSchoolIds.add(it) }
            }
        }

        // 根据收集的ID，向上追溯父级ID
        relatedCollegeIds.forEach { cid ->
            ctx.query<QAlkCollege>().id.eq(cid).deleted.ne(true).toAsync().findOne()
                ?.schoolId?.let { relatedSchoolIds.add(it) }
        }
        relatedMajorIds.forEach { mid ->
            val major = ctx.query<QAlkMajor>().id.eq(mid).deleted.ne(true).toAsync().findOne()
            major?.schoolId?.let { relatedSchoolIds.add(it) }
            major?.collegeId?.let { relatedCollegeIds.add(it) }
        }
        relatedClassIds.forEach { cid ->
            val clazz = ctx.query<QAlkClass>().id.eq(cid).deleted.ne(true).toAsync().findOne()
            clazz?.schoolId?.let { relatedSchoolIds.add(it) }
            clazz?.collegeId?.let { relatedCollegeIds.add(it) }
            clazz?.majorId?.let { relatedMajorIds.add(it) }
        }

        // 检查角色类型
        val role = ctx.query<QViewUserRoleEntity>()
            .userId.eq(userId)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        val roleType = role?.type
        val isSuperAdmin = roleType in listOf("super_admin", "admin")

        // 构建完整的层级数据
        val schools = ctx.query<QAlkSchool>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val allColleges = ctx.query<QAlkCollege>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val allMajors = ctx.query<QAlkMajor>().deleted.ne(true).orderBy().name.asc().toAsync().findList()
        val allClasses = ctx.query<QAlkClass>().deleted.ne(true).orderBy().name.asc().toAsync().findList()

        val collegeMap = allColleges.groupBy { it.schoolId }
        val majorMap = allMajors.groupBy { it.collegeId }
        val classMap = allClasses.groupBy { it.majorId }

        val visibleSchools = if (isSuperAdmin) schools else schools.filter { it.id in relatedSchoolIds }

        return visibleSchools.map { school ->
            val schoolColleges = collegeMap[school.id]?.mapNotNull { college ->
                val collegeMajors = majorMap[college.id]?.mapNotNull { major ->
                    val majorClasses = classMap[major.id]?.mapNotNull { clazz ->
                        // 超级管理员或班级管理员/加入组织可见全部班级；否则仅显示关联班级
                        if (isSuperAdmin || clazz.id in relatedClassIds || joinedOrgs.any { it.classId == clazz.id }) {
                            OrgTreeNode(
                                id = clazz.id,
                                name = clazz.name,
                                type = "class",
                                schoolId = school.id,
                                collegeId = college.id,
                                majorId = major.id
                            )
                        } else null
                    }?.filterNotNull() ?: emptyList()

                    if (isSuperAdmin || major.id in relatedMajorIds || majorClasses.isNotEmpty()) {
                        OrgTreeNode(
                            id = major.id,
                            name = major.name,
                            type = "major",
                            schoolId = school.id,
                            collegeId = college.id,
                            children = majorClasses
                        )
                    } else null
                }?.filterNotNull() ?: emptyList()

                if (isSuperAdmin || college.id in relatedCollegeIds || collegeMajors.isNotEmpty()) {
                    OrgTreeNode(
                        id = college.id,
                        name = college.name,
                        type = "college",
                        schoolId = school.id,
                        children = collegeMajors
                    )
                } else null
            }?.filterNotNull() ?: emptyList()

            OrgTreeNode(
                id = school.id,
                name = school.name,
                type = "school",
                children = schoolColleges
            )
        }
    }

    /**
     * 层级搜索：按关键字搜索指定类型的组织实体。
     * 
     * @param ctx 请求上下文
     * @param level 搜索级别: school/college/major/class
     * @param parentId 父级ID（学院需要schoolId，专业需要collegeId，班级需要majorId）
     * @param keyword 搜索关键字
     * @return 搜索结果列表，每项包含 id、name 字段
     */
    suspend fun searchOrganizations(
        ctx: Context,
        level: String,
        parentId: UUID? = null,
        keyword: String = ""
    ): List<Map<String, String>> {
        val result = mutableListOf<Map<String, String>>()
        val kw = keyword.trim()
        
        if (level.isBlank()) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "搜索级别不能为空")
        }
        
        when (level.lowercase()) {
            "school" -> {
                result.addAll(ctx.query<QAlkSchool> {
                    deleted.ne(true)
                    if (kw.isNotBlank()) name.contains(kw)
                    orderBy().name.asc()
                }.toAsync().findList().map { mapOf("id" to it.id.toString(), "name" to it.name) })
            }
            "college" -> {
                result.addAll(ctx.query<QAlkCollege> {
                    deleted.ne(true)
                    if (parentId != null) schoolId.eq(parentId)
                    if (kw.isNotBlank()) name.contains(kw)
                    orderBy().name.asc()
                }.toAsync().findList().map { mapOf("id" to it.id.toString(), "name" to it.name) })
            }
            "major" -> {
                result.addAll(ctx.query<QAlkMajor> {
                    deleted.ne(true)
                    if (parentId != null) collegeId.eq(parentId)
                    if (kw.isNotBlank()) name.contains(kw)
                    orderBy().name.asc()
                }.toAsync().findList().map { mapOf("id" to it.id.toString(), "name" to it.name) })
            }
            "class" -> {
                result.addAll(ctx.query<QAlkClass> {
                    deleted.ne(true)
                    if (parentId != null) majorId.eq(parentId)
                    if (kw.isNotBlank()) name.contains(kw)
                    orderBy().name.asc()
                }.toAsync().findList().map { mapOf("id" to it.id.toString(), "name" to it.name) })
            }
            "grade" -> {
                // 年级从 alk_organization 表中获取（去重）
                val grades = ctx.query<QAlkOrganization> {
                    deleted.ne(true)
                    status.eq(1)
                    if (kw.isNotBlank()) grade.contains(kw)
                }.toAsync().findList()
                    .map { it.grade }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .sorted()
                result.addAll(grades.mapIndexed { index, grade -> 
                    mapOf("id" to "grade_$index", "name" to grade) 
                })
            }
        }
        
        return result
    }
}