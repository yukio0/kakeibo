package jp.yukio0.kakeibo.category

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/categories")
class CategoryController(private val categoryService: CategoryService) {

  @GetMapping fun findAll(): List<CategoryResponse> = categoryService.findAll()

  @PostMapping
  fun create(@Valid @RequestBody request: CategoryRequest): ResponseEntity<CategoryResponse> =
    ResponseEntity.status(HttpStatus.CREATED).body(categoryService.create(request))

  @PutMapping("/{id}")
  fun update(
    @PathVariable id: Long,
    @Valid @RequestBody request: CategoryRequest,
  ): CategoryResponse = categoryService.update(id, request)

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  fun delete(@PathVariable id: Long) {
    categoryService.delete(id)
  }
}
