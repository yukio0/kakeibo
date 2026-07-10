package jp.yukio0.kakeibo.master

interface MasterRequest {
  val name: String?

  val displayOrder: Int?
}

/** `@NotBlank` を通過済みの前提で、前後の空白を落とした名前を返す。 */
val MasterRequest.normalizedName: String
  get() = checkNotNull(name).trim()

/** `@NotNull` と `@Min(0)` を通過済みの前提で、表示順を返す。 */
val MasterRequest.requiredDisplayOrder: Int
  get() = checkNotNull(displayOrder)
