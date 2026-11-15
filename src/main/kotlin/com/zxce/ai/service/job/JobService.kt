package com.zxce.ai.service.job

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.manager.BroadcastManager
import com.zxce.ai.manager.CacheManager
import com.zxce.ai.model.alumni.AlkConversation
import com.zxce.ai.model.alumni.AlkMessage
import com.zxce.ai.model.alumni.query.QAlkConnection
import com.zxce.ai.model.alumni.query.QAlkConversation
import com.zxce.ai.model.alumni.query.QAlkWorkExperience
import com.zxce.ai.model.job.*
import com.zxce.ai.model.job.query.*
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.email.sendMail
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.*
import kotlin.time.Duration.Companion.minutes

/**
 * 招聘业务服务，负责企业、职位、简历、投递与收藏等招聘相关业务逻辑的处理
 */
@Service
class JobService {

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var cacheManager: CacheManager

    @Autowired
    lateinit var broadcastManager: BroadcastManager

    companion object {
        /** 公共邮箱域名黑名单，认领企业时必须使用企业邮箱 */
        private val publicEmailDomains = setOf(
            "qq.com", "vip.qq.com", "163.com", "126.com", "gmail.com", "outlook.com",
            "hotmail.com", "sina.com", "sina.cn", "foxmail.com", "yeah.net", "139.com",
            "sohu.com", "aliyun.com", "tom.com", "21cn.com", "189.cn", "wo.cn",
            "icloud.com", "live.com", "msn.com", "yahoo.com", "aol.com", "proton.me"
        )
    }

    /**
     * 校验当前用户是否为指定企业的认领人或管理员，否则抛出无权限异常
     * @return 企业实体
     */
    private suspend fun checkCompanyOwner(ctx: Context, companyId: UUID): AlkCompany {
        val company = ctx.query<QAlkCompany>().id.eq(companyId).toAsync().findOne()
            ?: throw NoSuchElementException("企业不存在")
        if (company.ownerId != ctx.userId.toUuid() && !ctx.user.isAdmin) {
            throw IllegalAccessException("无权限操作")
        }
        return company
    }

    /**
     * 创建企业
     * @param ctx 请求上下文
     * @param data 企业数据
     * @return 操作结果
     */
    suspend fun createCompany(ctx: Context, data: Bundle): Any = ctx {
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkCompany>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 更新企业信息，仅企业认领人或管理员可操作
     * @param ctx 请求上下文
     * @param data 企业数据，需包含主键
     * @return 操作结果
     */
    suspend fun updateCompany(ctx: Context, data: Bundle): Any = ctx {
        val cid = data["id"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("企业ID不能为空")
        val company = checkCompanyOwner(ctx, cid)
        // 非管理员不得篡改认领相关字段
        if (!ctx.user.isAdmin) {
            data.remove("claimStatus")
            data.remove("ownerId")
            data.remove("emailDomain")
            data.remove("claimedAt")
        }
        company.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        mapper.readerForUpdating(company).readValue<AlkCompany>(mapper.writeValueAsBytes(data)) // 加载已存在记录后填充数据
        withContext(Dispatchers.IO) {
            company.save()
        }
        return success()
    }

    /**
     * 根据ID删除企业，仅企业认领人或管理员可操作
     * @param ctx 请求上下文
     * @param id 企业ID
     * @return 操作结果
     */
    suspend fun deleteCompany(ctx: Context, id: String): Any = ctx {
        val cid = id.toUuid()
        checkCompanyOwner(ctx, cid)
        withContext(Dispatchers.IO) {
            ctx.query<QAlkCompany>().id.eq(cid).toAsync().delete()
        }
        return success()
    }

    /**
     * 分页查询企业列表
     * @param ctx 请求上下文
     * @param keys 名称模糊搜索关键字，可为空
     * @param pageNo 页码，从1开始
     * @param pageSize 每页数量
     * @return 企业列表与总条数
     */
    suspend fun getCompanyList(ctx: Context, keys: String?, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize
        val q = ctx.query<QAlkCompany>()
        keys?.let { q.name.contains(it) }
        val (companies, count) = q.orderBy().createdAt.desc()
            .setFirstRow(offset)
            .setMaxRows(pageSize)
            .toAsync().findPagedList().run {
                list.map(mapper::toBundle) to totalCount
            }
        return@ctx companies to count
    }

    /**
     * 根据ID获取企业详情，并附带认领状态与校友网络加成信息
     * @param ctx 请求上下文
     * @param id 企业ID
     * @return 企业详情
     * @throws NoSuchElementException 企业不存在时抛出
     */
    suspend fun getCompanyDetail(ctx: Context, id: String): Bundle = ctx {
        val company = ctx.query<QAlkCompany>().id.eq(id.toUuid()).toAsync().findOne()
            ?: throw NoSuchElementException("企业不存在")
        val result = mapper.toBundle(company)
        // 当前在职校友（按用户去重）
        val alumniIds = ctx.query<QAlkWorkExperience> {
            companyId.eq(company.id)
            isCurrent.eq(true)
        }.toAsync().findList().map { it.userId }.toSet()
        result["currentAlumniCount"] = alumniIds.size
        // 当前用户的互关好友中在此公司任职的列表（alk_connection 双向查询 status=3，取另一端）
        val me = ctx.userId.toUuid()
        val connections = ctx.query<QAlkConnection> {
            or {
                userId.eq(me)
                targetUserId.eq(me)
            }
            status.eq(3)
        }.toAsync().findList()
        val friendIds = connections.map { if (it.userId == me) it.targetUserId else it.userId }.toSet()
        val friendAlumniIds = alumniIds intersect friendIds
        result["friendAlumni"] = if (friendAlumniIds.isEmpty()) emptyList<Bundle>()
        else ctx.query<QSysUserEntity> {
            this.id.isIn(friendAlumniIds)
        }.toAsync().findList().map { u ->
            bundleScope {
                "userId" set u.id.toString()
                "realName" set u.realName
                "avatar" set u.avatar
            }
        }
        return@ctx result
    }

    /**
     * 创建职位，仅企业认领人或管理员可操作
     * @param ctx 请求上下文
     * @param data 职位数据
     * @return 操作结果
     */
    suspend fun createJob(ctx: Context, data: Bundle): Any = ctx {
        val cid = data["companyId"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("企业ID不能为空")
        checkCompanyOwner(ctx, cid)
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkJob>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 更新职位信息，仅企业认领人或管理员可操作
     * @param ctx 请求上下文
     * @param data 职位数据，需包含主键
     * @return 操作结果
     */
    suspend fun updateJob(ctx: Context, data: Bundle): Any = ctx {
        val jid = data["id"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("职位ID不能为空")
        val job = ctx.query<QAlkJob>().id.eq(jid).toAsync().findOne()
            ?: throw NoSuchElementException("职位不存在")
        checkCompanyOwner(ctx, job.companyId)
        // 若迁移职位到其他企业，需同时校验目标企业权限
        data["companyId"]?.toString()?.toUuidOrNull()?.takeIf { it != job.companyId }?.let {
            checkCompanyOwner(ctx, it)
        }
        job.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        mapper.readerForUpdating(job).readValue<AlkJob>(mapper.writeValueAsBytes(data))
        withContext(Dispatchers.IO) {
            job.save()
        }
        return success()
    }

    /**
     * 根据ID删除职位，仅企业认领人或管理员可操作
     * @param ctx 请求上下文
     * @param id 职位ID
     * @return 操作结果
     */
    suspend fun deleteJob(ctx: Context, id: String): Any = ctx {
        val job = ctx.query<QAlkJob>().id.eq(id.toUuid()).toAsync().findOne()
            ?: throw NoSuchElementException("职位不存在")
        checkCompanyOwner(ctx, job.companyId)
        withContext(Dispatchers.IO) {
            job.delete()
        }
        return success()
    }

    /**
     * 分页查询职位列表，支持多条件过滤
     * @param ctx 请求上下文
     * @param params 查询参数，包含 pageNo、pageSize 及各筛选条件
     * @return 职位列表与总条数
     */
    suspend fun getJobList(ctx: Context, params: Bundle): Pair<List<Bundle>, Int> = ctx {
        val pageNo = (params["pageNo"] as? Number)?.toInt() ?: 1
        val pageSize = (params["pageSize"] as? Number)?.toInt() ?: 10
        val offset = (pageNo - 1) * pageSize
        val q = ctx.query<QAlkJob>().isActive.eq(true) // 仅查询启用状态的职位
        params["companyId"]?.let { q.companyId.eq(it.toString().toUuid()) }
        params["workLocation"]?.let { q.workLocation.contains(it.toString()) }
        params["educationRequirement"]?.let { q.educationRequirement.eq(it.toString()) }
        params["experienceRequirement"]?.let { q.experienceRequirement.eq(it.toString()) }
        params["jobType"]?.let { q.jobType.eq(it.toString()) }
        params["salaryMin"]?.let { q.salaryMin.ge(it as BigDecimal) }
        params["salaryMax"]?.let { q.salaryMax.le(it as BigDecimal) }
        params["title"]?.let { q.title.contains(it.toString()) }
        val (jobs, count) = q.orderBy().createdAt.desc()
            .setFirstRow(offset)
            .setMaxRows(pageSize)
            .toAsync().findPagedList().run {
                list.map(mapper::toBundle) to totalCount
            }
        fillJobCompanyInfo(ctx, jobs) // 填充职位关联的企业信息
        return@ctx jobs to count
    }

    /**
     * 批量为职位列表填充企业信息
     * @param ctx 请求上下文
     * @param jobs 职位列表
     */
    private suspend fun fillJobCompanyInfo(ctx: Context, jobs: List<Bundle>) {
        val companyIds = jobs.map { it["companyId"].toString().toUuid() }.toSet()
        if (companyIds.isEmpty()) return
        val companies = ctx.query<QAlkCompany> {
            id.isIn(companyIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>().mapValues { (_, v) -> mapper.toBundle(v) }
        jobs.forEach { job ->
            companies[job["companyId"].toString().toUuid()]?.let {
                job["company"] = it
            }
        }
    }

    /**
     * 根据ID获取职位详情，并累加浏览数
     * @param ctx 请求上下文
     * @param id 职位ID
     * @return 职位详情（含企业信息）
     * @throws NoSuchElementException 职位不存在时抛出
     */
    suspend fun getJobDetail(ctx: Context, id: String): Bundle = ctx {
        val job = ctx.query<QAlkJob>().id.eq(id.toUuid()).toAsync().findOne()
            ?: throw NoSuchElementException("职位不存在")
        val result = mapper.toBundle(job)
        val company = ctx.query<QAlkCompany>().id.eq(job.companyId).toAsync().findOne()
        company?.let { result["company"] = mapper.toBundle(it) }
        withContext(Dispatchers.IO) {
            job.viewsCount++ // 浏览数自增
            job.save()
        }
        return@ctx result
    }

    /**
     * 投递职位，已投递时会抛出异常
     * @param ctx 请求上下文
     * @param data 投递数据，需包含 jobId
     * @return 操作结果
     * @throws IllegalStateException 已投递该职位时抛出
     */
    suspend fun applyJob(ctx: Context, data: Bundle): Any = ctx {
        val targetJobId = data["jobId"].toString().toUuid()
        // 校验当前用户是否已投递该职位
        val exists = ctx.query<QAlkJobApplication> {
            jobId.eq(targetJobId)
            userId.eq(ctx.userId.toUuid())
        }.toAsync().exists()
        if (exists) throw IllegalStateException("已投递该职位")
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkJobApplication>(data)
        val job = ctx.query<QAlkJob>().id.eq(targetJobId).toAsync().findOne()
        withContext(Dispatchers.IO) {
            model.save()
            job?.let { it.applicationsCount++; it.save() } // 投递数自增
        }
        return success()
    }

    /**
     * 更新投递申请状态，仅职位所属企业的认领人或管理员可操作
     * @param ctx 请求上下文
     * @param data 投递数据，需包含主键与状态
     * @return 操作结果
     */
    suspend fun updateApplicationStatus(ctx: Context, data: Bundle): Any = ctx {
        val appId = data["id"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("申请ID不能为空")
        val application = ctx.query<QAlkJobApplication>().id.eq(appId).toAsync().findOne()
            ?: throw NoSuchElementException("投递记录不存在")
        val job = ctx.query<QAlkJob>().id.eq(application.jobId).toAsync().findOne()
            ?: throw NoSuchElementException("职位不存在")
        checkCompanyOwner(ctx, job.companyId)
        application.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        mapper.readerForUpdating(application).readValue<AlkJobApplication>(mapper.writeValueAsBytes(data))
        withContext(Dispatchers.IO) {
            application.save()
        }
        return success()
    }

    /**
     * 分页查询当前用户的投递记录，并填充关联职位与企业信息
     * @param ctx 请求上下文
     * @param pageNo 页码，从1开始
     * @param pageSize 每页数量
     * @return 投递列表与总条数
     */
    suspend fun getMyApplications(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize
        val pagedList = ctx.query<QAlkJobApplication> {
            userId.eq(ctx.userId.toUuid())
            orderBy().createdAt.desc()
            setFirstRow(offset)
            setMaxRows(pageSize)
        }.toAsync().findPagedList()
        val appList: List<AlkJobApplication> = pagedList.list
        val count: Int = pagedList.totalCount
        val apps: List<Bundle> = mapper.toBundle(appList)
        val jobIds = apps.map { it["jobId"].toString().toUuid() }.toSet()
        // 批量查询关联的职位信息
        val jobMap = ctx.query<QAlkJob> {
            id.isIn(jobIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val jobs: Map<UUID, Bundle> = jobMap.mapValues { (_, v) -> mapper.toBundle(v) }
        val companyIds = jobs.values.map { it["companyId"].toString().toUuid() }.toSet()
        // 批量查询关联的企业信息
        val companyMap = ctx.query<QAlkCompany> {
            id.isIn(companyIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val companies: Map<UUID, Bundle> = companyMap.mapValues { (_, v) -> mapper.toBundle(v) }
        apps.forEach { app ->
            val jobId = app["jobId"].toString().toUuid()
            jobs[jobId]?.let { job ->
                app["job"] = job
                val companyId = job["companyId"].toString().toUuid()
                companies[companyId]?.let { app["company"] = it }
            }
        }
        return@ctx apps to count
    }

    /**
     * 创建简历
     * @param ctx 请求上下文
     * @param data 简历数据
     * @return 操作结果
     */
    suspend fun createResume(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkResume>(data)
        val isDefault = model.isDefault
        withContext(Dispatchers.IO) {
            model.save()
            // 若设为默认简历，则将其他简历的默认标记置为 false
            if (isDefault) {
                ctx.query<QAlkResume> {
                    userId.eq(ctx.userId.toUuid())
                    id.ne(model.id)
                }.toAsync().findList().forEach {
                    it.isDefault = false
                    it.save()
                }
            }
        }
        return success()
    }

    /**
     * 更新简历信息
     * @param ctx 请求上下文
     * @param data 简历数据，需包含主键
     * @return 操作结果
     */
    suspend fun updateResume(ctx: Context, data: Bundle): Any = ctx {
        data["updatedBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkResume>(data, true)
        val isDefault = model.isDefault
        withContext(Dispatchers.IO) {
            model.save()
            // 若设为默认简历，则将其他简历的默认标记置为 false
            if (isDefault) {
                ctx.query<QAlkResume> {
                    userId.eq(ctx.userId.toUuid())
                    id.ne(model.id)
                }.toAsync().findList().forEach {
                    it.isDefault = false
                    it.save()
                }
            }
        }
        return success()
    }

    /**
     * 根据ID删除简历
     * @param ctx 请求上下文
     * @param id 简历ID
     * @return 操作结果
     */
    suspend fun deleteResume(ctx: Context, id: String): Any = ctx {
        val resume = ctx.query<QAlkResume>().id.eq(id.toUuid()).toAsync().findOne()
        withContext(Dispatchers.IO) {
            resume?.delete()
        }
        return success()
    }

    /**
     * 获取当前用户的简历列表
     * @param ctx 请求上下文
     * @return 简历列表
     */
    suspend fun getResumeList(ctx: Context): List<Bundle> = ctx {
        val list = ctx.query<QAlkResume>()
            .userId.eq(ctx.userId.toUuid())
            .orderBy().createdAt.desc()
            .toAsync().findList()
        return@ctx mapper.toBundle(list)
    }

    /**
     * 获取当前用户的默认简历
     * @param ctx 请求上下文
     * @return 默认简历信息，不存在时返回 null
     */
    suspend fun getDefaultResume(ctx: Context): Bundle? = ctx {
        val resume = ctx.query<QAlkResume>()
            .userId.eq(ctx.userId.toUuid())
            .isDefault.eq(true)
            .orderBy().id.asc().toAsync().findList().firstOrNull()
        return@ctx resume?.let { mapper.toBundle(it) }
    }

    /**
     * 切换职位收藏状态（收藏或取消收藏）
     * @param ctx 请求上下文
     * @param data 收藏数据，需包含 jobId 和 favorite（布尔值，表示是否收藏）
     * @return 操作结果
     */
    suspend fun toggleFavorite(ctx: Context, data: Bundle): Any = ctx {
        val jobId = data["jobId"].toString().toUuid()
        val favorite = data["favorite"] as Boolean
        // 查询当前用户是否已收藏该职位
        val exists = ctx.database.find(AlkJobFavorite::class.java)
            .where()
            .eq("userId", ctx.userId.toUuid())
            .eq("jobId", jobId)
            .exists()
        if (favorite) {
            // 收藏：若不存在则新增收藏记录
            if (!exists) {
                val model = AlkJobFavorite().apply {
                    id = UUID.randomUUID()
                    userId = ctx.userId.toUuid()
                    this.jobId = jobId
                    createdAt = java.sql.Timestamp(System.currentTimeMillis())
                    updatedAt = createdAt
                    createdBy = ctx.userIdOrNull ?: ctx.user.id
                    updatedBy = createdBy
                }
                withContext(Dispatchers.IO) {
                    model.save()
                }
            }
        } else {
            // 取消收藏：若存在则删除收藏记录
            if (exists) {
                ctx.database.find(AlkJobFavorite::class.java)
                    .where()
                    .eq("userId", ctx.userId.toUuid())
                    .eq("jobId", jobId)
                    .orderBy("id")
                    .findList().firstOrNull()?.let {
                        withContext(Dispatchers.IO) {
                            it.delete()
                        }
                    }
            }
        }
        return success()
    }

    /**
     * 分页查询当前用户收藏的职位列表，并填充关联企业信息
     * @param ctx 请求上下文
     * @param pageNo 页码，从1开始
     * @param pageSize 每页数量
     * @return 收藏职位列表与总条数
     */
    suspend fun getFavoriteJobList(ctx: Context, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val offset = (pageNo - 1) * pageSize
        // 分页查询当前用户的收藏记录
        val pagedList = ctx.database.find(AlkJobFavorite::class.java)
            .where()
            .eq("userId", ctx.userId.toUuid())
            .orderBy("createdAt desc")
            .setFirstRow(offset)
            .setMaxRows(pageSize)
            .findPagedList()
        val favorites: List<AlkJobFavorite> = pagedList.list
        val count: Int = pagedList.totalCount
        if (favorites.isEmpty()) {
            return@ctx emptyList<Bundle>() to 0
        }
        // 批量查询收藏的职位信息（仅返回启用状态的职位）
        val jobIds = favorites.map { it.jobId }.toSet()
        val jobs = ctx.database.find(AlkJob::class.java)
            .where()
            .`in`("id", jobIds)
            .eq("isActive", true)
            .findMap<UUID>()
        val jobBundles: Map<UUID, Bundle> = jobs.mapValues { (_, v) -> mapper.toBundle(v) }
        // 批量查询关联的企业信息
        val companyIds = jobBundles.values.map { it["companyId"].toString().toUuid() }.toSet()
        val companies = ctx.database.find(AlkCompany::class.java)
            .where()
            .`in`("id", companyIds)
            .findMap<UUID>()
        val companyBundles: Map<UUID, Bundle> = companies.mapValues { (_, v) -> mapper.toBundle(v) }
        // 组合职位与企业信息，过滤掉已禁用的职位
        val result = favorites.mapNotNull { fav ->
            jobBundles[fav.jobId]?.apply {
                val companyId = this["companyId"].toString().toUuid()
                companyBundles[companyId]?.let { this["company"] = it }
            }
        }
        return@ctx result to count
    }

    /**
     * 企业名称联想搜索，按名称模糊匹配最多返回10条
     * @param ctx 请求上下文
     * @param keyword 搜索关键字
     * @return 企业简要信息列表
     */
    suspend fun suggestCompanies(ctx: Context, keyword: String): List<Bundle> = ctx {
        val list = ctx.query<QAlkCompany> {
            if (keyword.isNotBlank()) name.contains(keyword)
            orderBy().createdAt.desc()
            setMaxRows(10)
        }.toAsync().findList()
        return@ctx list.map { c ->
            bundleScope {
                "id" set c.id.toString()
                "name" set c.name
                "logo" set c.logo
                "industry" set c.industry
                "city" set c.address
                "claimStatus" set c.claimStatus
            }
        }
    }

    /** 认领验证码缓存（key = userId:companyId:email） */
    private val claimVerifyCache by lazy { cacheManager["/job/company/claim"] }

    /**
     * 发送企业认领邮箱验证码
     * 校验：①当前用户有该公司在职工作经历 ②邮箱非公共域名 ③企业未被认证
     * @param ctx 请求上下文
     * @param data 包含 companyId 与 email
     * @return 操作结果
     */
    suspend fun sendClaimCode(ctx: Context, data: Bundle): Any = ctx {
        val cid = data["companyId"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("企业ID不能为空")
        val email = data["email"]?.toString()?.trim()?.lowercase()
        if (email.isNullOrBlank()) throw IllegalArgumentException("邮箱不能为空")
        val domain = email.substringAfter('@', "")
        if (domain.isBlank()) throw IllegalArgumentException("邮箱格式不正确")
        val company = ctx.query<QAlkCompany>().id.eq(cid).toAsync().findOne()
            ?: throw NoSuchElementException("企业不存在")
        // ① 仅在该企业任职的校友可认领
        val inService = ctx.query<QAlkWorkExperience> {
            userId.eq(ctx.userId.toUuid())
            companyId.eq(cid)
            isCurrent.eq(true)
        }.toAsync().exists()
        if (!inService) throw IllegalAccessException("仅在职校友可认领")
        // ② 公共邮箱域名黑名单
        if (domain in publicEmailDomains) throw IllegalArgumentException("请使用企业邮箱，公共邮箱域名不支持认领")
        // ③ 已认证企业不可重复认领
        if (company.claimStatus == 2) throw IllegalStateException("该企业已完成认证，无需重复认领")

        val code = Math.random().toString().removePrefix("0.").substring(0 until 6)
        claimVerifyCache.put("${ctx.userId}:${cid}:${email}", code, 5.minutes)
        val html = """
            <div style="font-family:sans-serif;padding:20px">
              <h2>企业认领验证</h2>
              <p>您正在认领企业「${company.name}」，验证码为：</p>
              <p style="font-size:28px;font-weight:bold;letter-spacing:4px">$code</p>
              <p>验证码 5 分钟内有效，请勿泄露给他人。若非本人操作请忽略本邮件。</p>
            </div>
        """.trimIndent()
        withContext(Dispatchers.IO) {
            ctx.sendMail(email, "企业认领验证", html)
        }
        return success()
    }

    /**
     * 提交企业认领申请。验证码通过或提供证明材料（走纯人工审核）均可提交
     * @param ctx 请求上下文
     * @param data 包含 companyId、email、code、proofFileId（可选）
     * @return 操作结果
     */
    suspend fun submitClaim(ctx: Context, data: Bundle): Any = ctx {
        val cid = data["companyId"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("企业ID不能为空")
        val email = data["email"]?.toString()?.trim()?.lowercase()
        if (email.isNullOrBlank()) throw IllegalArgumentException("邮箱不能为空")
        val code = data["code"]?.toString()?.trim()
        val proofFileId = data["proofFileId"]?.toString()
        val company = ctx.query<QAlkCompany>().id.eq(cid).toAsync().findOne()
            ?: throw NoSuchElementException("企业不存在")
        if (company.claimStatus == 2) throw IllegalStateException("该企业已完成认证，无需重复认领")

        val cacheKey = "${ctx.userId}:${cid}:${email}"
        var verifyPassed = false
        if (!code.isNullOrBlank()) {
            val cached = claimVerifyCache[cacheKey]
            if (cached != null && cached == code) {
                verifyPassed = true
                claimVerifyCache.remove(cacheKey)
            }
        }
        if (!verifyPassed && proofFileId.isNullOrBlank()) {
            throw IllegalArgumentException("邮箱验证码不正确或未提供证明材料")
        }
        val claim = ctx.createModel<AlkCompanyClaim> {
            this.companyId = cid
            this.userId = ctx.userId.toUuid()
            this.workEmail = email
            this.verifyPassed = verifyPassed
            this.proofFileId = proofFileId?.toUuidOrNull()
            this.status = 1
            this.createdBy = ctx.userIdOrNull ?: ctx.user.id
        }
        withContext(Dispatchers.IO) {
            claim.save()
            company.claimStatus = 1 // 待审核
            company.save()
        }
        return success()
    }

    /**
     * 获取当前用户的认领申请列表（含企业名称）
     * @param ctx 请求上下文
     * @return 认领申请列表
     */
    suspend fun getMyClaims(ctx: Context): List<Bundle> = ctx {
        val claims = ctx.query<QAlkCompanyClaim>()
            .userId.eq(ctx.userId.toUuid())
            .orderBy().createdAt.desc()
            .toAsync().findList()
        val companyIds = claims.map { it.companyId }.toSet()
        val companies = if (companyIds.isEmpty()) emptyMap() else ctx.query<QAlkCompany> {
            id.isIn(companyIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        return@ctx claims.map { claim ->
            mapper.toBundle(claim).apply {
                this["companyName"] = companies[claim.companyId]?.name
            }
        }
    }

    /**
     * 管理员查看全部认领申请（含企业名称与认领人姓名）
     * @param ctx 请求上下文
     * @param status 审核状态过滤，为空时查询全部
     * @return 认领申请列表
     */
    suspend fun getClaimList(ctx: Context, status: Int?): List<Bundle> = ctx {
        if (!ctx.user.isAdmin) throw IllegalAccessException("无权限操作")
        val st = status
        val claims = ctx.query<QAlkCompanyClaim> {
            st?.let { this.status.eq(it) }
            orderBy().createdAt.desc()
        }.toAsync().findList()
        val companyIds = claims.map { it.companyId }.toSet()
        val companies = if (companyIds.isEmpty()) emptyMap() else ctx.query<QAlkCompany> {
            id.isIn(companyIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val userIds = claims.map { it.userId }.toSet()
        val users = if (userIds.isEmpty()) emptyMap() else ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        return@ctx claims.map { claim ->
            mapper.toBundle(claim).apply {
                this["companyName"] = companies[claim.companyId]?.name
                this["userName"] = users[claim.userId]?.userName
                this["realName"] = users[claim.userId]?.realName
            }
        }
    }

    /**
     * 管理员审核认领申请。通过后企业标记为已认证并绑定认领人，结果私信通知认领人
     * @param ctx 请求上下文
     * @param data 包含 claimId、pass、remark（可选）
     * @return 操作结果
     */
    suspend fun reviewClaim(ctx: Context, data: Bundle): Any = ctx {
        if (!ctx.user.isAdmin) throw IllegalAccessException("无权限操作")
        val claimId = data["claimId"]?.toString()?.toUuidOrNull() ?: throw IllegalArgumentException("申请ID不能为空")
        val pass = (data["pass"] as? Boolean) ?: data["pass"]?.toString()?.toBooleanStrictOrNull()
            ?: throw IllegalArgumentException("审核结果不能为空")
        val remark = data["remark"]?.toString()
        val claim = ctx.query<QAlkCompanyClaim>().id.eq(claimId).toAsync().findOne()
            ?: throw NoSuchElementException("认领申请不存在")
        if (claim.status != 1) throw IllegalStateException("该申请已审核，请勿重复操作")
        val company = ctx.query<QAlkCompany>().id.eq(claim.companyId).toAsync().findOne()
            ?: throw NoSuchElementException("企业不存在")

        claim.status = if (pass) 2 else 3
        claim.remark = remark
        claim.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        withContext(Dispatchers.IO) {
            claim.save()
            if (pass) {
                company.claimStatus = 2
                company.ownerId = claim.userId
                company.emailDomain = claim.workEmail.substringAfter('@', "")
                company.claimedAt = Timestamp(System.currentTimeMillis())
            } else {
                company.claimStatus = 0
            }
            company.save()
        }
        val notice = if (pass) "您认领的企业「${company.name}」已通过审核，现在您可以发布和管理职位了。"
        else "您认领的企业「${company.name}」未通过审核${remark?.let { "：$it" } ?: ""}。"
        notifyUser(ctx, claim.userId, notice)
        return success()
    }

    /**
     * 以系统私信方式通知用户（直接写入会话与消息，不受好友权限限制）
     * @param ctx 请求上下文
     * @param targetUserId 接收人
     * @param content 通知内容
     */
    private suspend fun notifyUser(ctx: Context, targetUserId: UUID, content: String) {
        val senderId = ctx.userId.toUuid()
        // 恢复双方已软删除的会话记录
        ctx.database.sqlUpdate("UPDATE alk_conversation SET deleted = false WHERE user_id = :uid AND target_user_id = :tid AND deleted = true")
            .setParameter("uid", senderId.toString())
            .setParameter("tid", targetUserId.toString())
            .execute()
        ctx.database.sqlUpdate("UPDATE alk_conversation SET deleted = false WHERE user_id = :uid AND target_user_id = :tid AND deleted = true")
            .setParameter("uid", targetUserId.toString())
            .setParameter("tid", senderId.toString())
            .execute()
        var conversation = ctx.query<QAlkConversation>()
            .userId.eq(senderId)
            .targetUserId.eq(targetUserId)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
        if (conversation == null) {
            conversation = ctx.createModel<AlkConversation> {
                this.userId = senderId
                this.targetUserId = targetUserId
                this.createdBy = senderId
            }
            val targetConv = ctx.createModel<AlkConversation> {
                this.userId = targetUserId
                this.targetUserId = senderId
                this.createdBy = senderId
            }
            withContext(Dispatchers.IO) {
                conversation.save()
                targetConv.save()
            }
        }
        val message = ctx.createModel<AlkMessage> {
            this.conversationId = conversation.id
            this.senderId = senderId
            this.receiverId = targetUserId
            this.content = content
            this.messageType = 1
            this.createdBy = senderId
        }
        val targetConv = ctx.query<QAlkConversation>()
            .userId.eq(targetUserId)
            .targetUserId.eq(senderId)
            .orderBy().updatedAt.desc().toAsync().findList().firstOrNull()
        withContext(Dispatchers.IO) {
            message.save()
            conversation.lastMessage = content.take(50)
            conversation.save()
            targetConv?.let {
                it.lastMessage = content.take(50)
                it.unreadCount++
                it.save()
            }
        }
        broadcastManager.broadcastToUser("message:received", mapOf(
            "conversationId" to conversation.id.toString(),
            "senderId" to senderId.toString(),
            "content" to content,
            "messageType" to 1,
            "createdAt" to message.createdAt?.toString()
        ), targetUserId.toString())
    }

    /**
     * HR视角的投递列表，仅企业认领人或管理员可查看
     * @param ctx 请求上下文
     * @param companyId 企业ID
     * @param status 投递状态过滤，可为空
     * @param pageNo 页码，从1开始
     * @param pageSize 每页数量
     * @return 投递列表与总条数
     */
    suspend fun getCompanyApplications(ctx: Context, companyId: String, status: Int?, pageNo: Int, pageSize: Int): Pair<List<Bundle>, Int> = ctx {
        val cid = companyId.toUuid()
        checkCompanyOwner(ctx, cid)
        val jobIds = ctx.query<QAlkJob>().companyId.eq(cid).toAsync().findList().map { it.id }
        if (jobIds.isEmpty()) return@ctx emptyList<Bundle>() to 0
        val st = status
        val q = ctx.query<QAlkJobApplication> {
            this.jobId.isIn(jobIds)
            st?.let { this.status.eq(it) }
        }
        val pagedList = q.orderBy().createdAt.desc()
            .setFirstRow((pageNo - 1) * pageSize)
            .setMaxRows(pageSize)
            .toAsync().findPagedList()
        val apps: List<Bundle> = mapper.toBundle(pagedList.list)
        val userIds = pagedList.list.map { it.userId }.toSet()
        val users = if (userIds.isEmpty()) emptyMap() else ctx.query<QSysUserEntity> {
            id.isIn(userIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val resumeIds = pagedList.list.mapNotNull { it.resumeId }.toSet()
        val resumes = if (resumeIds.isEmpty()) emptyMap() else ctx.query<QAlkResume> {
            id.isIn(resumeIds)
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        val jobs = ctx.query<QAlkJob> {
            id.isIn(jobIds.toSet())
            id.asMapKey()
        }.toAsync().findMap<UUID>()
        apps.forEach { app ->
            val user = users[app["userId"].toString().toUuidOrNull()]
            app["realName"] = user?.realName
            app["avatar"] = user?.avatar
            app["resume"] = app["resumeId"]?.toString()?.toUuidOrNull()?.let { resumes[it] }?.let { mapper.toBundle(it) }
            app["job"] = jobs[app["jobId"].toString().toUuidOrNull()]?.let { mapper.toBundle(it) }
        }
        return@ctx apps to pagedList.totalCount
    }
}