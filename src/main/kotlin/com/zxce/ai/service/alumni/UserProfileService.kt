package com.zxce.ai.service.alumni

import com.fasterxml.jackson.databind.json.JsonMapper
import com.zxce.ai.manager.CacheManager
import com.zxce.ai.model.alumni.*
import com.zxce.ai.model.alumni.query.*
import com.zxce.ai.model.job.query.QAlkCompany
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.util.ErrorResponseException
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.email.sendMail
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.spring.ThymeleafContext
import com.zxce.ai.util.success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine
import java.util.*
import kotlin.time.Duration.Companion.minutes

@Service
class UserProfileService {

    companion object {
        private val encoder = BCryptPasswordEncoder(10)
        private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
        private val PHONE_REGEX = Regex("^1\\d{10}$")
    }

    @Autowired
    lateinit var mapper: JsonMapper

    @Autowired
    lateinit var cacheManager: CacheManager

    @Autowired
    lateinit var templateEngine: SpringWebFluxTemplateEngine

    /** 复用注册流程的邮箱验证码缓存（5 分钟有效） */
    private val emailVerifyCache by lazy { cacheManager["/system/login/email-verify"] }

    /**
     * 添加教育经历
     *
     * @param ctx 上下文对象
     * @param data 教育经历数据，包含学校名称、专业、学历、开始年份、结束年份等
     * @return 成功响应
     */
    suspend fun addEducation(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkEducation>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 更新教育经历
     *
     * @param ctx 上下文对象
     * @param data 教育经历更新数据，必须包含id字段
     * @return 成功响应
     */
    suspend fun updateEducation(ctx: Context, data: Bundle): Any = ctx {
        data["updatedBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkEducation>(data, true)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 删除教育经历
     *
     * @param ctx 上下文对象
     * @param id 教育经历记录ID
     * @return 成功响应
     */
    suspend fun deleteEducation(ctx: Context, id: String): Any = ctx {
        withContext(Dispatchers.IO) {
            ctx.query<QAlkEducation>().id.eq(id.toUuid()).toAsync().delete()
        }
        return success()
    }

    /**
     * 获取用户的教育经历列表
     *
     * @param ctx 上下文对象
     * @param userId 用户ID，为null时获取当前用户的教育经历
     * @return 教育经历列表（按结束年份降序排列）
     */
    suspend fun getEducationList(ctx: Context, userId: String?): List<Bundle> = ctx {
        val uid = userId?.toUuid() ?: ctx.userId.toUuid()
        val list = ctx.query<QAlkEducation>()
            .userId.eq(uid)
            .orderBy().endYear.desc()
            .toAsync().findList()
        return@ctx mapper.toBundle(list)
    }

    /**
     * 添加工作经历
     *
     * @param ctx 上下文对象
     * @param data 工作经历数据，包含公司名称、职位、开始日期、结束日期等
     * @return 成功响应
     */
    suspend fun addWorkExperience(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        resolveWorkCompany(ctx, data)
        val model = ctx.createModel<AlkWorkExperience>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 更新工作经历
     *
     * @param ctx 上下文对象
     * @param data 工作经历更新数据，必须包含id字段
     * @return 成功响应
     */
    suspend fun updateWorkExperience(ctx: Context, data: Bundle): Any = ctx {
        data["updatedBy"] = ctx.userIdOrNull ?: ctx.user.id
        if (data.containsKey("companyId") || data.containsKey("companyName")) {
            resolveWorkCompany(ctx, data)
        }
        val model = ctx.createModel<AlkWorkExperience>(data, true)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 解析工作经历的关联企业：优先使用传入的 companyId，否则按公司名称精确匹配 alk_company.name
     * @param ctx 上下文对象
     * @param data 工作经历数据
     */
    private suspend fun resolveWorkCompany(ctx: Context, data: Bundle) {
        if (!data["companyId"]?.toString().isNullOrBlank()) return
        val companyName = data["companyName"]?.toString() ?: return
        val company = ctx.query<QAlkCompany>()
            .name.eq(companyName)
            .orderBy().createdAt.asc().toAsync().findList().firstOrNull()
        data["companyId"] = company?.id?.toString()
    }

    /**
     * 删除工作经历
     *
     * @param ctx 上下文对象
     * @param id 工作经历记录ID
     * @return 成功响应
     */
    suspend fun deleteWorkExperience(ctx: Context, id: String): Any = ctx {
        withContext(Dispatchers.IO) {
            ctx.query<QAlkWorkExperience>().id.eq(id.toUuid()).toAsync().delete()
        }
        return success()
    }

    /**
     * 获取用户的工作经历列表
     *
     * @param ctx 上下文对象
     * @param userId 用户ID，为null时获取当前用户的工作经历
     * @return 工作经历列表（按开始日期降序排列）
     */
    suspend fun getWorkExperienceList(ctx: Context, userId: String?): List<Bundle> = ctx {
        val uid = userId?.toUuid() ?: ctx.userId.toUuid()
        val list = ctx.query<QAlkWorkExperience>()
            .userId.eq(uid)
            .orderBy().startDate.desc()
            .toAsync().findList()
        return@ctx mapper.toBundle(list)
    }

    /**
     * 添加用户标签
     *
     * @param ctx 上下文对象
     * @param data 标签数据，包含标签名称等信息
     * @return 成功响应
     */
    suspend fun addTag(ctx: Context, data: Bundle): Any = ctx {
        data["userId"] = ctx.userId
        data["createdBy"] = ctx.userIdOrNull ?: ctx.user.id
        val model = ctx.createModel<AlkUserTag>(data)
        withContext(Dispatchers.IO) {
            model.save()
        }
        return success()
    }

    /**
     * 删除用户标签
     *
     * @param ctx 上下文对象
     * @param id 标签ID
     * @return 成功响应
     */
    suspend fun deleteTag(ctx: Context, id: String): Any = ctx {
        withContext(Dispatchers.IO) {
            ctx.query<QAlkUserTag>().id.eq(id.toUuid()).toAsync().delete()
        }
        return success()
    }

    /**
     * 获取用户标签列表
     *
     * @param ctx 上下文对象
     * @param userId 用户ID，为null时获取当前用户的标签
     * @return 标签列表
     */
    suspend fun getTags(ctx: Context, userId: String?): List<Bundle> = ctx {
        val uid = userId?.toUuid() ?: ctx.userId.toUuid()
        val list = ctx.query<QAlkUserTag>().userId.eq(uid).toAsync().findList()
        return@ctx mapper.toBundle(list)
    }

    // ==================== 邮箱 / 手机绑定 ====================

    /**
     * 获取当前用户的联系方式
     */
    suspend fun getContact(ctx: Context): Bundle = ctx {
        val user = currentUser(ctx)
        bundleScope {
            "email" set user.email
            "phone" set user.phone
        }
    }

    /**
     * 发送邮箱绑定验证码
     * 复用注册流程的验证码缓存与邮件模板，验证码 5 分钟有效
     */
    suspend fun sendEmailCode(ctx: Context, email: String): Any = ctx {
        val target = email.trim()
        validateEmail(target)
        val uid = ctx.userId.toUuid()
        val occupied = ctx.query<QSysUserEntity> {
            this.email.eq(target)
            id.ne(uid)
        }.exists()
        if (occupied) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "该邮箱已被其他用户占用")

        val code = (0..999999).random().toString().padStart(6, '0')
        emailVerifyCache.put(target, code, 5.minutes)

        val user = currentUser(ctx)
        val exchangeCtx = ctx as IWebExchangeContext
        val tctx = ThymeleafContext(exchangeCtx.exchange)
        tctx.setVariables(bundleScope {
            "code" set code
            "userName" set user.userName
            "email" set target
            "emailBase64" set Base64.getUrlEncoder().encodeToString(target.toByteArray())
            "server" set exchangeCtx.serverHostString
        })
        val html = templateEngine.process("email-verify", tctx)
        ctx.sendMail(target, "邮箱绑定", html)

        success()
    }

    /**
     * 校验邮箱验证码并将邮箱写入当前用户
     */
    suspend fun bindEmail(ctx: Context, email: String, code: String): Any = ctx {
        val target = email.trim()
        validateEmail(target)
        val cached = emailVerifyCache[target]
            ?: throw ErrorResponseException(HttpStatus.BAD_REQUEST, "邮箱验证码已过期，请重新获取")
        if (cached != code.trim()) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "邮箱验证码不正确")
        emailVerifyCache.remove(target)

        val user = currentUser(ctx)
        user.email = target
        user.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        withContext(Dispatchers.IO) { user.save() }

        userBundle(user)
    }

    /**
     * 解绑当前用户邮箱
     */
    suspend fun unbindEmail(ctx: Context): Any = ctx {
        val user = currentUser(ctx)
        user.email = null
        user.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        withContext(Dispatchers.IO) { user.save() }
        userBundle(user)
    }

    /**
     * 绑定手机号
     * 无短信通道，使用登录密码做二次确认；响应中 verified=false 表示未经短信验证
     */
    suspend fun bindPhone(ctx: Context, phone: String, password: String?): Any = ctx {
        val target = phone.trim()
        if (!PHONE_REGEX.matches(target)) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "手机号格式不正确（需为 1 开头的 11 位数字）")
        }

        val user = currentUser(ctx)
        verifyPassword(user, password)

        val occupied = ctx.query<QSysUserEntity> {
            this.phone.eq(target)
            id.ne(user.id)
        }.exists()
        if (occupied) throw ErrorResponseException(HttpStatus.BAD_REQUEST, "该手机号已被其他用户占用")

        user.phone = target
        user.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        withContext(Dispatchers.IO) { user.save() }

        userBundle(user).apply { put("verified", false) }
    }

    /**
     * 校验登录密码后解绑手机号
     */
    suspend fun unbindPhone(ctx: Context, password: String?): Any = ctx {
        val user = currentUser(ctx)
        verifyPassword(user, password)
        user.phone = null
        user.updatedBy = ctx.userIdOrNull ?: ctx.user.id
        withContext(Dispatchers.IO) { user.save() }
        userBundle(user)
    }

    private fun currentUser(ctx: Context): SysUserEntity {
        return ctx.query<QSysUserEntity>().id.eq(ctx.userId.toUuid()).findOne()
            ?: throw ErrorResponseException(HttpStatus.NOT_FOUND, "用户不存在")
    }

    private fun validateEmail(email: String) {
        if (!EMAIL_REGEX.matches(email)) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "邮箱格式不正确")
        }
    }

    private fun verifyPassword(user: SysUserEntity, password: String?) {
        if (password.isNullOrBlank() || user.password.isNullOrEmpty() || !encoder.matches(password, user.password)) {
            throw ErrorResponseException(HttpStatus.BAD_REQUEST, "登录密码不正确")
        }
    }

    private fun userBundle(user: SysUserEntity): Bundle = mapper.toBundle(user).apply { remove("password") }
}