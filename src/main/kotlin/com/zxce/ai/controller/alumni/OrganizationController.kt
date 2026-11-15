package com.zxce.ai.controller.alumni

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.model.alumni.AlkClass
import com.zxce.ai.model.alumni.AlkClassAdmin
import com.zxce.ai.model.alumni.AlkCollege
import com.zxce.ai.model.alumni.AlkCollegeAdmin
import com.zxce.ai.model.alumni.AlkMajor
import com.zxce.ai.model.alumni.AlkMajorAdmin
import com.zxce.ai.model.alumni.AlkOrganization
import com.zxce.ai.model.alumni.AlkSchool
import com.zxce.ai.model.alumni.AlkSchoolAdmin
import com.zxce.ai.service.alumni.OrganizationService
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

data class BatchOrgItem(
    var grade: String = "",
    var college: String = "",
    var major: String = "",
    var className: String = ""
)

/**
 * 组织架构控制器
 *
 * 提供校友组织架构管理功能，包括组织查找、申请创建、加入、审核，以及学校、学院、专业、班级的四级组织管理和管理员分配等功能。
 */
@HostForward
@RestController
@RequestMapping("/api/organization")
class OrganizationController {

    @Autowired
    lateinit var organizationService: OrganizationService

    data class FindOrgParam(
        var schoolId: UUID? = null,
        var grade: String = "",
        var college: String = "",
        var major: String = "",
        var className: String = ""
    )

    /**
     * 查找组织
     *
     * @param context 当前会话上下文
     * @param param 包含 schoolId（学校ID）、grade（年级）、college（学院）、major（专业）、className（班级名称）
     * @return 找到的组织信息，未找到则返回null
     * HTTP方法: POST
     */
    @PostMapping("/find")
    suspend fun findOrganization(context: Context, @RequestBody param: FindOrgParam): AlkOrganization? {
        return organizationService.findOrganization(
            context,
            param.schoolId!!,
            param.grade,
            param.college,
            param.major,
            param.className
        )
    }

    /**
     * 申请创建组织
     *
     * @param context 当前会话上下文
     * @param param 包含 schoolId（学校ID）、grade（年级）、college（学院）、major（专业）、className（班级名称）
     * @return 创建的组织信息
     * HTTP方法: POST
     */
    @PostMapping("/apply")
    suspend fun applyCreateOrganization(context: Context, @RequestBody param: FindOrgParam): AlkOrganization {
        return organizationService.applyCreateOrganization(
            context,
            param.schoolId!!,
            param.grade,
            param.college,
            param.major,
            param.className
        )
    }

    /**
     * 加入指定组织
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/{id}/join")
    suspend fun joinOrganization(context: Context, @PathVariable id: UUID): Map<String, Any> {
        organizationService.joinOrganization(context, id)
        return mapOf("success" to true, "message" to "加入成功")
    }

    /**
     * 获取当前用户在指定组织中的成员状态
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 包含 status（成员状态）的结果
     * HTTP方法: POST
     */
    @PostMapping("/{id}/memberStatus")
    suspend fun getMemberStatus(context: Context, @PathVariable id: UUID): Map<String, Any> {
        val status = organizationService.getMemberStatus(context, id)
        return mapOf("status" to status)
    }

    /**
     * 获取当前用户所在的组织列表
     *
     * @param context 当前会话上下文
     * @return 当前用户所在的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/my")
    suspend fun getMyOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.getMyOrganizations(context)
    }

    /**
     * 获取所有组织列表
     *
     * @param context 当前会话上下文
     * @return 所有组织列表
     * HTTP方法: POST
     */
    @PostMapping("/listAll")
    suspend fun listAllOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.listAllOrganizations(context)
    }

    /**
     * 获取待审核的组织列表
     *
     * @param context 当前会话上下文
     * @return 待审核的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/pending")
    suspend fun listPendingOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.listPendingOrganizations(context)
    }

    /**
     * 获取已审核通过的组织列表
     *
     * @param context 当前会话上下文
     * @return 已审核通过的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/approved")
    suspend fun listApprovedOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.listApprovedOrganizations(context)
    }

    /**
     * 获取当前用户申请的组织列表
     *
     * @param context 当前会话上下文
     * @return 当前用户申请的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/myApplied")
    suspend fun listMyAppliedOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.listMyAppliedOrganizations(context)
    }

    /**
     * 获取当前用户管理的组织列表
     *
     * @param context 当前会话上下文
     * @return 当前用户管理的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/myOrganizations")
    suspend fun listMyOrganizations(context: Context): List<AlkOrganization> {
        return organizationService.listMyOrganizations(context)
    }

    /**
     * 审核通过指定组织
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/{id}/approve")
    suspend fun approveOrganization(context: Context, @PathVariable id: UUID): Map<String, Any> {
        organizationService.approveOrganization(context, id)
        return mapOf("success" to true, "message" to "审核通过")
    }

    /**
     * 拒绝指定组织的审核
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/{id}/reject")
    suspend fun rejectOrganization(context: Context, @PathVariable id: UUID): Map<String, Any> {
        organizationService.rejectOrganization(context, id)
        return mapOf("success" to true, "message" to "已拒绝")
    }

    data class BatchCreateOrgParam(
        var schoolId: UUID? = null,
        var items: List<BatchOrgItem> = emptyList()
    )

    /**
     * 批量创建组织
     *
     * @param context 当前会话上下文
     * @param param 包含 schoolId（学校ID）、items（批量创建项列表）
     * @return 创建的组织列表
     * HTTP方法: POST
     */
    @PostMapping("/batchCreate")
    suspend fun batchCreateOrganizations(context: Context, @RequestBody param: BatchCreateOrgParam): List<AlkOrganization> {
        return organizationService.batchCreateOrganizations(context, param.schoolId!!, param.items)
    }

    /**
     * 获取指定组织的成员列表
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 组织成员列表
     * HTTP方法: POST
     */
    @PostMapping("/{id}/members")
    suspend fun listMembers(context: Context, @PathVariable id: UUID): List<Bundle> {
        return organizationService.listMembers(context, id)
    }

    // ============================================================
    // 学校管理（超级管理员/校级管理员）
    // ============================================================

    data class SchoolParam(
        var id: UUID? = null,
        var name: String = ""
    )

    /**
     * 获取学校列表
     *
     * @param context 当前会话上下文
     * @return 学校列表
     * HTTP方法: POST
     */
    @PostMapping("/school/list")
    suspend fun listSchools(context: Context): List<AlkSchool> {
        return organizationService.listSchools(context)
    }

    /**
     * 创建学校
     *
     * @param context 当前会话上下文
     * @param param 包含 name（学校名称）
     * @return 创建的学校信息
     * HTTP方法: POST
     */
    @PostMapping("/school/create")
    suspend fun createSchool(context: Context, @RequestBody param: SchoolParam): AlkSchool {
        return organizationService.createSchool(context, param.name)
    }

    /**
     * 更新学校信息
     *
     * @param context 当前会话上下文
     * @param param 包含 id（学校ID）、name（学校名称）
     * @return 更新后的学校信息
     * HTTP方法: POST
     */
    @PostMapping("/school/update")
    suspend fun updateSchool(context: Context, @RequestBody param: SchoolParam): AlkSchool {
        return organizationService.updateSchool(context, param.id!!, param.name)
    }

    /**
     * 删除学校
     *
     * @param context 当前会话上下文
     * @param param 包含 id（学校ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/school/delete")
    suspend fun deleteSchool(context: Context, @RequestBody param: Map<String, UUID>): Map<String, Any> {
        organizationService.deleteSchool(context, param["id"]!!)
        return mapOf("success" to true, "message" to "删除成功")
    }

    // ============================================================
    // 学院管理（超级管理员/校级管理员）
    // ============================================================

    data class CollegeParam(
        var id: UUID? = null,
        var name: String = "",
        var schoolId: UUID? = null
    )

    /**
     * 获取学院列表
     *
     * @param context 当前会话上下文
     * @param param 包含 schoolId（可选，学校ID筛选）
     * @return 学院列表
     * HTTP方法: POST
     */
    @PostMapping("/college/list")
    suspend fun listColleges(context: Context, @RequestBody param: Map<String, UUID?>): List<AlkCollege> {
        return organizationService.listColleges(context, param["schoolId"])
    }

    /**
     * 创建学院
     *
     * @param context 当前会话上下文
     * @param param 包含 name（学院名称）、schoolId（所属学校ID）
     * @return 创建的学院信息
     * HTTP方法: POST
     */
    @PostMapping("/college/create")
    suspend fun createCollege(context: Context, @RequestBody param: CollegeParam): AlkCollege {
        return organizationService.createCollege(context, param.name, param.schoolId!!)
    }

    /**
     * 更新学院信息
     *
     * @param context 当前会话上下文
     * @param param 包含 id（学院ID）、name（学院名称）
     * @return 更新后的学院信息
     * HTTP方法: POST
     */
    @PostMapping("/college/update")
    suspend fun updateCollege(context: Context, @RequestBody param: CollegeParam): AlkCollege {
        return organizationService.updateCollege(context, param.id!!, param.name)
    }

    /**
     * 删除学院
     *
     * @param context 当前会话上下文
     * @param param 包含 id（学院ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/college/delete")
    suspend fun deleteCollege(context: Context, @RequestBody param: Map<String, UUID>): Map<String, Any> {
        organizationService.deleteCollege(context, param["id"]!!)
        return mapOf("success" to true, "message" to "删除成功")
    }

    // ============================================================
    // 专业管理（超级管理员/校级管理员/学院管理员）
    // ============================================================

    data class MajorParam(
        var id: UUID? = null,
        var name: String = "",
        var collegeId: UUID? = null,
        var schoolId: UUID? = null
    )

    /**
     * 获取专业列表
     *
     * @param context 当前会话上下文
     * @param param 包含 collegeId（可选，学院ID筛选）
     * @return 专业列表
     * HTTP方法: POST
     */
    @PostMapping("/major/list")
    suspend fun listMajors(context: Context, @RequestBody param: Map<String, UUID?>): List<AlkMajor> {
        return organizationService.listMajors(context, param["collegeId"])
    }

    /**
     * 创建专业
     *
     * @param context 当前会话上下文
     * @param param 包含 name（专业名称）、collegeId（所属学院ID）、schoolId（所属学校ID）
     * @return 创建的专业信息
     * HTTP方法: POST
     */
    @PostMapping("/major/create")
    suspend fun createMajor(context: Context, @RequestBody param: MajorParam): AlkMajor {
        return organizationService.createMajor(context, param.name, param.collegeId!!, param.schoolId!!)
    }

    /**
     * 更新专业信息
     *
     * @param context 当前会话上下文
     * @param param 包含 id（专业ID）、name（专业名称）
     * @return 更新后的专业信息
     * HTTP方法: POST
     */
    @PostMapping("/major/update")
    suspend fun updateMajor(context: Context, @RequestBody param: MajorParam): AlkMajor {
        return organizationService.updateMajor(context, param.id!!, param.name)
    }

    /**
     * 删除专业
     *
     * @param context 当前会话上下文
     * @param param 包含 id（专业ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/major/delete")
    suspend fun deleteMajor(context: Context, @RequestBody param: Map<String, UUID>): Map<String, Any> {
        organizationService.deleteMajor(context, param["id"]!!)
        return mapOf("success" to true, "message" to "删除成功")
    }

    // ============================================================
    // 班级管理（超级管理员/校级管理员/学院管理员/专业管理员）
    // ============================================================

    data class ClassParam(
        var id: UUID? = null,
        var name: String = "",
        var majorId: UUID? = null,
        var collegeId: UUID? = null,
        var schoolId: UUID? = null
    )

    /**
     * 获取班级列表
     *
     * @param context 当前会话上下文
     * @param param 包含 majorId（可选，专业ID筛选）
     * @return 班级列表
     * HTTP方法: POST
     */
    @PostMapping("/class/list")
    suspend fun listClasses(context: Context, @RequestBody param: Map<String, UUID?>): List<AlkClass> {
        return organizationService.listClasses(context, param["majorId"])
    }

    /**
     * 创建班级
     *
     * @param context 当前会话上下文
     * @param param 包含 name（班级名称）、majorId（所属专业ID）、collegeId（所属学院ID）、schoolId（所属学校ID）
     * @return 创建的班级信息
     * HTTP方法: POST
     */
    @PostMapping("/class/create")
    suspend fun createClass(context: Context, @RequestBody param: ClassParam): AlkClass {
        return organizationService.createClass(context, param.name, param.majorId!!, param.collegeId!!, param.schoolId!!)
    }

    /**
     * 更新班级信息
     *
     * @param context 当前会话上下文
     * @param param 包含 id（班级ID）、name（班级名称）
     * @return 更新后的班级信息
     * HTTP方法: POST
     */
    @PostMapping("/class/update")
    suspend fun updateClass(context: Context, @RequestBody param: ClassParam): AlkClass {
        return organizationService.updateClass(context, param.id!!, param.name)
    }

    /**
     * 删除班级
     *
     * @param context 当前会话上下文
     * @param param 包含 id（班级ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/class/delete")
    suspend fun deleteClass(context: Context, @RequestBody param: Map<String, UUID>): Map<String, Any> {
        organizationService.deleteClass(context, param["id"]!!)
        return mapOf("success" to true, "message" to "删除成功")
    }

    // ============================================================
    // 管理员分配
    // ============================================================

    data class AdminAssignParam(
        var userId: UUID? = null,
        var schoolId: UUID? = null,
        var collegeId: UUID? = null,
        var majorId: UUID? = null,
        var classId: UUID? = null
    )

    /**
     * 获取校级管理员列表
     *
     * @param context 当前会话上下文
     * @param param 包含 schoolId（学校ID）
     * @return 校级管理员列表
     * HTTP方法: POST
     */
    @PostMapping("/admin/school/list")
    suspend fun listSchoolAdmins(context: Context, @RequestBody param: Map<String, UUID>): List<AlkSchoolAdmin> {
        return organizationService.listSchoolAdmins(context, param["schoolId"]!!)
    }

    /**
     * 分配校级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、schoolId（学校ID）
     * @return 创建的校级管理员记录
     * HTTP方法: POST
     */
    @PostMapping("/admin/school/assign")
    suspend fun assignSchoolAdmin(context: Context, @RequestBody param: AdminAssignParam): AlkSchoolAdmin {
        return organizationService.assignSchoolAdmin(context, param.userId!!, param.schoolId!!)
    }

    /**
     * 移除校级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、schoolId（学校ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/admin/school/remove")
    suspend fun removeSchoolAdmin(context: Context, @RequestBody param: AdminAssignParam): Map<String, Any> {
        organizationService.removeSchoolAdmin(context, param.userId!!, param.schoolId!!)
        return mapOf("success" to true, "message" to "移除成功")
    }

    /**
     * 获取学院级管理员列表
     *
     * @param context 当前会话上下文
     * @param param 包含 collegeId（学院ID）
     * @return 学院级管理员列表
     * HTTP方法: POST
     */
    @PostMapping("/admin/college/list")
    suspend fun listCollegeAdmins(context: Context, @RequestBody param: Map<String, UUID>): List<AlkCollegeAdmin> {
        return organizationService.listCollegeAdmins(context, param["collegeId"]!!)
    }

    /**
     * 分配学院级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、collegeId（学院ID）
     * @return 创建的学院级管理员记录
     * HTTP方法: POST
     */
    @PostMapping("/admin/college/assign")
    suspend fun assignCollegeAdmin(context: Context, @RequestBody param: AdminAssignParam): AlkCollegeAdmin {
        return organizationService.assignCollegeAdmin(context, param.userId!!, param.collegeId!!)
    }

    /**
     * 移除学院级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、collegeId（学院ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/admin/college/remove")
    suspend fun removeCollegeAdmin(context: Context, @RequestBody param: AdminAssignParam): Map<String, Any> {
        organizationService.removeCollegeAdmin(context, param.userId!!, param.collegeId!!)
        return mapOf("success" to true, "message" to "移除成功")
    }

    /**
     * 获取专业级管理员列表
     *
     * @param context 当前会话上下文
     * @param param 包含 majorId（专业ID）
     * @return 专业级管理员列表
     * HTTP方法: POST
     */
    @PostMapping("/admin/major/list")
    suspend fun listMajorAdmins(context: Context, @RequestBody param: Map<String, UUID>): List<AlkMajorAdmin> {
        return organizationService.listMajorAdmins(context, param["majorId"]!!)
    }

    /**
     * 分配专业级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、majorId（专业ID）
     * @return 创建的专业级管理员记录
     * HTTP方法: POST
     */
    @PostMapping("/admin/major/assign")
    suspend fun assignMajorAdmin(context: Context, @RequestBody param: AdminAssignParam): AlkMajorAdmin {
        return organizationService.assignMajorAdmin(context, param.userId!!, param.majorId!!)
    }

    /**
     * 移除专业级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、majorId（专业ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/admin/major/remove")
    suspend fun removeMajorAdmin(context: Context, @RequestBody param: AdminAssignParam): Map<String, Any> {
        organizationService.removeMajorAdmin(context, param.userId!!, param.majorId!!)
        return mapOf("success" to true, "message" to "移除成功")
    }

    /**
     * 获取班级级管理员列表
     *
     * @param context 当前会话上下文
     * @param param 包含 classId（班级ID）
     * @return 班级级管理员列表
     * HTTP方法: POST
     */
    @PostMapping("/admin/class/list")
    suspend fun listClassAdmins(context: Context, @RequestBody param: Map<String, UUID>): List<AlkClassAdmin> {
        return organizationService.listClassAdmins(context, param["classId"]!!)
    }

    /**
     * 分配班级级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、classId（班级ID）
     * @return 创建的班级级管理员记录
     * HTTP方法: POST
     */
    @PostMapping("/admin/class/assign")
    suspend fun assignClassAdmin(context: Context, @RequestBody param: AdminAssignParam): AlkClassAdmin {
        return organizationService.assignClassAdmin(context, param.userId!!, param.classId!!)
    }

    /**
     * 移除班级级管理员
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）、classId（班级ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/admin/class/remove")
    suspend fun removeClassAdmin(context: Context, @RequestBody param: AdminAssignParam): Map<String, Any> {
        organizationService.removeClassAdmin(context, param.userId!!, param.classId!!)
        return mapOf("success" to true, "message" to "移除成功")
    }

    /**
     * 清除用户的所有组织管理员关联
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/admin/clear")
    suspend fun clearAllAdminAssignments(context: Context, @RequestBody param: Map<String, UUID>): Map<String, Any> {
        organizationService.clearAllAdminAssignments(context, param["userId"]!!)
        return mapOf("success" to true, "message" to "已清除所有组织管理员关联")
    }

    /**
     * 获取用户的管理员分配信息（简要）
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）
     * @return 用户的管理员分配信息映射
     * HTTP方法: POST
     */
    @PostMapping("/admin/user/list")
    suspend fun getUserAdminAssignment(context: Context, @RequestBody param: Map<String, UUID>): Map<String, String> {
        return organizationService.getUserAdminAssignment(context, param["userId"]!!)
    }

    /**
     * 获取用户的所有管理员分配信息（详细）
     *
     * @param context 当前会话上下文
     * @param param 包含 userId（用户ID）
     * @return 用户的所有管理员分配信息列表
     * HTTP方法: POST
     */
    @PostMapping("/admin/user/assignments")
    suspend fun getUserAdminAssignments(context: Context, @RequestBody param: Map<String, UUID>): List<Map<String, String>> {
        return organizationService.getUserAdminAssignments(context, param["userId"]!!)
    }

    /**
     * 获取完整的组织架构树
     *
     * @param context 当前会话上下文
     * @return 组织架构树节点列表
     * HTTP方法: POST
     */
    @PostMapping("/tree")
    suspend fun getOrganizationTree(context: Context): List<OrganizationService.OrgTreeNode> {
        return organizationService.getOrganizationTree(context)
    }

    /**
     * 获取当前用户可见的组织架构树
     *
     * @param context 当前会话上下文
     * @return 当前用户可见的组织架构树节点列表
     * HTTP方法: POST
     */
    @PostMapping("/myTree")
    suspend fun getMyOrganizationTree(context: Context): List<OrganizationService.OrgTreeNode> {
        return organizationService.getMyOrganizationTree(context)
    }

    // ============================================================
    // 组织成员管理（管理员权限）
    // ============================================================

    data class UpdateOrgParam(
        val name: String? = null,
        val grade: String? = null,
        val college: String? = null,
        val major: String? = null,
        val className: String? = null
    )

    /**
     * 更新组织信息（名称、年级等）
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @param param 包含 name、grade、college、major、className（均为可选）
     * @return 更新后的组织信息
     * HTTP方法: POST
     */
    @PostMapping("/{id}/update")
    suspend fun updateOrganization(
        context: Context,
        @PathVariable id: UUID,
        @RequestBody param: UpdateOrgParam
    ): AlkOrganization {
        return organizationService.updateOrganization(
            context,
            id,
            param.name,
            param.grade,
            param.college,
            param.major,
            param.className
        )
    }

    /**
     * 删除组织
     *
     * @param context 当前会话上下文
     * @param id 组织ID（路径参数）
     * @return 包含 success 和 message 的操作结果
     * HTTP方法: POST
     */
    @PostMapping("/{id}/delete")
    suspend fun deleteOrganization(context: Context, @PathVariable id: UUID): Map<String, Any> {
        organizationService.deleteOrganization(context, id)
        return mapOf("success" to true, "message" to "删除成功")
    }

    data class SearchOrgParam(
        var level: String = "",
        var parentId: UUID? = null,
        var keyword: String = ""
    )

    /**
     * 层级搜索组织
     *
     * @param context 当前会话上下文
     * @param param 包含 level（school/college/major/class/grade）、parentId（父级ID）、keyword（搜索关键字）
     * @return 搜索结果列表，每项包含 id、name 字段
     * HTTP方法: POST
     */
    @PostMapping("/search")
    suspend fun searchOrganizations(context: Context, @RequestBody param: SearchOrgParam): List<Map<String, String>> {
        return organizationService.searchOrganizations(
            context,
            param.level,
            param.parentId,
            param.keyword
        )
    }
}
