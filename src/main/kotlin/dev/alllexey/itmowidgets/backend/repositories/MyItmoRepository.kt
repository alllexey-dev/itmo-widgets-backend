package dev.alllexey.itmowidgets.backend.repositories

import dev.alllexey.itmowidgets.backend.model.MyItmoStorage
import org.springframework.data.jpa.repository.JpaRepository

interface MyItmoRepository : JpaRepository<MyItmoStorage, Long>