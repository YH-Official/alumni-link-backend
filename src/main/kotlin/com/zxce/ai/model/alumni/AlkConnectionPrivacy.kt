package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_connection_privacy")
class AlkConnectionPrivacy : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("关联sys_user.id")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("关系可见范围: self-仅自己可见, contacts-我的人脉可见, all-所有人可见")
    var visibility: String = "contacts"

    @DbComment("是否允许系统用于相关推荐")
    @JvmField
    var allowRecommendation: Boolean = true

    @DbComment("是否对他人显示共同联系人数量")
    @JvmField
    var showMutualCount: Boolean = true
}
