package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_user_profile")
class AlkUserProfile : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("性别: 0-未知 1-男 2-女")
    var gender: Int = 0

    @DbComment("出生日期")
    var birthday: java.sql.Date? = null

    @DbComment("毕业年份")
    var graduationYear: Int? = null

    @DbComment("院系")
    var faculty: String? = null

    @DbComment("专业")
    var major: String? = null

    @DbComment("学号")
    var studentId: String? = null

    @DbComment("当前城市")
    var currentCity: String? = null

    @DbComment("个人简介")
    var introduction: String? = null

    @DbComment("当前公司")
    var company: String? = null

    @DbComment("当前职位")
    var position: String? = null

    @DbComment("所属行业")
    var industry: String? = null

    @DbComment("微信号")
    var wechat: String? = null

    @DbComment("QQ号")
    var qq: String? = null

    @DbComment("资料是否公开")
    @JvmField
    var isPublic: Boolean = true
}