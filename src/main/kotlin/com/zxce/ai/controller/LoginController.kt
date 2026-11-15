package com.zxce.ai.controller

import com.zxce.ai.model.system.AuthorizeUser
import com.zxce.ai.model.system.SysUserEntity
import com.zxce.ai.service.LoginService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 登录认证控制器，处理用户登录、登出、验证码、验证和注册等接口。
 */
@RestController
@RequestMapping("/api")
class LoginController {

    @Autowired
    lateinit var loginService: LoginService

    /**
     * 用户登录接口，验证用户名和密码，返回授权信息。
     *
     * @param context 请求上下文
     * @param param 登录请求参数，包含用户名和密码
     * @return 授权用户信息，包含令牌和用户详情
     * @since 1.0.0
     */
    @PostMapping("/login")
    suspend fun login(context: Context, @RequestBody param: LoginFn): AuthorizeUser {
        return loginService.login(context, param.userName, param.password)
    }

    /**
     * 用户登出接口，清除当前会话的登录状态。
     *
     * @param context 请求上下文
     * @return 登出结果
     * @since 1.0.0
     */
    @PostMapping("/logout")
    suspend fun logout(context: Context): Any {
        return loginService.logout(context)
    }

    /**
     * 获取验证码接口，生成并返回图形验证码。
     *
     * @param context 请求上下文
     * @return 包含验证码ID和图片数据的Bundle对象
     * @since 1.0.0
     */
    @PostMapping("/captcha")
    suspend fun captcha(context: Context): Bundle {
        return loginService.captcha(context)
    }

    /**
     * 验证码校验接口，验证用户提交的验证码是否正确。
     *
     * @param context 请求上下文
     * @param param 验证请求参数，包含验证码ID、验证码值、用户名和邮箱
     * @return 验证结果
     * @since 1.0.0
     */
    @PostMapping("/verify")
    suspend fun verify(context: Context, @RequestBody param: VerifyFn): Any {
        return loginService.verify(context, param.id, param.code, param.userName, param.email)
    }

    /**
     * 用户注册接口，创建新用户账号。
     *
     * @param context 请求上下文
     * @param param 注册请求参数，包含用户名、密码、邮箱验证码和邮箱
     * @return 注册成功的用户实体信息
     * @since 1.0.0
     */
    @PostMapping("/register")
    suspend fun register(context: Context, @RequestBody param: RegisterFn): SysUserEntity {
        return loginService.register(context, param.userName, param.password, param.emailCode, param.email)
    }
}

data class LoginFn(
    var userName: String = "",
    var password: String? = null
)

data class VerifyFn(
    var id: Int = 0,
    var code: String = "",
    var userName: String = "",
    var email: String = ""
)

data class RegisterFn(
    var userName: String = "",
    var password: String = "",
    var emailCode: String = "",
    var email: String = ""
)