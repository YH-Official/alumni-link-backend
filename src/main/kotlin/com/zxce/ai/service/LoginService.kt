package com.zxce.ai.service

import cn.hutool.captcha.CaptchaUtil
import cn.hutool.captcha.generator.MathGenerator
import cn.hutool.core.math.Calculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zxce.ai.event.system.UserLoginEvent
import com.zxce.ai.event.system.UserLogoutEvent
import com.zxce.ai.manager.CacheManager
import com.zxce.ai.manager.SessionManager
import com.zxce.ai.model.system.AuthorizeUser
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.model.system.ViewUserRoleEntity
import com.zxce.ai.model.system.query.QSysUserEntity
import com.zxce.ai.service.system.UserService
import com.zxce.ai.util.bundle.*
import com.zxce.ai.util.ebean.*
import com.zxce.ai.util.email.sendMail
import com.zxce.ai.util.session.Context
import com.zxce.ai.util.session.IWebExchangeContext
import com.zxce.ai.util.spring.ThymeleafContext
import com.zxce.ai.util.success
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.thymeleaf.spring6.SpringWebFluxTemplateEngine
import java.awt.Color
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@Service
class LoginService {

    companion object {
        private val encoder = BCryptPasswordEncoder(10)

        /** 连续失败多少次后锁定 */
        private const val MAX_LOGIN_FAILURES = 5

        /** 锁定时长（毫秒） */
        private const val LOGIN_LOCK_MILLIS = 5 * 60 * 1000L

        /** 记录每个账号的连续失败次数与锁定截止时间，防止撞库 */
        private val loginFailures = ConcurrentHashMap<String, LongArray>()
    }

    /**
     * 校验账号是否处于登录锁定状态。
     *
     * @param userName 登录名
     * @throws IllegalAccessException 当账号仍处于锁定期内
     */
    private fun checkLoginLock(userName: String) {
        val record = loginFailures[userName] ?: return
        if (record[1] > System.currentTimeMillis()) {
            val seconds = (record[1] - System.currentTimeMillis()) / 1000 + 1
            throw IllegalAccessException("登录失败次数过多，请 ${seconds} 秒后再试")
        }
        if (record[1] > 0) loginFailures.remove(userName)
    }

    /**
     * 记录一次登录失败，达到阈值后锁定账号。
     *
     * @param userName 登录名
     */
    private fun recordLoginFailure(userName: String) {
        val record = loginFailures.computeIfAbsent(userName) { LongArray(2) }
        synchronized(record) {
            record[0]++
            if (record[0] >= MAX_LOGIN_FAILURES) {
                record[0] = 0
                record[1] = System.currentTimeMillis() + LOGIN_LOCK_MILLIS
            }
        }
    }

    @Autowired
    lateinit var session: SessionManager

    @Autowired
    lateinit var publisher: ApplicationEventPublisher

    @Autowired
    lateinit var templateEngine: SpringWebFluxTemplateEngine

    @Autowired
    lateinit var cacheManager: CacheManager

    @Autowired
    lateinit var userService: UserService

    /**
     * 用户登录验证
     *
     * 根据用户名或手机号查询用户，验证密码是否正确，创建会话并返回授权用户信息。
     *
     * @param ctx 请求上下文，用于数据库查询和会话管理
     * @param userName 用户名或手机号
     * @param password 密码，首次登录时可为空
     * @return 授权用户信息 [AuthorizeUser]，包含用户基本信息和会话令牌
     * @throws NoSuchElementException 当用户不存在时抛出
     * @throws IllegalAccessException 当密码不正确时抛出
     */
    suspend fun login(ctx: Context, userName: String, password: String?): AuthorizeUser {
        // 验证用户名不为空
        val trimmedUserName = userName.trim()
        if (trimmedUserName.isEmpty()) {
            throw IllegalArgumentException("用户名不能为空")
        }

        checkLoginLock(trimmedUserName)

        val user = ctx.query<QSysUserEntity> {
            or {
                this.userName.eq(trimmedUserName)
                this.phone.eq(trimmedUserName)
            }
            deleted.ne(true)
            orderBy().id.asc()
        }.toAsync().findList().firstOrNull() ?: run {
            recordLoginFailure(trimmedUserName)
            throw NoSuchElementException("用户不存在！")
        }

        if (!user.isUse) throw IllegalAccessException("账号已被禁用，请联系管理员")

        // 空密码账号一律拒绝登录，避免历史数据变成“无密码可进”
        if (user.password.isNullOrEmpty()) {
            throw IllegalAccessException("账号尚未设置密码，请联系管理员重置")
        }
        if (password.isNullOrEmpty() || !encoder.matches(password, user.password)) {
            recordLoginFailure(trimmedUserName)
            throw IllegalAccessException("密码不正确")
        }
        loginFailures.remove(trimmedUserName)

        val obj = session.get()
        obj["id"] = user.id.toString()
        obj["session:createdAt"] = Date().time

        session.save(obj["sessionId"]!!.toString())

        val authUser = AuthorizeUser().apply {
            this.id = user.id
            this.userName = user.userName
            this.realName = user.realName
            this.phone = user.phone
            this.email = user.email
            this.avatar = user.avatar
            this.isUse = user.isUse
            this.authorization = obj["sessionId"]?.toString()
        }

        publisher.publishEvent(UserLoginEvent(this, authUser, obj["sessionId"]!!.toString()))
        return authUser
    }

    /**
     * 用户登出
     *
     * 根据会话ID销毁用户会话，发布登出事件。
     *
     * @param ctx 请求上下文，用于获取会话ID
     * @param sessionId 可选的会话ID，为null时从上下文中获取
     * @return 操作结果，成功时返回成功消息
     */
    @Suppress("NAME_SHADOWING")
    suspend fun logout(ctx: Context, sessionId: String? = null): Any {
        val sessionId = try {
            sessionId ?: ctx.sessionId.toString()
        } catch (_: Throwable) {
            return success("SessionId哪去了？")
        }
        val out = if (session.contains(sessionId)) session[sessionId]
        else bundleOf()
        session.remove(sessionId)
        publisher.publishEvent(UserLogoutEvent(this, out, sessionId))
        return success()
    }


    private val captchaCache by lazy { cacheManager["/system/login/captcha"] }

    /**
     * 生成图形验证码
     *
     * 创建一个带有数学运算的图形验证码，将结果缓存并返回验证码图片的Base64编码和缓存ID。
     *
     * @param ctx 请求上下文，用于获取客户端地址进行验证码缓存标识
     * @return 包含验证码图片Base64编码和验证码ID的Bundle
     */
    suspend fun captcha(ctx: Context): Bundle {
        ctx as IWebExchangeContext
        val captcha = CaptchaUtil.createLineCaptcha(100, 40)
        captcha.setBackground(Color.BLACK)
        captcha.generator = MathGenerator()
        captcha.createCode()
        val result = Calculator.conversion(captcha.code).toInt()

        val cache = captchaCache.create {
            this.tag = ctx.clientAddressString
            this.value = result.toString()
        }

        val image = Base64.getEncoder().encodeToString(captcha.imageBytes)
        return bundleScope {
            "image" set image
            "id" set cache.id
        }
    }

    val emailVerifyCache by lazy { cacheManager["/system/login/email-verify"] }

    /**
     * 验证验证码并发送邮箱验证邮件
     *
     * 校验图形验证码是否正确，检查用户名和邮箱是否已存在，生成6位邮箱验证码并通过邮件发送。
     *
     * @param ctx 请求上下文，用于数据库查询和邮件发送
     * @param id 验证码ID，用于从缓存中查找验证码
     * @param code 用户输入的验证码答案
     * @param userName 待注册的用户名
     * @param email 待注册的邮箱地址
     * @return 操作成功的响应实体
     * @throws IllegalArgumentException 当验证码过期、不正确或用户名/邮箱已存在时抛出
     */
    suspend fun verify(
        ctx: Context,
        id: Int,
        code: String,
        userName: String,
        email: String
    ): ResponseEntity<ByteArray> {
        ctx as IWebExchangeContext

        val cache = captchaCache.query {
            this.id.eq(id)
        }.findOne()

        cache ?: throw IllegalArgumentException("验证码已过期")
        if (cache.value != code) throw IllegalArgumentException("验证码不正确")
        val hasUser = ctx.query<QSysUserEntity> {
            or {
                this.userName.eq(userName)
                this.email.eq(email)
            }
        }.exists()
        if (hasUser) throw IllegalArgumentException("用户名或邮箱已存在")
        cache.applyDatabase(ctx)
        cache.delete()

        val code1 = Math.random().toString().removePrefix("0.").substring(0 until 6)
        emailVerifyCache.put(email, code1, 5.minutes)

        val ctx1 = ThymeleafContext(ctx.exchange)

        val map = bundleScope {
            "code" set code1
            "userName" set userName
            "email" set email
            "emailBase64" set Base64.getUrlEncoder().encodeToString(email.toByteArray())
            "server" set ctx.serverHostString
        }

        ctx1.setVariables(map)

        val html = templateEngine.process("email-verify", ctx1)
        ctx.sendMail(email, "用户注册", html)

        return success()
    }

    /**
     * 用户注册
     *
     * 验证邮箱验证码，创建新用户账号并分配默认角色。
     *
     * @param ctx 请求上下文，用于数据库操作
     * @param userName 注册的用户名
     * @param password 用户密码
     * @param emailCode 邮箱验证码，用于验证邮箱有效性
     * @param email 用户邮箱地址
     * @return 新创建的用户实体，密码字段已置为null
     * @throws IllegalArgumentException 当邮箱验证码过期或不正确时抛出
     */
    suspend fun register(
        ctx: Context,
        userName: String,
        password: String,
        emailCode: String,
        email: String
    ): SysUserEntity {
        val emailCache = emailVerifyCache[email]
        emailCache ?: throw IllegalArgumentException("邮箱验证码已过期，请重新获取")
        if (emailCache != emailCode) throw IllegalArgumentException("邮箱验证码不正确")
        emailVerifyCache.remove(email)

        val out = userService.create(ctx, bundleScope {
            "userName" set userName
            "password" set password
            "email" set email
            "roleId" set 4
        })

        out.password = null

        return out
    }
}