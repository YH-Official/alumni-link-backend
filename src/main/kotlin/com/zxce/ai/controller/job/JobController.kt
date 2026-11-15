package com.zxce.ai.controller.job

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.job.JobService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.bundle.bundleScope
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 校友求职控制器
 *
 * 提供校友求职相关的RESTful API，包括企业管理、职位管理、求职申请、简历管理和收藏管理等功能。
 * 所有接口路径前缀为 /api/alumni/job，需要用户登录认证。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/job")
class JobController {

    @Autowired
    lateinit var jobService: JobService

    /**
     * 创建企业
     *
     * 创建一条新的企业信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 企业数据，包含企业名称、描述、联系方式等
     * @return 创建的企业信息
     * @since POST /api/alumni/job/company/create
     */
    @PostMapping("/company/create")
    suspend fun createCompany(context: Context, @RequestBody data: Bundle): Any {
        return jobService.createCompany(context, data)
    }

    /**
     * 更新企业信息
     *
     * 更新指定企业的信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 企业更新数据，包含企业ID及需要更新的字段
     * @return 更新后的企业信息
     * @since POST /api/alumni/job/company/update
     */
    @PostMapping("/company/update")
    suspend fun updateCompany(context: Context, @RequestBody data: Bundle): Any {
        return jobService.updateCompany(context, data)
    }

    /**
     * 删除企业
     *
     * 删除指定ID的企业。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（企业ID）的请求体
     * @return 删除操作结果
     * @since POST /api/alumni/job/company/delete
     */
    @PostMapping("/company/delete")
    suspend fun deleteCompany(context: Context, @RequestBody params: Bundle): Any {
        return jobService.deleteCompany(context, params["id"].toString())
    }

    data class CompanyListParam(
        var keys: String?,
        var pageNo: Int,
        var pageSize: Int
    )

    /**
     * 获取企业列表
     *
     * 分页获取企业列表，支持关键词搜索。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 keys（搜索关键词，可选）、pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（企业列表）和 total（总数）的分页数据
     * @since POST /api/alumni/job/company/list
     */
    @PostMapping("/company/list")
    suspend fun companyList(context: Context, @RequestBody params: CompanyListParam): Bundle {
        val (data, total) = jobService.getCompanyList(context, params.keys, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    /**
     * 获取企业详情
     *
     * 获取指定ID企业的详细信息（含认领状态与校友网络加成信息）。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（企业ID）的请求体
     * @return 企业详情数据
     * @since POST /api/alumni/job/company/detail
     */
    @PostMapping("/company/detail")
    suspend fun companyDetail(context: Context, @RequestBody params: Bundle): Bundle {
        return jobService.getCompanyDetail(context, params["id"].toString())
    }

    /**
     * 企业名称联想搜索
     *
     * 按名称模糊匹配企业，最多返回10条，供前端工作经历联想使用。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param keyword 搜索关键字
     * @return 企业简要信息列表
     * @since GET /api/alumni/job/company/suggest
     */
    @GetMapping("/company/suggest")
    suspend fun companySuggest(context: Context, @RequestParam keyword: String): List<Bundle> {
        return jobService.suggestCompanies(context, keyword)
    }

    /**
     * 发送企业认领邮箱验证码
     *
     * 仅在该企业任职的校友可发起认领，且邮箱须为企业邮箱（非公共域名）。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 包含 companyId（企业ID）与 email（企业邮箱）
     * @return 发送结果
     * @since POST /api/alumni/job/company/claim/send-code
     */
    @PostMapping("/company/claim/send-code")
    suspend fun claimSendCode(context: Context, @RequestBody data: Bundle): Any {
        return jobService.sendClaimCode(context, data)
    }

    /**
     * 提交企业认领申请
     *
     * 邮箱验证码通过或提供证明材料（纯人工审核）均可提交，提交后进入待审核状态。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 包含 companyId、email、code、proofFileId（可选）
     * @return 提交结果
     * @since POST /api/alumni/job/company/claim/submit
     */
    @PostMapping("/company/claim/submit")
    suspend fun claimSubmit(context: Context, @RequestBody data: Bundle): Any {
        return jobService.submitClaim(context, data)
    }

    /**
     * 获取我的认领申请列表
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 当前用户的认领申请列表
     * @since GET /api/alumni/job/company/claim/my
     */
    @GetMapping("/company/claim/my")
    suspend fun claimMy(context: Context): List<Bundle> {
        return jobService.getMyClaims(context)
    }

    /**
     * 管理员查看全部认领申请
     *
     * @param context 会话上下文，包含用户认证信息
     * @param status 审核状态过滤（1-待审核 2-通过 3-拒绝），为空时查询全部
     * @return 认领申请列表（含企业名称与认领人姓名）
     * @since GET /api/alumni/job/company/claim/list
     */
    @GetMapping("/company/claim/list")
    suspend fun claimList(context: Context, @RequestParam(required = false) status: Int?): List<Bundle> {
        return jobService.getClaimList(context, status)
    }

    /**
     * 管理员审核认领申请
     *
     * 通过后企业标记为已认证并绑定认领人，审核结果私信通知认领人。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 包含 claimId（申请ID）、pass（是否通过）、remark（备注，可选）
     * @return 审核结果
     * @since POST /api/alumni/job/company/claim/review
     */
    @PostMapping("/company/claim/review")
    suspend fun claimReview(context: Context, @RequestBody data: Bundle): Any {
        return jobService.reviewClaim(context, data)
    }

    /**
     * 创建职位
     *
     * 创建一条新的职位信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 职位数据，包含职位名称、所属企业、薪资等信息
     * @return 创建的职位信息
     * @since POST /api/alumni/job/create
     */
    @PostMapping("/create")
    suspend fun createJob(context: Context, @RequestBody data: Bundle): Any {
        return jobService.createJob(context, data)
    }

    /**
     * 更新职位信息
     *
     * 更新指定职位的信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 职位更新数据，包含职位ID及需要更新的字段
     * @return 更新后的职位信息
     * @since POST /api/alumni/job/update
     */
    @PostMapping("/update")
    suspend fun updateJob(context: Context, @RequestBody data: Bundle): Any {
        return jobService.updateJob(context, data)
    }

    /**
     * 删除职位
     *
     * 删除指定ID的职位。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（职位ID）的请求体
     * @return 删除操作结果
     * @since POST /api/alumni/job/delete
     */
    @PostMapping("/delete")
    suspend fun deleteJob(context: Context, @RequestBody params: Bundle): Any {
        return jobService.deleteJob(context, params["id"].toString())
    }

    /**
     * 获取职位列表
     *
     * 分页获取职位列表，支持多条件筛选。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 筛选参数，包含分页信息及其他筛选条件
     * @return 包含 data（职位列表）和 total（总数）的分页数据
     * @since POST /api/alumni/job/list
     */
    @PostMapping("/list")
    suspend fun jobList(context: Context, @RequestBody params: Bundle): Bundle {
        val (data, total) = jobService.getJobList(context, params)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    /**
     * 获取职位详情
     *
     * 获取指定ID职位的详细信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（职位ID）的请求体
     * @return 职位详情数据
     * @since POST /api/alumni/job/detail
     */
    @PostMapping("/detail")
    suspend fun jobDetail(context: Context, @RequestBody params: Bundle): Bundle {
        return jobService.getJobDetail(context, params["id"].toString())
    }

    /**
     * 申请职位
     *
     * 对指定职位提交求职申请。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 申请数据，包含职位ID、简历ID等信息
     * @return 申请结果
     * @since POST /api/alumni/job/apply
     */
    @PostMapping("/apply")
    suspend fun apply(context: Context, @RequestBody data: Bundle): Any {
        return jobService.applyJob(context, data)
    }

    /**
     * 更新申请状态
     *
     * 更新求职申请的状态（如通过、拒绝等）。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 更新数据，包含申请ID和新状态
     * @return 更新结果
     * @since POST /api/alumni/job/application/update
     */
    @PostMapping("/application/update")
    suspend fun updateApplication(context: Context, @RequestBody data: Bundle): Any {
        return jobService.updateApplicationStatus(context, data)
    }

    data class ApplicationListParam(
        var pageNo: Int,
        var pageSize: Int
    )

    /**
     * 获取我的求职申请列表
     *
     * 分页获取当前用户的求职申请记录。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（申请列表）和 total（总数）的分页数据
     * @since POST /api/alumni/job/application/list
     */
    @PostMapping("/application/list")
    suspend fun applicationList(context: Context, @RequestBody params: ApplicationListParam): Bundle {
        val (data, total) = jobService.getMyApplications(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    /**
     * 获取企业投递列表（HR视角）
     *
     * 分页获取指定企业收到的全部投递记录，仅企业认领人或管理员可查看。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param companyId 企业ID
     * @param status 投递状态过滤，可为空
     * @param pageNo 页码，从1开始
     * @param pageSize 每页数量
     * @return 包含 data（投递列表）和 total（总数）的分页数据
     * @since GET /api/alumni/job/application/company-list
     */
    @GetMapping("/application/company-list")
    suspend fun applicationCompanyList(
        context: Context,
        @RequestParam companyId: String,
        @RequestParam(required = false) status: Int?,
        @RequestParam(defaultValue = "1") pageNo: Int,
        @RequestParam(defaultValue = "10") pageSize: Int
    ): Bundle {
        val (data, total) = jobService.getCompanyApplications(context, companyId, status, pageNo, pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }

    /**
     * 创建简历
     *
     * 创建一份新的简历。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 简历数据，包含个人信息、教育经历、工作经历等
     * @return 创建的简历信息
     * @since POST /api/alumni/job/resume/create
     */
    @PostMapping("/resume/create")
    suspend fun createResume(context: Context, @RequestBody data: Bundle): Any {
        return jobService.createResume(context, data)
    }

    /**
     * 更新简历
     *
     * 更新指定简历的信息。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 简历更新数据，包含简历ID及需要更新的字段
     * @return 更新后的简历信息
     * @since POST /api/alumni/job/resume/update
     */
    @PostMapping("/resume/update")
    suspend fun updateResume(context: Context, @RequestBody data: Bundle): Any {
        return jobService.updateResume(context, data)
    }

    /**
     * 删除简历
     *
     * 删除指定ID的简历。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 包含 id（简历ID）的请求体
     * @return 删除操作结果
     * @since POST /api/alumni/job/resume/delete
     */
    @PostMapping("/resume/delete")
    suspend fun deleteResume(context: Context, @RequestBody params: Bundle): Any {
        return jobService.deleteResume(context, params["id"].toString())
    }

    /**
     * 获取简历列表
     *
     * 获取当前用户的所有简历列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 简历列表
     * @since POST /api/alumni/job/resume/list
     */
    @PostMapping("/resume/list")
    suspend fun resumeList(context: Context): List<Bundle> {
        return jobService.getResumeList(context)
    }

    /**
     * 获取默认简历
     *
     * 获取当前用户的默认简历。
     *
     * @param context 会话上下文，包含用户认证信息
     * @return 默认简历信息，若不存在则返回null
     * @since POST /api/alumni/job/resume/default
     */
    @PostMapping("/resume/default")
    suspend fun defaultResume(context: Context): Bundle? {
        return jobService.getDefaultResume(context)
    }

    /**
     * 切换职位收藏状态
     *
     * 对指定职位进行收藏或取消收藏操作。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param data 收藏参数，包含职位ID等信息
     * @return 操作结果
     * @since POST /api/alumni/job/favorite
     */
    @PostMapping("/favorite")
    suspend fun favorite(context: Context, @RequestBody data: Bundle): Any {
        return jobService.toggleFavorite(context, data)
    }

    data class FavoriteListParam(
        var pageNo: Int,
        var pageSize: Int
    )

    /**
     * 获取收藏职位列表
     *
     * 分页获取当前用户收藏的职位列表。
     *
     * @param context 会话上下文，包含用户认证信息
     * @param params 分页参数，包含 pageNo（页码）、pageSize（每页数量）
     * @return 包含 data（收藏职位列表）和 total（总数）的分页数据
     * @since POST /api/alumni/job/favorite/list
     */
    @PostMapping("/favorite/list")
    suspend fun favoriteList(context: Context, @RequestBody params: FavoriteListParam): Bundle {
        val (data, total) = jobService.getFavoriteJobList(context, params.pageNo, params.pageSize)
        return bundleScope {
            "data" set data
            "total" set total
        }
    }
}