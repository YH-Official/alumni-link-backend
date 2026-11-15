package com.zxce.ai.controller.alumni

import com.zxce.ai.annotation.HostForward
import com.zxce.ai.service.alumni.UserProfileService
import com.zxce.ai.util.bundle.Bundle
import com.zxce.ai.util.session.Context
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.bind.annotation.*

/**
 * 用户资料控制器
 *
 * 提供校友教育经历管理、工作经历管理、标签管理等功能。
 */
@HostForward
@RestController
@RequestMapping("/api/alumni/profile")
class UserProfileController {

    @Autowired
    lateinit var userProfileService: UserProfileService

    /**
     * 添加教育经历
     *
     * @param context 当前会话上下文
     * @param data 教育经历数据（包含学校、专业、学历等信息）
     * @return 教育经历创建结果
     * HTTP方法: POST
     */
    @PostMapping("/education/add")
    suspend fun addEducation(context: Context, @RequestBody data: Bundle): Any {
        return userProfileService.addEducation(context, data)
    }

    /**
     * 更新教育经历
     *
     * @param context 当前会话上下文
     * @param data 教育经历数据（包含ID和需要更新的字段）
     * @return 教育经历更新结果
     * HTTP方法: POST
     */
    @PostMapping("/education/update")
    suspend fun updateEducation(context: Context, @RequestBody data: Bundle): Any {
        return userProfileService.updateEducation(context, data)
    }

    /**
     * 删除教育经历
     *
     * @param context 当前会话上下文
     * @param params 包含 id 的参数体
     * @return 删除操作结果
     * HTTP方法: POST
     */
    @PostMapping("/education/delete")
    suspend fun deleteEducation(context: Context, @RequestBody params: Bundle): Any {
        return userProfileService.deleteEducation(context, params["id"].toString())
    }

    /**
     * 获取当前用户的教育经历列表
     *
     * @param context 当前会话上下文
     * @return 教育经历列表
     * HTTP方法: POST
     */
    @PostMapping("/education/list")
    suspend fun educationList(context: Context): List<Bundle> {
        return userProfileService.getEducationList(context, null)
    }

    /**
     * 添加工作经历
     *
     * @param context 当前会话上下文
     * @param data 工作经历数据（包含公司、职位、任职时间等信息）
     * @return 工作经历创建结果
     * HTTP方法: POST
     */
    @PostMapping("/work/add")
    suspend fun addWork(context: Context, @RequestBody data: Bundle): Any {
        return userProfileService.addWorkExperience(context, data)
    }

    /**
     * 更新工作经历
     *
     * @param context 当前会话上下文
     * @param data 工作经历数据（包含ID和需要更新的字段）
     * @return 工作经历更新结果
     * HTTP方法: POST
     */
    @PostMapping("/work/update")
    suspend fun updateWork(context: Context, @RequestBody data: Bundle): Any {
        return userProfileService.updateWorkExperience(context, data)
    }

    /**
     * 删除工作经历
     *
     * @param context 当前会话上下文
     * @param params 包含 id 的参数体
     * @return 删除操作结果
     * HTTP方法: POST
     */
    @PostMapping("/work/delete")
    suspend fun deleteWork(context: Context, @RequestBody params: Bundle): Any {
        return userProfileService.deleteWorkExperience(context, params["id"].toString())
    }

    /**
     * 获取当前用户的工作经历列表
     *
     * @param context 当前会话上下文
     * @return 工作经历列表
     * HTTP方法: POST
     */
    @PostMapping("/work/list")
    suspend fun workList(context: Context): List<Bundle> {
        return userProfileService.getWorkExperienceList(context, null)
    }

    /**
     * 添加个人标签
     *
     * @param context 当前会话上下文
     * @param data 标签数据
     * @return 标签创建结果
     * HTTP方法: POST
     */
    @PostMapping("/tag/add")
    suspend fun addTag(context: Context, @RequestBody data: Bundle): Any {
        return userProfileService.addTag(context, data)
    }

    /**
     * 删除个人标签
     *
     * @param context 当前会话上下文
     * @param params 包含 id 的参数体
     * @return 删除操作结果
     * HTTP方法: POST
     */
    @PostMapping("/tag/delete")
    suspend fun deleteTag(context: Context, @RequestBody params: Bundle): Any {
        return userProfileService.deleteTag(context, params["id"].toString())
    }

    /**
     * 获取当前用户的标签列表
     *
     * @param context 当前会话上下文
     * @return 标签列表
     * HTTP方法: POST
     */
    @PostMapping("/tag/list")
    suspend fun tagList(context: Context): List<Bundle> {
        return userProfileService.getTags(context, null)
    }

    // ==================== 邮箱 / 手机绑定 ====================

    data class EmailParam(
        var email: String = ""
    )

    data class EmailBindParam(
        var email: String = "",
        var code: String = ""
    )

    data class PhoneBindParam(
        var phone: String = "",
        var password: String? = null
    )

    data class PasswordParam(
        var password: String? = null
    )

    /**
     * 发送邮箱绑定验证码（5 分钟有效）
     * HTTP方法: POST
     */
    @PostMapping("/email/send-code")
    suspend fun sendEmailCode(context: Context, @RequestBody params: EmailParam): Any {
        return userProfileService.sendEmailCode(context, params.email)
    }

    /**
     * 校验验证码并绑定邮箱
     * HTTP方法: POST
     */
    @PostMapping("/email/bind")
    suspend fun bindEmail(context: Context, @RequestBody params: EmailBindParam): Any {
        return userProfileService.bindEmail(context, params.email, params.code)
    }

    /**
     * 解绑邮箱
     * HTTP方法: POST
     */
    @PostMapping("/email/unbind")
    suspend fun unbindEmail(context: Context): Any {
        return userProfileService.unbindEmail(context)
    }

    /**
     * 绑定手机号（使用登录密码二次确认）
     * HTTP方法: POST
     */
    @PostMapping("/phone/bind")
    suspend fun bindPhone(context: Context, @RequestBody params: PhoneBindParam): Any {
        return userProfileService.bindPhone(context, params.phone, params.password)
    }

    /**
     * 校验登录密码后解绑手机号
     * HTTP方法: POST
     */
    @PostMapping("/phone/unbind")
    suspend fun unbindPhone(context: Context, @RequestBody params: PasswordParam): Any {
        return userProfileService.unbindPhone(context, params.password)
    }

    /**
     * 获取当前用户的联系方式
     * HTTP方法: GET
     */
    @GetMapping("/contact")
    suspend fun contact(context: Context): Bundle {
        return userProfileService.getContact(context)
    }
}
