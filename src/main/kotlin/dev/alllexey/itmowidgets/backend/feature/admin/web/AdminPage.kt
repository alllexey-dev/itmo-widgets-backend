package dev.alllexey.itmowidgets.backend.feature.admin.web

import dev.alllexey.itmowidgets.backend.platform.error.InvalidRequestDataException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/** One page of an admin list; [page] is zero-based, [total] counts every matching item. */
data class AdminPage<T>(val items: List<T>, val page: Int, val size: Int, val total: Long) {
    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100

        fun request(page: Int, size: Int, sort: Sort = Sort.unsorted()): PageRequest {
            if (page < 0 || size !in 1..MAX_SIZE) throw InvalidRequestDataException("Invalid page")
            return PageRequest.of(page, size, sort)
        }

        fun <T> of(source: Page<*>, items: List<T>) = AdminPage(items, source.number, source.size, source.totalElements)
    }
}
