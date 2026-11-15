package com.zxce.ai.model.forum

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_forum_category")
class AlkForumCategory : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("学校名称（对应alk_organization.school）")
    @Column(nullable = false)
    lateinit var schoolName: String

    @DbComment("学校ID（对应alk_school.id）")
    var schoolId: UUID? = null

    @DbComment("关联组织ID（对应alk_organization.id）")
    var organizationId: UUID? = null

    @DbComment("系统内置分区标识（非学校组织分区时使用）")
    var internalId: String? = null

    @DbComment("分区图标URL")
    var icon: String? = null

    @DbComment("分区描述")
    var description: String? = null

    @DbComment("帖子数缓存")
    var postCount: Int = 0

    @DbComment("排序权重")
    var sortOrder: Int = 0

    @DbComment("是否启用")
    var isActive: Boolean = true
}