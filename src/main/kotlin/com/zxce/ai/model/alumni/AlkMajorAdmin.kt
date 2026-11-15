package com.zxce.ai.model.alumni

import com.zxce.ai.model.system.SystemModel
import io.ebean.annotation.DbComment
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.*

@Entity
@Table(name = "alk_major_admin")
class AlkMajorAdmin : SystemModel() {
    @Id
    @Column(nullable = false)
    @DbComment("主键")
    lateinit var id: UUID

    @DbComment("用户ID")
    @Column(nullable = false)
    lateinit var userId: UUID

    @DbComment("专业ID")
    @Column(nullable = false)
    lateinit var majorId: UUID
}
