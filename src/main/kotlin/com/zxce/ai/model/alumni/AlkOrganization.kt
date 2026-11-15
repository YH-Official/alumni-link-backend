package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Transient
import java.util.*

@Entity
@Table(name = "alk_organization")
class AlkOrganization : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("学校ID")
    var schoolId: UUID? = null

    @DbComment("学校名称")
    @Column(nullable = false)
    lateinit var school: String

    @DbComment("年级")
    @Column(nullable = false)
    lateinit var grade: String

    @DbComment("学院ID")
    var collegeId: UUID? = null

    @DbComment("学院")
    @Column(nullable = false)
    lateinit var college: String

    @DbComment("专业ID")
    var majorId: UUID? = null

    @DbComment("专业")
    @Column(nullable = false)
    lateinit var major: String

    @DbComment("班级ID")
    var classId: UUID? = null

    @DbComment("班级")
    var className: String? = null

    @DbComment("组织名称")
    var name: String? = null

    @DbComment("成员数量")
    var memberCount: Int = 0

    @DbComment("状态: 0-待审核 1-已审核 2-已拒绝")
    var status: Int = 0

    @Transient
    @DbComment("未读消息数（非持久化）")
    var unreadCount: Int = 0
}